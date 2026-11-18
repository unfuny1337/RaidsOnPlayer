package com.raidsonplayer;

import com.raidsonplayer.RaidData.Base;
import com.raidsonplayer.RaidData.Kind;
import com.raidsonplayer.RaidData.Squad;
import com.raidsonplayer.RaidData.State;
import net.minecraft.ChatFormatting;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.raid.Raid;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.MapItem;
import net.minecraft.world.item.enchantment.Enchantments;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.saveddata.maps.MapDecorationTypes;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.*;

/** Server-side brain: progression, bases and camps, day and night raids, scouts, patrols. Runs once per second on the overworld. */
public final class RaidManager {
    /** Advancements needed to reach each stage (index = stage). */
    static final ResourceLocation[][] STAGE_REQS = {
            {}, {},
            {ResourceLocation.parse("minecraft:story/smelt_iron")},
            {ResourceLocation.parse("minecraft:story/mine_diamond"), ResourceLocation.parse("create:andesite_alloy")},
            {ResourceLocation.parse("create:brass")},
    };
    static final int[] GARRISON_CAP = Composition.GARRISON_CAP;
    static final int CAMP_SPACING = 72; // blocks between raider camps (a stage 4 expansion camp clears 30 around its centre)
    static final int BASE_RADIUS = 64, SEEN_UNSEEN_TICKS = 3600, ATTACK_TIMEOUT = 4800;
    static final double MARCH_SPEED = 2.5; // blocks per second while virtual
    static final double CANNON_RANGE = 150;
    static final boolean CRANK_BACK_OPENS = false; // turn(back=true) extends the piston = closes the gate (measured in game)
    static final TicketType<ChunkPos> REBUILD_TICKET = TicketType.create("raidsonplayer_rebuild", java.util.Comparator.comparingLong(ChunkPos::toLong), 300);
    static final TicketType<ChunkPos> RAID_TICKET = TicketType.create("raidsonplayer_raid", java.util.Comparator.comparingLong(ChunkPos::toLong), 100);

    private RaidManager() {}

    public static void tick(MinecraftServer server) {
        ServerLevel level = server.overworld();
        long t = level.getGameTime();
        if (t % 20 != 0) return;
        RaidData d = RaidData.get(server);
        advanceClock(level, d, t);
        if (t % 100 == 0) {
            updateStage(server, d);
            updateMembership(server, d, t);
        }
        for (Base b : List.copyOf(d.bases.values())) tickBase(level, d, b, t);
        for (Squad s : List.copyOf(d.squads.values())) tickSquad(level, d, s, t);
        d.setDirty();
    }

    static long dayTime(Level level) { return level.getDayTime() % 24000; }

    /**
     * A base's raider points, in ticks (1200 = 1 point, so a day of time is 20): the clock (time and sleep) plus kills at the base.
     * Day raids, camp expansion, garrison regrowth, the night raid cooldown and patrols all run on it.
     */
    static long points(RaidData d, Base b) {
        Base main = b.parent != null ? d.bases.getOrDefault(b.parent, b) : b;
        return d.clock + main.bonus;
    }

    /**
     * Ticks that passed, plus however far the day jumped beyond them (sleeping, /time add). Setting the time back is ignored,
     * and with the daylight cycle off days still pass at the normal rate. Starts at game time, so older saves keep their timers.
     */
    static void advanceClock(ServerLevel level, RaidData d, long t) {
        long day = level.getDayTime(), ticks = t - d.lastTick;
        if (d.clock == 0) d.clock = t;
        else if (d.lastTick > 0 && ticks > 0 && ticks <= 200) d.clock += ticks + Math.max(0, day - d.lastDay - ticks);
        d.lastTick = t;
        d.lastDay = day;
    }

    /** Night raids run from dusk to dawn. */
    static boolean night(Level level) {
        long dt = dayTime(level);
        return dt >= 12500 && dt < 23000;
    }

    // ---------------------------------------------------------------- progression

    static void updateStage(MinecraftServer server, RaidData d) {
        List<ServerPlayer> players = server.getPlayerList().getPlayers();
        int s = Progression.nextStage(d.stage, players.size(), stage ->
                (int) players.stream().filter(p -> Arrays.stream(STAGE_REQS[stage]).allMatch(id -> done(p, id))).count());
        if (s > d.stage) setStage(server, d, s);
    }

    static void setStage(MinecraftServer server, RaidData d, int s) {
        d.stage = s;
        d.setDirty();
        server.getPlayerList().broadcastSystemMessage(
                Component.literal("The raiders grow stronger... (stage " + s + ")").withStyle(ChatFormatting.DARK_RED), false);
    }

    static boolean done(ServerPlayer p, ResourceLocation id) {
        AdvancementHolder h = p.server.getAdvancements().get(id);
        return h != null && p.getAdvancements().getOrStartProgress(h).isDone();
    }

    // ---------------------------------------------------------------- bases

    static void updateMembership(MinecraftServer server, RaidData d, long t) {
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            BlockPos rp = p.getRespawnPosition();
            boolean valid = rp != null && p.getRespawnDimension() == Level.OVERWORLD;
            Base cur = baseOf(d, p);
            if (cur != null && valid && cur.home.closerThan(rp, BASE_RADIUS)) continue;
            if (cur != null) cur.members.remove(p.getUUID());
            if (!valid) continue;
            Base near = d.bases.values().stream().filter(b -> b.parent == null && b.home.closerThan(rp, BASE_RADIUS)).findFirst().orElse(null);
            if (near == null) {
                near = new Base();
                near.home = rp.immutable();
                near.nextRaid = points(d, near) + raidInterval(server.overworld());
                near.lastRegen = points(d, near);
                near.scoutSent = true; // no scout due yet (the morning roll or a planned night calls one)
                d.bases.put(near.id, near);
                RaidsOnPlayer.LOG.info("New player base at {} for {}", rp, p.getName().getString());
            }
            near.members.add(p.getUUID());
        }
        for (Base x : d.bases.values()) // expansion camps raid whoever lives at the base they were built around
            if (x.parent != null) {
                x.members.clear();
                Base p = d.bases.get(x.parent);
                if (p != null) x.members.addAll(p.members);
            }
    }

    static long raidInterval(ServerLevel level) {
        int min = Config.RAID_MIN_DAYS.get(), max = Math.max(min, Config.RAID_MAX_DAYS.get());
        return (long) Mth.randomBetweenInclusive(level.random, min * 24000, max * 24000);
    }

    static void tickBase(ServerLevel level, RaidData d, Base b, long t) {
        if (b.camp == null) {
            if (t >= b.nextPlaceTry && !b.members.isEmpty()) placeCamp(level, d, b, t);
            return;
        }
        double seen = level.getServer().getPlayerList().getViewDistance() * 16.0;
        if (!level.getPlayers(p -> !p.isSpectator() && horizontalDist(p.position(), Vec3.atCenterOf(b.camp)) < seen).isEmpty()) b.lastSeen = t;
        if (b.campStage != d.stage) { // they rebuild overnight: from the next morning on, once no player has seen the camp for 3 min
            if (b.upgradeAt == 0) b.upgradeAt = d.clock + 24000 - dayTime(level);
            if (d.clock >= b.upgradeAt && b.raid == null && b.waves == 0 && nightSquads(d, b).isEmpty() && t - b.lastSeen >= SEEN_UNSEEN_TICKS
                    && campLoaded(level, b)) rebuild(level, d, b);
        }

        Squad g = d.squads.get(b.garrison);
        long pts = points(d, b);
        if (g != null && pts - b.lastRegen >= 24000) { // one raider per day's worth of points joins until the camp is at full strength
            b.lastRegen = pts;
            reinforce(g, d.stage, cap(b, d.stage), 1);
        }
        boolean night = night(level);
        if (g != null && b.raid == null && b.waves == 0 && !night && pts >= b.nextRaid && canRaid(g) && raidable(level, b)) {
            if (scouted(d, b) && level.random.nextDouble() < Config.FULL_DAY_CHANCE.get()) {
                b.nextRaid = pts + raidInterval(level);
                startFullRaid(level, d, b, t);
            } else startRaid(level, d, b, g, t);
        }

        // Tower cannons shell the player base when it is within range, someone is home and the artilleryman is in camp.
        if (!b.cannons.isEmpty() && t >= b.nextCannon && horizontalDist(Vec3.atCenterOf(b.camp), Vec3.atCenterOf(b.home)) <= CANNON_RANGE
                && g != null && g.roles.contains(Role.ARTILLERYMAN) && memberNear(level, b, 64)) {
            BlockPos m = b.cannons.get(level.random.nextInt(b.cannons.size()));
            Vec3 aim = groundNear(level, b.home, 8);
            if (friendsNear(level, aim, BLAST)) b.nextCannon = t + 100; // raiders at the target: hold fire, look again in 5 s
            else {
                b.nextCannon = t + 600 + level.random.nextInt(600);
                if (level.isLoaded(m)) Artillery.fireCannon(level, m, aim);
            }
        }

        tickFullRaid(level, d, b, t);
        if (b.parent != null) return; // expansion camps: garrison, day raids and cannons only
        tickNightPlan(level, d, b, t, night);
        tickNightRaid(level, d, b, t, night);
        tickPatrolRoll(level, d, b, t);
        tickExpansion(level, d, b, t);
    }

    // ---------------------------------------------------------------- expansion

    /** Every expansionDays the raiders put up another camp around a lived-in base, up to maxCampsPerBase (first one included). */
    static void tickExpansion(ServerLevel level, RaidData d, Base b, long t) {
        int days = Config.EXPAND_DAYS.get();
        if (days <= 0 || b.members.isEmpty()) return;
        long pts = points(d, b);
        if (b.nextExpand == 0) b.nextExpand = pts + days * 24000L;
        if (pts < b.nextExpand) return;
        b.nextExpand = pts + days * 24000L;
        if (camps(d, b) < Config.MAX_CAMPS.get()) expand(level, d, b, t);
    }

    /** A new camp around `b`'s home; it is built on the next base tick (placeCamp, retried until a site is found). */
    static void expand(ServerLevel level, RaidData d, Base b, long t) {
        Base x = new Base();
        x.parent = b.id;
        x.home = b.home;
        x.members.addAll(b.members);
        x.nextRaid = points(d, x) + raidInterval(level);
        x.lastRegen = points(d, x);
        d.bases.put(x.id, x);
    }

    static int camps(RaidData d, Base b) {
        return 1 + (int) d.bases.values().stream().filter(x -> b.id.equals(x.parent)).count();
    }

    /** A random surface point within `spread` blocks of `p` (the chunk must be loaded). */
    static Vec3 groundNear(ServerLevel level, BlockPos p, double spread) {
        double x = p.getX() + 0.5 + Mth.nextDouble(level.random, -spread, spread), z = p.getZ() + 0.5 + Mth.nextDouble(level.random, -spread, spread);
        return new Vec3(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, Mth.floor(x), Mth.floor(z)), z);
    }

    /** The side of the camp that faces the player base: the gate goes there. */
    static Direction facing(BlockPos camp, BlockPos home) {
        int dx = home.getX() - camp.getX(), dz = home.getZ() - camp.getZ();
        return Math.abs(dx) > Math.abs(dz) ? (dx > 0 ? Direction.EAST : Direction.WEST) : (dz > 0 ? Direction.SOUTH : Direction.NORTH);
    }

    /** Where a squad is headed while OUTBOUND or attacking: its own target, or the base home. */
    static BlockPos dest(Squad s, Base b) { return s.target != null ? s.target : b.home; }

    /** Keeps the base area loaded until its entities are in memory, so old contraptions can be removed before a rebuild. */
    static boolean campLoaded(ServerLevel level, Base b) {
        ChunkPos c = new ChunkPos(b.camp);
        level.getChunkSource().addRegionTicket(REBUILD_TICKET, c, 3, c);
        for (int dx = -2; dx <= 2; dx++)
            for (int dz = -2; dz <= 2; dz++) if (!level.areEntitiesLoaded(ChunkPos.asLong(c.x + dx, c.z + dz))) return false;
        return true;
    }

    static void apply(Base b, CampBuilder.Layout l, int stage) {
        b.chests.clear();
        b.chests.addAll(l.chests());
        b.cannons.clear();
        b.cannons.addAll(l.cannons());
        b.gate = l.gate();
        b.gateCrank = l.gateCrank();
        b.campRadius = l.radius();
        b.campStage = stage;
    }

    /** Opens or closes the fence gates of the outpost gate (the watchman does it when a raid leaves or comes home). */
    static void setGate(ServerLevel level, Base b, boolean open) {
        if (b.gate == null || !level.isLoaded(b.gate)) return;
        if (b.gateCrank != null) { Cranks.turn(level, b.gateCrank, open == CRANK_BACK_OPENS, 100); return; } // stage 4 Create gate
        for (BlockPos p : BlockPos.betweenClosed(b.gate.offset(-1, 0, -1), b.gate.offset(1, 0, 1))) {
            BlockState st = level.getBlockState(p);
            if (st.getBlock() instanceof FenceGateBlock && st.getValue(FenceGateBlock.OPEN) != open)
                level.setBlock(p, st.setValue(FenceGateBlock.OPEN, open), 10);
        }
        level.playSound(null, b.gate, open ? SoundEvents.FENCE_GATE_OPEN : SoundEvents.FENCE_GATE_CLOSE, SoundSource.BLOCKS, 1f, 1f);
    }

    static void placeCamp(ServerLevel level, RaidData d, Base b, long t) {
        int min = Config.CAMP_MIN_DIST.get(), max = Math.max(min, Config.CAMP_MAX_DIST.get());
        for (int i = 0; i < 12; i++) {
            double a = level.random.nextDouble() * Math.PI * 2, r = Mth.nextDouble(level.random, min, max);
            int x = b.home.getX() + (int) (Math.cos(a) * r), z = b.home.getZ() + (int) (Math.sin(a) * r);
            // not near another player's home (its own home is skipped: rounding puts a site at exactly `min` just inside it)
            if (d.bases.values().stream().anyMatch(o -> o != b && !o.home.equals(b.home) && o.home.distToCenterSqr(x, o.home.getY(), z) < (double) min * min)) continue;
            if (d.bases.values().stream().anyMatch(o -> o.camp != null && o.camp.distToCenterSqr(x, o.camp.getY(), z) < CAMP_SPACING * CAMP_SPACING)) continue;
            BlockPos c = CampBuilder.trySite(level, x, z);
            if (c == null) continue;
            b.camp = c;
            apply(b, CampBuilder.build(level, c, d.stage, 0, facing(c, b.home), b.parent != null, level.random), d.stage);
            setGate(level, b, false);
            Squad g = newSquad(b, Kind.GARRISON, State.GARRISON, Vec3.atBottomCenterOf(c), Composition.garrison(d.stage, cap(b, d.stage)), t);
            d.squads.put(g.id, g);
            b.garrison = g.id;
            RaidsOnPlayer.LOG.info("Raider camp (stage {}) built at {} for base {}", d.stage, c, b.home);
            if (b.parent != null) tell(level, b, "The raiders built a new camp to the " + direction(b.home, c) + " of your base! ("
                    + camps(d, d.bases.getOrDefault(b.parent, b)) + " camps now)", ChatFormatting.DARK_RED);
            return;
        }
        b.nextPlaceTry = t + 600; // all candidates were water or cliffs: try again in 30 s
    }

    static Squad newSquad(Base b, Kind kind, State state, Vec3 pos, List<Role> roles, long t) {
        Squad s = new Squad();
        s.base = b.id;
        s.kind = kind;
        s.state = state;
        s.pos = pos;
        s.roles.addAll(roles);
        s.startCount = roles.size();
        s.hadLeader = roles.contains(Role.LEADER);
        s.stateSince = t;
        return s;
    }

    static void rebuild(ServerLevel level, RaidData d, Base b) {
        Squad g = d.squads.get(b.garrison);
        if (g != null) dematerialize(g, level); // nobody is watching, so no one sees them vanish
        List<ItemStack> kept = CampBuilder.empty(level, b.chests);
        Artillery.clearContraptions(level, b.camp, b.campRadius + 4);
        apply(b, CampBuilder.build(level, b.camp, d.stage, b.campRadius, facing(b.camp, b.home), b.parent != null, level.random), d.stage);
        CampBuilder.deposit(level, b.camp, b.chests, kept);
        setGate(level, b, false);
        if (g != null) reinforce(g, d.stage, cap(b, d.stage), Integer.MAX_VALUE);
        b.upgradeAt = 0;
        RaidsOnPlayer.LOG.info("Raider camp at {} rebuilt for stage {}", b.camp, d.stage);
    }

    /** Adds up to n classes the garrison lacks. At full strength a surplus raider makes room, so it never grows past the cap. */
    /** Full garrison strength of this camp: expansion camps hold half again as many. */
    static int cap(Base b, int stage) { return (b.parent != null ? Composition.EXPANSION_CAP : GARRISON_CAP)[stage]; }

    static void reinforce(Squad g, int stage, int cap, int n) {
        List<Role> miss = Composition.missing(g.roles, stage, cap);
        for (int i = 0; i < n && i < miss.size(); i++) {
            if (g.roles.size() >= cap && !g.roles.remove(Role.RAIDER)) break;
            g.roles.add(miss.get(i));
        }
    }

    /** Someone to raid: a player of the base is home, or raidsWhenAway is on and the base still has players. */
    static boolean raidable(ServerLevel level, Base b) {
        return Config.RAIDS_WHEN_AWAY.get() ? !b.members.isEmpty() : memberNear(level, b, 128);
    }

    /** raidsWhenAway: a day or night raid squad at a base nobody is near keeps the area loaded and raids it anyway. */
    static boolean raidingAway(Squad s, Base b) {
        return Config.RAIDS_WHEN_AWAY.get() && (s.kind == Kind.RAID || s.kind == Kind.NIGHT) && s.state != State.RETREAT
                && horizontalDist(s.pos, Vec3.atBottomCenterOf(dest(s, b))) < 48;
    }

    static boolean memberNear(ServerLevel level, Base b, double r) {
        return b.members.stream().map(level::getPlayerByUUID)
                .anyMatch(p -> p != null && horizontalDist(p.position(), Vec3.atCenterOf(b.home)) < r);
    }

    static void tell(ServerLevel level, Base b, String msg, ChatFormatting color) {
        for (UUID u : b.members)
            if (level.getServer().getPlayerList().getPlayer(u) instanceof ServerPlayer p)
                p.sendSystemMessage(Component.literal(msg).withStyle(color));
    }

    static void horn(ServerLevel level, Base b) {
        for (UUID u : b.members)
            if (level.getPlayerByUUID(u) instanceof ServerPlayer p)
                p.playNotifySound(SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 1f, 1f);
    }

    // ---------------------------------------------------------------- day raids

    /** Who leaves on a day raid: everyone but the watchman (a raider) and the artilleryman, who stays with the guns. */
    static List<Role> raidParty(Squad g) {
        List<Role> going = new ArrayList<>(g.roles);
        going.remove(Role.ARTILLERYMAN);
        going.remove(Role.RAIDER);
        return going;
    }

    static boolean canRaid(Squad g) { return raidParty(g).size() >= 2; }

    static void startRaid(ServerLevel level, RaidData d, Base b, Squad g, long t) {
        List<Role> going = raidParty(g);
        Squad s = newSquad(b, Kind.RAID, State.OUTBOUND, g.pos, going, t);
        for (Role r : going) g.roles.remove(r);
        List<Role> need = new ArrayList<>(going);
        for (Iterator<UUID> it = g.live.iterator(); it.hasNext(); ) {
            if (level.getEntity(it.next()) instanceof RaiderEntity e && !e.watchman && need.remove(e.role())) {
                it.remove();
                e.squadId = s.id;
                e.clearRestriction();
                s.live.add(e.getUUID());
            }
        }
        d.squads.put(s.id, s);
        setGate(level, b, true);
        b.raid = s.id;
        b.nextRaid = points(d, b) + raidInterval(level);
        tell(level, b, going.size() + " raiders set out from their camp to the " + direction(b.home, b.camp) + "!", ChatFormatting.RED);
        horn(level, b);
        RaidsOnPlayer.LOG.info("Raid of {} {} launched from {} at base {}", going.size(), going, b.camp, b.home);
    }

    static String direction(BlockPos from, BlockPos to) {
        double a = Math.toDegrees(Math.atan2(to.getX() - from.getX(), from.getZ() - to.getZ()));
        String[] dirs = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};
        return dirs[(int) Math.floorMod(Math.round(a / 45), 8)];
    }

    // ---------------------------------------------------------------- night raids and scouts

    /** Weekly roll in the morning; the scout goes out that day; at dusk the raid launches against what the raiders know. */
    static void tickNightPlan(ServerLevel level, RaidData d, Base b, long t, boolean night) {
        long dt = dayTime(level);
        long pts = points(d, b);
        if (b.nextNight == 0) b.nextNight = pts + Config.NIGHT_PERIOD_DAYS.get() * 24000L;
        if (!b.members.isEmpty() && !b.nightPlanned && pts >= b.nextNight && dt < 6000) { // rolled in the morning so a scout has the day
            if (level.random.nextDouble() < nightChance(b)) {
                planNight(b);
                b.nightMisses = 0;
                b.nextNight = pts + Config.NIGHT_PERIOD_DAYS.get() * 24000L;
            } else {
                b.nightMisses++;
                b.nextNight = pts + 24000 - dt; // try again next morning, with better odds
            }
        }
        if (!night && dt < 11000 && !b.scoutSent && b.scout == null && memberNear(level, b, 128)) sendScout(level, d, b, t); // planned night or a lone visit
        if (!b.nightPlanned) return;
        if (night) {
            Squad sc = b.scout == null ? null : d.squads.get(b.scout);
            if (sc != null && sc.sawPlayer) scoutReported(level, d, b, sc); // still out at dusk, but alive: his report is in
            launchNight(level, d, b, t);
        }
    }

    /** Chance of this morning's night raid roll: the base chance plus one step per morning it has missed, up to certain. */
    static double nightChance(Base b) { return Math.min(1, Config.NIGHT_CHANCE.get() + b.nightMisses * Config.NIGHT_CHANCE_STEP.get()); }

    static void planNight(Base b) {
        b.nightPlanned = true;
        b.freshIntel = false;
        b.scoutSent = false;
    }

    static void sendScout(ServerLevel level, RaidData d, Base b, long t) {
        Squad s = newSquad(b, Kind.SCOUT, State.OUTBOUND, Vec3.atBottomCenterOf(b.camp), List.of(Role.SCOUT), t);
        s.target = watchPoint(level, b);
        d.squads.put(s.id, s);
        b.scout = s.id;
        b.scoutSent = true;
        RaidsOnPlayer.LOG.info("Scout sent from {} to watch {} from {}", b.camp, b.home, s.target);
    }

    /** About 40 blocks from home on the camp's side, on the highest ground among a few loaded candidates. */
    static BlockPos watchPoint(ServerLevel level, Base b) {
        Vec3 home = Vec3.atCenterOf(b.home), dir = Vec3.atCenterOf(b.camp).subtract(home).multiply(1, 0, 1).normalize();
        BlockPos best = BlockPos.containing(home.add(dir.scale(40)));
        int bestY = Integer.MIN_VALUE;
        for (int i = -3; i <= 3; i++) {
            Vec3 side = new Vec3(-dir.z, 0, dir.x).scale(i * 6);
            BlockPos p = BlockPos.containing(home.add(dir.scale(40 + Math.abs(i) * 3)).add(side));
            if (!level.isLoaded(p)) continue;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ());
            if (!level.getFluidState(new BlockPos(p.getX(), y - 1, p.getZ())).isEmpty()) continue; // not on a lake
            if (y > bestY) { bestY = y; best = new BlockPos(p.getX(), y, p.getZ()); }
        }
        return best;
    }

    static void scoutReported(ServerLevel level, RaidData d, Base b, Squad sc) {
        sc.sawPlayer = false; // report once
        b.freshIntel = true;
        for (UUID m : b.members) d.lastScouted.put(m, b.id);
        tell(level, b, "A raider scout got away. They know where your base is now...", ChatFormatting.GOLD);
        RaidsOnPlayer.LOG.info("Scout reported base {}", b.home);
    }

    /** Dusk on a planned night: hit this base with a fresh report, else the last base a scout reported, else nothing. */
    static void launchNight(ServerLevel level, RaidData d, Base b, long t) {
        Base target = b;
        if (!b.freshIntel) {
            target = b.members.stream().map(d.lastScouted::get).filter(Objects::nonNull).map(d.bases::get)
                    .filter(x -> x != null && x.camp != null).findFirst().orElse(null);
            if (target == null) {
                b.nightPlanned = false;
                tell(level, b, "The raiders don't know where you live. No night raid tonight.", ChatFormatting.GREEN);
                return;
            }
        }
        // nobody home: they wait for someone, all night and the nights after, and send another scout each day
        Vec3 at = Vec3.atCenterOf(target.home); // (an old base has no members of its own: whoever planned this counts)
        if (!Config.RAIDS_WHEN_AWAY.get() && b.members.stream().map(level::getPlayerByUUID).noneMatch(p -> p != null && horizontalDist(p.position(), at) < 128)) {
            b.scoutSent = false;
            return;
        }
        b.nightPlanned = false;
        boolean fresh = target == b && b.freshIntel;
        if (target.waves > 0) return; // a full-sized raid is already on there
        if (fresh && level.random.nextDouble() < Config.FULL_NIGHT_CHANCE.get()) { // the scout's report calls for everything they have
            b.scout = null;
            startFullRaid(level, d, b, t);
            return;
        }
        List<List<Role>> parties = Composition.night(d.stage, new Random(level.random.nextLong()), Config.demomanChance(d.stage));
        int total = sendOut(d, target, Kind.NIGHT, parties, t);
        target.freshIntel = fresh; // demomen work better with a fresh report
        target.strikes = fresh && d.stage >= 3 ? (d.stage >= 4 ? 3 + level.random.nextInt(3) : 2 + level.random.nextInt(2)) : 0;
        target.nextStrike = Long.MAX_VALUE; // set when the first squad arrives
        b.freshIntel = fresh && target == b;
        b.scout = null;
        String msg = "Night raid! " + total + " raiders in " + parties.size() + " squads are coming from the " + direction(target.home, target.camp) + "!";
        tell(level, b, target == b ? msg : "The raiders are attacking your old base at " + target.home.toShortString() + "!", ChatFormatting.DARK_RED);
        if (target != b) tell(level, target, msg, ChatFormatting.DARK_RED);
        setGate(level, target, true);
        wake(level, target);
        horn(level, target);
        if (target != b) horn(level, b);
        RaidsOnPlayer.LOG.info("Night raid of {} in {} squads from {} on {} (fresh intel {})", total, parties.size(), target.camp, target.home, fresh);
    }

    /** Squads march out of the camp side by side and fan out on arrival, hitting the home from several sides. Returns the head count. */
    static int sendOut(RaidData d, Base b, Kind kind, List<List<Role>> parties, long t) {
        Vec3 camp = Vec3.atBottomCenterOf(b.camp), home = Vec3.atBottomCenterOf(b.home);
        Vec3 side = new Vec3(-(home.z - camp.z), 0, home.x - camp.x).normalize();
        int total = 0;
        for (int i = 0; i < parties.size(); i++) {
            double o = i - (parties.size() - 1) / 2.0;
            Squad s = newSquad(b, kind, State.OUTBOUND, camp.add(side.scale(o * 4)), parties.get(i), t);
            s.target = BlockPos.containing(home.add(side.scale(o * 8)));
            d.squads.put(s.id, s);
            total += s.count();
        }
        return total;
    }

    // ---------------------------------------------------------------- full-sized raids

    static final int WAVE_BREAK = 600, WAVE_TIMEOUT = 6000; // 30 s between waves; a wave still fighting after 5 min gets the next one on top

    /**
     * Wave defense: stage + 1 waves from the camp, each sent once the last is beaten (or has fought 5 minutes); later waves
     * send more squads. Only against a base a scout has reported. Ignores dawn, so a night one can run into the day.
     */
    static void startFullRaid(ServerLevel level, RaidData d, Base b, long t) {
        b.waves = Composition.waves(d.stage);
        b.wave = 0;
        b.nextWave = t;
        b.freshIntel = true; // they know the layout: demomen carry more TNT and blow walls without waiting
        tell(level, b, "A full-sized raid! The raiders are coming at you in " + b.waves + " waves from the " + direction(b.home, b.camp) + "!", ChatFormatting.DARK_RED);
        wake(level, b);
        RaidsOnPlayer.LOG.info("Full raid of {} waves from {} on {}", b.waves, b.camp, b.home);
        launchWave(level, d, b, t);
    }

    static List<Squad> fullSquads(RaidData d, Base b) {
        return d.squads.values().stream().filter(s -> s.kind == Kind.FULL && s.base.equals(b.id)).toList();
    }

    static void tickFullRaid(ServerLevel level, RaidData d, Base b, long t) {
        if (b.waves == 0) return;
        boolean fighting = fullSquads(d, b).stream().anyMatch(s -> s.state != State.RETREAT);
        if (b.wave >= b.waves) { // the last wave is out: the raid is over when it is beaten
            if (!fighting) endFullRaid(level, d, b, true);
            return;
        }
        if (fighting && t - b.waveAt < WAVE_TIMEOUT) return;
        if (b.nextWave == 0) {
            b.nextWave = fighting ? t : t + WAVE_BREAK;
            if (!fighting) tell(level, b, "Wave " + b.wave + " of " + b.waves + " is beaten! The next one comes in 30 seconds.", ChatFormatting.GOLD);
        }
        if (t >= b.nextWave) launchWave(level, d, b, t);
    }

    static void launchWave(ServerLevel level, RaidData d, Base b, long t) {
        if (!memberNear(level, b, 128)) { endFullRaid(level, d, b, false); return; }
        b.wave++;
        b.nextWave = 0;
        b.waveAt = t;
        b.waveArrived = false;
        Random r = new Random(level.random.nextLong());
        List<List<Role>> parties = new ArrayList<>();
        for (int i = Composition.waveSquads(d.stage, b.wave); i > 0; i--) parties.add(Composition.squad(d.stage, r, Config.demomanChance(d.stage)));
        int total = sendOut(d, b, Kind.FULL, parties, t);
        setGate(level, b, true);
        horn(level, b);
        tell(level, b, (b.wave == b.waves ? "Final wave! " : "Wave " + b.wave + " of " + b.waves + ": ") + total + " raiders in "
                + parties.size() + (parties.size() == 1 ? " squad" : " squads") + " are coming!", ChatFormatting.RED);
        RaidsOnPlayer.LOG.info("Full raid wave {}/{}: {} squads, {} raiders", b.wave, b.waves, parties.size(), total);
    }

    static void endFullRaid(ServerLevel level, RaidData d, Base b, boolean won) {
        tell(level, b, won ? "You held off the full-sized raid! All " + b.waves + " waves are beaten."
                : "Nobody was home: the raiders called off the full-sized raid.", won ? ChatFormatting.GREEN : ChatFormatting.GOLD);
        b.wave = b.waves = 0;
        b.nextWave = 0;
        if (fullSquads(d, b).isEmpty() && nightSquads(d, b).isEmpty()) setGate(level, b, false);
    }

    /** The horn wakes everyone sleeping near the base. */
    static void wake(ServerLevel level, Base b) {
        for (ServerPlayer p : level.players())
            if (p.isSleeping() && horizontalDist(p.position(), Vec3.atCenterOf(b.home)) < 128) p.stopSleepInBed(true, true);
    }

    static List<Squad> nightSquads(RaidData d, Base b) {
        return d.squads.values().stream().filter(s -> s.kind == Kind.NIGHT && s.base.equals(b.id)).toList();
    }

    /** True while a night raid is under way near this player: no sleeping through it. */
    static boolean nightRaidNear(ServerPlayer p) {
        RaidData d = RaidData.get(p.server);
        return d.bases.values().stream().anyMatch(b -> horizontalDist(p.position(), Vec3.atCenterOf(b.home)) < 128
                && (b.waves > 0 || nightSquads(d, b).stream().anyMatch(s -> s.state != State.RETREAT)));
    }

    static void tickNightRaid(ServerLevel level, RaidData d, Base b, long t, boolean night) {
        List<Squad> squads = nightSquads(d, b);
        if (squads.isEmpty()) { b.strikes = 0; return; }
        if (!night && squads.stream().anyMatch(s -> s.state != State.RETREAT)) {
            for (Squad s : squads) enter(s, State.RETREAT, t);
            tell(level, b, "Dawn breaks. The night raiders fall back to their camp.", ChatFormatting.GOLD);
            return;
        }
        if (b.strikes > 0 && t >= b.nextStrike && squads.stream().anyMatch(s -> s.state == State.ATTACK)) {
            if (!artilleryAlive(d, b)) { b.strikes = 0; return; } // no one left to call the guns
            if (!callStrike(level, d, b)) return; // their own men are in the way: try again in a second
            b.strikes--;
            b.nextStrike = t + (d.stage >= 4 ? 400 + level.random.nextInt(400) : 800 + level.random.nextInt(600));
        }
    }

    static boolean artilleryAlive(RaidData d, Base b) {
        return d.squads.values().stream().anyMatch(s -> s.base.equals(b.id) && s.roles.contains(Role.ARTILLERYMAN));
    }

    /**
     * An airstrike is due. An artilleryman out on a raid (preferably one who can see a player) fires a flare and the
     * shells follow on what he sees; with none in the field, the camp's guns fire blind on the home area. False (nothing
     * fired) while raiders are too close to the target.
     */
    static boolean callStrike(ServerLevel level, RaidData d, Base b) {
        RaiderEntity spotter = null;
        for (Squad s : d.squads.values())
            if (s.base.equals(b.id) && s.kind.assault() && s.state != State.RETREAT)
                for (UUID u : s.live)
                    if (level.getEntity(u) instanceof RaiderEntity e && e.role() == Role.ARTILLERYMAN && e.isAlive() && e.strikeAt < 0
                            && (spotter == null || spotter.spotted() == null && e.spotted() != null)) spotter = e;
        if (spotter != null) {
            Player p = spotter.spotted();
            if (strikeAim(level, b, d.stage, p == null ? null : p.position()) == null) return false;
            spotter.signal();
            return true;
        }
        if (!strike(level, b, d.stage, null)) return false;
        tell(level, b, "The raiders called an airstrike from the " + direction(b.home, b.camp) + "! Take cover!", ChatFormatting.DARK_RED);
        return true;
    }

    /**
     * No raider may be this close to where a shell lands. A CBC HE shell (entity explosive power 12) hurts out to 24
     * blocks; at 14 one of their own (10% damage to raiders, RaiderEntity.hurt) still takes ~5, a quarter of their health.
     */
    static final double BLAST = 14;

    /** A raider within r (horizontally; shells fall from the sky, so ±48 in height) of `at`: the guns hold fire rather than hit their own. */
    static boolean friendsNear(ServerLevel level, Vec3 at, double r) {
        return !level.getEntitiesOfClass(RaiderEntity.class, AABB.ofSize(at, r * 2, 96, r * 2),
                e -> e.isAlive() && horizontalDist(e.position(), at) < r).isEmpty();
    }

    static double strikeSpread(int stage) { return stage >= 4 ? 4 : 8; }

    /**
     * Where an airstrike would aim: around `seen` (else a defender near home, else the home) with a fresh random offset,
     * a few tries for a spot clear of raiders. Null if every try lands too close to them.
     */
    @Nullable static Vec3 strikeAim(ServerLevel level, Base b, int stage, @Nullable Vec3 seen) {
        List<ServerPlayer> targets = b.members.stream().map(u -> level.getServer().getPlayerList().getPlayer(u))
                .filter(p -> p != null && p.level() == level && horizontalDist(p.position(), Vec3.atCenterOf(b.home)) < 64).toList();
        Vec3 at = seen != null ? seen : targets.isEmpty() ? groundNear(level, b.home, 6) : targets.get(level.random.nextInt(targets.size())).position();
        double off = stage >= 4 ? 4 : 10, clear = strikeSpread(stage) * 1.5 + BLAST; // shells scatter over a square: its corners
        for (int i = 0; i < 4; i++) {
            Vec3 aim = at.add(Mth.nextDouble(level.random, -off, off), 0, Mth.nextDouble(level.random, -off, off));
            if (!friendsNear(level, aim, clear)) return aim;
        }
        return null;
    }

    /** Shells on strikeAim. Stage 4: more shells, tighter. False if raiders are too close to the target to fire. */
    static boolean strike(ServerLevel level, Base b, int stage, @Nullable Vec3 seen) {
        Vec3 aim = strikeAim(level, b, stage, seen);
        if (aim == null) return false;
        int shells = stage >= 4 ? 3 + level.random.nextInt(2) : 2;
        Artillery.airstrike(level, aim, Vec3.atCenterOf(b.camp).subtract(aim), shells, strikeSpread(stage), level.random);
        return true;
    }

    // ---------------------------------------------------------------- patrols

    static void tickPatrolRoll(ServerLevel level, RaidData d, Base b, long t) {
        long pts = points(d, b);
        if (b.members.isEmpty() || pts < b.nextPatrol || dayTime(level) > 6000) return;
        b.nextPatrol = pts + 24000 - dayTime(level); // next morning
        // a scout looks in today (tickNightPlan sends him once someone is home); planned nights send their own
        if (!b.nightPlanned && b.scout == null && level.random.nextDouble() < Config.SCOUT_CHANCE.get()) b.scoutSent = false;
        if (b.patrol == null && level.random.nextDouble() < Config.PATROL_CHANCE.get()) startPatrol(level, d, b, t);
    }

    static void startPatrol(ServerLevel level, RaidData d, Base b, long t) {
        Squad s = newSquad(b, Kind.PATROL, State.OUTBOUND, Vec3.atBottomCenterOf(b.camp), Composition.patrol(new Random(level.random.nextLong())), t);
        s.target = waypoint(level, b);
        d.squads.put(s.id, s);
        b.patrol = s.id;
        setGate(level, b, true);
        RaidsOnPlayer.LOG.info("Patrol of {} out from {}", s.roles, b.camp);
    }

    /** Somewhere on dry land 30-110 blocks around the player base (unloaded ground is taken as is: they walk it as data). */
    static BlockPos waypoint(ServerLevel level, Base b) {
        for (int i = 0; i < 8; i++) {
            double a = level.random.nextDouble() * Math.PI * 2, r = Mth.nextDouble(level.random, 30, 110);
            BlockPos p = BlockPos.containing(b.home.getX() + Math.cos(a) * r, b.home.getY(), b.home.getZ() + Math.sin(a) * r);
            if (!level.isLoaded(p)) return p;
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, p.getX(), p.getZ());
            if (level.getFluidState(new BlockPos(p.getX(), y - 1, p.getZ())).isEmpty()) return new BlockPos(p.getX(), y, p.getZ());
        }
        return b.home;
    }

    // ---------------------------------------------------------------- squads

    static void tickSquad(ServerLevel level, RaidData d, Squad s, long t) {
        Base b = d.bases.get(s.base);
        if (b == null || b.camp == null || (s.count() <= 0 && s.kind != Kind.GARRISON)) {
            dematerialize(s, level);
            d.squads.remove(s.id);
            if (b != null) ended(level, d, b, s);
            return;
        }
        s.live.removeIf(u -> !(level.getEntity(u) instanceof RaiderEntity e) || !e.isAlive() || !s.id.equals(e.squadId));
        boolean mat = !s.live.isEmpty();
        if (mat) s.pos = centroid(level, s);
        double range = mat ? 112 : 96; // hysteresis so squads don't flicker at the edge
        boolean away = raidingAway(s, b);
        if (away) { // nobody around to load the base: the raid does it (5x5 chunks entity-ticking around the squad)
            ChunkPos c = new ChunkPos(BlockPos.containing(s.pos));
            level.getChunkSource().addRegionTicket(RAID_TICKET, c, 4, c);
        }
        boolean near = away || !level.getPlayers(p -> !p.isSpectator() && horizontalDist(p.position(), s.pos) < range).isEmpty();
        if (!near) {
            if (mat) dematerialize(s, level);
            mat = false;
        } else if (s.live.size() < s.count() && level.isPositionEntityTicking(BlockPos.containing(s.pos))
                && level.getDifficulty() != Difficulty.PEACEFUL) {
            materialize(level, d, s, b);
            mat = true;
        }

        Vec3 dest = Vec3.atBottomCenterOf(dest(s, b)), camp = Vec3.atBottomCenterOf(b.camp);
        if (!mat && s.state == State.GARRISON) s.pos = camp;
        if (!mat && (s.state == State.OUTBOUND || s.state == State.RETREAT)) {
            Vec3 to = s.state == State.OUTBOUND ? dest : camp;
            Vec3 dir = to.subtract(s.pos).multiply(1, 0, 1);
            double len = dir.length();
            s.pos = len <= MARCH_SPEED ? new Vec3(to.x, s.pos.y, to.z) : s.pos.add(dir.scale(MARCH_SPEED / len));
        }
        switch (s.kind) {
            case GARRISON -> { }
            case SCOUT -> tickScout(level, d, s, b, mat, t);
            case PATROL -> tickPatrol(level, d, s, b, mat, t);
            default -> tickRaid(level, d, s, b, mat, t);
        }
    }

    /** A squad is gone (all dead, or its base vanished). */
    static void ended(ServerLevel level, RaidData d, Base b, Squad s) {
        if (s.id.equals(b.raid)) {
            b.raid = null;
            tell(level, b, "The raid is broken!", ChatFormatting.GREEN);
        }
        if (s.id.equals(b.scout)) b.scout = null;
        if (s.id.equals(b.patrol)) b.patrol = null;
        if (s.kind == Kind.NIGHT) tell(level, b, nightSquads(d, b).isEmpty() ? "The last night raid squad is wiped out!" : "A night raid squad is wiped out!", ChatFormatting.GREEN);
    }

    static void tickRaid(ServerLevel level, RaidData d, Squad s, Base b, boolean mat, long t) {
        boolean nightRaid = s.kind != Kind.RAID; // night and full-raid squads: one of several, fight until beaten
        String who = nightRaid ? "A raider squad" : "The raiders";
        switch (s.state) {
            case GARRISON -> { }
            case OUTBOUND -> {
                if (mat && losing(level, s)) {
                    enter(s, State.RETREAT, t);
                    tell(level, b, who + (nightRaid ? " was" : " were") + " beaten on the road and turns back!", ChatFormatting.GREEN);
                } else if (horizontalDist(s.pos, Vec3.atBottomCenterOf(dest(s, b))) < 24) {
                    if (mat) {
                        enter(s, State.ATTACK, t);
                        if (s.kind == Kind.FULL) {
                            if (!b.waveArrived) tell(level, b, "Wave " + b.wave + " has reached your base!", ChatFormatting.RED);
                            b.waveArrived = true;
                        } else if (nightRaid && b.nextStrike == Long.MAX_VALUE) {
                            b.nextStrike = t + 300 + level.random.nextInt(300); // first shells soon after they reach the walls
                            tell(level, b, "The night raiders have reached your base!", ChatFormatting.RED);
                        } else if (!nightRaid) tell(level, b, "The raiders have reached your base!", ChatFormatting.RED);
                    } else if (!raidingAway(s, b) || level.getDifficulty() == Difficulty.PEACEFUL) enter(s, State.RETREAT, t); // nobody home: nothing to raid (raidsWhenAway: wait for the area to load)
                }
            }
            case ATTACK -> {
                if (s.kind != Kind.NIGHT && d.stage >= 3 && !s.airstrike && mat && t - s.stateSince > 300 && artilleryAlive(d, b) && scouted(d, b)) {
                    s.airstrike = callStrike(level, d, b); // not while they would hit their own: retried every second
                }
                if (losing(level, s)) {
                    enter(s, State.RETREAT, t);
                    tell(level, b, who + (s.leaderless() ? ", leaderless, " + (nightRaid ? "breaks" : "break") : nightRaid ? " is losing and pulls" : " are losing and pull") + " back to their camp!", ChatFormatting.GREEN);
                } else if (!mat || !nightRaid && (t - s.stateSince > ATTACK_TIMEOUT || s.stolen >= 2 * s.count()
                        || s.stolen > 0 && t - s.lastSteal > 600)) { // day raid: took something and found nothing more for 30 s
                    enter(s, State.RETREAT, t);
                    tell(level, b, who + (nightRaid ? " heads" : " head") + (s.stolen > 0 ? " home with your loot!" : " home."), ChatFormatting.GOLD);
                }
            }
            case RETREAT -> {
                if (horizontalDist(s.pos, Vec3.atBottomCenterOf(b.camp)) < 12) returnHome(level, d, s, b);
            }
        }
    }

    /** A night raid with a fresh scout report, or a full-sized raid: demomen carry more TNT and blow walls without waiting. */
    static boolean knowsLayout(Squad s, Base b) { return s.kind == Kind.FULL || s.kind == Kind.NIGHT && b.freshIntel; }

    /** Has a scout ever reported this base? (Day raid airstrikes need to know where to aim.) */
    static boolean scouted(RaidData d, Base b) { return d.lastScouted.containsValue(b.parent != null ? b.parent : b.id); }

    static void tickScout(ServerLevel level, RaidData d, Squad s, Base b, boolean mat, long t) {
        Vec3 home = Vec3.atCenterOf(b.home);
        boolean night = night(level);
        switch (s.state) {
            case OUTBOUND -> {
                if (night) enter(s, State.RETREAT, t);
                else if (horizontalDist(s.pos, Vec3.atBottomCenterOf(dest(s, b))) < 6) enter(s, State.ATTACK, t);
            }
            case ATTACK -> { // watching
                if (!s.sawPlayer) s.sawPlayer = seesSomeone(level, s, home);
                long watched = t - s.stateSince;
                if (s.sawPlayer && watched > 1200 || watched > 6000 || night) enter(s, State.RETREAT, t);
            }
            case RETREAT -> { // he "left" once he is out of sight range of the base or home
                if (horizontalDist(s.pos, home) > 112 || horizontalDist(s.pos, Vec3.atBottomCenterOf(b.camp)) < 12) {
                    if (s.sawPlayer) scoutReported(level, d, b, s);
                    dematerialize(s, level);
                    d.squads.remove(s.id);
                    b.scout = null;
                }
            }
            default -> { }
        }
    }

    /** A player near the base that the scout can see (or any, if the scout is not spawned: nobody is close to him then). */
    static boolean seesSomeone(ServerLevel level, Squad s, Vec3 home) {
        for (ServerPlayer p : level.players()) {
            if (p.isSpectator() || horizontalDist(p.position(), home) > 48) continue;
            if (s.live.isEmpty()) return true;
            for (UUID u : s.live) if (level.getEntity(u) instanceof RaiderEntity e && e.hasLineOfSight(p)) return true;
        }
        return false;
    }

    static void tickPatrol(ServerLevel level, RaidData d, Squad s, Base b, boolean mat, long t) {
        switch (s.state) {
            case OUTBOUND, ATTACK -> {
                if (dayTime(level) >= 12000 || mat && losing(level, s)) enter(s, State.RETREAT, t); // home at dusk
                else if (horizontalDist(s.pos, Vec3.atBottomCenterOf(dest(s, b))) < 8) s.target = waypoint(level, b);
            }
            case RETREAT -> {
                if (horizontalDist(s.pos, Vec3.atBottomCenterOf(b.camp)) < 12) returnHome(level, d, s, b);
            }
            default -> { }
        }
    }

    static void enter(Squad s, State st, long t) {
        s.state = st;
        s.stateSince = t;
    }

    /**
     * "They understand they are losing": half the party is dead, or the survivors are below 35% total health.
     * Without their leader they break sooner: a quarter dead, or below 50%.
     */
    static boolean losing(ServerLevel level, Squad s) {
        boolean shaky = s.leaderless();
        if (s.count() * (shaky ? 4 : 2) <= s.startCount * (shaky ? 3 : 1)) return true;
        float hp = 0, max = 0;
        for (UUID u : s.live)
            if (level.getEntity(u) instanceof RaiderEntity e) { hp += e.getHealth(); max += e.getMaxHealth(); }
        return max > 0 && hp / max < (shaky ? 0.5f : 0.35f);
    }

    static void returnHome(ServerLevel level, RaidData d, Squad s, Base b) {
        Squad g = d.squads.get(b.garrison);
        List<ItemStack> loot = new ArrayList<>(s.loot);
        s.loot.clear();
        int cap = cap(b, d.stage);
        for (UUID u : s.live)
            if (level.getEntity(u) instanceof RaiderEntity e) {
                loot.addAll(e.getInventory().removeAllItems());
                if (g != null && g.roles.size() < cap) {
                    e.squadId = g.id;
                    e.restrictTo(b.camp, b.gate != null ? 8 : 10);
                    g.live.add(u);
                    g.roles.add(e.role());
                } else e.discard(); // extra survivors disperse
            }
        if (g != null) { // survivors that were not spawned join too, up to full strength
            List<Role> virtual = new ArrayList<>(s.roles);
            for (UUID u : s.live) if (level.getEntity(u) instanceof RaiderEntity e) virtual.remove(e.role());
            for (Role r : virtual) if (g.roles.size() < cap) g.roles.add(r);
        }
        s.live.clear();
        CampBuilder.deposit(level, b.camp, b.chests, loot);
        d.squads.remove(s.id);
        if (b.waves == 0 && (s.kind == Kind.RAID || s.kind == Kind.PATROL || nightSquads(d, b).isEmpty() && fullSquads(d, b).isEmpty())) setGate(level, b, false);
        if (s.id.equals(b.raid)) b.raid = null;
        if (s.id.equals(b.patrol)) b.patrol = null;
        RaidsOnPlayer.LOG.info("{} squad home at {} with {} survivors and {} stacks", s.kind, b.camp, s.count(), loot.size());
    }

    static void materialize(ServerLevel level, RaidData d, Squad s, Base b) {
        List<Role> missing = new ArrayList<>(s.roles);
        boolean hasWatchman = false;
        for (UUID u : s.live)
            if (level.getEntity(u) instanceof RaiderEntity e) {
                missing.remove(e.role());
                hasWatchman |= e.watchman;
            }
        boolean intel = knowsLayout(s, b);
        List<RaiderEntity> spawned = new ArrayList<>();
        for (Role role : missing) {
            RaiderEntity e = RaidsOnPlayer.RAIDER.get().create(level);
            if (e == null) return;
            // One garrison raider of an outpost is the watchman: he stands outside the gate.
            boolean watchman = s.kind == Kind.GARRISON && b.gate != null && !hasWatchman && role == Role.RAIDER;
            hasWatchman |= watchman;
            Vec3 at = watchman ? Vec3.atBottomCenterOf(b.gate.relative(facing(b.camp, b.home), 2)) : s.pos;
            double x = at.x + (watchman ? 0 : Mth.nextDouble(level.random, -3, 3)), z = at.z + (watchman ? 0 : Mth.nextDouble(level.random, -3, 3));
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(x), Mth.floor(z));
            e.moveTo(x, y, z, level.random.nextFloat() * 360f, 0f);
            e.squadId = s.id;
            e.watchman = watchman;
            e.setPersistenceRequired();
            equip(e, role, d.stage, intel, level);
            if (watchman) e.restrictTo(BlockPos.containing(at), 2);
            else if (s.state == State.GARRISON) e.restrictTo(b.camp, role == Role.ARTILLERYMAN ? 5 : b.gate != null ? 8 : 10);
            level.addFreshEntity(e);
            s.live.add(e.getUUID());
            spawned.add(e);
        }
        // Loot carried while out of sight goes back into the carriers' pockets, so killing them returns it.
        for (int i = 0; !s.loot.isEmpty() && !spawned.isEmpty(); i++) {
            ItemStack rest = spawned.get(i % spawned.size()).getInventory().addItem(s.loot.remove(0));
            if (!rest.isEmpty()) { s.loot.add(rest); if (i > spawned.size() * 6) break; }
        }
    }

    static void dematerialize(Squad s, ServerLevel level) {
        for (UUID u : s.live)
            if (level.getEntity(u) instanceof RaiderEntity e) {
                s.loot.addAll(e.getInventory().removeAllItems());
                e.discard();
            }
        s.live.clear();
    }

    /** Material by stage: stone, iron, iron (diamond one time in four), diamond. */
    static Item tier(int stage, RandomSource r, Item stone, Item iron, Item diamond) {
        return switch (stage) {
            case 1 -> stone;
            case 2 -> iron;
            case 3 -> r.nextInt(4) == 0 ? diamond : iron;
            default -> diamond;
        };
    }

    static void equip(RaiderEntity e, Role role, int stage, boolean intel, ServerLevel level) {
        RandomSource r = level.random;
        int s = role == Role.LEADER ? Math.min(4, stage + 1) : stage; // leaders are a tier better equipped
        e.setRole(role);
        e.weapon = new ItemStack(tier(s, r, Items.STONE_SWORD, Items.IRON_SWORD, Items.DIAMOND_SWORD));
        e.tools.clear();
        switch (role) {
            case RAIDER -> e.tools.add(new ItemStack(tier(stage, r, Items.STONE_SHOVEL, Items.IRON_SHOVEL, Items.DIAMOND_SHOVEL)));
            case MINER -> {
                e.weapon = new ItemStack(tier(stage, r, Items.STONE_PICKAXE, Items.IRON_PICKAXE, Items.DIAMOND_PICKAXE));
                e.tools.add(e.weapon);
                e.tools.add(new ItemStack(tier(stage, r, Items.STONE_AXE, Items.IRON_AXE, Items.DIAMOND_AXE)));
            }
            case LEADER -> e.bow = new ItemStack(Items.BOW);
            case ARTILLERYMAN -> {
                e.bow = new ItemStack(Items.BOW);
                e.special = new ItemStack(Items.SPYGLASS); // spots for the guns
            }
            case DEMOMAN -> {
                e.special = new ItemStack(Items.TNT);
                e.tnt = intel ? 4 : 2;
            }
            case SCOUT -> {
                e.special = new ItemStack(Items.SPYGLASS);
                Objects.requireNonNull(e.getAttribute(Attributes.MOVEMENT_SPEED)).setBaseValue(0.42);
            }
        }
        if (role == Role.LEADER && stage >= 4)
            e.weapon.enchant(level.registryAccess().lookupOrThrow(Registries.ENCHANTMENT).getOrThrow(Enchantments.SHARPNESS), 2);
        e.setItemSlot(EquipmentSlot.MAINHAND, e.role() == Role.DEMOMAN ? e.special : e.weapon);
        if (s >= 2) e.setItemSlot(EquipmentSlot.CHEST, new ItemStack(s >= 4 ? Items.DIAMOND_CHESTPLATE : s == 3 ? Items.IRON_CHESTPLATE : Items.LEATHER_CHESTPLATE));
        if (role == Role.LEADER) {
            e.setItemSlot(EquipmentSlot.HEAD, Raid.getLeaderBannerInstance(level.registryAccess().lookupOrThrow(Registries.BANNER_PATTERN)));
            e.setDropChance(EquipmentSlot.HEAD, 0.25f);
        } else if (s >= 4) e.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
    }

    /** A map centred on a raider camp with a red X on it (dropped by squad leaders). */
    static ItemStack campMap(ServerLevel level, BlockPos camp) {
        ItemStack map = MapItem.create(level, camp.getX(), camp.getZ(), (byte) 2, true, true);
        MapItem.renderBiomePreviewMap(level, map);
        MapItemSavedData.addTargetDecoration(map, camp, "raidsonplayer_camp", MapDecorationTypes.RED_X);
        map.set(DataComponents.ITEM_NAME, Component.translatable("item.raidsonplayer.camp_map"));
        return map;
    }

    static Vec3 centroid(ServerLevel level, Squad s) {
        double x = 0, y = 0, z = 0;
        int n = 0;
        for (UUID u : s.live) {
            Entity e = level.getEntity(u);
            if (e != null) { x += e.getX(); y += e.getY(); z += e.getZ(); n++; }
        }
        return n == 0 ? s.pos : new Vec3(x / n, y / n, z / n);
    }

    static double horizontalDist(Vec3 a, Vec3 b) {
        return Math.sqrt((a.x - b.x) * (a.x - b.x) + (a.z - b.z) * (a.z - b.z));
    }

    // ---------------------------------------------------------------- events

    public static void onDeath(RaiderEntity e, boolean byPlayer) {
        if (e.squadId == null || !(e.level() instanceof ServerLevel sl)) return;
        RaidData d = RaidData.get(sl.getServer());
        Squad s = d.squads.get(e.squadId);
        if (s == null || !s.live.remove(e.getUUID())) return;
        s.roles.remove(e.role());
        Base b = d.bases.get(s.base);
        if (b == null) return;
        if (byPlayer) { // revenge: every raider a player kills brings their next moves closer
            Base main = b.parent != null ? d.bases.getOrDefault(b.parent, b) : b;
            main.bonus += Config.KILL_POINTS.get() * (e.role() == Role.LEADER ? 3 : 1) * 1200L;
        }
        if (s.kind == Kind.SCOUT) tell(sl, b, "You killed the raider scout before he could report back.", ChatFormatting.GREEN);
        else if (e.role() == Role.LEADER && s.kind != Kind.GARRISON && s.count() > 0)
            tell(sl, b, "A squad leader has fallen! His squad falters.", ChatFormatting.GREEN);
    }

    @Nullable static Base baseOf(RaidData d, ServerPlayer p) {
        return d.bases.values().stream().filter(b -> b.parent == null && b.members.contains(p.getUUID())).findFirst().orElse(null);
    }
}
