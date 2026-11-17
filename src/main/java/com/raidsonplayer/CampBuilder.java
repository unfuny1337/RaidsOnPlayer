package com.raidsonplayer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.util.RandomSource;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.loot.LootParams;
import net.minecraft.world.level.storage.loot.LootTable;
import net.minecraft.world.level.storage.loot.parameters.LootContextParamSets;
import net.minecraft.world.level.storage.loot.parameters.LootContextParams;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Builds raider bases in code. Stage 1 = two tents (concept.png). Stages 2-4 = the outpost (concept2.png): walls, four corner
 * towers, a gate with a watchman, a loot room against the back wall and raiders inside, built from wood, cobblestone, then
 * industrial blocks. Cannons sit on the towers from stage 3.
 */
public final class CampBuilder {
    public record Layout(List<BlockPos> chests, @Nullable BlockPos gate, List<BlockPos> cannons, int radius) {}

    private CampBuilder() {}

    /** Surface y (first free block) at x,z, loading/generating the chunk if needed. */
    static int surface(ServerLevel level, int x, int z) {
        return level.getChunk(x >> 4, z >> 4).getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15) + 1;
    }

    /** Returns a base origin near x,z if the spot is dry and flat enough for an outpost, else null. Generates chunks synchronously. */
    @Nullable
    static BlockPos trySite(ServerLevel level, int x, int z) {
        int y = surface(level, x, z);
        BlockPos c = new BlockPos(x, y, z);
        if (level.getBiome(c).is(BiomeTags.IS_OCEAN) || level.getBiome(c).is(BiomeTags.IS_RIVER)) return null;
        int min = y, max = y;
        for (int dx = -16; dx <= 16; dx += 8)
            for (int dz = -16; dz <= 16; dz += 8) {
                int h = surface(level, x + dx, z + dz);
                if (!level.getFluidState(new BlockPos(x + dx, h - 1, z + dz)).isEmpty()) return null;
                min = Math.min(min, h);
                max = Math.max(max, h);
            }
        return max - min <= 8 ? c : null;
    }

    /** Builds the base for a stage, gate facing `front`, and fills its chests. Clears the footprint first (also used for rebuilds). */
    static Layout build(ServerLevel level, BlockPos c, int stage, int clearRadius, Direction front, RandomSource r) {
        int radius = stage == 1 ? 9 : 24;
        flatten(level, c, Math.max(radius, clearRadius), stage == 1 ? 10 : 18);
        Layout l = stage == 1 ? tents(level, c, r) : outpost(level, c, stage, front, r);
        fillLoot(level, l.chests(), stage, r);
        return l;
    }

    /** Levels a disc: clears `height` blocks above the floor, fills dirt below it, grass on top. */
    static void flatten(ServerLevel level, BlockPos c, int radius, int height) {
        for (int cx = (c.getX() - radius) >> 4; cx <= (c.getX() + radius) >> 4; cx++)
            for (int cz = (c.getZ() - radius) >> 4; cz <= (c.getZ() + radius) >> 4; cz++) level.getChunk(cx, cz);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++)
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue;
                int x = c.getX() + dx, z = c.getZ() + dz;
                for (int y = c.getY(); y <= c.getY() + height; y++) level.setBlock(p.set(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                int top = surface(level, x, z);
                for (int y = Math.min(top, c.getY() - 1); y < c.getY() - 1; y++) level.setBlock(p.set(x, y, z), Blocks.DIRT.defaultBlockState(), 2);
                level.setBlock(p.set(x, c.getY() - 1, z), Blocks.GRASS_BLOCK.defaultBlockState(), 2);
            }
    }

    // ------------------------------------------------------------------ stage 1: tents

    static Layout tents(ServerLevel level, BlockPos c, RandomSource r) {
        List<BlockPos> chests = new ArrayList<>();
        Block[] wools = {Blocks.WHITE_WOOL, Blocks.LIGHT_GRAY_WOOL, Blocks.BROWN_WOOL};
        tent(level, c.offset(-5, 0, 0), wools[r.nextInt(3)].defaultBlockState(), r, chests);
        tent(level, c.offset(5, 0, 0), wools[r.nextInt(3)].defaultBlockState(), r, chests);
        level.setBlock(c.offset(0, 0, 3), Blocks.CAMPFIRE.defaultBlockState(), 3);
        return new Layout(chests, null, List.of(), 9);
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

    // ------------------------------------------------------------------ stages 2-4: outpost

    /** Local coordinates: +z is the front (gate side), y=0 is the ground floor. Rotated so the front faces `front`. */
    private record Canvas(ServerLevel level, BlockPos c, Rotation rot) {
        BlockPos at(int x, int y, int z) { return c.offset(new BlockPos(x, y, z).rotate(rot)); }

        void set(int x, int y, int z, BlockState s) {
            BlockPos p = at(x, y, z);
            level.setBlock(p, Block.updateFromNeighbourShapes(s.rotate(rot), level, p), 3);
        }
    }

    private record Palette(BlockState wall, BlockState pillar, BlockState wallTop, BlockState tower, BlockState towerTop,
                           BlockState floor, BlockState floorAlt, BlockState room, BlockState roof) {}

    static Rotation rotationTo(Direction front) {
        return switch (front) {
            case WEST -> Rotation.CLOCKWISE_90;
            case NORTH -> Rotation.CLOCKWISE_180;
            case EAST -> Rotation.COUNTERCLOCKWISE_90;
            default -> Rotation.NONE;
        };
    }

    static BlockState block(String id) {
        return BuiltInRegistries.BLOCK.get(ResourceLocation.parse(id)).defaultBlockState();
    }

    /** Wood type of the local trees, guessed from the biome name (works for most modded biomes too). */
    static String wood(ServerLevel level, BlockPos c) {
        String b = level.getBiome(c).unwrapKey().map(k -> k.location().getPath()).orElse("");
        if (b.contains("taiga") || b.contains("snowy") || b.contains("grove") || b.contains("frozen")) return "spruce";
        if (b.contains("birch")) return "birch";
        if (b.contains("dark_forest")) return "dark_oak";
        if (b.contains("jungle") || b.contains("bamboo")) return "jungle";
        if (b.contains("savanna") || b.contains("badlands") || b.contains("desert")) return "acacia";
        if (b.contains("cherry")) return "cherry";
        if (b.contains("mangrove")) return "mangrove";
        return "oak";
    }

    static Palette palette(int stage, String w) {
        String mc = "minecraft:";
        return switch (stage) {
            case 2 -> new Palette(block(mc + w + "_log"), block(mc + "stripped_" + w + "_log"), block(mc + w + "_fence"),
                    block(mc + w + "_planks"), block(mc + w + "_log"), Blocks.COARSE_DIRT.defaultBlockState(),
                    Blocks.DIRT_PATH.defaultBlockState(), block(mc + w + "_planks"), block(mc + w + "_slab"));
            case 3 -> new Palette(Blocks.COBBLESTONE.defaultBlockState(), Blocks.MOSSY_COBBLESTONE.defaultBlockState(),
                    Blocks.COBBLESTONE_WALL.defaultBlockState(), Blocks.STONE_BRICKS.defaultBlockState(), Blocks.STONE_BRICK_WALL.defaultBlockState(),
                    Blocks.COBBLESTONE.defaultBlockState(), Blocks.GRAVEL.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState(),
                    Blocks.COBBLESTONE_SLAB.defaultBlockState());
            default -> new Palette(block("create:industrial_iron_block"), block("create:andesite_casing"), Blocks.IRON_BARS.defaultBlockState(),
                    block("create:andesite_casing"), Blocks.IRON_BARS.defaultBlockState(), Blocks.POLISHED_ANDESITE.defaultBlockState(),
                    Blocks.ANDESITE.defaultBlockState(), Blocks.STONE_BRICKS.defaultBlockState(), Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
        };
    }

    static Layout outpost(ServerLevel level, BlockPos c, int stage, Direction front, RandomSource r) {
        Canvas k = new Canvas(level, c, rotationTo(front));
        String w = wood(level, c);
        Palette p = palette(stage, w);

        for (int x = -10; x <= 10; x++)
            for (int z = -10; z <= 10; z++) k.set(x, -1, z, r.nextInt(3) == 0 ? p.floorAlt : p.floor);

        // Walls 5 high with a 3x2 gate in the front; every 4th column is a pillar; a crenel on every other column.
        for (int i = -11; i <= 11; i++) {
            BlockState s = Math.floorMod(i, 4) == 0 ? p.pillar : p.wall;
            for (int y = 0; y <= 4; y++) {
                if (Math.abs(i) > 1 || y > 1) k.set(i, y, 11, s);
                k.set(i, y, -11, s);
                k.set(-11, y, i, s);
                k.set(11, y, i, s);
            }
            if (i % 2 == 0) for (int[] e : new int[][]{{i, 11}, {i, -11}, {-11, i}, {11, i}}) k.set(e[0], 5, e[1], p.wallTop);
        }
        BlockState gate = block("minecraft:" + w + "_fence_gate").setValue(FenceGateBlock.FACING, Direction.SOUTH);
        for (int x = -1; x <= 1; x++) k.set(x, 0, 11, gate);
        k.set(-2, 2, 12, Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, Direction.SOUTH));
        k.set(2, 2, 12, Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, Direction.SOUTH));

        // Corner towers: 5x5, 9 high, ladder on the courtyard side, crenels on the corners. Cannons from stage 3.
        List<int[]> towers = new ArrayList<>(List.of(new int[]{1, 1}, new int[]{-1, 1}, new int[]{1, -1}, new int[]{-1, -1}));
        for (int[] t : towers) {
            int tx = 11 * t[0], tz = 11 * t[1];
            for (int dx = -2; dx <= 2; dx++)
                for (int dz = -2; dz <= 2; dz++) {
                    for (int y = 0; y <= 8; y++) k.set(tx + dx, y, tz + dz, p.tower);
                    if (Math.abs(dx) == 2 && Math.abs(dz) == 2) k.set(tx + dx, 9, tz + dz, p.towerTop);
                }
            BlockState ladder = Blocks.LADDER.defaultBlockState().setValue(LadderBlock.FACING, t[0] > 0 ? Direction.WEST : Direction.EAST);
            for (int y = 0; y <= 8; y++) k.set(tx - 3 * t[0], y, tz - t[1], ladder);
        }
        List<BlockPos> cannons = new ArrayList<>();
        if (stage >= 3) {
            Collections.shuffle(towers, new java.util.Random(r.nextLong()));
            int n = stage == 3 ? 1 + r.nextInt(2) : 2 + r.nextInt(3);
            for (int[] t : towers.subList(0, n)) {
                BlockPos mount = k.at(11 * t[0], 9, 11 * t[1]);
                String err = Artillery.buildCannon(level, mount, front);
                if (err == null) cannons.add(mount);
                else RaidsOnPlayer.LOG.warn("Cannon at {} did not assemble: {}", mount, err);
            }
        }

        // Loot room against the back wall, door facing the courtyard.
        for (int x = -4; x <= 4; x++)
            for (int z = -10; z <= -6; z++) {
                for (int y = 0; y <= 3; y++) if (Math.abs(x) == 4 || z == -10 || z == -6) k.set(x, y, z, p.room);
                k.set(x, 4, z, p.roof.hasProperty(SlabBlock.TYPE) ? p.roof.setValue(SlabBlock.TYPE, SlabType.BOTTOM) : p.roof);
            }
        BlockState door = block("minecraft:" + w + "_door").setValue(DoorBlock.FACING, Direction.SOUTH);
        k.set(0, 0, -6, door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        k.set(0, 1, -6, door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        k.set(0, 3, -8, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        int[][] chestSpots = {{-3, -9}, {3, -9}, {-1, -9}, {1, -9}, {-3, -7}, {3, -7}, {-3, -8}, {3, -8}};
        int chests = 4 + (stage - 2) + r.nextInt(3); // stage 2: 4-6, stage 3: 5-7, stage 4: 6-8
        List<BlockPos> chestPos = new ArrayList<>();
        for (int i = 0; i < chests; i++) {
            int[] s = chestSpots[i];
            Direction f = s[1] == -9 ? Direction.SOUTH : s[0] < 0 ? Direction.EAST : Direction.WEST;
            k.set(s[0], 0, s[1], Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, f));
            chestPos.add(k.at(s[0], 0, s[1]));
        }

        // Barracks beds along the left wall and a campfire in the yard.
        for (int z = -3; z <= 3; z += 3) {
            BlockState bed = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.WEST);
            k.set(-9, 0, z, bed.setValue(BedBlock.PART, BedPart.FOOT));
            k.set(-10, 0, z, bed.setValue(BedBlock.PART, BedPart.HEAD));
        }
        k.set(0, 0, -2, Blocks.CAMPFIRE.defaultBlockState());

        if (stage == 2) {
            // Wheat farm with a water source, and a Create press over a depot driven by a water wheel (decorative).
            for (int x = 5; x <= 9; x++)
                for (int z = 3; z <= 8; z++) {
                    if (x == 7 && z == 5) { k.set(x, -1, z, Blocks.WATER.defaultBlockState()); continue; }
                    k.set(x, -1, z, Blocks.FARMLAND.defaultBlockState().setValue(FarmBlock.MOISTURE, 7));
                    k.set(x, 0, z, Blocks.WHEAT.defaultBlockState().setValue(CropBlock.AGE, r.nextInt(8)));
                }
            k.set(7, 0, -3, block("create:depot"));
            k.set(7, 2, -3, Artillery.with(block("create:mechanical_press"), "facing", "east"));
            k.set(8, 2, -3, Artillery.with(block("create:shaft"), "axis", "x"));
            k.set(9, 2, -3, Artillery.with(block("create:water_wheel"), "facing", "east"));
        }
        if (stage >= 3) {
            // Lava moat two blocks wide around the towers, with a stone bridge in front of the gate.
            for (int x = -16; x <= 16; x++)
                for (int z = -16; z <= 16; z++) {
                    if (Math.max(Math.abs(x), Math.abs(z)) < 15) continue;
                    k.set(x, -2, z, Blocks.COBBLESTONE.defaultBlockState());
                    k.set(x, -1, z, Math.abs(x) <= 1 && z > 0 ? p.floor : Blocks.LAVA.defaultBlockState());
                }
        }
        if (stage >= 4) {
            // Smokestack in the back-left corner.
            for (int y = 0; y <= 8; y++) k.set(-8, y, -8, Blocks.BRICKS.defaultBlockState());
            k.set(-8, 9, -8, Blocks.HAY_BLOCK.defaultBlockState());
            k.set(-8, 10, -8, Blocks.CAMPFIRE.defaultBlockState().setValue(CampfireBlock.SIGNAL_FIRE, true));
        }
        return new Layout(chestPos, k.at(0, 0, 11), cannons, 24);
    }

    // ------------------------------------------------------------------ loot

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
