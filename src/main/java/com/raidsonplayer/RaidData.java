package com.raidsonplayer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.LongTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.*;

/** World state: progression stage, player bases with their raider camps, and squads (raiders as data). */
public class RaidData extends SavedData {
    public enum State { GARRISON, OUTBOUND, ATTACK, RETREAT }

    public int stage = 1;
    public final Map<UUID, Base> bases = new LinkedHashMap<>();
    public final Map<UUID, Squad> squads = new LinkedHashMap<>();

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
    }

    public static class Squad {
        public UUID id = UUID.randomUUID();
        public UUID base;
        public State state;
        public Vec3 pos;
        public int count, startCount, stolen;
        public boolean airstrike;
        public long stateSince, lastSteal;
        public final List<ItemStack> loot = new ArrayList<>();
        /** Entity UUIDs currently materialized. Not saved: raider entities are recreated from this data after a restart. */
        public final Set<UUID> live = new HashSet<>();
        public final Set<BlockPos> claimed = new HashSet<>();
    }

    public static RaidData get(MinecraftServer server) {
        return server.overworld().getDataStorage().computeIfAbsent(
                new SavedData.Factory<>(RaidData::new, RaidData::load, null), RaidsOnPlayer.MODID);
    }

    @Override
    public CompoundTag save(CompoundTag tag, HolderLookup.Provider reg) {
        tag.putInt("stage", stage);
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
            bl.add(t);
        }
        tag.put("bases", bl);
        ListTag sl = new ListTag();
        for (Squad s : squads.values()) {
            CompoundTag t = new CompoundTag();
            t.putUUID("id", s.id);
            t.putUUID("base", s.base);
            t.putString("state", s.state.name());
            t.putDouble("x", s.pos.x);
            t.putDouble("y", s.pos.y);
            t.putDouble("z", s.pos.z);
            t.putInt("count", s.count);
            t.putInt("startCount", s.startCount);
            t.putInt("stolen", s.stolen);
            t.putBoolean("airstrike", s.airstrike);
            t.putLong("stateSince", s.stateSince);
            ListTag loot = new ListTag();
            s.loot.stream().filter(i -> !i.isEmpty()).forEach(i -> loot.add(i.save(reg)));
            t.put("loot", loot);
            sl.add(t);
        }
        tag.put("squads", sl);
        return tag;
    }

    static RaidData load(CompoundTag tag, HolderLookup.Provider reg) {
        RaidData d = new RaidData();
        d.stage = Math.max(1, tag.getInt("stage"));
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
            d.bases.put(b.id, b);
        }
        for (Tag e : tag.getList("squads", Tag.TAG_COMPOUND)) {
            CompoundTag t = (CompoundTag) e;
            Squad s = new Squad();
            s.id = t.getUUID("id");
            s.base = t.getUUID("base");
            s.state = State.valueOf(t.getString("state"));
            s.pos = new Vec3(t.getDouble("x"), t.getDouble("y"), t.getDouble("z"));
            s.count = t.getInt("count");
            s.startCount = t.getInt("startCount");
            s.stolen = t.getInt("stolen");
            s.airstrike = t.getBoolean("airstrike");
            s.stateSince = t.getLong("stateSince");
            for (Tag i : t.getList("loot", Tag.TAG_COMPOUND)) ItemStack.parse(reg, i).ifPresent(s.loot::add);
            d.squads.put(s.id, s);
        }
        return d;
    }
}
