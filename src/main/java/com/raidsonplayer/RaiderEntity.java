package com.raidsonplayer;

import com.raidsonplayer.RaidData.Base;
import com.raidsonplayer.RaidData.Squad;
import com.raidsonplayer.RaidData.State;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.*;

/**
 * A pillager that belongs to a squad. It is a "view" of squad data: RaidManager spawns and discards it, and copies
 * loaded from disk are dropped (the squad respawns them). Future raider classes should extend this.
 */
public class RaiderEntity extends Pillager {
    @Nullable public UUID squadId;
    boolean stale;     // loaded from disk while belonging to a squad: the squad data is the source of truth
    boolean stuck;     // wants to move but hasn't for 5 s; consumed by the mechanism goal
    int stuckChecks;   // consecutive stuck checks; the march goal hops forward after a few
    boolean stealing;
    @Nullable private Vec3 lastCheck;
    private final Map<BlockPos, Long> usedMechanisms = new HashMap<>();

    public RaiderEntity(EntityType<? extends Pillager> type, Level level) {
        super(type, level);
        if (getNavigation() instanceof GroundPathNavigation nav) nav.setCanOpenDoors(true);
    }

    @Override
    protected void registerGoals() {
        // Replaces Pillager/Raider/Patrol goals entirely: no vanilla raid or patrol behaviour.
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(1, new MarchGoal(true));
        goalSelector.addGoal(2, new RangedCrossbowAttackGoal<>(this, 1.0, 8.0F));
        goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.1, false) {
            @Override public boolean canUse() { return !holdingCrossbow() && super.canUse(); }
        });
        goalSelector.addGoal(1, new UseMechanismGoal()); // beats melee: a raider pressed against a wall goes for the lever
        goalSelector.addGoal(4, new StealGoal());
        goalSelector.addGoal(4, new OpenDoorGoal(this, true));
        goalSelector.addGoal(5, new MarchGoal(false));
        goalSelector.addGoal(6, new MoveTowardsRestrictionGoal(this, 0.8));
        goalSelector.addGoal(8, new RandomStrollGoal(this, 0.6));
        goalSelector.addGoal(9, new LookAtPlayerGoal(this, Player.class, 15.0F));
        goalSelector.addGoal(10, new LookAtPlayerGoal(this, Mob.class, 15.0F));
        targetSelector.addGoal(1, new HurtByTargetGoal(this, Raider.class) {
            @Override public boolean canUse() { return !retreating() && super.canUse(); }
            @Override public boolean canContinueToUse() { return !retreating() && super.canContinueToUse(); }
        }.setAlertOthers());
        targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true) {
            @Override public boolean canUse() { return !retreating() && super.canUse(); }
            @Override public boolean canContinueToUse() { return !retreating() && super.canContinueToUse(); }
        });
        targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, IronGolem.class, true) {
            @Override public boolean canUse() { return !retreating() && super.canUse(); }
            @Override public boolean canContinueToUse() { return !retreating() && super.canContinueToUse(); }
        });
    }

    boolean holdingCrossbow() { return isHolding(s -> s.getItem() instanceof CrossbowItem); }

    @Nullable Squad squad() {
        return squadId == null || !(level() instanceof ServerLevel sl) ? null : RaidData.get(sl.getServer()).squads.get(squadId);
    }

    @Nullable Base base(Squad s) {
        return RaidData.get(((ServerLevel) level()).getServer()).bases.get(s.base);
    }

    boolean retreating() {
        Squad s = squad();
        return s != null && s.state == State.RETREAT;
    }

    @Override
    public void tick() {
        if (stale && !level().isClientSide) {
            // The squad already respawned this raider from data; only its pockets (stolen loot) need handing back.
            Squad s = squad();
            for (ItemStack i : getInventory().removeAllItems()) if (s != null) s.loot.add(i); else spawnAtLocation(i);
            discard();
            return;
        }
        super.tick();
        if (level().isClientSide) return;
        if (retreating() && getTarget() != null) setTarget(null); // pack alerts bypass target goals
        if (tickCount % 100 == 0) {
            Squad s = squad();
            Base b = s == null ? null : base(s);
            boolean wantsToMove = b != null && switch (s.state) {
                case GARRISON -> getTarget() != null && distanceToSqr(getTarget()) > 9; // defenders open their own gate
                case OUTBOUND, RETREAT -> true;
                case ATTACK -> stealing || getTarget() != null && distanceToSqr(getTarget()) > 9
                        || distanceToSqr(Vec3.atCenterOf(b.home)) > 12 * 12;
            };
            stuck = wantsToMove && lastCheck != null && position().distanceTo(lastCheck) < 1.5;
            stuckChecks = stuck ? stuckChecks + 1 : 0;
            lastCheck = position();
        }
    }

    @Override public boolean removeWhenFarAway(double distanceSq) { return false; }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (squadId != null) tag.putUUID("ropSquad", squadId);
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        stale = tag.hasUUID("ropSquad");
        if (stale) squadId = tag.getUUID("ropSquad");
    }

    @Override
    protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean recentlyHit) {
        super.dropCustomDeathLoot(level, source, recentlyHit);
        for (ItemStack s : getInventory().removeAllItems()) spawnAtLocation(s); // stolen goods go back to the player
    }

    /** Walks the squad toward its target (home on a raid, camp on retreat). Waypoints keep paths within follow range. */
    class MarchGoal extends Goal {
        private final boolean retreatOnly;
        private int repath;

        MarchGoal(boolean retreatOnly) {
            this.retreatOnly = retreatOnly;
            setFlags(EnumSet.of(Flag.MOVE));
        }

        @Nullable Vec3 target() {
            Squad s = squad();
            if (s == null) return null;
            Base b = base(s);
            if (b == null || b.camp == null) return null;
            if (retreatOnly) return s.state == State.RETREAT ? Vec3.atBottomCenterOf(b.camp) : null;
            if (getTarget() != null) return null;
            if (s.state == State.OUTBOUND) return Vec3.atBottomCenterOf(b.home);
            if (s.state == State.ATTACK && distanceToSqr(Vec3.atCenterOf(b.home)) > 12 * 12) return Vec3.atBottomCenterOf(b.home);
            return null;
        }

        @Override public boolean canUse() { return target() != null; }
        @Override public void start() { repath = 0; }

        @Override
        public void tick() {
            Vec3 t = target();
            if (t == null) return;
            if (--repath <= 0 || getNavigation().isDone()) {
                repath = 40;
                Vec3 d = t.subtract(position()).multiply(1, 0, 1);
                Vec3 wp = d.length() > 16 ? position().add(d.normalize().scale(16)) : t;
                int y = level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(wp.x), (int) Math.floor(wp.z));
                getNavigation().moveTo(wp.x, y, wp.z, retreatOnly ? 1.2 : 1.0);
            }
            // Stuck for 15 s where no player can see (cave, ditch), or 40 s regardless: hop forward onto the surface.
            if (stuckChecks >= 8 || stuckChecks >= 3 && level().players().stream().noneMatch(p -> p.distanceToSqr(RaiderEntity.this) < 48 * 48 && p.hasLineOfSight(RaiderEntity.this))) {
                Vec3 d = t.subtract(position()).multiply(1, 0, 1);
                if (d.length() > 1) {
                    Vec3 p = position().add(d.normalize().scale(Math.min(8, d.length())));
                    int y = level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) Math.floor(p.x), (int) Math.floor(p.z));
                    teleportTo(p.x, y, p.z);
                    lastCheck = null;
                }
                stuckChecks = 0;
            }
        }
    }

    /** When stuck, try nearby buttons, levers, fence gates and Create hand cranks to open a way. */
    class UseMechanismGoal extends Goal {
        private BlockPos pos;
        private int ticks, cranking;
        private boolean used;

        UseMechanismGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }

        @Override public boolean requiresUpdateEveryTick() { return true; }

        @Override
        public boolean canUse() {
            if (!stuck) return false;
            stuck = false; // one scan per stuck check (every 5 s), not every tick
            Squad s = squad();
            if (s == null) return false;
            long now = level().getGameTime();
            usedMechanisms.values().removeIf(t -> now - t > 600);
            pos = BlockPos.betweenClosedStream(blockPosition().offset(-10, -3, -10), blockPosition().offset(10, 3, 10))
                    .filter(p -> !usedMechanisms.containsKey(p) && isMechanism(level().getBlockState(p), p))
                    .map(BlockPos::immutable)
                    .min(Comparator.comparingDouble(p -> p.distSqr(blockPosition())))
                    .orElse(null);
            return pos != null;
        }

        @Override public void start() { ticks = 0; cranking = 0; used = false; getNavigation().moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 1.0); }
        @Override public boolean canContinueToUse() { return ticks < 160 && (!used || cranking > 0); }

        @Override
        public void stop() {
            stuck = false;
            usedMechanisms.put(pos, level().getGameTime());
        }

        @Override
        public void tick() {
            ticks++;
            getLookControl().setLookAt(Vec3.atCenterOf(pos));
            if (cranking > 0) { cranking--; turnCrank(); return; }
            if (used || distanceToSqr(Vec3.atCenterOf(pos)) > 3.0 * 3.0) return;
            used = true;
            swing(InteractionHand.MAIN_HAND);
            BlockState st = level().getBlockState(pos);
            Block b = st.getBlock();
            if (b instanceof ButtonBlock bb) bb.press(st, level(), pos, null);
            else if (b instanceof LeverBlock lb) lb.pull(st, level(), pos, null);
            else if (b instanceof FenceGateBlock) {
                level().setBlock(pos, st.setValue(FenceGateBlock.OPEN, !st.getValue(FenceGateBlock.OPEN)), 10);
                level().playSound(null, pos, SoundEvents.FENCE_GATE_OPEN, SoundSource.BLOCKS, 1f, 1f);
            } else cranking = 60;
        }

        private void turnCrank() {
            BlockEntity be = level().getBlockEntity(pos);
            Method m = be == null ? null : crankMethod(be);
            try { if (m != null) m.invoke(be, false); } catch (ReflectiveOperationException e) { cranking = 0; }
        }

        private boolean isMechanism(BlockState st, BlockPos p) {
            Block b = st.getBlock();
            if (b instanceof ButtonBlock) return !st.getValue(ButtonBlock.POWERED);
            if (b instanceof LeverBlock || b instanceof FenceGateBlock) return true;
            if (!st.hasBlockEntity()) return false;
            BlockEntity be = level().getBlockEntity(p);
            return be != null && crankMethod(be) != null;
        }
    }

    private static final Map<Class<?>, Optional<Method>> CRANKS = new HashMap<>();

    /** Create's HandCrankBlockEntity (and valve handles) expose public turn(boolean back); a player calls it every tick while holding use. */
    @Nullable static Method crankMethod(BlockEntity be) {
        return CRANKS.computeIfAbsent(be.getClass(), c -> {
            String key = BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType()) + "";
            if (!key.startsWith("create:")) return Optional.empty();
            try { return Optional.of(c.getMethod("turn", boolean.class)); } catch (NoSuchMethodException e) { return Optional.empty(); }
        }).orElse(null);
    }

    /** During an attack with no one to fight: go to a storage block near the player base and take two stacks. */
    class StealGoal extends Goal {
        private BlockPos pos;
        private Squad squad;
        private int ticks;

        StealGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }

        @Override
        public boolean canUse() {
            if (tickCount % 20 != 0 || getTarget() != null || full()) return false;
            squad = squad();
            if (squad == null || squad.state != State.ATTACK) return false;
            Base b = base(squad);
            if (b == null) return false;
            pos = findStorage(b.home);
            return pos != null;
        }

        private boolean full() { return getInventory().getItems().stream().noneMatch(ItemStack::isEmpty); }

        @Nullable private BlockPos findStorage(BlockPos home) {
            BlockPos best = null;
            double bestD = Double.MAX_VALUE;
            for (int cx = (home.getX() - 24) >> 4; cx <= (home.getX() + 24) >> 4; cx++)
                for (int cz = (home.getZ() - 24) >> 4; cz <= (home.getZ() + 24) >> 4; cz++) {
                    if (!level().hasChunk(cx, cz)) continue;
                    for (BlockPos p : level().getChunk(cx, cz).getBlockEntitiesPos()) {
                        if (squad.claimed.contains(p) || p.distSqr(home) > 24 * 24) continue;
                        IItemHandler h = level().getCapability(Capabilities.ItemHandler.BLOCK, p, null);
                        if (h == null || h.getSlots() < 9 || isEmpty(h)) continue;
                        double d = p.distSqr(blockPosition());
                        if (d < bestD) { bestD = d; best = p.immutable(); }
                    }
                }
            return best;
        }

        private static boolean isEmpty(IItemHandler h) {
            for (int i = 0; i < h.getSlots(); i++) if (!h.getStackInSlot(i).isEmpty()) return false;
            return true;
        }

        private boolean done;

        @Override public void start() { ticks = 0; done = false; stealing = true; squad.claimed.add(pos); getNavigation().moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 1.0); }
        @Override public boolean canContinueToUse() { return !done && ticks < 300 && getTarget() == null && squad.state == State.ATTACK; }
        @Override public void stop() { stealing = false; squad.claimed.remove(pos); }

        @Override
        public void tick() {
            ticks++;
            getLookControl().setLookAt(Vec3.atCenterOf(pos));
            if (ticks % 40 == 0) getNavigation().moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 1.0);
            if (distanceToSqr(Vec3.atCenterOf(pos)) > 2.5 * 2.5) return;
            IItemHandler h = level().getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
            if (h != null) {
                List<Integer> slots = new ArrayList<>();
                for (int i = 0; i < h.getSlots(); i++) if (!h.getStackInSlot(i).isEmpty()) slots.add(i);
                Collections.shuffle(slots, new Random(getRandom().nextLong()));
                int taken = 0;
                for (int slot : slots) {
                    if (taken >= 2 || full()) break;
                    ItemStack got = h.extractItem(slot, 64, false);
                    ItemStack rest = getInventory().addItem(got);
                    if (!rest.isEmpty()) h.insertItem(slot, rest, false);
                    if (rest.getCount() < got.getCount()) taken++;
                }
                squad.stolen += taken;
                if (taken > 0) squad.lastSteal = level().getGameTime();
                swing(InteractionHand.MAIN_HAND);
                level().playSound(null, pos, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 0.6f, 1f);
            }
            done = true;
        }
    }
}
