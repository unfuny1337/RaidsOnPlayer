package com.raidsonplayer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import javax.annotation.Nullable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Create Big Cannons glue. CBC is a required dependency but has no public API, so its few entry points are called by
 * reflection: BigCannonBlock.onPlace (links cannon blocks), CannonMountBlockEntity.assemble/setYaw/setPitch/disassemble,
 * and FuzedBigCannonProjectile.setFuze. Shells are spawned directly at the muzzle: no ammo logistics, nobody behind the gun.
 */
final class Artillery {
    private static final ResourceLocation HE_SHELL = ResourceLocation.parse("createbigcannons:he_shell");

    private Artillery() {}

    static BlockState with(BlockState s, String prop, String value) {
        Property<?> p = s.getBlock().getStateDefinition().getProperty(prop);
        return p == null ? s : withTyped(s, p, value);
    }

    private static <T extends Comparable<T>> BlockState withTyped(BlockState s, Property<T> p, String v) {
        return p.getValue(v).map(x -> s.setValue(p, x)).orElse(s);
    }

    @Nullable
    private static Method method(Class<?> c, String name, Class<?>... args) {
        for (; c != null; c = c.getSuperclass())
            try {
                Method m = c.getDeclaredMethod(name, args);
                m.setAccessible(true);
                return m;
            } catch (NoSuchMethodException ignored) {}
        return null;
    }

    @Nullable
    private static Object call(Object target, String name, Class<?>[] types, Object... args) {
        Method m = method(target.getClass(), name, types);
        try { return m == null ? null : m.invoke(target, args); } catch (ReflectiveOperationException e) { return null; }
    }

    @Nullable
    static Entity contraption(@Nullable BlockEntity mount) {
        return mount == null ? null : (Entity) call(mount, "getContraption", new Class<?>[0]);
    }

    /**
     * Places a cast iron cannon (breech, chamber, two barrels) 2 blocks above a mount, pointing `dir`, and assembles it.
     * Returns null on success, otherwise why it failed. Tries both breech orientations.
     */
    @Nullable
    static String buildCannon(ServerLevel level, BlockPos mount, Direction dir) {
        String error = "unknown";
        for (Direction breech : new Direction[]{dir, dir.getOpposite()}) {
            level.setBlock(mount, with(with(CampBuilder.block("createbigcannons:cannon_mount"), "facing", dir.getSerializedName()), "vertical_direction", "down"), 3);
            String[] parts = {"cast_iron_sliding_breech", "cast_iron_cannon_chamber", "cast_iron_cannon_barrel", "cast_iron_cannon_barrel"};
            List<BlockPos> placed = new ArrayList<>();
            for (int i = 0; i < parts.length; i++) {
                BlockPos p = mount.above(2).relative(dir, i - 1); // a floor mount (vertical_direction=down) carries the cannon 2 blocks up
                Direction f = i == 0 ? breech : dir;
                level.setBlock(p, with(CampBuilder.block("createbigcannons:" + parts[i]), "facing", f.getSerializedName()), 3);
                placed.add(p);
            }
            try {
                Method onPlace = Class.forName("rbasamoyai.createbigcannons.cannons.big_cannons.BigCannonBlock").getMethod("onPlace", Level.class, BlockPos.class);
                for (BlockPos p : placed) onPlace.invoke(null, level, p);
                BlockEntity be = level.getBlockEntity(mount);
                Method assemble = be == null ? null : method(be.getClass(), "assemble");
                if (assemble == null) return "no cannon mount block entity";
                assemble.invoke(be);
                if (contraption(be) != null || level.getBlockState(mount.above(2)).isAir()) return null; // blocks moved into the contraption
                error = "assembled nothing";
            } catch (InvocationTargetException e) {
                Throwable c = e.getCause(); // Create's AssemblyException carries its text in a public `component` field
                try { error = ((net.minecraft.network.chat.Component) c.getClass().getField("component").get(c)).getString(); }
                catch (ReflectiveOperationException | RuntimeException x) { error = String.valueOf(c); }
            } catch (ReflectiveOperationException | RuntimeException e) {
                return e.toString();
            }
        }
        return error;
    }

    /** Removes Create/CBC contraptions and glue around a base before it is rebuilt (their blocks would otherwise float over the new build). */
    static void clearContraptions(ServerLevel level, BlockPos c, int r) {
        for (Entity e : level.getEntities((Entity) null, new AABB(c).inflate(r, 32, r), e -> {
            String path = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath();
            return path.contains("contraption") || path.contains("glue");
        })) e.discard();
    }

    /** Super Glue over the box from a to b, so a piston moves those blocks together. */
    static void glue(ServerLevel level, BlockPos a, BlockPos b) {
        try {
            Class<?> c = Class.forName("com.simibubi.create.content.contraptions.glue.SuperGlueEntity");
            AABB box = (AABB) c.getMethod("span", BlockPos.class, BlockPos.class).invoke(null, a, b);
            level.addFreshEntity((Entity) c.getConstructor(Level.class, AABB.class).newInstance(level, box));
        } catch (ReflectiveOperationException | RuntimeException e) {
            RaidsOnPlayer.LOG.warn("Could not glue the gate: {}", e.toString());
        }
    }

    static Vec3 velocity(Vec3 from, Vec3 to, int ticks) {
        double[] v = Ballistics.solve(new double[]{from.x, from.y, from.z}, new double[]{to.x, to.y, to.z}, ticks);
        return new Vec3(v[0], v[1], v[2]);
    }

    /** Turns the assembled cannon on `mount` toward `target` and fires an impact-fuzed HE shell. False if there is no cannon. */
    static boolean fireCannon(ServerLevel level, BlockPos mount, Vec3 target) {
        BlockEntity be = level.getBlockEntity(mount);
        if (contraption(be) == null) return false;
        Vec3 pivot = Vec3.atCenterOf(mount.above(2));
        int ticks = (int) Mth.clamp(pivot.distanceTo(target) / 2.5, 20, 100);
        Vec3 muzzle = pivot.add(velocity(pivot, target, ticks).normalize().scale(3));
        Vec3 v = velocity(muzzle, target, ticks - 1);
        double h = Math.sqrt(v.x * v.x + v.z * v.z);
        call(be, "setYaw", new Class<?>[]{float.class}, (float) Math.toDegrees(Math.atan2(-v.x, v.z)));
        call(be, "setPitch", new Class<?>[]{float.class}, (float) Math.toDegrees(Math.atan2(v.y, h)));
        spawnShell(level, muzzle, v);
        boom(level, muzzle);
        return true;
    }

    /** 2-4 HE shells diving onto `target` from far out along `from` (the direction of the raider base). */
    static void airstrike(ServerLevel level, Vec3 target, Vec3 from, int shells, RandomSource r) {
        Vec3 dir = new Vec3(from.x, 0, from.z).normalize();
        for (int i = 0; i < shells; i++) {
            Vec3 aim = target.add(Mth.nextDouble(r, -7, 7), 0, Mth.nextDouble(r, -7, 7));
            Vec3 start = target.add(dir.scale(90)).add(Mth.nextDouble(r, -8, 8), 60, Mth.nextDouble(r, -8, 8));
            if (!level.isLoaded(BlockPos.containing(start))) start = target.add(dir.scale(40)).add(0, 60, 0);
            spawnShell(level, start, velocity(start, aim, 70 + 8 * i));
        }
    }

    static void spawnShell(ServerLevel level, Vec3 pos, Vec3 v) {
        if (!BuiltInRegistries.ENTITY_TYPE.containsKey(HE_SHELL)) return;
        Entity e = BuiltInRegistries.ENTITY_TYPE.get(HE_SHELL).create(level);
        if (e == null) return;
        e.setPos(pos);
        e.setDeltaMovement(v);
        call(e, "setFuze", new Class<?>[]{ItemStack.class}, new ItemStack(BuiltInRegistries.ITEM.get(ResourceLocation.parse("createbigcannons:impact_fuze"))));
        call(e, "setOrientation", new Class<?>[]{Vec3.class}, v.normalize());
        level.addFreshEntity(e);
    }

    static void boom(ServerLevel level, Vec3 at) {
        SoundEvent s = BuiltInRegistries.SOUND_EVENT.get(ResourceLocation.parse("createbigcannons:fire_big_cannon"));
        if (s != null) level.playSound(null, at.x, at.y, at.z, s, SoundSource.BLOCKS, 8f, 1f);
        level.sendParticles(ParticleTypes.EXPLOSION, at.x, at.y, at.z, 2, 0.3, 0.3, 0.3, 0);
        level.sendParticles(ParticleTypes.CAMPFIRE_COSY_SMOKE, at.x, at.y, at.z, 12, 0.4, 0.4, 0.4, 0.02);
    }
}
