package com.raidsonplayer;

import com.raidsonplayer.RaidData.Base;
import com.raidsonplayer.RaidData.Kind;
import com.raidsonplayer.RaidData.Squad;
import com.raidsonplayer.RaidData.State;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.goal.*;
import net.minecraft.world.entity.ai.goal.target.HurtByTargetGoal;
import net.minecraft.world.entity.ai.goal.target.NearestAttackableTargetGoal;
import net.minecraft.world.entity.ai.navigation.GroundPathNavigation;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.entity.animal.IronGolem;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.AbstractIllager;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.entity.projectile.ProjectileUtil;
import net.minecraft.world.entity.raid.Raider;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.CrossbowItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.event.EventHooks;
import net.neoforged.neoforge.items.IItemHandler;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.*;

/**
 * A pillager that belongs to a squad and has a class (Role). It is a "view" of squad data: RaidManager spawns and
 * discards it, and copies loaded from disk are dropped (the squad respawns them).
 */
public class RaiderEntity extends Pillager {
    private static final EntityDataAccessor<Integer> ROLE = SynchedEntityData.defineId(RaiderEntity.class, EntityDataSerializers.INT);

    @Nullable public UUID squadId;
    boolean stale;     // loaded from disk while belonging to a squad: the squad data is the source of truth
    boolean stuck;     // wants to move but hasn't for 5 s; consumed by the mechanism, breach and dig goals (in that order)
    int stuckChecks;   // consecutive stuck checks; the march goal hops forward after a few (far from the target only)
    int stuckAt = -1, scannedAt = -1; // the mechanism scan runs once per stuck event; the dig/breach goals follow it
    boolean stealing, watchman, watching;
    @Nullable private Vec3 lastCheck;
    private final Map<BlockPos, Long> usedMechanisms = new HashMap<>();
    private boolean crankBack; // alternates, so a raider tries both ways on a crank-driven gate

    // Gear. The hand shows one of these at a time (updateHands): weapon, a bow at range, TNT or a spyglass, a tool while digging.
    ItemStack weapon = ItemStack.EMPTY, bow = ItemStack.EMPTY, special = ItemStack.EMPTY;
    final List<ItemStack> tools = new ArrayList<>();
    int tnt;
    @Nullable private ItemStack digTool;
    @Nullable Vec3 bombAt; // a squad mate lit TNT here: get clear until fleeUntil
    int fleeUntil;

    public RaiderEntity(EntityType<? extends Pillager> type, Level level) {
        super(type, level);
        if (getNavigation() instanceof GroundPathNavigation nav) nav.setCanOpenDoors(true);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(ROLE, 0);
    }

    public Role role() { return Role.of(entityData.get(ROLE)); }
    void setRole(Role r) { entityData.set(ROLE, r.ordinal()); }

    @Override
    protected Component getTypeName() {
        return role() == Role.RAIDER ? super.getTypeName() : Component.translatable("entity.raidsonplayer." + role().name().toLowerCase(Locale.ROOT));
    }

    @Override
    protected void registerGoals() {
        // Replaces Pillager/Raider/Patrol goals entirely: no vanilla raid or patrol behaviour.
        goalSelector.addGoal(0, new FloatGoal(this));
        goalSelector.addGoal(0, new FleeBombGoal());
        goalSelector.addGoal(1, new AvoidEntityGoal<>(this, Player.class, 16, 1.0, 1.35) {
            @Override public boolean canUse() { return role() == Role.SCOUT && super.canUse(); } // scouts run, they don't fight
        });
        goalSelector.addGoal(1, new MarchGoal(true));
        goalSelector.addGoal(1, new UseMechanismGoal()); // beats melee: a raider pressed against a wall goes for the lever
        goalSelector.addGoal(1, new BreachGoal());
        goalSelector.addGoal(1, new DigGoal());
        goalSelector.addGoal(2, new RangedBowAttackGoal<>(this, 1.0, 25, 16.0F));
        goalSelector.addGoal(2, new RangedCrossbowAttackGoal<>(this, 1.0, 8.0F));
        goalSelector.addGoal(2, new MeleeAttackGoal(this, 1.1, false) {
            @Override public boolean canUse() { return !holdingRanged() && super.canUse(); }
        });
        goalSelector.addGoal(4, new WatchGoal());
        goalSelector.addGoal(4, new StealGoal());
        goalSelector.addGoal(4, new OpenDoorGoal(this, true));
        goalSelector.addGoal(5, new MarchGoal(false));
        goalSelector.addGoal(6, new MoveTowardsRestrictionGoal(this, 0.8));
        goalSelector.addGoal(8, new RandomStrollGoal(this, 0.6));
        goalSelector.addGoal(9, new LookAtPlayerGoal(this, Player.class, 15.0F));
        goalSelector.addGoal(10, new LookAtPlayerGoal(this, Mob.class, 15.0F));
        targetSelector.addGoal(1, new HurtByTargetGoal(this, Raider.class) {
            @Override public boolean canUse() { return fights() && super.canUse(); }
            @Override public boolean canContinueToUse() { return fights() && super.canContinueToUse(); }
        }.setAlertOthers());
        targetSelector.addGoal(2, new NearestAttackableTargetGoal<>(this, Player.class, true) {
            @Override public boolean canUse() { return fights() && super.canUse(); }
            @Override public boolean canContinueToUse() { return fights() && super.canContinueToUse(); }
        });
        targetSelector.addGoal(3, new NearestAttackableTargetGoal<>(this, IronGolem.class, true) {
            @Override public boolean canUse() { return fights() && super.canUse(); }
            @Override public boolean canContinueToUse() { return fights() && super.canContinueToUse(); }
        });
    }

    boolean holdingRanged() { return isHolding(s -> s.getItem() instanceof CrossbowItem || s.getItem() instanceof BowItem); }
    boolean fights() { return !retreating() && role() != Role.SCOUT; }

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

    @Nullable RaiderEntity leader(Squad s) {
        for (UUID u : s.live)
            if (((ServerLevel) level()).getEntity(u) instanceof RaiderEntity e && e.role() == Role.LEADER && e.isAlive()) return e;
        return null;
    }

    boolean canGrief() {
        return Config.BREAK_BLOCKS.get() && EventHooks.canEntityGrief(level(), this);
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
        if (tickCount % 10 == 0) updateHands();
        if (tickCount % 20 == 0 && role() == Role.LEADER && getTarget() != null) shareTarget();
        if (tickCount % 100 == 0) {
            Squad s = squad();
            Base b = s == null ? null : base(s);
            boolean wantsToMove = b != null && switch (s.state) {
                case GARRISON -> getTarget() != null && distanceToSqr(getTarget()) > 9; // defenders open their own gate
                case OUTBOUND, RETREAT -> true;
                case ATTACK -> s.kind != Kind.SCOUT && (stealing || getTarget() != null && distanceToSqr(getTarget()) > 9
                        || cutOff(RaidManager.dest(s, b)));
            };
            stuck = wantsToMove && lastCheck != null && position().distanceTo(lastCheck) < 1.5;
            if (stuck) stuckAt = tickCount;
            stuckChecks = stuck ? stuckChecks + 1 : 0;
            lastCheck = position();
        }
    }

    /** Far from the base, or close but walled off from it (no path in): either way there is a way to make. */
    private boolean cutOff(BlockPos dest) {
        double d = distanceToSqr(Vec3.atBottomCenterOf(dest));
        if (d > 12 * 12) return true;
        if (d < 3 * 3) return false;
        Path p = getNavigation().createPath(dest, 1);
        return p == null || !p.canReach();
    }

    /** The leader calls targets: squad mates with nothing to do join his fight. */
    private void shareTarget() {
        Squad s = squad();
        if (s == null) return;
        for (UUID u : s.live)
            if (((ServerLevel) level()).getEntity(u) instanceof RaiderEntity m && m != this && m.getTarget() == null
                    && m.fights() && m.distanceToSqr(this) < 24 * 24) m.setTarget(getTarget());
    }

    /** Puts the right item in hand: a tool while digging, the bow at range, TNT or the spyglass when not fighting. */
    void updateHands() {
        LivingEntity t = getTarget();
        double d = t == null ? Double.MAX_VALUE : distanceToSqr(t);
        ItemStack hand = getMainHandItem();
        ItemStack want = digTool != null ? digTool : switch (role()) {
            case SCOUT -> watching ? special : weapon;
            case LEADER, ARTILLERYMAN -> t == null ? weapon : d > 7 * 7 ? bow : d < 5 * 5 ? weapon : hand; // keep the hand in between
            case DEMOMAN -> tnt > 0 && d > 4 * 4 ? special : weapon;
            default -> weapon;
        };
        if (want.isEmpty()) want = weapon;
        if (hand != want && !want.isEmpty()) setItemSlot(EquipmentSlot.MAINHAND, want);
    }

    @Override
    public AbstractIllager.IllagerArmPose getArmPose() {
        if (isHolding(Items.SPYGLASS)) return AbstractIllager.IllagerArmPose.CROSSBOW_HOLD; // spyglass raised to the eye
        if (isAggressive() && isHolding(s -> s.getItem() instanceof BowItem)) return AbstractIllager.IllagerArmPose.BOW_AND_ARROW;
        return super.getArmPose();
    }

    @Override
    public void performRangedAttack(LivingEntity target, float power) {
        ItemStack held = getItemInHand(ProjectileUtil.getWeaponHoldingHand(this, i -> i instanceof BowItem));
        if (!(held.getItem() instanceof BowItem)) { super.performRangedAttack(target, power); return; }
        AbstractArrow arrow = ProjectileUtil.getMobArrow(this, new ItemStack(Items.ARROW), power, held);
        double dx = target.getX() - getX(), dy = target.getY(1 / 3.0) - arrow.getY(), dz = target.getZ() - getZ();
        arrow.shoot(dx, dy + Math.sqrt(dx * dx + dz * dz) * 0.2, dz, 1.6F, 14 - level().getDifficulty().getId() * 4);
        playSound(SoundEvents.SKELETON_SHOOT, 1.0F, 1.0F / (getRandom().nextFloat() * 0.4F + 0.8F));
        level().addFreshEntity(arrow);
    }

    @Override public boolean removeWhenFarAway(double distanceSq) { return false; }

    /** Raiders take cover from their own side's TNT and shells: a tenth of the damage. (Without this, airstrikes wiped out the attackers.) */
    @Override
    public boolean hurt(DamageSource source, float amount) {
        boolean ownTnt = source.getEntity() instanceof RaiderEntity && source.getDirectEntity() instanceof PrimedTnt;
        boolean ownShell = source.getDirectEntity() != null ? source.getDirectEntity().getTags().contains(Artillery.RAIDER_SHELL)
                : source.getMsgId().startsWith("createbigcannons") && Artillery.raiderFireAt(level(), position());
        return super.hurt(source, ownTnt || ownShell ? amount * 0.1f : amount);
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        if (squadId != null) tag.putUUID("ropSquad", squadId);
        tag.putInt("ropRole", role().ordinal());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        stale = tag.hasUUID("ropSquad");
        if (stale) squadId = tag.getUUID("ropSquad");
        setRole(Role.of(tag.getInt("ropRole")));
    }

    @Override
    protected void dropCustomDeathLoot(ServerLevel level, DamageSource source, boolean recentlyHit) {
        super.dropCustomDeathLoot(level, source, recentlyHit);
        for (ItemStack s : getInventory().removeAllItems()) spawnAtLocation(s); // stolen goods go back to the player
        if (role() == Role.LEADER && source.getEntity() instanceof Player && getRandom().nextDouble() < Config.MAP_CHANCE.get()) {
            Squad s = squad();
            Base b = s == null ? null : base(s);
            if (b != null && b.camp != null) spawnAtLocation(RaidManager.campMap(level, b.camp));
        }
    }

    /** Walks the squad toward its goal (the target on a raid, the camp on retreat). Followers keep to their leader. */
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
            Vec3 dest = Vec3.atBottomCenterOf(RaidManager.dest(s, b));
            boolean go = s.state == State.OUTBOUND
                    || s.state == State.ATTACK && s.kind != Kind.SCOUT && distanceToSqr(dest.add(0, 0.5, 0)) > 12 * 12;
            if (!go) return null;
            RaiderEntity leader = role() == Role.LEADER || s.leaderless() ? null : leader(s);
            if (leader != null && leader.distanceToSqr(RaiderEntity.this) < 40 * 40)
                return distanceToSqr(leader) > 5 * 5 ? leader.position() : null; // fall in behind him
            return dest;
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
                getNavigation().moveTo(wp.x, y, wp.z, retreatOnly ? 1.2 : role() == Role.LEADER ? 0.9 : 1.0);
            }
            // Far from the target and stuck for 15 s where no player can see (cave, ditch), or 40 s regardless: hop forward
            // onto the surface. Near the target, diggers and demomen deal with walls instead.
            Squad s = squad();
            Base b = s == null ? null : base(s);
            boolean far = b == null || retreatOnly || distanceToSqr(Vec3.atCenterOf(RaidManager.dest(s, b))) > 48 * 48;
            if (far && (stuckChecks >= 8 || stuckChecks >= 3 && level().players().stream().noneMatch(p -> p.distanceToSqr(RaiderEntity.this) < 48 * 48 && p.hasLineOfSight(RaiderEntity.this)))) {
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

    /** Where this raider is trying to get to: its target, or the squad's destination while it is still far from it. */
    @Nullable Vec3 moveGoal() {
        if (getTarget() != null) return getTarget().position();
        Squad s = squad();
        Base b = s == null ? null : base(s);
        if (b == null || b.camp == null) return null;
        return switch (s.state) {
            case GARRISON -> null;
            case RETREAT -> Vec3.atBottomCenterOf(b.camp);
            case OUTBOUND, ATTACK -> Vec3.atBottomCenterOf(RaidManager.dest(s, b));
        };
    }

    /** Diggers and demomen only work on a raid and near where they're headed, not on the long march. */
    boolean onTheJob() {
        Squad s = squad();
        Base b = s == null ? null : base(s);
        if (b == null || s.kind == Kind.GARRISON || s.kind == Kind.SCOUT || s.state == State.GARRISON) return false;
        return getTarget() != null || distanceToSqr(Vec3.atCenterOf(RaidManager.dest(s, b))) < 48 * 48 || s.state == State.RETREAT;
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
            if (!stuck || scannedAt == stuckAt) return false;
            scannedAt = stuckAt; // one scan per stuck check (every 5 s), not every tick
            Squad s = squad();
            if (s == null) return false;
            long now = level().getGameTime();
            usedMechanisms.values().removeIf(t -> now - t > 600);
            pos = BlockPos.betweenClosedStream(blockPosition().offset(-10, -3, -10), blockPosition().offset(10, 3, 10))
                    .filter(p -> !usedMechanisms.containsKey(p) && isMechanism(level().getBlockState(p), p))
                    .map(BlockPos::immutable)
                    .min(Comparator.comparingDouble(p -> p.distSqr(blockPosition())))
                    .orElse(null);
            if (pos != null) stuck = false;
            return pos != null;
        }

        @Override public void start() { ticks = 0; cranking = 0; used = false; getNavigation().moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, 1.0); }
        @Override public boolean canContinueToUse() { return ticks < 160 && (!used || cranking > 0); }

        @Override
        public void stop() {
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
            } else { cranking = 100; crankBack = !crankBack; }
        }

        private void turnCrank() {
            BlockEntity be = level().getBlockEntity(pos);
            Method m = be == null ? null : crankMethod(be);
            try { if (m != null) m.invoke(be, crankBack); } catch (ReflectiveOperationException e) { cranking = 0; }
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

    // ---------------------------------------------------------------- digging (raiders: doors and dirt; miners: anything their tools can harvest)

    private static boolean passable(Level level, BlockPos p, BlockState st) {
        return st.getCollisionShape(level, p).isEmpty() && !(st.getBlock() instanceof DoorBlock);
    }

    /** Whether this raider may and can break the block: like a player with its tools, never beds, chests or blocks holding back fluid. */
    boolean canBreak(BlockPos p, BlockState st) {
        if (st.isAir() || !st.getFluidState().isEmpty()) return false;
        float hardness = st.getDestroySpeed(level(), p);
        Block b = st.getBlock();
        if (hardness < 0 || b instanceof BedBlock) return false;
        boolean door = b instanceof DoorBlock || b instanceof TrapDoorBlock || b instanceof FenceGateBlock;
        if (st.hasBlockEntity() && !door) return false;
        for (Direction d : Direction.values()) if (!level().getFluidState(p.relative(d)).isEmpty()) return false; // would let water or lava in
        boolean ok = switch (role()) {
            case RAIDER -> door || st.is(BlockTags.MINEABLE_WITH_SHOVEL);
            case MINER -> door || !st.requiresCorrectToolForDrops() || tools.stream().anyMatch(t -> t.isCorrectToolForDrops(st));
            default -> false;
        };
        return ok && EventHooks.onEntityDestroyBlock(this, p, st);
    }

    ItemStack bestTool(BlockState st) {
        ItemStack best = weapon;
        for (ItemStack t : tools) if (t.getDestroySpeed(st) > best.getDestroySpeed(st)) best = t;
        return best;
    }

    /** Mining progress per tick, the player formula: speed / hardness / 30, or / 100 without the right tool. */
    float breakSpeed(ItemStack tool, BlockState st, BlockPos p) {
        float hardness = st.getDestroySpeed(level(), p);
        if (hardness <= 0) return 1;
        boolean harvest = !st.requiresCorrectToolForDrops() || tool.isCorrectToolForDrops(st);
        return tool.getDestroySpeed(st) / hardness / (harvest ? 30f : 100f);
    }

    /** When stuck near the target: dig a way. Level through a wall, a step up toward higher ground, or down to tunnel under what it can't break. */
    class DigGoal extends Goal {
        private final List<BlockPos> plan = new ArrayList<>();
        private BlockPos next;
        private int idx, ticks, settle;
        private float progress;
        private boolean failed;

        DigGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }

        @Override public boolean requiresUpdateEveryTick() { return true; }

        @Override
        public boolean canUse() {
            if (!stuck || scannedAt != stuckAt || (role() != Role.RAIDER && role() != Role.MINER) || !onTheJob() || !canGrief()) return false;
            Vec3 goal = moveGoal();
            if (goal == null || !plan(goal)) return false;
            stuck = false;
            return true;
        }

        private boolean plan(Vec3 goal) {
            BlockPos feet = blockPosition();
            Direction f = Direction.getNearest(goal.x - getX(), 0, goal.z - getZ());
            if (f.getAxis().isVertical()) return false;
            BlockPos front = feet.relative(f);
            int dy = Mth.floor(goal.y) - feet.getY();
            boolean frontSolid = !passable(level(), front, level().getBlockState(front));
            List<BlockPos> flat = List.of(front, front.above());
            if (!frontSolid && passable(level(), front.above(), level().getBlockState(front.above()))) return false; // the way ahead is open
            List<BlockPos> up = frontSolid ? List.of(feet.above(2), front.above(), front.above(2)) : null;
            BlockPos floor = feet.below(2);
            boolean canGoDown = feet.getY() > goal.y - 5 && level().getFluidState(floor).isEmpty()
                    && !passable(level(), floor, level().getBlockState(floor));
            List<BlockPos> down = canGoDown ? List.of(feet.below()) : null;
            List<List<BlockPos>> order = dy >= 1 ? Arrays.asList(up, flat, down) : dy <= -2 ? Arrays.asList(down, flat, up) : Arrays.asList(flat, down, up);
            for (List<BlockPos> o : order) {
                if (o == null) continue;
                boolean work = false, ok = true;
                for (BlockPos p : o) {
                    BlockState st = level().getBlockState(p);
                    if (passable(level(), p, st)) continue;
                    if (!canBreak(p, st)) { ok = false; break; }
                    work = true;
                }
                if (!ok || !work) continue;
                plan.clear();
                plan.addAll(o);
                next = o == flat ? front : o == up ? front.above() : feet.below();
                return true;
            }
            return false;
        }

        @Override public void start() { idx = 0; ticks = 0; settle = 0; progress = 0; failed = false; getNavigation().stop(); }
        @Override public boolean canContinueToUse() { return !failed && ticks < 600 && settle < 30; }

        @Override
        public void stop() {
            if (idx < plan.size()) level().destroyBlockProgress(getId(), plan.get(idx), -1);
            digTool = null;
            updateHands();
            lastCheck = null; // a fresh 5 s before the next stuck check
            if (!failed && idx >= plan.size()) { stuck = true; stuckAt = scannedAt = tickCount; } // plan the next step right away
        }

        @Override
        public void tick() {
            ticks++;
            if (idx >= plan.size()) { // cleared: step into the gap
                if (settle++ == 0) getNavigation().moveTo(next.getX() + 0.5, next.getY(), next.getZ() + 0.5, 1.0);
                if (settle == 10) getMoveControl().setWantedPosition(next.getX() + 0.5, next.getY(), next.getZ() + 0.5, 1.0);
                return;
            }
            BlockPos p = plan.get(idx);
            BlockState st = level().getBlockState(p);
            if (passable(level(), p, st)) { idx++; progress = 0; return; }
            if (!canBreak(p, st)) { failed = true; return; }
            getLookControl().setLookAt(Vec3.atCenterOf(p));
            ItemStack tool = bestTool(st);
            if (digTool != tool) { digTool = tool; updateHands(); }
            if (ticks % 5 == 0) swing(InteractionHand.MAIN_HAND);
            if (ticks % 4 == 0) {
                var sound = st.getSoundType(level(), p, RaiderEntity.this);
                level().playSound(null, p, sound.getHitSound(), SoundSource.HOSTILE, sound.getVolume() * 0.25f, sound.getPitch() * 0.5f);
            }
            progress += breakSpeed(tool, st, p);
            if (progress >= 1) {
                level().destroyBlockProgress(getId(), p, -1);
                level().destroyBlock(p, true, RaiderEntity.this);
                idx++;
                progress = 0;
            } else level().destroyBlockProgress(getId(), p, (int) (progress * 10));
        }
    }

    /**
     * Demoman: lights TNT against what blocks the way, then runs. With a fresh scout report he knows the layout and
     * also blows any wall between him and the base without waiting to get stuck.
     */
    class BreachGoal extends Goal {
        private int ticks;
        private Vec3 bomb;

        BreachGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }

        @Override
        public boolean canUse() {
            if (role() != Role.DEMOMAN || tnt <= 0 || !onTheJob() || !canGrief()) return false;
            boolean stuckHere = stuck && scannedAt == stuckAt;
            if (!stuckHere && !(tickCount % 40 == 0 && knowsLayout() && wallAhead())) return false;
            if (stuckHere) stuck = false;
            return true;
        }

        private boolean knowsLayout() {
            Squad s = squad();
            Base b = s == null ? null : base(s);
            return b != null && s.kind == Kind.NIGHT && b.freshIntel;
        }

        private boolean wallAhead() {
            Vec3 goal = moveGoal();
            if (goal == null || distanceToSqr(goal) > 24 * 24) return false;
            Vec3 eye = getEyePosition(), dir = goal.add(0, 1, 0).subtract(eye).normalize();
            HitResult hit = level().clip(new ClipContext(eye, eye.add(dir.scale(2.5)), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, RaiderEntity.this));
            return hit.getType() == HitResult.Type.BLOCK;
        }

        @Override
        public void start() {
            ticks = 0;
            Vec3 goal = moveGoal();
            Vec3 dir = goal == null ? getLookAngle() : goal.subtract(position()).multiply(1, 0, 1).normalize();
            bomb = position().add(dir.scale(0.5));
            PrimedTnt t = new PrimedTnt(level(), bomb.x, bomb.y, bomb.z, RaiderEntity.this);
            t.setFuse(80);
            level().addFreshEntity(t);
            level().playSound(null, t.getX(), t.getY(), t.getZ(), SoundEvents.TNT_PRIMED, SoundSource.HOSTILE, 1.0F, 1.0F);
            level().gameEvent(RaiderEntity.this, GameEvent.PRIME_FUSE, BlockPos.containing(bomb));
            swing(InteractionHand.MAIN_HAND);
            tnt--;
            runFrom(RaiderEntity.this, bomb);
            Squad s = squad(); // "fire in the hole": squad mates near the bomb clear out too
            if (s != null) for (UUID u : s.live)
                if (((ServerLevel) level()).getEntity(u) instanceof RaiderEntity m && m != RaiderEntity.this && m.distanceToSqr(bomb) < 9 * 9) {
                    m.bombAt = bomb;
                    m.fleeUntil = m.tickCount + 80;
                }
        }

        /** Straight away from the bomb, 12 blocks out on the surface; random escape if that has no path. */
        private static void runFrom(RaiderEntity e, Vec3 bomb) {
            Vec3 dir = e.position().subtract(bomb).multiply(1, 0, 1);
            dir = dir.lengthSqr() < 0.01 ? e.getLookAngle().reverse().multiply(1, 0, 1).normalize() : dir.normalize();
            Vec3 to = e.position().add(dir.scale(12));
            int y = e.level().getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, Mth.floor(to.x), Mth.floor(to.z));
            if (!e.getNavigation().moveTo(to.x, y, to.z, 1.5)) {
                Vec3 away = DefaultRandomPos.getPosAway(e, 12, 4, bomb);
                if (away != null) e.getNavigation().moveTo(away.x, away.y, away.z, 1.5);
            }
        }

        @Override public boolean canContinueToUse() { return ticks < 90; }

        @Override
        public void tick() {
            if (++ticks % 20 == 0 && getNavigation().isDone() && distanceToSqr(bomb) < 10 * 10) runFrom(RaiderEntity.this, bomb);
        }
    }

    /** A squad mate's TNT is about to go off nearby: drop everything and get clear. */
    class FleeBombGoal extends Goal {
        FleeBombGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.TARGET)); }

        @Override public boolean canUse() { return bombAt != null && tickCount < fleeUntil; }
        @Override public boolean canContinueToUse() { return canUse(); }
        @Override public void start() { setTarget(null); BreachGoal.runFrom(RaiderEntity.this, bombAt); }
        @Override public void stop() { bombAt = null; }

        @Override
        public void tick() {
            if (tickCount % 20 == 0 && getNavigation().isDone() && distanceToSqr(bombAt) < 10 * 10) BreachGoal.runFrom(RaiderEntity.this, bombAt);
        }
    }

    /** Scout on watch: stands at his watch point looking at the base through a spyglass. */
    class WatchGoal extends Goal {
        WatchGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }

        @Nullable private Squad watch() {
            Squad s = squad();
            return role() == Role.SCOUT && s != null && s.kind == Kind.SCOUT && s.state == State.ATTACK ? s : null;
        }

        @Override public boolean canUse() { return watch() != null; }
        @Override public void stop() { watching = false; }

        @Override
        public void tick() {
            Squad s = watch();
            Base b = s == null ? null : base(s);
            if (b == null || s.target == null) return;
            Vec3 post = Vec3.atBottomCenterOf(level().getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, s.target));
            boolean there = distanceToSqr(post) < 3 * 3;
            if (!there && (tickCount % 40 == 0 || getNavigation().isDone())) getNavigation().moveTo(post.x, post.y, post.z, 1.0);
            if (there) getNavigation().stop();
            watching = there;
            getLookControl().setLookAt(Vec3.atCenterOf(b.home).add(0, 2, 0));
        }
    }

    /** During an attack with no one to fight: go to a storage block near the player base and take two stacks. */
    class StealGoal extends Goal {
        private BlockPos pos;
        private Squad squad;
        private int ticks;

        StealGoal() { setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK)); }

        @Override
        public boolean canUse() {
            if (tickCount % 20 != 0 || getTarget() != null || full() || role() == Role.SCOUT || role() == Role.DEMOMAN && tnt > 0) return false;
            squad = squad();
            if (squad == null || squad.state != State.ATTACK || squad.kind == Kind.SCOUT) return false;
            Base b = base(squad);
            if (b == null) return false;
            pos = findStorage(RaidManager.dest(squad, b));
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
