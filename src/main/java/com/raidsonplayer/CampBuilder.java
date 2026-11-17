package com.raidsonplayer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;

/** Builds raider camps in code. Stage 1 = two A-frame tents with beds and chests around a campfire (concept.png). */
public final class CampBuilder {
    public static final int RADIUS = 9;

    private CampBuilder() {}

    /** Surface y (first free block) at x,z, loading/generating the chunk if needed. */
    static int surface(ServerLevel level, int x, int z) {
        return level.getChunk(x >> 4, z >> 4).getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15) + 1;
    }

    /** Returns a camp origin near x,z if the spot is dry and flat enough, else null. Generates chunks synchronously. */
    @Nullable
    static BlockPos trySite(ServerLevel level, int x, int z) {
        int y = surface(level, x, z);
        BlockPos c = new BlockPos(x, y, z);
        if (level.getBiome(c).is(BiomeTags.IS_OCEAN) || level.getBiome(c).is(BiomeTags.IS_RIVER)) return null;
        int min = y, max = y;
        for (int[] o : new int[][]{{-7, -7}, {7, -7}, {-7, 7}, {7, 7}, {0, 0}}) {
            int h = surface(level, x + o[0], z + o[1]);
            if (!level.getFluidState(new BlockPos(x + o[0], h - 1, z + o[1])).isEmpty()) return null;
            min = Math.min(min, h);
            max = Math.max(max, h);
        }
        return max - min <= 6 ? c : null;
    }

    /** Builds the camp for a stage and returns its chest positions. Clears the footprint first (also used for rebuilds). */
    static List<BlockPos> build(ServerLevel level, BlockPos c, int stage, int clearRadius, RandomSource r) {
        flatten(level, c, Math.max(RADIUS, clearRadius));
        List<BlockPos> chests = new ArrayList<>();
        // shortcut: stages 2-4 reuse the tents until the outpost (concept2.png) is built in milestone 2.
        Block[] wools = {Blocks.WHITE_WOOL, Blocks.LIGHT_GRAY_WOOL, Blocks.BROWN_WOOL};
        tent(level, c.offset(-5, 0, 0), wools[r.nextInt(3)].defaultBlockState(), r, chests);
        tent(level, c.offset(5, 0, 0), wools[r.nextInt(3)].defaultBlockState(), r, chests);
        level.setBlock(c.offset(0, 0, 3), Blocks.CAMPFIRE.defaultBlockState(), 3);
        fillLoot(level, chests, stage, r);
        return chests;
    }

    /** Levels a disc: clears 10 blocks above the floor, fills dirt below it, grass on top. */
    static void flatten(ServerLevel level, BlockPos c, int radius) {
        for (int cx = (c.getX() - radius) >> 4; cx <= (c.getX() + radius) >> 4; cx++)
            for (int cz = (c.getZ() - radius) >> 4; cz <= (c.getZ() + radius) >> 4; cz++) level.getChunk(cx, cz);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++)
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue;
                int x = c.getX() + dx, z = c.getZ() + dz;
                for (int y = c.getY(); y <= c.getY() + 10; y++) level.setBlock(p.set(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                int top = surface(level, x, z);
                for (int y = Math.min(top, c.getY() - 1); y < c.getY() - 1; y++) level.setBlock(p.set(x, y, z), Blocks.DIRT.defaultBlockState(), 2);
                level.setBlock(p.set(x, c.getY() - 1, z), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
            }
    }

    /** A-frame tent, opening facing south (+z). o = centre of the opening at floor level. Bed inside, 1-2 chests by the opening. */
    static void tent(ServerLevel level, BlockPos o, BlockState wool, RandomSource r, List<BlockPos> chests) {
        for (int dz = 0; dz <= 4; dz++) {
            BlockPos row = o.north(dz);
            level.setBlock(row.west(2), wool, 2);
            level.setBlock(row.east(2), wool, 2);
            level.setBlock(row.above().west(), wool, 2);
            level.setBlock(row.above().east(), wool, 2);
            level.setBlock(row.above(2), wool, 2);
        }
        BlockPos back = o.north(4);
        level.setBlock(back.west(), wool, 2);
        level.setBlock(back, wool, 2);
        level.setBlock(back.east(), wool, 2);
        level.setBlock(back.above(), wool, 2);
        level.setBlock(o.above(3), Blocks.OAK_FENCE.defaultBlockState(), 2);
        level.setBlock(back.above(3), Blocks.OAK_FENCE.defaultBlockState(), 2);
        BlockState bed = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.NORTH);
        level.setBlock(o.north(2), bed.setValue(BedBlock.PART, BedPart.FOOT), 3);
        level.setBlock(o.north(3), bed.setValue(BedBlock.PART, BedPart.HEAD), 3);
        int n = 1 + r.nextInt(2);
        for (int i = 0; i < n; i++) {
            BlockPos p = o.offset(-2, 0, 1 + i);
            level.setBlock(p, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.EAST), 3);
            chests.add(p);
        }
    }

    static void fillLoot(ServerLevel level, List<BlockPos> chests, int stage, RandomSource r) {
        LootTable table = level.getServer().reloadableRegistries().getLootTable(ResourceKey.create(Registries.LOOT_TABLE,
                ResourceLocation.fromNamespaceAndPath(RaidsOnPlayer.MODID, "chests/stage" + stage)));
        int rolls = Config.LOOT.get().ordinal() + 1;
        for (BlockPos p : chests)
            if (level.getBlockEntity(p) instanceof Container c)
                for (int i = 0; i < rolls; i++)
                    table.fill(c, new LootParams.Builder(level).withParameter(LootContextParams.ORIGIN, Vec3.atCenterOf(p))
                            .create(LootContextParamSets.CHEST), r.nextLong());
    }

    /** Takes everything out of the given chests (used before a rebuild so nothing is lost). */
    static List<ItemStack> empty(ServerLevel level, List<BlockPos> chests) {
        List<ItemStack> out = new ArrayList<>();
        for (BlockPos p : chests) {
            level.getChunk(p);
            if (level.getBlockEntity(p) instanceof Container c)
                for (int i = 0; i < c.getContainerSize(); i++) if (!c.getItem(i).isEmpty()) out.add(c.removeItemNoUpdate(i));
        }
        return out;
    }

    /** Puts items into the camp chests; whatever doesn't fit drops at the camp centre. */
    static void deposit(ServerLevel level, BlockPos camp, List<BlockPos> chests, List<ItemStack> items) {
        for (ItemStack s : items) {
            for (BlockPos p : chests) {
                if (s.isEmpty()) break;
                level.getChunk(p);
                if (!(level.getBlockEntity(p) instanceof Container c)) continue;
                for (int i = 0; i < c.getContainerSize() && !s.isEmpty(); i++) {
                    ItemStack in = c.getItem(i);
                    if (in.isEmpty()) { c.setItem(i, s.copyAndClear()); }
                    else if (ItemStack.isSameItemSameComponents(in, s) && in.getCount() < in.getMaxStackSize()) {
                        int move = Math.min(s.getCount(), in.getMaxStackSize() - in.getCount());
                        in.grow(move);
                        s.shrink(move);
                    }
                }
                c.setChanged();
            }
            if (!s.isEmpty()) Block.popResource(level, camp.above(), s);
        }
    }
}
