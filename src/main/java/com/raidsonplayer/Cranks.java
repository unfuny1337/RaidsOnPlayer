package com.raidsonplayer;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.entity.BlockEntity;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/** Turns Create hand cranks for a while, the way a player holding right-click does: one turn() call per tick. */
final class Cranks {
    private record Job(ServerLevel level, BlockPos pos, boolean back, int[] ticksLeft) {}

    private static final List<Job> JOBS = new ArrayList<>();

    private Cranks() {}

    static void turn(ServerLevel level, BlockPos pos, boolean back, int ticks) {
        JOBS.removeIf(j -> j.pos.equals(pos) && j.level == level);
        JOBS.add(new Job(level, pos, back, new int[]{ticks}));
    }

    static void tick() {
        JOBS.removeIf(j -> {
            if (--j.ticksLeft[0] < 0 || !j.level.isLoaded(j.pos)) return true;
            BlockEntity be = j.level.getBlockEntity(j.pos);
            Method m = be == null ? null : RaiderEntity.crankMethod(be);
            try { if (m != null) m.invoke(be, j.back); } catch (ReflectiveOperationException e) { return true; }
            return m == null;
        });
    }
}
