package com.raidsonplayer;

import com.raidsonplayer.RaidData.Base;
import com.raidsonplayer.RaidData.Squad;
import com.raidsonplayer.RaidData.State;
import net.minecraft.ChatFormatting;
import net.minecraft.advancements.AdvancementHolder;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.world.Difficulty;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.*;

/** Server-side brain: progression, bases and camps, raid timers and squads. Runs once per second on the overworld. */
public final class RaidManager {
    /** Advancements needed to reach each stage (index = stage). */
    static final ResourceLocation[][] STAGE_REQS = {
            {}, {},
            {ResourceLocation.parse("minecraft:story/smelt_iron")},
            {ResourceLocation.parse("minecraft:story/mine_diamond"), ResourceLocation.parse("create:andesite_alloy")},
            {ResourceLocation.parse("create:brass")},
    };
    static final int[] GARRISON_CAP = {0, 4, 6, 8, 10};
    static final int BASE_RADIUS = 64, SEEN_UNSEEN_TICKS = 3600, ATTACK_TIMEOUT = 4800;
    static final double MARCH_SPEED = 2.5; // blocks per second while virtual

    private RaidManager() {}

    public static void tick(MinecraftServer server) {
        ServerLevel level = server.overworld();
        long t = level.getGameTime();
        if (t % 20 != 0) return;
        RaidData d = RaidData.get(server);
        if (t % 100 == 0) {
            updateStage(server, d);
            updateMembership(server, d, t);
        }
        for (Base b : d.bases.values()) tickBase(level, d, b, t);
        for (Squad s : List.copyOf(d.squads.values())) tickSquad(level, d, s, t);
        d.setDirty();
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
            Base cur = d.bases.values().stream().filter(b -> b.members.contains(p.getUUID())).findFirst().orElse(null);
            if (cur != null && valid && cur.home.closerThan(rp, BASE_RADIUS)) continue;
            if (cur != null) cur.members.remove(p.getUUID());
            if (!valid) continue;
            Base near = d.bases.values().stream().filter(b -> b.home.closerThan(rp, BASE_RADIUS)).findFirst().orElse(null);
            if (near == null) {
                near = new Base();
                near.home = rp.immutable();
                near.nextRaid = t + raidInterval(server.overworld());
                near.lastRegen = t;
                d.bases.put(near.id, near);
                RaidsOnPlayer.LOG.info("New player base at {} for {}", rp, p.getName().getString());
            }
            near.members.add(p.getUUID());
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
        if (b.campStage != d.stage && b.raid == null && t - b.lastSeen >= SEEN_UNSEEN_TICKS) rebuild(level, d, b);

        Squad g = d.squads.get(b.garrison);
        if (g != null && t - b.lastRegen >= 24000) {
            b.lastRegen = t;
            g.count = Math.max(g.count, Math.min(GARRISON_CAP[d.stage], g.count + 1));
        }
        if (g != null && b.raid == null && t >= b.nextRaid && g.count >= 2 && memberNear(level, b, 128)) startRaid(level, b, g, t);
    }

    static void placeCamp(ServerLevel level, RaidData d, Base b, long t) {
        int min = Config.CAMP_MIN_DIST.get(), max = Math.max(min, Config.CAMP_MAX_DIST.get());
        for (int i = 0; i < 12; i++) {
            double a = level.random.nextDouble() * Math.PI * 2, r = Mth.nextDouble(level.random, min, max);
            int x = b.home.getX() + (int) (Math.cos(a) * r), z = b.home.getZ() + (int) (Math.sin(a) * r);
            if (d.bases.values().stream().anyMatch(o -> o.home.distToCenterSqr(x, o.home.getY(), z) < (double) min * min)) continue;
            BlockPos c = CampBuilder.trySite(level, x, z);
            if (c == null) continue;
            b.camp = c;
            b.campRadius = CampBuilder.RADIUS;
            b.campStage = d.stage;
            b.chests.clear();
            b.chests.addAll(CampBuilder.build(level, c, d.stage, 0, level.random));
            Squad g = new Squad();
            g.base = b.id;
            g.state = State.GARRISON;
            g.pos = Vec3.atBottomCenterOf(c);
            g.count = g.startCount = GARRISON_CAP[d.stage];
            d.squads.put(g.id, g);
            b.garrison = g.id;
            RaidsOnPlayer.LOG.info("Raider camp (stage {}) built at {} for base {}", d.stage, c, b.home);
            return;
        }
        b.nextPlaceTry = t + 600; // all candidates were water or cliffs: try again in 30 s
    }

    static void rebuild(ServerLevel level, RaidData d, Base b) {
        Squad g = d.squads.get(b.garrison);
        if (g != null) dematerialize(g, level); // nobody is watching, so no one sees them vanish
        List<ItemStack> kept = CampBuilder.empty(level, b.chests);
        List<BlockPos> chests = CampBuilder.build(level, b.camp, d.stage, b.campRadius, level.random);
        b.chests.clear();
        b.chests.addAll(chests);
        CampBuilder.deposit(level, b.camp, chests, kept);
        b.campStage = d.stage;
        b.campRadius = CampBuilder.RADIUS;
        if (g != null) g.count = Math.max(g.count, GARRISON_CAP[d.stage]);
        RaidsOnPlayer.LOG.info("Raider camp at {} rebuilt for stage {}", b.camp, d.stage);
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

    static void startRaid(ServerLevel level, Base b, Squad g, long t) {
        int k = Math.max(2, g.count - 1); // one stays home as the watchman
        k = Math.min(k, g.count);
        Squad s = new Squad();
        s.base = b.id;
        s.state = State.OUTBOUND;
        s.pos = g.pos;
        s.count = s.startCount = k;
        s.stateSince = t;
        g.count -= k;
        for (Iterator<UUID> it = g.live.iterator(); it.hasNext() && s.live.size() < k; ) {
            if (level.getEntity(it.next()) instanceof RaiderEntity e) {
                it.remove();
                e.squadId = s.id;
                e.clearRestriction();
                s.live.add(e.getUUID());
            }
        }
        RaidData.get(level.getServer()).squads.put(s.id, s);
        b.raid = s.id;
        b.nextRaid = t + raidInterval(level);
        tell(level, b, k + " raiders set out from their camp to the " + direction(b.home, b.camp) + "!", ChatFormatting.RED);
        for (UUID u : b.members)
            if (level.getPlayerByUUID(u) instanceof ServerPlayer p)
                p.playNotifySound(SoundEvents.RAID_HORN.value(), SoundSource.HOSTILE, 1f, 1f);
        RaidsOnPlayer.LOG.info("Raid of {} launched from {} at base {}", k, b.camp, b.home);
    }

    static String direction(BlockPos from, BlockPos to) {
        double a = Math.toDegrees(Math.atan2(to.getX() - from.getX(), from.getZ() - to.getZ()));
        String[] dirs = {"north", "north-east", "east", "south-east", "south", "south-west", "west", "north-west"};
        return dirs[(int) Math.floorMod(Math.round(a / 45), 8)];
    }

    // ---------------------------------------------------------------- squads

    static void tickSquad(ServerLevel level, RaidData d, Squad s, long t) {
        Base b = d.bases.get(s.base);
        if (b == null || b.camp == null || (s.count <= 0 && s.state != State.GARRISON)) {
            dematerialize(s, level);
            d.squads.remove(s.id);
            if (b != null && s.id.equals(b.raid)) {
                b.raid = null;
                tell(level, b, "The raid is broken!", ChatFormatting.GREEN);
            }
            return;
        }
        s.live.removeIf(u -> !(level.getEntity(u) instanceof RaiderEntity e) || !e.isAlive() || !s.id.equals(e.squadId));
        boolean mat = !s.live.isEmpty();
        if (mat) s.pos = centroid(level, s);
        double range = mat ? 112 : 96; // hysteresis so squads don't flicker at the edge
        boolean near = !level.getPlayers(p -> !p.isSpectator() && horizontalDist(p.position(), s.pos) < range).isEmpty();
        if (!near) {
            if (mat) dematerialize(s, level);
            mat = false;
        } else if (s.live.size() < s.count && level.isPositionEntityTicking(BlockPos.containing(s.pos))
                && level.getDifficulty() != Difficulty.PEACEFUL) {
            materialize(level, d, s, b);
            mat = true;
        }

        Vec3 home = Vec3.atBottomCenterOf(b.home), camp = Vec3.atBottomCenterOf(b.camp);
        if (!mat && s.state == State.GARRISON) s.pos = camp;
        if (!mat && (s.state == State.OUTBOUND || s.state == State.RETREAT)) {
            Vec3 to = s.state == State.OUTBOUND ? home : camp;
            Vec3 dir = to.subtract(s.pos).multiply(1, 0, 1);
            double len = dir.length();
            s.pos = len <= MARCH_SPEED ? new Vec3(to.x, s.pos.y, to.z) : s.pos.add(dir.scale(MARCH_SPEED / len));
        }
        switch (s.state) {
            case GARRISON -> { }
            case OUTBOUND -> {
                if (mat && losing(level, s)) {
                    enter(s, State.RETREAT, t);
                    tell(level, b, "The raiders were beaten on the road and turn back!", ChatFormatting.GREEN);
                } else if (horizontalDist(s.pos, home) < 24) {
                    if (mat) {
                        enter(s, State.ATTACK, t);
                        tell(level, b, "The raiders have reached your base!", ChatFormatting.RED);
                    } else enter(s, State.RETREAT, t); // nobody home: nothing to raid
                }
            }
            case ATTACK -> {
                if (losing(level, s)) {
                    enter(s, State.RETREAT, t);
                    tell(level, b, "The raiders are losing and pull back to their camp!", ChatFormatting.GREEN);
                } else if (!mat || t - s.stateSince > ATTACK_TIMEOUT || s.stolen >= 2 * s.count
                        || s.stolen > 0 && t - s.lastSteal > 600) { // took something and found nothing more for 30 s
                    enter(s, State.RETREAT, t);
                    tell(level, b, s.stolen > 0 ? "The raiders head home with your loot!" : "The raiders head home.", ChatFormatting.GOLD);
                }
            }
            case RETREAT -> {
                if (horizontalDist(s.pos, camp) < 12) returnHome(level, d, s, b);
            }
        }
    }

    static void enter(Squad s, State st, long t) {
        s.state = st;
        s.stateSince = t;
    }

    /** "They understand they are losing": half the party is dead, or the survivors are below 35% total health. */
    static boolean losing(ServerLevel level, Squad s) {
        if (s.count * 2 <= s.startCount) return true;
        float hp = 0, max = 0;
        for (UUID u : s.live)
            if (level.getEntity(u) instanceof RaiderEntity e) { hp += e.getHealth(); max += e.getMaxHealth(); }
        return max > 0 && hp / max < 0.35f;
    }

    static void returnHome(ServerLevel level, RaidData d, Squad s, Base b) {
        Squad g = d.squads.get(b.garrison);
        List<ItemStack> loot = new ArrayList<>(s.loot);
        s.loot.clear();
        for (UUID u : s.live)
            if (level.getEntity(u) instanceof RaiderEntity e) {
                loot.addAll(e.getInventory().removeAllItems());
                if (g != null) {
                    e.squadId = g.id;
                    e.restrictTo(b.camp, 10);
                    g.live.add(u);
                } else e.discard();
            }
        s.live.clear();
        if (g != null) g.count = Math.max(g.count, Math.min(GARRISON_CAP[d.stage], g.count + s.count)); // extra survivors disperse
        CampBuilder.deposit(level, b.camp, b.chests, loot);
        d.squads.remove(s.id);
        b.raid = null;
        RaidsOnPlayer.LOG.info("Raid squad home at {} with {} survivors and {} stacks", b.camp, s.count, loot.size());
    }

    static void materialize(ServerLevel level, RaidData d, Squad s, Base b) {
        List<RaiderEntity> spawned = new ArrayList<>();
        while (s.live.size() < s.count) {
            RaiderEntity e = RaidsOnPlayer.RAIDER.get().create(level);
            if (e == null) return;
            double x = s.pos.x + Mth.nextDouble(level.random, -3, 3), z = s.pos.z + Mth.nextDouble(level.random, -3, 3);
            int y = level.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(x), Mth.floor(z));
            e.moveTo(x, y, z, level.random.nextFloat() * 360f, 0f);
            e.squadId = s.id;
            e.setPersistenceRequired();
            equip(e, d.stage, level);
            if (s.state == State.GARRISON) e.restrictTo(b.camp, 10);
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

    static void equip(RaiderEntity e, int stage, ServerLevel level) {
        var r = level.random;
        ItemStack weapon = switch (stage) {
            case 1 -> new ItemStack(Items.STONE_SWORD);
            case 2 -> new ItemStack(r.nextInt(3) == 0 ? Items.CROSSBOW : Items.IRON_SWORD);
            case 3 -> new ItemStack(r.nextInt(3) == 0 ? Items.CROSSBOW : r.nextInt(8) == 0 ? Items.DIAMOND_SWORD : Items.IRON_SWORD);
            default -> new ItemStack(r.nextInt(3) == 0 ? Items.CROSSBOW : Items.DIAMOND_SWORD);
        };
        e.setItemSlot(EquipmentSlot.MAINHAND, weapon);
        if (stage >= 2) e.setItemSlot(EquipmentSlot.CHEST, new ItemStack(stage >= 4 ? Items.DIAMOND_CHESTPLATE : stage == 3 ? Items.IRON_CHESTPLATE : Items.LEATHER_CHESTPLATE));
        if (stage >= 4) e.setItemSlot(EquipmentSlot.HEAD, new ItemStack(Items.DIAMOND_HELMET));
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

    public static void onDeath(RaiderEntity e) {
        if (e.squadId == null || !(e.level() instanceof ServerLevel sl)) return;
        Squad s = RaidData.get(sl.getServer()).squads.get(e.squadId);
        if (s != null && s.live.remove(e.getUUID())) s.count--;
    }

    @Nullable static Base baseOf(RaidData d, ServerPlayer p) {
        return d.bases.values().stream().filter(b -> b.members.contains(p.getUUID())).findFirst().orElse(null);
    }
}
