package com.raidsonplayer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.*;

/** World state: progression stage, player bases with their raider camps, squads (raiders as data) and scout reports. */
public class RaidData extends SavedData {
    public enum State { GARRISON, OUTBOUND, ATTACK, RETREAT }
    /**
     * RAID = day raid by the garrison; NIGHT = night raid squad; FULL = a squad of a full-sized raid wave;
     * SCOUT: ATTACK means watching the base; PATROL: OUTBOUND to a waypoint.
     */
    public enum Kind {
        GARRISON, RAID, NIGHT, PATROL, SCOUT, FULL;

        /** Out to attack a player base. */
        public boolean assault() { return this == RAID || this == NIGHT || this == FULL; }
    }

    public int stage = 1;
    /** Day-scale timers (day raids, expansion, regrowth, night rolls, patrols) run on this: game time plus time skipped by sleeping. */
    public long clock;
    long lastTick, lastDay; // not saved: what the clock last saw
    public final Map<UUID, Base> bases = new LinkedHashMap<>();
    public final Map<UUID, Squad> squads = new LinkedHashMap<>();
    /** Player -> the base a scout last saw them at. A night raid whose scout was killed goes there. */
    public final Map<UUID, UUID> lastScouted = new HashMap<>();

    public static class Base {
        public UUID id = UUID.randomUUID();
        public BlockPos home;
        public final Set<UUID> members = new HashSet<>();
        @Nullable public BlockPos camp;
        public int campStage, campRadius;
        public final List<BlockPos> chests = new ArrayList<>();
        public final List<BlockPos> cannons = new ArrayList<>();
        @Nullable public BlockPos gate, gateCrank;
        public long nextRaid, lastSeen, lastRegen, nextPlaceTry, nextCannon;
        @Nullable public UUID garrison, raid;
        // night raids
        public long nextNight, nextStrike, nextPatrol;
        public boolean nightPlanned, freshIntel, scoutSent;
        public int strikes;
        @Nullable public UUID scout, patrol;
        // full-sized raid: wave `wave` of `waves` is out (waves == 0: none); the next sets out at nextWave (0: not yet due)
        public int wave, waves;
        public long nextWave, waveAt;
        public boolean waveArrived;
        // expansion: a camp the raiders added around another base's home shares its home and players (parent = that base)
        @Nullable public UUID parent;
        public long nextExpand;
        /** Raider clock time from which the camp may be rebuilt for a new stage (the morning after the stage went up); 0 = nothing pending. */
        public long upgradeAt;
    }

    public static class Squad {
        public UUID id = UUID.randomUUID();
        public UUID base;
        public Kind kind = Kind.RAID;
        public State state;
        public Vec3 pos;
        /** Where OUTBOUND/ATTACK go: the base home, a scout's watch point or a patrol waypoint. Null = the base home. */
        @Nullable public BlockPos target;
        public final List<Role> roles = new ArrayList<>();
        public int startCount, stolen;
        public boolean airstrike, hadLeader, sawPlayer;
        public long stateSince, lastSteal;
        public final List<ItemStack> loot = new ArrayList<>();
        /** Entity UUIDs currently materialized. Not saved: raider entities are recreated from this data after a restart. */
        public final Set<UUID> live = new HashSet<>();
        public final Set<BlockPos> claimed = new HashSet<>();

        public int count() { return roles.size(); }

        /** Had a leader and lost him: the squad is shakier and no longer acts together. */
        public boolean leaderless() { return hadLeader && !roles.contains(Role.LEADER); }
    }

    public static RaidData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(RaidData::new, RaidData::load, null), RaidsOnPlayer.MODID);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider reg) {
        tag.putInt("stage", stage);
        tag.putLong("clock", clock);
        ListTag bl = new ListTag();
        for (Base b : bases.values()) {
            CompoundTag t = new CompoundTag();
            t.putUUID("id", b.id);
            t.putLong("home", b.home.asLong());
            ListTag m = new ListTag();
            b.members.forEach(u -> m.add(NbtUtils.createUUID(u)));
            t.put("members", m);
            if (b.camp != null) t.putLong("camp", b.camp.asLong());
            t.putInt("campStage", b.campStage);
            t.putInt("campRadius", b.campRadius);
            ListTag c = new ListTag();
            b.chests.forEach(p -> c.add(LongTag.valueOf(p.asLong())));
            t.put("chests", c);
            ListTag cn = new ListTag();
            b.cannons.forEach(p -> cn.add(LongTag.valueOf(p.asLong())));
            t.put("cannons", cn);
            if (b.gate != null) t.putLong("gate", b.gate.asLong());
            if (b.gateCrank != null) t.putLong("gateCrank", b.gateCrank.asLong());
            t.putLong("nextRaid", b.nextRaid);
            t.putLong("lastSeen", b.lastSeen);
            t.putLong("lastRegen", b.lastRegen);
            if (b.garrison != null) t.putUUID("garrison", b.garrison);
            if (b.raid != null) t.putUUID("raid", b.raid);
            t.putLong("nextNight", b.nextNight);
            t.putLong("nextStrike", b.nextStrike);
            t.putLong("nextPatrol", b.nextPatrol);
            t.putBoolean("nightPlanned", b.nightPlanned);
            t.putBoolean("freshIntel", b.freshIntel);
            t.putBoolean("scoutSent", b.scoutSent);
            t.putInt("strikes", b.strikes);
            if (b.scout != null) t.putUUID("scout", b.scout);
            if (b.patrol != null) t.putUUID("patrol", b.patrol);
            t.putInt("wave", b.wave);
            t.putInt("waves", b.waves);
            t.putLong("nextWave", b.nextWave);
            t.putLong("waveAt", b.waveAt);
            t.putBoolean("waveArrived", b.waveArrived);
            if (b.parent != null) t.putUUID("parent", b.parent);
            t.putLong("nextExpand", b.nextExpand);
            t.putLong("upgradeAt", b.upgradeAt);
            bl.add(t);
        }
        tag.put("bases", bl);
        ListTag sl = new ListTag();
        for (Squad s : squads.values()) {
            CompoundTag t = new CompoundTag();
            t.putUUID("id", s.id);
            t.putUUID("base", s.base);
            t.putString("kind", s.kind.name());
            t.putString("state", s.state.name());
            t.putDouble("x", s.pos.x);
            t.putDouble("y", s.pos.y);
            t.putDouble("z", s.pos.z);
            if (s.target != null) t.putLong("target", s.target.asLong());
            ListTag roles = new ListTag();
            s.roles.forEach(r -> roles.add(StringTag.valueOf(r.name())));
            t.put("roles", roles);
            t.putInt("startCount", s.startCount);
            t.putInt("stolen", s.stolen);
            t.putBoolean("airstrike", s.airstrike);
            t.putBoolean("hadLeader", s.hadLeader);
            t.putBoolean("sawPlayer", s.sawPlayer);
            t.putLong("stateSince", s.stateSince);
            ListTag loot = new ListTag();
            s.loot.stream().filter(i -> !i.isEmpty()).forEach(i -> loot.add(i.save(reg)));
            t.put("loot", loot);
            sl.add(t);
        }
        tag.put("squads", sl);
        ListTag ls = new ListTag();
        lastScouted.forEach((player, base) -> {
            CompoundTag t = new CompoundTag();
            t.putUUID("player", player);
            t.putUUID("base", base);
            ls.add(t);
        });
        tag.put("lastScouted", ls);
        return tag;
    }

    static RaidData load(CompoundTag tag, HolderLookup.Provider reg) {
        RaidData d = new RaidData();
        d.stage = Math.max(1, tag.getInt("stage"));
        d.clock = tag.getLong("clock");
        for (Tag e : tag.getList("bases", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) e;
            Base b = new Base();
            b.id = t.getUUID("id");
            b.home = BlockPos.of(t.getLong("home"));
            for (Tag u : t.getList("members", Tag.TAG_INT_ARRAY)) b.members.add(NbtUtils.loadUUID(u));
            if (t.contains("camp")) b.camp = BlockPos.of(t.getLong("camp"));
            b.campStage = t.getInt("campStage");
            b.campRadius = t.getInt("campRadius");
            for (Tag c : t.getList("chests", Tag.TAG_LONG)) b.chests.add(BlockPos.of(((LongTag) c).getAsLong()));
            for (Tag c : t.getList("cannons", Tag.TAG_LONG)) b.cannons.add(BlockPos.of(((LongTag) c).getAsLong()));
            if (t.contains("gate")) b.gate = BlockPos.of(t.getLong("gate"));
            if (t.contains("gateCrank")) b.gateCrank = BlockPos.of(t.getLong("gateCrank"));
            b.nextRaid = t.getLong("nextRaid");
            b.lastSeen = t.getLong("lastSeen");
            b.lastRegen = t.getLong("lastRegen");
            if (t.hasUUID("garrison")) b.garrison = t.getUUID("garrison");
            if (t.hasUUID("raid")) b.raid = t.getUUID("raid");
            b.nextNight = t.getLong("nextNight");
            b.nextStrike = t.getLong("nextStrike");
            b.nextPatrol = t.getLong("nextPatrol");
            b.nightPlanned = t.getBoolean("nightPlanned");
            b.freshIntel = t.getBoolean("freshIntel");
            b.scoutSent = t.getBoolean("scoutSent");
            b.strikes = t.getInt("strikes");
            if (t.hasUUID("scout")) b.scout = t.getUUID("scout");
            if (t.hasUUID("patrol")) b.patrol = t.getUUID("patrol");
            b.wave = t.getInt("wave");
            b.waves = t.getInt("waves");
            b.nextWave = t.getLong("nextWave");
            b.waveAt = t.getLong("waveAt");
            b.waveArrived = t.getBoolean("waveArrived");
            if (t.hasUUID("parent")) b.parent = t.getUUID("parent");
            b.nextExpand = t.getLong("nextExpand");
            b.upgradeAt = t.getLong("upgradeAt");
            d.bases.put(b.id, b);
        }
        for (Tag e : tag.getList("squads", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) e;
            Squad s = new Squad();
            s.id = t.getUUID("id");
            s.base = t.getUUID("base");
            s.state = State.valueOf(t.getString("state"));
            s.kind = t.contains("kind") ? Kind.valueOf(t.getString("kind")) : s.state == State.GARRISON ? Kind.GARRISON : Kind.RAID;
            s.pos = new Vec3(t.getDouble("x"), t.getDouble("y"), t.getDouble("z"));
            if (t.contains("target")) s.target = BlockPos.of(t.getLong("target"));
            if (t.contains("roles")) for (Tag r : t.getList("roles", Tag.TAG_STRING)) s.roles.add(Role.valueOf(r.getAsString()));
            else for (int i = 0; i < t.getInt("count"); i++) s.roles.add(Role.RAIDER); // saved before classes existed
            s.startCount = t.getInt("startCount");
            s.stolen = t.getInt("stolen");
            s.airstrike = t.getBoolean("airstrike");
            s.hadLeader = t.getBoolean("hadLeader");
            s.sawPlayer = t.getBoolean("sawPlayer");
            s.stateSince = t.getLong("stateSince");
            for (Tag i : t.getList("loot", Tag.TAG_COMPOUND)) ItemStack.parse(reg, i).ifPresent(s.loot::add);
            d.squads.put(s.id, s);
        }
        for (Tag e : tag.getList("lastScouted", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) e;
            d.lastScouted.put(t.getUUID("player"), t.getUUID("base"));
        }
        return d;
    }
}
