package com.raidsonplayer;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.logging.LogUtils;
import com.raidsonplayer.RaidData.Base;
import com.raidsonplayer.RaidData.Squad;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.item.PrimedTnt;
import net.minecraft.world.entity.monster.Pillager;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.entity.EntityAttributeCreationEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.CanContinueSleepingEvent;
import net.neoforged.neoforge.event.entity.player.CanPlayerSleepEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import com.raidsonplayer.client.ClientTerritory;
import net.neoforged.neoforge.event.level.ExplosionEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.registries.DeferredHolder;
import net.neoforged.neoforge.registries.DeferredRegister;
import org.slf4j.Logger;

@Mod(RaidsOnPlayer.MODID)
public class RaidsOnPlayer {
    public static final String MODID = "raidsonplayer";
    public static final Logger LOG = LogUtils.getLogger();

    static final DeferredRegister<EntityType<?>> ENTITIES = DeferredRegister.create(Registries.ENTITY_TYPE, MODID);
    public static final DeferredHolder<EntityType<?>, EntityType<RaiderEntity>> RAIDER = ENTITIES.register("raider",
            () -> EntityType.Builder.<RaiderEntity>of(RaiderEntity::new, MobCategory.MONSTER)
                    .sized(0.6F, 1.95F).passengerAttachments(2.0F).ridingOffset(-0.6F).clientTrackingRange(8).build("raider"));

    public RaidsOnPlayer(IEventBus modBus, ModContainer container) {
        ENTITIES.register(modBus);
        // Player-like stats: 20 health, fists do 1, so the weapon decides (stone sword = 5, like a player's).
        modBus.addListener((EntityAttributeCreationEvent e) -> e.put(RAIDER.get(), Pillager.createAttributes()
                .add(Attributes.MAX_HEALTH, 20.0).add(Attributes.ATTACK_DAMAGE, 1.0).build()));
        container.registerConfig(ModConfig.Type.COMMON, Config.SPEC);
        // Raider land for map mods (Xaero's World Map / Minimap): sent on login and whenever it changes
        modBus.addListener((RegisterPayloadHandlersEvent e) -> e.registrar("1")
                .playToClient(TerritoryPayload.TYPE, TerritoryPayload.CODEC, (p, ctx) -> ClientTerritory.set(p)));
        NeoForge.EVENT_BUS.addListener((PlayerEvent.PlayerLoggedInEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p) PacketDistributor.sendToPlayer(p, RaidManager.territory(RaidData.get(p.server)));
        });

        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post e) -> {
            Cranks.tick();
            RaidManager.tick(e.getServer());
        });
        NeoForge.EVENT_BUS.addListener((AdvancementEvent.AdvancementEarnEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p) RaidManager.updateStage(p.server, RaidData.get(p.server));
        });
        NeoForge.EVENT_BUS.addListener((LivingDeathEvent e) -> {
            if (e.getEntity() instanceof RaiderEntity r) {
                var src = e.getSource();
                LOG.debug("Raider {} died: {} direct={} tags={} owner={}", r.role(), src.getMsgId(),
                        src.getDirectEntity() == null ? null : src.getDirectEntity().getType().toShortString(),
                        src.getDirectEntity() == null ? null : src.getDirectEntity().getTags(),
                        src.getEntity() == null ? null : src.getEntity().getType().toShortString());
                RaidManager.onDeath(r, src.getEntity() instanceof Player);
            }
        });
        // Raider TNT (thrown or breaching) still hurts, but leaves blocks alone when raider griefing is off.
        NeoForge.EVENT_BUS.addListener((ExplosionEvent.Detonate e) -> {
            if (e.getExplosion().getDirectSourceEntity() instanceof PrimedTnt t && t.getOwner() instanceof RaiderEntity r && !r.canGrief())
                e.getAffectedBlocks().clear();
        });
        NeoForge.EVENT_BUS.addListener(RaidsOnPlayer::commands);
        // No sleeping through a night raid: the horn wakes you, and the bed refuses you until it is over.
        NeoForge.EVENT_BUS.addListener((CanPlayerSleepEvent e) -> {
            if (e.getProblem() == null && RaidManager.nightRaidNear(e.getEntity())) e.setProblem(Player.BedSleepingProblem.NOT_SAFE);
        });
        NeoForge.EVENT_BUS.addListener((CanContinueSleepingEvent e) -> {
            if (e.getEntity() instanceof ServerPlayer p && RaidManager.nightRaidNear(p)) e.setContinueSleeping(false);
        });
    }

    /** /rop info | stage | raid | rebuild | fire | gate | airstrike | night [launch] | full | expand | scout | patrol | hunt  (ops only; for admins and testing) */
    static void commands(RegisterCommandsEvent e) {
        e.getDispatcher().register(Commands.literal("rop").requires(s -> s.hasPermission(2))
                .then(Commands.literal("info").executes(c -> info(c.getSource())))
                .then(Commands.literal("stage").then(Commands.argument("n", IntegerArgumentType.integer(1, 4)).executes(c -> {
                    RaidManager.setStage(c.getSource().getServer(), data(c.getSource()), IntegerArgumentType.getInteger(c, "n"));
                    return 1;
                })))
                .then(Commands.literal("raid").executes(c -> {
                    Base b = RaidManager.baseOf(data(c.getSource()), c.getSource().getPlayerOrException());
                    if (b == null) { c.getSource().sendFailure(Component.literal("You have no base (set a bed in the overworld).")); return 0; }
                    b.nextRaid = 0;
                    c.getSource().sendSuccess(() -> Component.literal("Raid due now (starts if the camp has 2+ raiders and you are within 128 blocks of home, or raidsWhenAway is on)."), false);
                    return 1;
                }))
                .then(Commands.literal("rebuild").executes(c -> {
                    Base b = RaidManager.baseOf(data(c.getSource()), c.getSource().getPlayerOrException());
                    if (b == null || b.camp == null) { c.getSource().sendFailure(Component.literal("No camp yet.")); return 0; }
                    if (!RaidManager.campLoaded(c.getSource().getServer().overworld(), b)) { c.getSource().sendFailure(Component.literal("Loading the camp area, run it again in a few seconds.")); return 0; }
                    RaidManager.rebuild(c.getSource().getServer().overworld(), data(c.getSource()), b);
                    c.getSource().sendSuccess(() -> Component.literal("Camp rebuilt."), false);
                    return 1;
                }))
                .then(Commands.literal("fire").executes(c -> { // every cannon of your raider base fires at your home now, ignoring range
                    Base b = RaidManager.baseOf(data(c.getSource()), c.getSource().getPlayerOrException());
                    ServerLevel level = c.getSource().getServer().overworld();
                    int fired = 0;
                    if (b != null) for (BlockPos m : b.cannons) if (Artillery.fireCannon(level, m, RaidManager.groundNear(level, b.home, 8))) fired++;
                    int n = fired;
                    c.getSource().sendSuccess(() -> Component.literal(n + " cannon(s) fired."), false);
                    return n;
                }))
                .then(Commands.literal("gate").then(Commands.literal("open").executes(c -> gate(c.getSource(), true))).then(Commands.literal("close").executes(c -> gate(c.getSource(), false))))
                .then(Commands.literal("airstrike").executes(c -> { // airstrike on yourself from your raider base's side
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    Base b = RaidManager.baseOf(data(c.getSource()), p);
                    if (b == null || b.camp == null) { c.getSource().sendFailure(Component.literal("No camp yet.")); return 0; }
                    Artillery.airstrike(p.serverLevel(), p.position(), net.minecraft.world.phys.Vec3.atCenterOf(b.camp).subtract(p.position()), 3, 7, p.getRandom());
                    return 1;
                }))
                // night: plan a night raid (a scout comes by day, the raid at dusk); "night launch": launch it right now
                .then(Commands.literal("night").executes(c -> withBase(c.getSource(), (level, d, b) -> {
                    RaidManager.planNight(b);
                    return "Night raid planned: a scout comes while you are home by day, the raid at dusk.";
                })).then(Commands.literal("launch").executes(c -> withBase(c.getSource(), (level, d, b) -> {
                    RaidManager.planNight(b);
                    b.freshIntel = true; // as if the scout got away
                    for (java.util.UUID m : b.members) d.lastScouted.put(m, b.id);
                    RaidManager.launchNight(level, d, b, level.getGameTime());
                    return "Night raid launched with a scout report (it falls back at dawn, so use it at night).";
                }))))
                .then(Commands.literal("full").executes(c -> withBase(c.getSource(), (level, d, b) -> { // full-sized raid now, skipping the scout and the roll
                    if (b.waves > 0) return "A full-sized raid is already on (wave " + b.wave + " of " + b.waves + ").";
                    RaidManager.startFullRaid(level, d, b, level.getGameTime());
                    return "Full-sized raid started.";
                })))
                .then(Commands.literal("expand").executes(c -> withBase(c.getSource(), (level, d, b) -> { // another camp now, skipping the timer
                    if (RaidManager.camps(d, b) >= Config.MAX_CAMPS.get()) return "Already at maxCampsPerBase (" + Config.MAX_CAMPS.get() + ").";
                    RaidManager.expand(level, d, b, level.getGameTime());
                    return "The raiders will build another camp around your base.";
                })))
                .then(Commands.literal("scout").executes(c -> withBase(c.getSource(), (level, d, b) -> {
                    RaidManager.planNight(b);
                    RaidManager.sendScout(level, d, b, level.getGameTime());
                    return "Scout sent to watch your base.";
                })))
                .then(Commands.literal("patrol").executes(c -> withBase(c.getSource(), (level, d, b) -> {
                    if (b.patrol != null) return "A patrol is already out.";
                    RaidManager.startPatrol(level, d, b, level.getGameTime());
                    return "Patrol sent out.";
                })))
                .then(Commands.literal("hunt").executes(c -> { // a hunting party from the camp whose land you stand on, skipping the roll
                    ServerPlayer p = c.getSource().getPlayerOrException();
                    Base b = RaidManager.landOf(data(c.getSource()), p.position());
                    if (b == null) { c.getSource().sendFailure(Component.literal("You're not on raider land.")); return 0; }
                    RaidManager.startHunt(p.serverLevel(), data(c.getSource()), b, p, p.serverLevel().getGameTime());
                    return 1;
                })));
    }

    static RaidData data(CommandSourceStack s) { return RaidData.get(s.getServer()); }

    interface BaseAction { String run(ServerLevel level, RaidData d, Base b); }

    static int withBase(CommandSourceStack src, BaseAction a) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Base b = RaidManager.baseOf(data(src), src.getPlayerOrException());
        if (b == null || b.camp == null) { src.sendFailure(Component.literal("You have no raider camp yet (set a bed in the overworld).")); return 0; }
        String msg = a.run(src.getServer().overworld(), data(src), b);
        src.sendSuccess(() -> Component.literal(msg), false);
        return 1;
    }

    static int gate(CommandSourceStack src, boolean open) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        Base b = RaidManager.baseOf(data(src), src.getPlayerOrException());
        if (b == null || b.gate == null) { src.sendFailure(Component.literal("Your raider base has no gate.")); return 0; }
        RaidManager.setGate(src.getServer().overworld(), b, open);
        return 1;
    }

    static int info(CommandSourceStack src) {
        RaidData d = data(src);
        ServerLevel level = src.getServer().overworld();
        StringBuilder sb = new StringBuilder("Stage " + d.stage + ", raider day " + d.clock / 24000 + ", " + d.bases.size() + " base(s)");
        for (Base b : d.bases.values()) {
            long pts = RaidManager.points(d, b);
            sb.append(b.parent != null ? (b.root ? "\n  + root camp of home " : "\n  + expansion camp of home ") : "\n- home ").append(b.home.toShortString()).append(" members ").append(b.members.size())
                    .append(" camp ").append(b.camp == null ? "none" : b.camp.toShortString() + " (stage " + b.campStage + ")")
                    .append(b.emptySince != 0 ? ", EMPTY (retake in " + Math.max(0, b.emptySince + 24000 - pts) / 1200 + " pts, held " + b.hold + " s)" : "")
                    .append(b.regroupAt == Long.MAX_VALUE ? ", camp taken (other roots stand)" : b.regroupAt > 0 ? ", survivors regroup in " + Math.max(0, b.regroupAt - pts) / 1200 + " pts" : "")
                    .append(" next raid in ").append(Math.max(0, b.nextRaid - RaidManager.points(d, b)) / 1200).append(" pts")
                    .append(b.parent == null ? ", kills +" + b.bonus / 1200 + " pts, next camp in " + Math.max(0, b.nextExpand - RaidManager.points(d, b)) / 1200
                            + " pts, night raid " + (RaidManager.points(d, b) < b.nextNight && b.nightMisses == 0
                            ? "in " + (b.nextNight - RaidManager.points(d, b)) / 1200 + " pts" : (int) (RaidManager.nightChance(b) * 100) + "% each morning") : "")
                    .append(b.nightPlanned ? ", night raid planned" : "").append(b.freshIntel ? ", scouted" : "")
                    .append(b.waves > 0 ? ", full raid wave " + b.wave + "/" + b.waves : "");
            for (Squad s : d.squads.values())
                if (s.base.equals(b.id))
                    sb.append("\n    ").append(s.kind).append(' ').append(s.state).append(' ').append(s.roles).append(" at ")
                            .append((int) s.pos.x).append(' ').append((int) s.pos.y).append(' ').append((int) s.pos.z)
                            .append(s.live.isEmpty() ? " (virtual)" : " (" + s.live.size() + " spawned)")
                            .append(s.loot.isEmpty() ? "" : " carrying " + s.loot.size() + " stacks");
        }
        src.sendSuccess(() -> Component.literal(sb.toString()), false);
        return 1;
    }
}
