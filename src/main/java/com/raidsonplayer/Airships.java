package com.raidsonplayer;

import com.raidsonplayer.RaidData.Base;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.fml.ModList;
import org.joml.Quaterniond;
import org.joml.Quaterniondc;
import org.joml.Vector3d;
import org.joml.Vector3dc;

import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.*;

/**
 * Raider airships: the user's airship_mk2 blueprint (Create Aeronautics on Sable physics), placed with the Aeronautics
 * Toolgun's blueprint printer and flown by a raider pilot through the ship's own controls: two throttle levers
 * (propellers, burner) and the linked typewriter (D/A rudder, F drops the two hanging HE shells). Optional: without
 * Aeronautics and the Toolgun there are no airships. None of these mods has a public API, so everything is reflection.
 * Every ship gets its own random redstone-link frequencies (typewriter keys and lever links follow), so ships never
 * steer each other, and a captured ship answers only its own typewriter.
 */
final class Airships {
    private static final String BLUEPRINT = "/data/raidsonplayer/airships/airship_mk2.excraft";
    // airship_mk2's controls: typewriter keys (GLFW codes) and the frequency pair of the burner lever's link
    static final int KEY_RIGHT = 68, KEY_LEFT = 65, KEY_DROP = 70; // D, A, F
    private static final String BURNER_FREQ = "minecraft:campfire|minecraft:campfire";
    /** The ship's nose in plot space: from the rudder (z 1022) toward the cockpit (z 1038). */
    static Vec3 forward = new Vec3(0, 0, 1);
    /** Measured at cruise: holding D swings the nose toward larger atan2(z, x) angles, A the other way. */
    static int turnSign = 1;
    private static final int CRUISE = 30;    // blocks above the ground (or the target) the pilot keeps
    private static final double START = 200; // blocks from the target where the ship appears (its chunks are kept loaded: ticket)
    /** The blueprint's creative motor runs at 16 rpm: ~1.2 blocks/s, too slow for the sail rudder to steer. */
    private static final int MOTOR_RPM = 64;
    /**
     * Aeronautics thins the air with height: warm, airship_mk2 tops out around y 150 (measured). Placed cold it can't
     * fly at all until its burner has heated the balloon, which took 3x longer in one world than another (it fell 226
     * blocks and blew up its own shells on the ground), so a new ship is held still until it can lift itself (steady).
     */
    private static final int CEILING = 150;

    enum Phase { INBOUND, OUTBOUND, /** pilot lost */ FALLING }

    static final class Ship {
        final List<UUID> parts = new ArrayList<>();
        UUID base, root;
        @Nullable UUID pilot;
        BlockEntity typewriter, throttle, burner, motor;
        Vec3 target, away;
        Phase phase = Phase.INBOUND;
        long since, launched;
        /** The balloon carries the ship: it was held still until then (steady). */
        boolean warm;
        /** How much the hull sank in the last tick of the hold, blocks/s: the lift test. */
        double sink = -1;
        int liftTicks;
        double lastNose = Double.NaN;
        int held = -1, dropTicks;
        @Nullable Vec3 last, aim;
        @Nullable BlockPos seat;
    }

    /** The highest ground (trees and buildings included) within r blocks of `p`, sampled every 4 blocks (loaded chunks only). */
    static int highest(ServerLevel level, Vec3 p, int r) {
        int top = level.getMinBuildHeight();
        for (int dx = -r; dx <= r; dx += 4)
            for (int dz = -r; dz <= r; dz += 4) {
                int x = Mth.floor(p.x) + dx, z = Mth.floor(p.z) + dz;
                if (level.hasChunk(x >> 4, z >> 4)) top = Math.max(top, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z));
            }
        return top;
    }

    static final List<Ship> SHIPS = new ArrayList<>();
    @Nullable private static MinecraftServer cleaned;

    private Airships() {}

    static boolean available() { return ModList.get().isLoaded("aeronautics") && ModList.get().isLoaded("create_aeronautics_toolgun"); }

    // ---------------------------------------------------------------- launch

    /**
     * An airship with a pilot appears START blocks out from `target` on the side of the raider camp, turned to face it,
     * and flies at it. The rudder alone turns it only a degree or so a second, so it comes in straight from their base,
     * crosses the target and keeps going to leave. Null if it could not be placed.
     */
    @Nullable
    static Ship launch(ServerLevel level, RaidData d, Base b, Vec3 target, long t) {
        Vec3 dir = b.camp == null ? Vec3.ZERO : target.subtract(Vec3.atBottomCenterOf(b.camp)).multiply(1, 0, 1);
        dir = dir.lengthSqr() < 1 ? new Vec3(0, 0, 1) : dir.normalize(); // from their base to the target (no camp: from the north)
        Vec3 at = target.subtract(dir.scale(START));
        // The shells hang armed under the hull: it appears and cruises CRUISE above the highest ground on its way in,
        // and where that is too close to its ceiling there is no airship.
        int ground = (int) target.y;
        for (int k = 0; k <= START; k += 8) { // the ship's own track; its far end may be past the players' loaded chunks
            Vec3 p = at.add(dir.scale(k));
            for (int dx = -8; dx <= 8; dx += 16) for (int dz = -8; dz <= 8; dz += 16) level.getChunk(BlockPos.containing(p.x + dx, 0, p.z + dz));
            ground = Math.max(ground, highest(level, p, 8));
        }
        if (ground + CRUISE + 10 > CEILING) {
            RaidsOnPlayer.LOG.info("No airship: the ground on its way in reaches y {}, too high under its ceiling of y {}", ground, CEILING);
            return null;
        }
        BlockPos spawn = BlockPos.containing(at.x, ground + CRUISE + 10, at.z);
        try {
            Map<String, String[]> freq = new HashMap<>();
            byte[] bytes = blueprint(level.random, freq);
            String burnerItem = freq.get(BURNER_FREQ)[0];
            Ship s = print(level, spawn, bytes, burnerItem);
            if (s.motor != null && homePlot(s.motor)) { // a copy is printed while this one still holds that plot, then this one goes
                spawn = BlockPos.containing(Vec3.atLowerCornerOf(spawn).add(dir.scale(48))); // clear of it: printed on the same spot, the copy was shoved off course by what was left of it
                Ship copy = print(level, spawn, bytes, burnerItem);
                remove(level, s.parts);
                s = copy;
                RaidsOnPlayer.LOG.info("Airship came out in its blueprint's own plot; flying a copy instead (home plot again: {})", s.motor != null && homePlot(s.motor));
            }
            BlockPos seat = s.seat;
            if (s.typewriter == null || s.throttle == null || s.burner == null || s.motor == null || seat == null) {
                RaidsOnPlayer.LOG.warn("Airship placed at {} but its controls were not found (typewriter {}, throttle {}, burner {}, motor {}, seat {}); removing it",
                        spawn, s.typewriter != null, s.throttle != null, s.burner != null, s.motor != null, seat);
                remove(level, s.parts);
                return null;
            }
            turn(level, s, dir);
            s.base = b.id;
            s.target = target;
            s.away = target.add(dir.scale(400)); // straight on past it
            s.since = s.launched = t;
            RaiderEntity pilot = RaidsOnPlayer.RAIDER.get().create(level);
            if (pilot != null) {
                RaidManager.equip(pilot, Role.PILOT, d.stage, false, level);
                pilot.setPersistenceRequired();
                pilot.moveTo(seat.getX() + 0.5, seat.getY() + 0.5, seat.getZ() + 0.5, 0, 0);
                level.addFreshEntity(pilot);
                sitDown(level, seat, pilot);
                s.pilot = pilot.getUUID();
            }
            SHIPS.add(s);
            d.airships.addAll(s.parts);
            RaidsOnPlayer.LOG.info("Airship of {} parts launched at {} toward {} (pilot seated: {}, shells: {})", s.parts.size(), spawn, BlockPos.containing(target), pilot != null && pilot.isPassenger(), shells(level, s.parts));
            return s;
        } catch (Throwable e) {
            RaidsOnPlayer.LOG.warn("Could not launch an airship at {}", spawn, e);
            return null;
        }
    }

    /**
     * Turns the whole ship about a vertical axis through the hull so its nose (south as printed) points along `dir`:
     * every part is teleported (Sable) to its turned pose in the same tick, so the ropes and stickers between them hold.
     */
    private static void turn(ServerLevel level, Ship s, Vec3 dir) throws ReflectiveOperationException {
        Quaterniond r = new Quaterniond().rotateY(Math.atan2(dir.x, dir.z));
        Vector3d pivot = new Vector3d((Vector3dc) call(call(subLevel(level, s.root), "logicalPose"), "position"));
        Class<?> body = Class.forName("dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle");
        for (UUID u : s.parts) {
            Object sub = subLevel(level, u);
            if (sub == null) continue;
            Object pose = call(sub, "logicalPose");
            Vector3d p = new Vector3d((Vector3dc) call(pose, "position")).sub(pivot).rotate(r).add(pivot);
            call(call(body, "of", sub), "teleport", p, new Quaterniond(r).mul((Quaterniondc) call(pose, "orientation")));
        }
    }

    /** Prints the blueprint at `spawn` and finds the new ship's parts and controls (null fields for any not found). */
    private static Ship print(ServerLevel level, BlockPos spawn, byte[] bytes, String burnerItem) throws Throwable {
        Set<UUID> before = new HashSet<>();
        for (Object sub : subLevels(level)) before.add(id(sub));
        place(level, spawn, bytes);
        Ship s = new Ship();
        for (Object sub : subLevels(level)) {
            if (before.contains(id(sub))) continue;
            s.parts.add(id(sub));
            for (LevelChunk c : chunks(sub)) {
                for (BlockEntity be : c.getBlockEntities().values()) {
                    String kind = be.getClass().getSimpleName();
                    if (kind.equals("LinkedTypewriterBlockEntity")) { s.typewriter = be; s.root = id(sub); }
                    else if (kind.equals("CreativeMotorBlockEntity")) s.motor = be;
                    else if (kind.equals("ThrottleLeverBlockEntity")) { // which one is the burner's: the link it powers (a neighbour) is on the burner frequency
                        boolean burner = false;
                        for (BlockPos n : BlockPos.betweenClosed(be.getBlockPos().offset(-1, -1, -1), be.getBlockPos().offset(1, 1, 1))) {
                            BlockEntity link = c.getBlockEntity(n);
                            burner |= link != null && burnerItem.equals(link.saveWithoutMetadata(level.registryAccess()).getCompound("FrequencyFirst").getString("id"));
                        }
                        if (burner) s.burner = be; else s.throttle = be;
                    }
                }
                if (s.seat == null) s.seat = seat(c);
            }
        }
        return s;
    }

    /**
     * Whether the ship came out in the blueprint's own plot, as the first raider ship in a world does. The Toolgun hands
     * every printed ship the Create gear network of that plot (named after the motor's position there), with the
     * motor driven instead of driving. Elsewhere that sorts itself out when the motor speeds up to MOTOR_RPM; in that
     * very plot Create takes it for a gear loop and breaks the motor (a dead drive: the ship hung where it appeared),
     * and the ship got shoved off course while warming up.
     */
    private static boolean homePlot(BlockEntity motor) throws ReflectiveOperationException {
        Object net = motor.getClass().getField("network").get(motor);
        return net instanceof Long id && id == motor.getBlockPos().asLong();
    }

    /**
     * The blueprint with fresh link frequencies: every frequency pair (redstone links, typewriter keys) maps to a new pair
     * of random items, the same everywhere in the ship. `freq` gets the mapping ("first|last" -> new first, new last).
     */
    static byte[] blueprint(RandomSource r, Map<String, String[]> freq) throws IOException {
        CompoundTag root;
        try (InputStream in = Objects.requireNonNull(Airships.class.getResourceAsStream(BLUEPRINT), BLUEPRINT)) {
            root = NbtIo.readCompressed(in, NbtAccounter.unlimitedHeap());
        }
        remap(root, freq, r);
        motor(root);
        disarm(root);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        NbtIo.writeCompressed(root, out);
        return out.toByteArray();
    }

    private static void remap(Tag t, Map<String, String[]> freq, RandomSource r) {
        if (t instanceof CompoundTag c) {
            if (c.contains("FrequencyFirst", Tag.TAG_COMPOUND) && c.contains("FrequencyLast", Tag.TAG_COMPOUND)) swap(c, "FrequencyFirst", "FrequencyLast", freq, r);
            if (c.contains("FirstItem", Tag.TAG_COMPOUND) && c.contains("SecondItem", Tag.TAG_COMPOUND)) swap(c, "FirstItem", "SecondItem", freq, r);
            for (String k : c.getAllKeys()) remap(c.get(k), freq, r);
        } else if (t instanceof ListTag l) for (Tag x : l) remap(x, freq, r);
    }

    /**
     * The shells come out of the blueprint without their impact fuzes: in the user's singleplayer game they went off the
     * moment the ship was placed (holing the balloon), which never happened on a dedicated server. They never need one:
     * on the drop (and when a falling ship nears the ground) they become shell projectiles (release).
     */
    private static void disarm(Tag t) {
        if (t instanceof CompoundTag c) {
            if ("createbigcannons:fuzed_block".equals(c.getString("id"))) c.remove("Fuze");
            for (String k : c.getAllKeys()) disarm(c.get(k));
        } else if (t instanceof ListTag l) for (Tag x : l) disarm(x);
    }

    /**
     * The bomb rigs (every part but the hull) go, and each shell on them becomes an impact-fuzed HE shell projectile at
     * its spot, keeping the ship's speed (vel, blocks/s). No ship part ever comes down on the ground as a physics body:
     * a crashing sub-level that breaks a fragile block (leaves, plants) mid-step hangs Sable's native physics (the
     * server watchdog killed the dev server on one). Returns the number of shells.
     */
    static int release(ServerLevel level, Ship s, Vec3 vel) {
        int n = 0;
        List<UUID> rigs = new ArrayList<>(s.parts);
        rigs.remove(s.root);
        for (UUID u : rigs) {
            Object sub = subLevel(level, u);
            if (sub == null) continue;
            for (LevelChunk c : chunks(sub))
                for (BlockEntity be : c.getBlockEntities().values())
                    if ("createbigcannons:fuzed_block".equals(String.valueOf(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType())))) {
                        Artillery.spawnShell(level, world(sub, be.getBlockPos()), vel.scale(1 / 20.0).add(0, -0.2, 0));
                        n++;
                    }
        }
        remove(level, rigs);
        s.parts.retainAll(List.of(s.root));
        return n;
    }

    /** Shells still on these parts (CBC fuzed-block entities), armed or not. */
    static int shells(ServerLevel level, List<UUID> parts) {
        int n = 0;
        for (UUID u : parts) {
            Object sub = subLevel(level, u);
            if (sub == null) continue;
            for (LevelChunk c : chunks(sub))
                for (BlockEntity be : c.getBlockEntities().values())
                    if ("createbigcannons:fuzed_block".equals(String.valueOf(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType())))) n++;
        }
        return n;
    }

    /** Sets every creative motor on the ship to MOTOR_RPM, keeping its direction. */
    private static void motor(Tag t) {
        if (t instanceof CompoundTag c) {
            if ("create:motor".equals(c.getString("id")) && c.contains("ScrollValue")) c.putInt("ScrollValue", (int) Math.signum(c.getInt("ScrollValue")) * MOTOR_RPM);
            for (String k : c.getAllKeys()) motor(c.get(k));
        } else if (t instanceof ListTag l) for (Tag x : l) motor(x);
    }

    private static void swap(CompoundTag c, String a, String b, Map<String, String[]> freq, RandomSource r) {
        String key = c.getCompound(a).getString("id") + "|" + c.getCompound(b).getString("id");
        String[] to = freq.computeIfAbsent(key, k -> new String[]{randomItem(r), randomItem(r)});
        c.getCompound(a).putString("id", to[0]);
        c.getCompound(b).putString("id", to[1]);
    }

    private static String randomItem(RandomSource r) {
        while (true) {
            var item = BuiltInRegistries.ITEM.getRandom(r).orElseThrow();
            if (item.value() != Items.AIR) return item.key().location().toString();
        }
    }

    /** Create's seat in this plot chunk, if any (seats have no block entity, so the blocks are scanned). */
    @Nullable
    private static BlockPos seat(LevelChunk c) {
        int x0 = c.getPos().getMinBlockX(), z0 = c.getPos().getMinBlockZ();
        for (int i = c.getMinSection(); i < c.getMaxSection(); i++) {
            if (c.getSection(c.getSectionIndexFromSectionY(i)).hasOnlyAir()) continue;
            for (int y = i * 16; y < i * 16 + 16; y++)
                for (int x = x0; x < x0 + 16; x++)
                    for (int z = z0; z < z0 + 16; z++) {
                        BlockPos p = new BlockPos(x, y, z);
                        var id = BuiltInRegistries.BLOCK.getKey(c.getBlockState(p).getBlock());
                        if (id.getNamespace().equals("create") && id.getPath().endsWith("_seat")) return p;
                    }
        }
        return null;
    }

    // ---------------------------------------------------------------- flight

    /** Every 5 ticks: each pilot flies his ship. Leftover raider ships from before a restart (pilots aren't saved) are removed first. */
    static void tick(MinecraftServer server) {
        ServerLevel level = server.overworld();
        for (Ship s : List.copyOf(SHIPS)) {
            Object root = level.getGameTime() % 20 == 0 ? subLevel(level, s.root) : null;
            if (root != null) { // keeps its area running past the players' simulation distance (a ticket lasts 5 s)
                ChunkPos c = new ChunkPos(BlockPos.containing(world(root, s.typewriter.getBlockPos())));
                level.getChunkSource().addRegionTicket(RaidManager.RAID_TICKET, c, 4, c);
            }
            if (!s.warm) steady(level, RaidData.get(server), s);
            else if (s.phase == Phase.FALLING) falling(level, RaidData.get(server), s);
        }
        if (level.getGameTime() % 5 != 0) return;
        RaidData d = RaidData.get(server);
        if (cleaned != server) {
            cleaned = server;
            SHIPS.clear();
            if (!d.airships.isEmpty() && available()) {
                remove(level, d.airships);
                RaidsOnPlayer.LOG.info("Removed {} parts of raider airships left from before the restart", d.airships.size());
            }
            d.airships.clear();
        }
        for (Ship s : List.copyOf(SHIPS)) {
            try { fly(level, d, s); } catch (Exception e) {
                RaidsOnPlayer.LOG.warn("Airship control failed; the ship is left adrift", e);
                forget(d, s);
            }
        }
    }

    private static void fly(ServerLevel level, RaidData d, Ship s) {
        Object root = subLevel(level, s.root);
        Entity pilot = s.pilot == null ? null : level.getEntity(s.pilot);
        Base b = d.bases.get(s.base);
        if (root == null || s.typewriter.isRemoved()) { forget(d, s); return; }
        if (s.phase == Phase.FALLING) return; // watched every tick (falling)
        if (!(pilot instanceof RaiderEntity p) || !p.isAlive() || !p.isPassenger()) { adrift(level, d, s, b); return; }
        Vec3 pos = world(root, s.typewriter.getBlockPos());
        Vec3 nose = normal(root, forward).multiply(1, 0, 1);
        Vec3 vel = s.last == null ? Vec3.ZERO : pos.subtract(s.last).scale(4); // blocks per second
        s.last = pos;
        long t = level.getGameTime();
        // Near the target, the bombs go where an airstrike would: on a defender of the base it raids (around the
        // target itself when that isn't the home, as with /rop airship), never within the blast of a raider.
        boolean onHome = b != null && s.target.distanceTo(Vec3.atBottomCenterOf(b.home)) < 2;
        if (s.phase == Phase.INBOUND && b != null && pos.subtract(s.target).horizontalDistance() < 64
                && (s.aim == null || t % 20 == 0 && RaidManager.friendsNear(level, s.aim, RaidManager.BLAST)))
            s.aim = RaidManager.strikeAim(level, b, d.stage, onHome ? null : s.target);
        Vec3 goal = s.phase == Phase.OUTBOUND ? s.away : s.aim != null ? s.aim : s.target;
        Vec3 to = goal.subtract(pos).multiply(1, 0, 1);
        double dist = to.length();

        // altitude: hot air is slow, so heat by how far off the cruise height and how fast it is climbing or sinking;
        // cruise height counts the highest ground just ahead, so a hill or a tree never touches the armed shells
        int ground = Math.max(highest(level, pos.add(nose.normalize().scale(16)), 16), (int) s.target.y);
        double err = ground + CRUISE - pos.y;
        boolean warming = !s.warm; // held still (steady): full heat, propellers off
        signal(s.burner, warming ? 15 : (int) Math.round(Mth.clamp(8 + err * 0.5 - vel.y * 4, 0, 15)));

        // heading: D/A swing the sail rudder (through the directional gearshift and torsion spring), so it only bites
        // at speed. The ship keeps swinging once it turns: steer on where the nose will be in ~1.5 s (yaw rate damping).
        double noseDeg = Math.toDegrees(Math.atan2(nose.z, nose.x));
        double yawRate = Double.isNaN(s.lastNose) ? 0 : Mth.wrapDegrees(noseDeg - s.lastNose) * 4; // degrees per second
        s.lastNose = noseDeg;
        double turn = Mth.wrapDegrees(Math.toDegrees(Math.atan2(to.z, to.x)) - noseDeg), lead = turn - yawRate * 1.5;
        hold(s, Math.abs(lead) < 8 ? -1 : lead * turnSign > 0 ? KEY_RIGHT : KEY_LEFT);
        power(s, warming ? 0 : 15); // spinning up while it sags only knocks the nose off course

        if (t % 40 == 0) RaidsOnPlayer.LOG.debug("Airship {} at {} height {}/{} vy {} nose {} turn {} dist {} burner {} throttle {}", s.phase, BlockPos.containing(pos),
                (int) pos.y, ground + CRUISE, String.format("%.2f", vel.y), (int) noseDeg, (int) turn + "/" + (int) yawRate, (int) dist, call(s.burner, "getState"), call(s.throttle, "getState"));
        if (s.dropTicks > 0 && --s.dropTicks == 0) call(s.typewriter, "releaseKey", KEY_DROP);
        boolean over = s.phase == Phase.INBOUND && s.aim != null && (dist < 10 || dist < 20 && to.dot(nose) < 0); // close, or just passed it
        if (over && !RaidManager.friendsNear(level, s.aim, RaidManager.BLAST)) { // over a clear target: bombs away, then on and out
            call(s.typewriter, "pressKey", KEY_DROP); // the stickers let go...
            s.dropTicks = 4;
            int shells = release(level, s, vel); // ...and the shells fall as projectiles
            Artillery.markZone(level, s.aim); // raiders caught in it anyway take a tenth (RaiderEntity.hurt)
            s.phase = Phase.OUTBOUND;
            s.since = t;
            if (b != null) RaidManager.tell(level, b, "The raider airship dropped its bombs on your base!", ChatFormatting.DARK_RED);
            RaidsOnPlayer.LOG.info("Airship dropped {} shells at {} onto {}", shells, BlockPos.containing(pos), BlockPos.containing(s.aim));
        } else if (s.phase == Phase.INBOUND && t - s.since > 4800) { // 4 min and no clear drop: leaves with the bombs
            s.phase = Phase.OUTBOUND;
            s.since = t;
        } else if (s.phase == Phase.OUTBOUND && (level.players().stream().noneMatch(pl -> pl.distanceToSqr(pos) < 160 * 160) || t - s.since > 3600)) {
            RaidsOnPlayer.LOG.info("Airship flew off at {}", BlockPos.containing(pos));
            remove(level, s.parts);
            pilot.discard();
            forget(d, s);
        }
    }

    /**
     * A new ship hangs still until its balloon can carry it: every tick each part's velocity is cancelled (Sable), and
     * how much the hull sank since the last tick is the lift test. A second of hardly any sinking: warm, let go. Never
     * warm in 90 s (a broken balloon): the ship is removed instead.
     */
    private static void steady(ServerLevel level, RaidData d, Ship s) {
        try {
            Class<?> body = Class.forName("dev.ryanhcode.sable.api.physics.handle.RigidBodyHandle");
            for (UUID u : s.parts) {
                Object sub = subLevel(level, u);
                if (sub == null) continue;
                Object h = call(body, "of", sub);
                if (h == null || !Boolean.TRUE.equals(call(h, "isValid"))) continue;
                Vector3dc v = (Vector3dc) call(h, "getLinearVelocity"), w = (Vector3dc) call(h, "getAngularVelocity");
                if (u.equals(s.root)) s.sink = v.y();
                call(h, "addLinearAndAngularVelocity", new Vector3d(v).negate(), new Vector3d(w).negate());
            }
        } catch (Exception e) {
            RaidsOnPlayer.LOG.warn("Could not hold the airship still; letting it go", e);
            s.warm = true;
            return;
        }
        long age = level.getGameTime() - s.launched;
        s.liftTicks = age > 200 && s.sink > -0.05 ? s.liftTicks + 1 : 0; // never before 10 s: warming up took 13-15 s every time
        if (age % 20 == 0) RaidsOnPlayer.LOG.debug("Airship warming up: sank {} blocks/s in the last tick, shells {}", String.format("%.3f", s.sink), shells(level, s.parts));
        if (s.liftTicks >= 20) {
            s.warm = true;
            RaidsOnPlayer.LOG.info("Airship warm after {} s; under way with {} shells", age / 20, shells(level, s.parts));
        } else if (age > 1800) {
            RaidsOnPlayer.LOG.warn("Airship balloon never lifted it in 90 s; removing the ship");
            remove(level, s.parts);
            if (s.pilot != null && level.getEntity(s.pilot) instanceof RaiderEntity p) p.discard();
            forget(d, s);
        }
    }

    /**
     * The pilot is dead (or fell out) and the ship is out of control: the rudder jams one way, the propellers keep
     * turning, the burner goes out, and it spirals down (falling).
     */
    private static void adrift(ServerLevel level, RaidData d, Ship s, @Nullable Base b) {
        hold(s, level.random.nextBoolean() ? KEY_LEFT : KEY_RIGHT); // stays held: nobody releases it any more
        signal(s.burner, 0);
        s.phase = Phase.FALLING;
        s.since = level.getGameTime();
        if (b != null) RaidManager.tell(level, b, "The raider pilot is down! His airship is out of control!", ChatFormatting.GOLD);
        RaidsOnPlayer.LOG.info("Airship pilot lost; the ship is out of control");
    }

    /**
     * Every tick while out of control: just before the hull or its hanging bombs reach the ground (or after a minute)
     * the ship blows up with its shells where it is, instead of crashing into the ground (see release).
     */
    private static void falling(ServerLevel level, RaidData d, Ship s) {
        Object root = subLevel(level, s.root);
        if (root == null) { forget(d, s); return; }
        Vec3 pos = world(root, s.typewriter.getBlockPos());
        Vec3 vel = s.last == null ? Vec3.ZERO : pos.subtract(s.last).scale(20);
        s.last = pos;
        if (pos.y - highest(level, pos, 10) > 14 && level.getGameTime() - s.since < 1200) return; // the rigs hang ~10 below the cockpit
        int shells = release(level, s, vel);
        remove(level, s.parts);
        level.explode(null, pos.x, pos.y, pos.z, 4f, Level.ExplosionInteraction.TNT);
        RaidsOnPlayer.LOG.info("Out-of-control airship blew up at {} ({} shells)", BlockPos.containing(pos), shells);
        forget(d, s);
    }

    private static void forget(RaidData d, Ship s) {
        SHIPS.remove(s);
        d.airships.removeAll(s.parts);
    }

    private static void hold(Ship s, int key) {
        if (key == s.held) return;
        if (s.held >= 0) call(s.typewriter, "releaseKey", s.held);
        if (key >= 0) call(s.typewriter, "pressKey", key);
        s.held = key;
    }

    /**
     * Propeller power 0-15. The throttle lever feeds the analog transmissions in front of the propellers, which brake:
     * speed = 1 - (signal + 1) / 16, so signal 0 is full power and 15 stops them (the blueprint was saved parked at 15).
     */
    private static void power(Ship s, int p) { signal(s.throttle, 15 - p); }

    private static void signal(BlockEntity lever, int strength) {
        if (!Integer.valueOf(strength).equals(call(lever, "getState"))) call(lever, "setSignal", strength);
    }

    // ---------------------------------------------------------------- Sable / Toolgun / Create glue

    private static void place(ServerLevel level, BlockPos at, byte[] bytes) throws ReflectiveOperationException {
        Class<?> c = Class.forName("com.enxv.aeronauticsstructuretool.printer.PortableStructurePrinterPlacement");
        for (Method m : c.getDeclaredMethods())
            if (m.getName().equals("placeBlueprint") && m.getParameterCount() == 7) {
                m.setAccessible(true);
                m.invoke(null, level, at, null, "raider_airship", bytes, UUID.randomUUID(), null);
                return;
            }
        throw new NoSuchMethodException("PortableStructurePrinterPlacement.placeBlueprint");
    }

    private static void sitDown(ServerLevel level, BlockPos seat, Entity e) throws ReflectiveOperationException {
        Class.forName("com.simibubi.create.content.contraptions.actors.seat.SeatBlock")
                .getMethod("sitDown", Level.class, BlockPos.class, Entity.class).invoke(null, level, seat, e);
    }

    /** Removes these ship parts (and whatever rides on them). */
    static void remove(ServerLevel level, List<UUID> parts) {
        try {
            Object container = container(level);
            for (UUID u : parts) {
                Object sub = subLevel(level, u);
                if (sub == null) continue;
                call(sub, "deleteAllEntities");
                sable().removeSubLevel.invoke(container, sub, sable().removed);
            }
        } catch (Throwable e) {
            RaidsOnPlayer.LOG.warn("Could not remove airship parts {}", parts, e);
        }
    }

    /**
     * Sable's sub-level container, bound through method handles: Java reflection lists every method of a class, and
     * SubLevelContainer has a ClientLevel overload that a dedicated server must never load.
     */
    private record Sable(MethodHandle getContainer, MethodHandle getAllSubLevels, MethodHandle getSubLevel, MethodHandle removeSubLevel, Object removed) {}
    @Nullable private static Sable sable;

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Sable sable() throws ReflectiveOperationException {
        if (sable != null) return sable;
        MethodHandles.Lookup l = MethodHandles.lookup();
        Class<?> base = Class.forName("dev.ryanhcode.sable.api.sublevel.SubLevelContainer"),
                server = Class.forName("dev.ryanhcode.sable.api.sublevel.ServerSubLevelContainer"),
                sub = Class.forName("dev.ryanhcode.sable.sublevel.SubLevel"),
                reason = Class.forName("dev.ryanhcode.sable.sublevel.storage.SubLevelRemovalReason");
        return sable = new Sable(
                l.findStatic(base, "getContainer", MethodType.methodType(server, ServerLevel.class)),
                l.findVirtual(server, "getAllSubLevels", MethodType.methodType(List.class)),
                l.findVirtual(base, "getSubLevel", MethodType.methodType(sub, UUID.class)),
                l.findVirtual(base, "removeSubLevel", MethodType.methodType(void.class, sub, reason)),
                Enum.valueOf((Class) reason, "REMOVED"));
    }

    private static Object container(ServerLevel level) throws Throwable {
        return Objects.requireNonNull(sable().getContainer.invoke(level), "Sable container");
    }

    private static List<?> subLevels(ServerLevel level) throws Throwable { return (List<?>) sable().getAllSubLevels.invoke(container(level)); }

    @Nullable private static Object subLevel(ServerLevel level, UUID id) {
        try { return sable().getSubLevel.invoke(container(level), id); } catch (Throwable e) { return null; }
    }

    private static UUID id(Object sub) { return (UUID) call(sub, "getUniqueId"); }

    private static List<LevelChunk> chunks(Object sub) {
        List<LevelChunk> out = new ArrayList<>();
        for (Object holder : (Collection<?>) call(call(sub, "getPlot"), "getLoadedChunks")) out.add((LevelChunk) call(holder, "getChunk"));
        return out;
    }

    /** A plot position on the ship, in the world. */
    private static Vec3 world(Object sub, BlockPos plot) { return (Vec3) call(call(sub, "logicalPose"), "transformPosition", Vec3.atCenterOf(plot)); }

    private static Vec3 normal(Object sub, Vec3 local) { return (Vec3) call(call(sub, "logicalPose"), "transformNormal", local); }

    /** Calls a public method by name and argument count, trying overloads until one takes the arguments. On a Class: its static method. */
    @Nullable
    static Object call(Object target, String name, Object... args) {
        Class<?> c = target instanceof Class<?> k ? k : target.getClass();
        for (Method m : c.getMethods()) {
            if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
            try {
                return m.invoke(target instanceof Class<?> ? null : target, args);
            } catch (IllegalArgumentException wrongOverload) {
                // try the next one
            } catch (InvocationTargetException e) {
                throw new RuntimeException(name + " failed", e.getCause());
            } catch (IllegalAccessException e) {
                throw new RuntimeException(name + " not accessible", e);
            }
        }
        throw new IllegalStateException("no method " + c.getSimpleName() + "." + name + " for " + args.length + " args");
    }
}
