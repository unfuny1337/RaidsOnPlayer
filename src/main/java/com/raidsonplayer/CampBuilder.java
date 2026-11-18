package com.raidsonplayer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.BiomeTags;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds raider bases in code. Stage 1 = two tents (concept.png). Stages 2-4 = the outpost (concept2.png): walls, four corner
 * towers, a gate with a watchman, a loot room against the back wall and raiders inside, built from wood, cobblestone, then
 * industrial blocks. Cannons sit on the towers from stage 3.
 */
public final class CampBuilder {
    public record Layout(List<BlockPos> chests, @Nullable BlockPos gate, @Nullable BlockPos gateCrank, List<BlockPos> cannons, int radius) {}

    private CampBuilder() {}

    /** Surface y (first free block) at x,z, loading/generating the chunk if needed. */
    static int surface(ServerLevel level, int x, int z) {
        return level.getChunk(x >> 4, z >> 4).getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x & 15, z & 15) + 1;
    }

    /**
     * Returns a base origin near x,z if the spot is dry and flat enough for an outpost, else null. The floor goes at the
     * median ground height around it, so the levelling cuts and fills about equally. Generates chunks synchronously.
     */
    @Nullable
    static BlockPos trySite(ServerLevel level, int x, int z) {
        BlockPos at = new BlockPos(x, surface(level, x, z), z);
        if (level.getBiome(at).is(BiomeTags.IS_OCEAN) || level.getBiome(at).is(BiomeTags.IS_RIVER)) return null;
        List<Integer> hs = new ArrayList<>();
        for (int dx = -16; dx <= 16; dx += 8)
            for (int dz = -16; dz <= 16; dz += 8) {
                int h = surface(level, x + dx, z + dz);
                if (!level.getFluidState(new BlockPos(x + dx, h - 1, z + dz)).isEmpty()) return null;
                hs.add(h);
            }
        hs.sort(null);
        return hs.get(hs.size() - 1) - hs.get(0) <= 8 ? new BlockPos(x, hs.get(hs.size() / 2), z) : null;
    }

    /** Builds the base for a stage, gate facing `front`, and fills its chests. Clears the footprint first (also used for rebuilds). */
    static Layout build(ServerLevel level, BlockPos c, int stage, int clearRadius, Direction front, boolean expansion, RandomSource r) {
        int h = outpostHalf(stage, expansion);
        int radius = Math.max(stage == 1 ? (expansion ? 14 : 9) : h + 13, clearRadius);
        Ground g = ground(level, c, radius + 4); // read before flattening, from terrain the build never touches (also on rebuilds)
        flatten(level, c, radius, stage == 1 ? 10 : 18, g);
        Layout l = stage == 1 ? tents(level, c, expansion, r) : outpost(level, c, stage, h, front, g, r);
        snow(level, c, radius);
        fillLoot(level, l.chests(), stage, r);
        return l;
    }

    /** Half-width of the outpost walls: 11 for a player base's first camp; expansion camps are bigger, 13/15/17 at stages 2/3/4. */
    static int outpostHalf(int stage, boolean expansion) { return expansion ? 9 + 2 * stage : 11; }

    /** The biome's natural ground: its top block (grass, sand, podzol, snow...) and the block under it. */
    record Ground(BlockState top, BlockState under) {}

    /** Reads the ground from the most common surface blocks on a ring around c, so it works for modded biomes too. */
    static Ground ground(ServerLevel level, BlockPos c, int ring) {
        Map<Block, Integer> tops = new HashMap<>(), unders = new HashMap<>();
        for (int i = 0; i < 16; i++) {
            double a = i * Math.PI / 8;
            int x = c.getX() + (int) Math.round(Math.cos(a) * ring), z = c.getZ() + (int) Math.round(Math.sin(a) * ring);
            BlockPos top = new BlockPos(x, surface(level, x, z) - 1, z);
            if (!natural(level, top)) continue;
            tops.merge(level.getBlockState(top).getBlock(), 1, Integer::sum);
            if (natural(level, top.below())) unders.merge(level.getBlockState(top.below()).getBlock(), 1, Integer::sum);
        }
        BlockState under = mostCommon(unders, Blocks.DIRT);
        if (under.getBlock() instanceof FallingBlock) // fill can sit on ponds or caves, so it must not fall: sand -> sandstone
            under = (under.is(Blocks.RED_SAND) ? Blocks.RED_SANDSTONE : under.is(BlockTags.SAND) ? Blocks.SANDSTONE : Blocks.STONE).defaultBlockState();
        return new Ground(mostCommon(tops, Blocks.GRASS_BLOCK), under);
    }

    /** A full terrain block: not fluid, a tree trunk or a block entity (chest, spawner, suspicious sand). */
    private static boolean natural(ServerLevel level, BlockPos p) {
        BlockState s = level.getBlockState(p);
        return s.getFluidState().isEmpty() && !s.is(BlockTags.LOGS) && !s.hasBlockEntity() && s.isCollisionShapeFullBlock(level, p);
    }

    private static BlockState mostCommon(Map<Block, Integer> counts, Block fallback) {
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(fallback).defaultBlockState();
    }

    /** Width of the slope that joins the levelled disc to the natural ground around it. */
    static final int BLEND = 12;

    /**
     * Levels a disc: clears above the floor (at least `height` blocks, and any higher ground), fills the biome's
     * under-block below it, its top block on top. Around it a BLEND-wide ring is cut or filled into an eased slope from
     * the floor out to the natural ground, so the camp sits in the land instead of on a plinth or in a pit.
     */
    static void flatten(ServerLevel level, BlockPos c, int radius, int height, Ground g) {
        int outer = radius + BLEND;
        for (int cx = (c.getX() - outer) >> 4; cx <= (c.getX() + outer) >> 4; cx++)
            for (int cz = (c.getZ() - outer) >> 4; cz <= (c.getZ() + outer) >> 4; cz++) level.getChunk(cx, cz);
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int floor = c.getY(); // first free block above the floor
        for (int dx = -outer; dx <= outer; dx++)
            for (int dz = -outer; dz <= outer; dz++) {
                double d = Math.sqrt(dx * dx + dz * dz);
                if (d > outer) continue;
                int x = c.getX() + dx, z = c.getZ() + dz, top = surface(level, x, z);
                if (d <= radius) {
                    for (int y = floor; y <= Math.max(floor + height, top); y++) level.setBlock(p.set(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                    for (int y = Math.min(top, floor - 1); y < floor - 1; y++) level.setBlock(p.set(x, y, z), g.under, 2);
                    level.setBlock(p.set(x, floor - 1, z), g.top, 2);
                    continue;
                }
                int ground = groundTop(level, x, z, g);
                if (ground == NONE) continue; // lava, or something built: leave it be
                int surf = level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z); // above any water
                boolean wet = !level.getFluidState(p.set(x, surf - 1, z)).isEmpty();
                BlockState own = level.getBlockState(p.set(x, ground, z)); // this spot's surface (sand at a desert's edge, snow...)
                double t = (d - radius) / BLEND;
                t = t * t * (3 - 2 * t); // eased at both ends: no crease at the floor's edge or where the slope meets the land
                int want = (int) Math.round(floor + (ground + 1 - floor) * t); // first free block after shaping
                if (want <= ground) { // cut down, with the plants and tree trunks that stood on the removed ground
                    if (wet) continue; // never dig out a lake bed
                    for (int y = want; y < Math.max(top, ground + 3); y++) level.setBlock(p.set(x, y, z), Blocks.AIR.defaultBlockState(), 2);
                } else for (int y = ground; y < want - 1; y++) level.setBlock(p.set(x, y, z), g.under, 2); // build up (a bank into water)
                if (want != ground + 1) level.setBlock(p.set(x, want - 1, z), wet && want < surf ? g.under : own, 2);
            }
    }

    static final int NONE = Integer.MIN_VALUE;

    /**
     * y of the natural ground at x,z under trees, plants and water (a lake bed); NONE if the column is lava or built on (a
     * fence, a farm, a log wall: logs only count as a tree under leaves).
     */
    static int groundTop(ServerLevel level, int x, int z, Ground g) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos(x, level.getHeight(Heightmap.Types.MOTION_BLOCKING, x, z) - 1, z);
        boolean canopy = false;
        for (int i = 0; i < 48; i++, p.move(Direction.DOWN)) {
            BlockState s = level.getBlockState(p);
            if (s.getFluidState().is(FluidTags.LAVA) || s.hasBlockEntity()) return NONE;
            canopy |= s.is(BlockTags.LEAVES);
            if (s.isAir() || !s.getFluidState().isEmpty() || s.is(BlockTags.LEAVES) || canopy && s.is(BlockTags.LOGS) || s.is(BlockTags.REPLACEABLE_BY_TREES)
                    || s.getBlock() instanceof BushBlock || s.is(Blocks.SNOW) || s.is(Blocks.CACTUS) || s.is(Blocks.SUGAR_CANE)) continue;
            return s.is(BlockTags.DIRT) || s.is(BlockTags.SAND) || s.is(BlockTags.BASE_STONE_OVERWORLD) || s.is(BlockTags.TERRACOTTA)
                    || s.is(BlockTags.SNOW) || s.is(Blocks.GRAVEL) || s.is(Blocks.SANDSTONE) || s.is(Blocks.RED_SANDSTONE)
                    || s.is(Blocks.CLAY) || s.is(Blocks.ICE) || s.is(Blocks.PACKED_ICE)
                    || s.is(g.top().getBlock()) || s.is(g.under().getBlock()) ? p.getY() : NONE;
        }
        return NONE;
    }

    /** Snow where snowfall would put it (vanilla's own rule) in cold biomes: open floor (not roofs or walls) and the slope around it. */
    static void snow(ServerLevel level, BlockPos c, int radius) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        int outer = radius + BLEND;
        for (int dx = -outer; dx <= outer; dx++)
            for (int dz = -outer; dz <= outer; dz++) {
                int d2 = dx * dx + dz * dz;
                if (d2 > outer * outer) continue;
                p.set(c.getX() + dx, 0, c.getZ() + dz);
                p.setY(level.getHeight(Heightmap.Types.MOTION_BLOCKING, p.getX(), p.getZ()));
                if ((d2 > radius * radius || p.getY() == c.getY()) && level.getBiome(p).value().shouldSnow(level, p))
                    level.setBlock(p, Blocks.SNOW.defaultBlockState(), 3);
            }
    }

    // ------------------------------------------------------------------ stage 1: tents

    /** Two tents and a campfire; an expansion camp has a second row of two behind them, with its own campfire. */
    static Layout tents(ServerLevel level, BlockPos c, boolean expansion, RandomSource r) {
        List<BlockPos> chests = new ArrayList<>();
        Block[] wools = {Blocks.WHITE_WOOL, Blocks.LIGHT_GRAY_WOOL, Blocks.BROWN_WOOL};
        for (int z = 0; z <= (expansion ? 10 : 0); z += 10) {
            tent(level, c.offset(-5, 0, z), wools[r.nextInt(3)].defaultBlockState(), r, chests);
            tent(level, c.offset(5, 0, z), wools[r.nextInt(3)].defaultBlockState(), r, chests);
            level.setBlock(c.offset(0, 0, z + 3), Blocks.CAMPFIRE.defaultBlockState(), 3);
        }
        return new Layout(chests, null, null, List.of(), expansion ? 14 : 9);
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
        for (int i = 0; i < n; i++) { // side by side at the front-left corner (concept.png), facing out like the opening
            BlockPos p = o.offset(-3 - i, 0, 0);
            level.setBlock(p, Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, Direction.SOUTH), 3);
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

    static Palette palette(int stage, String w, Ground g) {
        String mc = "minecraft:";
        boolean dirt = g.top().is(BlockTags.DIRT); // trodden yard: coarse dirt and paths on grassland, the biome's own ground elsewhere
        return switch (stage) {
            case 2 -> new Palette(block(mc + w + "_log"), block(mc + "stripped_" + w + "_log"), block(mc + w + "_fence"),
                    block(mc + w + "_planks"), block(mc + w + "_log"), dirt ? Blocks.COARSE_DIRT.defaultBlockState() : g.top(),
                    dirt ? Blocks.DIRT_PATH.defaultBlockState() : g.under(), block(mc + w + "_planks"), block(mc + w + "_slab"));
            case 3 -> new Palette(Blocks.COBBLESTONE.defaultBlockState(), Blocks.MOSSY_COBBLESTONE.defaultBlockState(),
                    Blocks.COBBLESTONE_WALL.defaultBlockState(), Blocks.STONE_BRICKS.defaultBlockState(), Blocks.STONE_BRICK_WALL.defaultBlockState(),
                    Blocks.COBBLESTONE.defaultBlockState(), Blocks.GRAVEL.defaultBlockState(), Blocks.COBBLESTONE.defaultBlockState(),
                    Blocks.COBBLESTONE_SLAB.defaultBlockState());
            default -> new Palette(block("create:industrial_iron_block"), block("create:andesite_casing"), Blocks.IRON_BARS.defaultBlockState(),
                    block("create:andesite_casing"), Blocks.IRON_BARS.defaultBlockState(), Blocks.POLISHED_ANDESITE.defaultBlockState(),
                    Blocks.ANDESITE.defaultBlockState(), Blocks.STONE_BRICKS.defaultBlockState(), Blocks.SMOOTH_STONE_SLAB.defaultBlockState());
        };
    }

    /** The walled outpost; `h` is the half-width of the walls (outpostHalf). From h 15 there is also a tower mid-way along each side wall. */
    static Layout outpost(ServerLevel level, BlockPos c, int stage, int h, Direction front, Ground g, RandomSource r) {
        Canvas k = new Canvas(level, c, rotationTo(front));
        String w = wood(level, c);
        Palette p = palette(stage, w, g);

        for (int x = 1 - h; x <= h - 1; x++)
            for (int z = 1 - h; z <= h - 1; z++) k.set(x, -1, z, r.nextInt(3) == 0 ? p.floorAlt : p.floor);

        // Walls 5 high with a 3x2 gate in the front; every 4th column is a pillar; a crenel on every other column.
        for (int i = -h; i <= h; i++) {
            BlockState s = Math.floorMod(i, 4) == 0 ? p.pillar : p.wall;
            for (int y = 0; y <= 4; y++) {
                if (Math.abs(i) > 1 || y > 1) k.set(i, y, h, s);
                k.set(i, y, -h, s);
                k.set(-h, y, i, s);
                k.set(h, y, i, s);
            }
            if (i % 2 == 0) for (int[] e : new int[][]{{i, h}, {i, -h}, {-h, i}, {h, i}}) k.set(e[0], 5, e[1], p.wallTop);
        }
        BlockPos gateCrank = null;
        if (stage >= 4) gateCrank = createGate(k, p.wall, h);
        else {
            BlockState gate = block("minecraft:" + w + "_fence_gate").setValue(FenceGateBlock.FACING, Direction.SOUTH);
            for (int x = -1; x <= 1; x++) k.set(x, 0, h, gate);
        }
        k.set(-2, 2, h + 1, Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, Direction.SOUTH));
        k.set(2, 2, h + 1, Blocks.WALL_TORCH.defaultBlockState().setValue(WallTorchBlock.FACING, Direction.SOUTH));

        // Corner towers: 5x5, 9 high, ladder on the courtyard side, crenels on the corners. Cannons from stage 3.
        List<int[]> towers = new ArrayList<>(List.of(new int[]{1, 1}, new int[]{-1, 1}, new int[]{1, -1}, new int[]{-1, -1}));
        boolean midTowers = h >= 15;
        if (midTowers) { towers.add(new int[]{1, 0}); towers.add(new int[]{-1, 0}); }
        for (int[] t : towers) {
            int tx = h * t[0], tz = h * t[1];
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
                BlockPos mount = k.at(h * t[0], 9, h * t[1]);
                String err = Artillery.buildCannon(level, mount, front);
                if (err == null) cannons.add(mount);
                else RaidsOnPlayer.LOG.warn("Cannon at {} did not assemble: {}", mount, err);
            }
        }

        // Loot room against the back wall, door facing the courtyard; wider in the big camps, with more chests.
        int b = -h, rw = midTowers ? 6 : 4; // back wall z, room half-width
        for (int x = -rw; x <= rw; x++)
            for (int z = b + 1; z <= b + 5; z++) {
                for (int y = 0; y <= 3; y++) if (Math.abs(x) == rw || z == b + 1 || z == b + 5) k.set(x, y, z, p.room);
                k.set(x, 4, z, p.roof.hasProperty(SlabBlock.TYPE) ? p.roof.setValue(SlabBlock.TYPE, SlabType.BOTTOM) : p.roof);
            }
        BlockState door = block("minecraft:" + w + "_door").setValue(DoorBlock.FACING, Direction.SOUTH);
        k.set(0, 0, b + 5, door.setValue(DoorBlock.HALF, DoubleBlockHalf.LOWER));
        k.set(0, 1, b + 5, door.setValue(DoorBlock.HALF, DoubleBlockHalf.UPPER));
        k.set(0, 3, b + 3, Blocks.LANTERN.defaultBlockState().setValue(LanternBlock.HANGING, true));
        List<int[]> chestSpots = new ArrayList<>(List.of(new int[]{-3, b + 2}, new int[]{3, b + 2}, new int[]{-1, b + 2}, new int[]{1, b + 2},
                new int[]{-3, b + 4}, new int[]{3, b + 4}, new int[]{-3, b + 3}, new int[]{3, b + 3}));
        if (midTowers) for (int z = b + 2; z <= b + 4; z++) { chestSpots.add(new int[]{-5, z}); chestSpots.add(new int[]{5, z}); }
        // stage 2: 4-6, stage 3: 5-7, stage 4: 6-8; expansion camps 1-3 more
        int chests = Math.min(chestSpots.size(), 4 + (stage - 2) + r.nextInt(3) + (h - 11) / 2);
        List<BlockPos> chestPos = new ArrayList<>();
        for (int i = 0; i < chests; i++) {
            int[] s = chestSpots.get(i);
            Direction f = s[0] <= -3 ? Direction.EAST : s[0] >= 3 ? Direction.WEST : Direction.SOUTH; // side rows face the aisle
            k.set(s[0], 0, s[1], Blocks.CHEST.defaultBlockState().setValue(ChestBlock.FACING, f));
            chestPos.add(k.at(s[0], 0, s[1]));
        }

        // Barracks beds along the left wall (more in the big camps, none where the mid tower stands) and a campfire in the yard.
        int rows = 1 + (h - 11) / 3;
        for (int z = -3 * rows; z <= 3 * rows; z += 3) {
            if (midTowers && z == 0) continue;
            BlockState bed = Blocks.RED_BED.defaultBlockState().setValue(BedBlock.FACING, Direction.WEST);
            k.set(2 - h, 0, z, bed.setValue(BedBlock.PART, BedPart.FOOT));
            k.set(1 - h, 0, z, bed.setValue(BedBlock.PART, BedPart.HEAD));
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
            for (int x = -h - 5; x <= h + 5; x++)
                for (int z = -h - 5; z <= h + 5; z++) {
                    if (Math.max(Math.abs(x), Math.abs(z)) < h + 4) continue;
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
        return new Layout(chestPos, k.at(0, 0, h), gateCrank, cannons, h + 13);
    }

    /**
     * Stage 4 gate made with Create: a glued 3x2 iron-bar gate in a wall pocket beside the doorway, a sticky mechanical
     * piston (3 extension poles) that slides it across the opening, and a hand crank on the yard side to drive it.
     * Built open; returns the crank position.
     */
    static BlockPos createGate(Canvas k, BlockState wall, int h) {
        for (int x = 2; x <= 4; x++)
            for (int y = 0; y <= 1; y++) {
                k.set(x, y, h, Blocks.IRON_BARS.defaultBlockState());
                k.set(x, y, h - 1, wall); // the pocket is walled in on both sides, so it is never a hole when the gate is shut
                k.set(x, y, h + 1, wall);
            }
        // Create's piston shaft axis is FACING x axis_along_first (X-facing: false = Z shaft; Z-facing: true = X shaft) and
        // isn't fixed up by rotation, so set it in world terms: a horizontal shaft the yard-side crank can drive.
        Direction f = k.rot().rotate(Direction.WEST);
        k.level().setBlock(k.at(5, 0, h), Artillery.with(Artillery.with(block("create:sticky_mechanical_piston"), "facing", f.getSerializedName()),
                "axis_along_first", String.valueOf(f.getAxis() == Direction.Axis.Z)), 3);
        for (int x = 6; x <= 8; x++) k.set(x, 0, h, Artillery.with(block("create:piston_extension_pole"), "facing", "west"));
        k.set(5, 0, h - 1, Artillery.with(block("create:hand_crank"), "facing", "north"));
        Artillery.glue(k.level(), k.at(2, 0, h), k.at(4, 1, h));
        return k.at(5, 0, h - 1);
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
