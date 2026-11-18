# Raids on Player: MODLOG

## Intake (2026-11-17)
- Game: Minecraft Java 1.21.1, NeoForge 21.1.266, FreesmLauncher instance `1.21.1(2)`.
- Mods in instance: create 6.0.10, createbigcannons 5.11.7, create-aeronautics 1.3.2, createdieselgenerators 1.3.15, DnDesires 2.3a.
- Toolchain: JDK 21.0.13 at `C:/Program Files/Eclipse Adoptium/jdk-21.0.13+9`.
- Spec: `C:\Users\unfuny\Desktop\prompt.txt`. Concepts: `C:\Users\unfuny\Pictures\concept.png` (stage 1 tents), `concept2.png` (outpost).
- Route: loader API (NeoForge), Create + CBC as compile-time deps. PvE, singleplayer + dedicated server.

## Concepts, as read
- **Stage 1 (concept.png):** 2 A-frame tents, each with a bed inside; chests by the tent openings (2 at one tent, 1 at the other).
- **Outpost "форпост" (concept2.png):** a walled square with 4 corner towers (3), a front gate (4) with a watchman (1) standing at it,
  a loot room (2) against the back wall, and a few raiders loitering inside. Cannons (5) sit on the tower tops, stage 3+ only.
  Inference: the outpost is the layout for stages 2-4 (wood, then cobble, then industrial); the "stage 3+" label on cannons implies it already exists at stage 2.

## Progression triggers (advancement IDs, verified in the jars)
| Stage | Trigger |
|---|---|
| 1 | world load |
| 2 | `minecraft:story/smelt_iron` |
| 3 | `minecraft:story/mine_diamond` AND `create:andesite_alloy` |
| 4 | `create:brass` (inventory_changed on `create:brass_ingot`) |
Stage moves up when >50% of players have the advancement(s). It never moves down.

## Hard problems noted
- **Long marches through unloaded chunks:** mobs don't tick there. The plan is a "virtual" raid party that moves along a path as data,
  and real pillagers appear when the party enters chunks loaded by a player (and turn back into data when they leave).
- **Create hand crank:** needs a held right-click from a player. The raider goal will call `HandCrankBlockEntity` turning directly.
- **CBC cannons:** relevant classes are `CannonMountBlockEntity`, `HEShellProjectile`, `ImpactFuzeItem` and `CBCEntityTypes`.
  Plan: spawn the fuzed shell entity at the muzzle with a ballistic velocity, rather than handling ammo logistics.
- **Base rebuild:** only when no player has seen the base for 180 s (seen = within render distance + line of sight, or simply chunk-in-view).

## Decisions (user, 2026-11-17)
- **Base** = a player's bed/respawn point. Beds within 64 blocks of each other form one base, with one raider camp.
- **Structures** are generated in code from the concepts. Wood type comes from the biome.
- **At your base**, raiders open doors and use buttons, levers and Create hand cranks. They steal from chests and carry the loot
  home to their camp chests. They don't break blocks.
- **Milestone 1** = stage 1 end to end: progression tracking (all 4 stages), tent camp, timed raid, march, fight, retreat, walk home.

## Design (mine)
- **Own entity type** `raidsonplayer:raider` extends Pillager and uses the vanilla pillager renderer. It is the base for future raider classes.
- **Squads are data; entities are a view.** A squad (garrison, outbound, attack or retreat) lives in SavedData with a position and a
  member count. Members exist as entities only while the squad is near a player in entity-ticking chunks. Raider entities are never
  saved to chunks (`shouldBeSaved=false`); they are recreated from the squad data. Duplicates are impossible, and marches go through
  unloaded land.
- **Raids start** when the timer is due AND a base member is online within 128 blocks of home, so nobody gets raided while away.
- **Progression** counts online players. The stage only goes up.
- **"Seen"** = a player within (view distance × 16) blocks of the camp.
- **Chest contents survive a rebuild**: they are collected and put into the new chests.

## Build and test setup
- Project: NeoForge MDK (ModDevGradle) in this folder. `gradlew build` → `build/libs/raidsonplayer-1.0.0.jar`.
  The first run took 17 min (Gradle 9.2.1 download plus decompile). Later builds take about 5-15 s.
- Dev server: `gradlew runServer`, `run/server.properties` (bound to 127.0.0.1, RCON 25575, pw `roptest`, seed 20261117).
  RCON client: scratchpad `rcon.py`.
- Dev client: `gradlew runJoin` (folder `run-client/`) quick-joins `127.0.0.1:25565`. `localhost` resolved to an IPv6 address and was refused ("getsockopt").
- `run/mods` and `run-client/mods` hold create 6.0.10, CBC 5.11.7, ritchiesprojectilelib 2.1.2 (copied from instance `1.21.1`).
- **The user's instance `1.21.1(2)` is currently missing ritchiesprojectilelib** (CBC requires it), plus veil, sable and other libraries that were present at the 01:17 launch.

## Verified in game (dev server + client, 2026-11-17)
1. A spawnpoint creates a base. The camp was built 281 blocks away (stage 1 tents, beds, chests, campfire, 4 raiders with stone swords). Screenshot checked.
2. `/rop raid`: 3 raiders march as data, appear about 90 blocks from home, reach home, and switch to ATTACK.
3. Stealing: one raider emptied a chest (diamonds, iron, bread, logs) into its pockets.
4. Server restart mid-retreat: squad data survived. Stale raiders handed their pockets back to the squad (4 stacks), and the squad walked home.
   The loot ended up in the camp chest.
5. Lever: a loot chest inside a locked hut. The raiders pulled the outside lever, the iron door opened, and they stole everything.

## Gotchas
1. Raiders got stuck in a cave under the base. The path to a surface waypoint failed, and the hop rescue required no player within 24 blocks.
   Fix: hop when no player has line of sight, or after 40 s regardless.
2. Raiders saved to disk carry stolen items. Discarding them as stale lost the loot. Fix: hand the inventory back to the squad (or drop it) before discarding.
3. Stuck detection only lived in the march goal, so a raider stuck at a wall while stealing or chasing never used levers.
   Moved into `RaiderEntity.tick`. The mechanism goal runs at priority 1 so it beats melee.
4. Camp placement generates chunks synchronously: a ~2 s server stall the first time.

6. Create hand crank: a raider stuck at the hut wall found the crank 6.5 blocks away and turned it (BE NBT `Speed: 32.0f InUse: 9`).
7. Combat: raiders killed Dev ("Dev was slain by Raider"). With 2 of 3 killed, the squad went to RETREAT, sent its chat message, and the survivor ran for camp.
8. Progression: `advancement grant ... story/smelt_iron` → "stage 2" broadcast. The camp rebuilt when the raid ended and it had gone unseen for 180 s.
   The garrison grew to 6. Stolen items survived the rebuild, and stage 2 loot (raw iron/zinc, andesite alloy, iron tools) was added.

More gotchas:
5. The mechanism search box was ±6 and the crank was 6.5 blocks from where the raiders got stuck. Now ±10, scanned once per 5 s stuck check.
6. Pillager stats with a stone sword hit for 9 (base 5 + 4). Raiders now have 20 HP and base damage 1, so the weapon decides, as for a player.
7. Survivors returning could push the garrison past its cap, giving unbounded growth. Returns are now capped at the stage cap.
8. A spawnpoint covered by blocks is cleared by vanilla on respawn, so the player leaves the base (members 0) until a new bed is set. This is by design.
9. Dev client death: no input tool, so `gamerule doImmediateRespawn true` + client restart respawns automatically.

## Milestone 2 (branch `milestone-2`, 2026-11-17)
Verified in game (dev server + client):
1. **Outposts:** stage 2 spruce outpost in a snowy taiga (biome wood), with walls, 4 towers with ladders, a gate facing the player base,
   a loot room, barracks, a farm and the campfire. Stage 3: cobblestone, crenels, lava moat with a bridge at the gate. Stage 4:
   industrial iron, andesite casing towers, iron-bar tops, a smokestack.
2. **CBC cannons:** they assemble on tower mounts and aim themselves (yaw 74.6° vs a true bearing of 73.7°). An HE shell landed 8.7
   blocks from home inside the ±8 random spread. Crater seen.
3. **Airstrike:** `/rop airstrike` and automatically 15 s after a stage 4 raid reaches the base ("from the east" was correct).
4. **Create gate (stage 4):** a sticky mechanical piston slides a glued 3x2 iron-bar gate; a hand crank drives it. The gate closes
   after building, opens when a raid leaves (9 raiders marched out), and closes when the raid returns. The watchman stands outside it.

CBC/Create facts learned (no public API; reflection only):
- Floor cannon mount = `vertical_direction=down`, and the cannon sits **2 blocks above** the mount (`assemble()` looks at relative(dir, -2)).
- `BigCannonBlock.onPlace(Level, BlockPos)` (static) links cannon blocks placed with setBlock (sets `Connections` in their BE NBT).
- `CannonMountBlockEntity`: protected `assemble()`, public `setYaw/setPitch(float)`, `getContraption()`. Yaw uses MC convention (south=0, north=180).
  Back face = assembly redstone, front face = fire redstone.
- HE shell physics: gravity -0.05/tick; drag = 0.01 × |v| along -v; pos += v then v += forces. The `Ballistics` solver matches it.
- `FuzedBigCannonProjectile.setFuze(ItemStack)` + `createbigcannons:impact_fuze` makes an impact-fuzed shell.
- Create piston shaft axis = f(FACING, axis_along_first): X-facing false→Z, Z-facing true→X. **Block rotation does not fix this up.**
- `HandCrankBlockEntity.turn(back=true)` extends the gate piston = closes the gate. A crank only spins while turn() is called every tick (`Cranks`).
- `SuperGlueEntity(Level, AABB)` + `SuperGlueEntity.span(a, b)` glues blocks.

More gotchas:
10. The first cannon attempt was 1 block above the mount and silently assembled nothing (no exception). Read `assemble()` bytecode.
11. Rebuilds happen while nobody is near, so the camp's entities may not be loaded, and old contraptions would float over the new build.
    Fix: a region ticket plus `areEntitiesLoaded`, then remove contraption and glue entities, then build.
12. The gate pocket became a hole in the wall when the gate was shut. Fix: wall layers on both sides of the pocket.
13. A raid took the watchman along. Fix: entities restricted to radius ≤ 2 stay.
14. HE craters next to a lava moat break it and spill lava. Accepted: it's combat damage.

Not verified: crank direction for gate orientations other than west (rotation should preserve it); the stage 2 press/water wheel
orientation up close (decorative, doesn't spin).

## Biome ground and chest facing (2026-11-17, after user feedback)
- **Ground** is read from untouched terrain on a ring 4 blocks outside the build (most common full surface block and the block under it,
  ignoring fluids, logs and block entities), so a rebuild reads natural terrain too. Under-blocks that fall (sand) become sandstone,
  because the fill can sit on ponds. Stage 2 yard: coarse dirt and path on grassland, the biome's own ground elsewhere.
  Cold biomes get snow on open ground (vanilla `Biome.shouldSnow`, MOTION_BLOCKING top at floor level only).
- **Chests:** tent chests sit side by side at the front-left corner (concept.png), facing out. Loot room side rows face the aisle.
- Verified in game: desert stage 1 (sand, chests facing out) and stage 2 (sand ring and yard, acacia; chest facings checked with
  `execute if block`), snowy stage 4 (snow-covered ring and yard), savanna and jungle (grass).
15. Camp placement skipped sites within `min` of *any* home, including its own. Rounding puts a site at exactly `min` just inside,
    so with min == max no camp was ever placed. Fixed: only other bases' homes count.

## Milestone 3 intake: classes, night raids, scouts, patrols (spec `C:\Users\unfuny\Desktop\prompt2.txt`, 2026-11-18)
User decisions:
- Day raids stay (small steal-and-run raids by the garrison). Night raids are the new big multi-squad assaults.
- Night raid: every 7 days a 20% roll plans one. A scout comes that day. If he escapes, the raid hits the current base and gets
  airstrikes (stage 3+). If he's killed, the raid hits the last base a scout reported, without airstrikes. If there's none, it's called off.
- Extras: leader wears the ominous banner, scout watches through a spyglass, a leader killed by a player drops a map to the camp.
- The spec overrides the old "no block breaking" decision: raiders break doors and dig dirt, miners tunnel, demomen use TNT.
  This is gated by config `raidersBreakBlocks` and the `mobGriefing` gamerule.

Design:
- `Role` {RAIDER, MINER, LEADER, ARTILLERYMAN, DEMOMAN, SCOUT}. One entity type with a synced role (name, arm pose). Squads hold
  a role list instead of a count. Death removes that role, and materializing spawns the roles not yet live.
- Squad `kind` {GARRISON, RAID (day), NIGHT, PATROL, SCOUT} plus a `target` (home, watch point or waypoint).
- Gear (material by stage: stone, iron, iron/rare diamond, diamond): raider = sword + shovel; miner = pickaxe + axe;
  leader = sword one tier up + bow + banner; artilleryman = sword + bow; demoman = TNT, then a sword; scout = sword + spyglass.
- Digging is like a player mining (speed / hardness / 30, or 100 without the right tool). Unbreakable: hardness < 0, block
  entities other than doors, beds, blocks next to fluids, blocks the tools can't harvest. A miner blocked at his level digs
  stairs down and tunnels under, then comes back up.
- Leader: followers keep to him and take his target. After his death the squad is leaderless: it retreats once 25% are dead
  or below 50% health, and it no longer follows or focuses fire.
- Airstrikes need stage 3+ and a living artilleryman (camp or raid). Night raids also need a fresh scout report.
  Stage 4 strikes are more frequent and tighter. Each strike lands at a random offset.
- Night raid squads per stage: 2x4, 3-4x5, 4x6, 4x7 with the required classes. Demomen replace raiders by a per-stage chance.
  The horn wakes sleepers, and sleeping is blocked while a night raid is on.
- Patrols: on a daily roll, a leader and 2-3 raiders wander near the player base and go home at dusk.
- Plan: 3a classes + digging + leader in day raids; 3b night raids + scout + demoman + artillery rules + map;
  3c patrols + config + polish. (In the end it was all built in one pass, then tested and fixed in game.)

## Milestone 3 results (branch `milestone-3`, 2026-11-18)
Test setup: a walled test home on flat desert (cobblestone, dirt and stone brick walls, an iron door with no button, 2 chests),
camp 35 blocks away (dev config 32/40, restored to 100/300 afterwards). Commands: `/rop night [launch]`, `/rop scout`, `/rop patrol`.
Verified in game:
1. Garrison classes and gear: leader with the ominous banner, iron swords, a demoman holding TNT. Names in /damage output ("Raider Leader").
2. Day raid (leader, miner, demoman, 2 raiders): the demoman's TNT breached the north wall, raiders dug the sand, and the squad got inside.
3. Night raid, stage 3. It launched by itself at dusk after the scout's report: "24 raiders in 4 squads", each with a leader,
   2 miners, an artilleryman, and raiders/demomen. Airstrikes came about 25 s after arrival and then about a minute apart.
   Leader deaths gave "His squad falters" and a leaderless retreat. At dawn: "Dawn breaks...", and everyone retreated.
   Survivors rejoined the garrison up to full strength.
4. Scout at ~210 blocks: he marched as data, appeared, went to his post, held the spyglass up to his eye, saw the player,
   watched for about 60 s, left, and "A raider scout got away" followed. Killing a scout gives "You killed the raider scout before
   he could report back" and clears the fresh report.
5. Patrol: a leader and 2 raiders wandered waypoints and headed home at dusk.
6. A leader killed by a player dropped a "Raider Camp Map" (explorer style, red X on the camp).

More gotchas:
16. Raiders next to home but behind a wall never counted as stuck: only "more than 12 blocks away" or "stealing" counted, and with
    2 chests only 2 raiders stole. Fix: in ATTACK, a raider is also stuck when there's no path to home (`cutOff`).
17. The raiders' own airstrikes wiped out the attackers. CBC blast damage carries no entity (msgId
    `createbigcannons.cannon_projectile`, direct null), so the raiders' shells are recognised by place and time (`Artillery.ZONES`).
    Raiders take x0.1 from their own shells and TNT. Player-fired shells still hit in full.
18. A demoman died to his own TNT. Fix: 4 s fuse, he runs straight away, and squad mates within 9 blocks flee (`FleeBombGoal`).
19. Patrol waypoints and the scout post landed in water. Fix: a dry-land check (fluid at the surface is skipped).
20. A garrison over its cap (old saves) kept gaining missing classes. Fix: `reinforce()` swaps a surplus raider for the missing class.
21. In a JSON patch through a bash heredoc, `\\n` arrived as a real newline: write the patch scripts with the Write tool.
Not verified: horn waking a sleeper and the bed refusal during a night raid (the dev client has no input to lie in a bed);
miners tunnelling under obsidian (stairs-down logic only seen with sand); the demoman's proactive breach with a fresh report, on its own.

## Smarter raiders (branch `milestone-3`, 2026-11-18)
Request: demomen throw TNT when there is nothing to breach; bow users switch to the bow when the player can't be reached;
leaders put in boats for the squad; artillerymen keep their distance and call the airstrikes; the leader orders flanks on a
running player; "depend more on the leader, use any opportunity".
Built (RaiderEntity unless noted):
- `unreachable` (path check every second, target within 16): leaders take the bow, raiders and miners undermine
  (break the block under a target standing 1.5+ blocks above them, within reach). Melee goal stops once a bow is in hand.
- Demoman `throwTnt`: target seen 5-16 blocks off, cooldown 3-5 s, keeps one stick on the way to a base. Lob velocity is
  closed form (`Ballistics.throwTnt`, vanilla PrimedTnt step: gravity 0.04, move, drag 0.98; unit tested).
  `ExplosionEvent.Detonate`: raider TNT breaks no blocks when raider griefing is off (vanilla TNT ignores mobGriefing).
- Leader `lead()`: shares his target (as before) and, when a player target moves 3+ blocks/s away from him, gives flank orders:
  mates alternate left/right/straight; `FlankGoal` runs to target + 0.6d ahead + 0.5d to the side (caps 10/7), speed 1.25, until 5 blocks off.
- Leader `launchBoats`: on the way somewhere 12+ blocks off with 6 of the next 10 columns open water, puts in boats
  (two raiders per boat), reusing empty floating boats ahead that no other raider has claimed. `BoardGoal` (priority 0),
  `startRiding` override (vanilla boats grab any mob that bumps them), `ride()`: the first passenger steers toward
  `moveGoal()` with a player-strength paddle push; all get out when the boat grounds or noses into land. Boats stay behind.
- Artilleryman: spyglass, always the bow when fighting, avoids players within 8, and on a raid hangs back 22 blocks on the camp
  side of the target (dry ground, tries 22/18/26/14/30). RaidManager `callStrike`: a field artilleryman (preferably one who sees
  a player) fires a red flare and the shells land 3 s later on whoever he sees; kill him and they never come. With none in the
  field, the camp guns fire blind as before. Prompt2 rules unchanged (needs him alive, stage 4 more often and tighter, random offset).
Verified in game (dev server, walled test home, camp 35 blocks north):
1. Demomen threw TNT at the player on a pillar (log: "threw TNT at Dev from 15/9 blocks"), blowing the pillar away.
2. Undermining: "RAIDER undermines Dev at -289,67,2236" on a dirt pillar, the player dropped.
3. Bow switch: 3 garrison leaders held bows while the player stood 6 up on stone; 8 damage absorbed from arrows in 5 s; an arrow
   knocked the player off, then they went back to swords.
4. Boats: a 40x11 water strip across the path. 4 leaders each put in 3 boats for 5 raiders, all 4 squads crossed and landed on
   the far shore (boats left at z 2210). The first try had two leaders share boats; fixed with the claimed check.
5. Artillerymen stood at their posts on the dry north shore holding the spyglass. Flares at 04:28:45, 04:29:39, 04:30:23 with the
   chat warning; shells came 3 s after the flare (2 shells at stage 3, landing 14 and 20 blocks from the player).
6. Flank orders fired on a sprinting player, in the right direction (run vector (5.2,0,0) and (5.8,0,0)).
Not verified: the flank movement itself (the test field was cratered; squad mates were busy at the base or in pits and never
got in range); undermining by miners through stone; boats on a retreat.
Gotchas:
22. Day raids at stage 3 on a scouted base call a strike 15 s after arrival: it craters any test setup. Test AI at stage 2.
23. Mobs only look for targets 4 blocks up or down (`getTargetSearchArea`): raiders in a crater never see a player on the rim.
24. Teleport-running the test player past solid ground got the client kicked ("Flying is not enabled"), which ends the raid
    (squads go virtual, then home). `allow-flight=true` in run/server.properties while testing; restored to false after.
25. A digger moved away mid-dig kept "mining" the old block for up to 30 s. Fix: the dig fails when the block is out of reach.
26. Debug lines (flank, throw, boats, undermine) are LOG.debug; the dev run logs INFO only, so switch them to info to watch.

## Full-sized raids (2026-11-18)
Request: a "full sized raid", mostly at night, sometimes by day; wave defense with a stage-dependent number of waves; late
waves send more than one squad; only against a base a scout has reconned; a chance on night raids, a lower one on day raids.
Built: squad kind `FULL`, Base fields `wave/waves/nextWave/waveAt/waveArrived` (saved). `Composition.waves(stage)` = stage + 1,
`waveSquads` = 1 in the first half, 2 after, 3 in the last wave from stage 3 ([1,2], [1,2,2], [1,1,2,3], [1,1,2,2,3]; tested);
each squad is the night raid squad (`Composition.squad`). `RaidManager.tickFullRaid`: next wave 30 s after the current one is
beaten (all its squads dead or retreating), or after 5 min regardless; called off if nobody is within 128 blocks of home when a
wave is due. Triggers: `launchNight` with a fresh scout report rolls `fullRaidNightChance` (0.5); a due day raid on a base in
`lastScouted` rolls `fullRaidDayChance` (0.01; day raids come every 2-4 days, night raids about one in 35 days, so this keeps most
full raids at night). Full squads fight like night squads (no steal limit or timeout), ignore dawn, call one strike each at
stage 3+ (artilleryman flare), count as knowing the layout (more TNT), block sleeping, and keep the gate open between waves.
`/rop full` starts one now; `/rop info` shows the wave.
Verified in game (stage 1): `/rop full` gave "2 waves", wave 1 (1 squad) reached the base, was killed, "Wave 1 of 2 is beaten!
The next one comes in 30 seconds", the final wave of 2 squads came 30 s later, then "You held off the full-sized raid!".
With both chances at 1.0: `/rop night launch` became a full raid and its squad kept attacking at dawn (time 23500); leaving
during the break gave "Nobody was home: the raiders called off the full-sized raid"; a due day raid became a full raid
(it waited until the camp had 2+ raiders, as day raids do).
Not verified: stages 2-4 wave sizes in game (unit tested only), the 5-minute overdue wave, sleeping blocked during a full raid.

## Camp expansion (2026-11-18)
Request: every 2 days, guaranteed, the raiders build a new base near your current base.
Built: an expansion camp is a `Base` with `parent` = the base it was built around: same home, players mirrored from the parent
in `updateMembership` (players themselves only ever join main bases; `baseOf` skips expansions). Main bases with players run
`tickExpansion`: every `expansionDays` (2) another camp, up to `maxCampsPerBase` (8, first included). The new camp is placed by
the usual `placeCamp` (baseDistanceMin-Max from home, dry and flat, retried every 30 s) at least 64 blocks from every other camp,
built for the current stage with a full garrison, announced in chat. Expansion camps have their own garrison, day raids
(full raid chance included), tower cannons and stage rebuilds; night raids, scouts and patrols stay with the first camp. A scout
report on the home counts for its expansion camps. `/rop expand` adds one now; `/rop info` lists them under their home.
Verified in game: `/rop expand` built a stage 3 camp 195 blocks north of home with an 8-raider garrison and "The raiders built a
new camp to the north of your base! (2 camps now)"; `/tick sprint 2d` (real ticks, so game time advances) gave the timed one,
133 blocks south-west, "(3 camps now)", and a day raid set out during the sprint.
Gotcha 27: `/time add` changes the day time only; timers on game time need `/tick sprint`.

## Sleeping counts as a day (2026-11-18)
Day-scale timers (day raids, expansion, garrison regrowth, the weekly night roll, patrols) moved from game time to
`RaidData.clock`: every second it adds the ticks that passed plus however far the day time jumped beyond them (sleeping,
/time add). Setting the time back is ignored; with doDaylightCycle off it still runs at tick speed. It starts at game time, so
older saves keep their timers. Short timers (waves, strikes, cannons, 30 s retries, scouts) stay on game time.
`/rop info` shows the raider day. Verified over RCON: /time add 12000 moved the next day raid 57 -> 47 min; /time set 1000
afterwards left it at 47; with the daylight cycle off it went 47 -> 46 in 62 s.

## Bigger expansion camps, overnight rebuilds (2026-11-18)
Request: expansion camps bigger depending on stage (user chose: bigger build and more raiders); camps rebuild only after
nights and only when no player sees them.
Built:
- `CampBuilder.outpost` takes the wall half-width `h` (`outpostHalf`: 11 for a first camp, 9 + 2*stage = 13/15/17 for
  expansion camps). Floor, walls, gate (and the stage 4 Create gate pocket), towers, cannon mounts, loot room, beds and moat all
  follow `h`; the footprint radius is h + 13. From h 15: a tower mid-way along each side wall (more cannon spots), a wider loot
  room with up to 14 chest spots, 1-3 more chests, more beds. Stage 1 expansion camps: two rows of two tents, radius 14.
- Garrison: `Composition.EXPANSION_CAP` {6, 9, 12, 15} (extra slots are raiders); `RaidManager.cap(b, stage)` everywhere the
  cap was used (placement, daily regrowth, rebuilds, survivors rejoining). Camp spacing raised to 72.
- Rebuild: `Base.upgradeAt` (raider clock) = the next morning after the stage changed; the camp is rebuilt from then on, once
  nobody has been in view for 3 min and it isn't raiding. `/rop rebuild` stays immediate.
Verified in game: a stage 4 expansion camp 35 blocks across with 6 towers, cannons, moat and wide loot room (screenshot from
above), garrison 15; a stage 1 expansion camp with 4 tents and 2 campfires, garrison 6. After `/rop stage 4` at day 1000 with
the player out of view, 4000 ticks of daytime gave no rebuilds; jumping to the next morning rebuilt every camp (ours and the
other test bases') within ~30 s as their areas loaded.
Not verified: stages 2 and 3 expansion builds in game (same code path, h 13 and 15).

## Raider points, night raid buildup (2026-11-18)
Request: replace the time-based progression with points (sleeping and killing raiders give the raiders points, so they
progress and expand quicker); night raids more frequent, with a chance that builds up after the cooldown until guaranteed.
User chose: points from time, sleep and kills; the stage keeps following advancements.
Built: `RaidManager.points(d, b)` = `d.clock` (time + slept time) + the main base's `bonus` (kills), in ticks, 1200 = 1 point
(a day of time = 20 points). Day raids, expansion, garrison regrowth, the night raid cooldown and patrols run on it (camp
rebuilds stay on the clock: they wait for the morning). A raider killed by a player (damage source entity is a player) adds
`killPoints` (2; a leader triple) to the base it belonged to (expansion camps credit their main base). Night raids: after the
`nightRaidPeriodDays` (7 days' worth of points) cooldown, a roll every morning at `nightRaidChance` (0.2) + `nightRaidChanceStep`
(0.2) per miss; a hit restarts the cooldown. Expected wait after the cooldown ~2.5 mornings, at most 5. `/rop info` shows points.
Verified in game: killing a leader as the player (`/damage ... by Dev`) gave +6 points and moved the next day raid 21 -> 15 and
the night cooldown 137 -> 131; a raider removed with /kill gave none. With chance 0 and step 0.5: after skipping the cooldown the
mornings went 50% -> 100% -> planned, and the cooldown restarted at 139 points.

## Raiders in boats, more scouts, raids when away (2026-11-18)
Request: scouting more common; raiders must actually sit in the boats ("empty boats crossing water and raiders teleporting");
a config option to enable/disable raids when the player is not home.
- Boats: `RaiderEntity.startRiding` only let a raider into "his" boat, but the client seats passengers through the same method
  (`ClientPacketListener.handleSetEntityPassengersPacket` -> `startRiding(vehicle, true)`) and `boat` is server-only, so the
  client refused: the boat moved empty while the raider stood frozen (the server sends no position for passengers), then
  "teleported" on landing. The check is now server-side only.
- Scouts: `scoutChance` (0.4): each morning (the patrol roll) a base with no night planned and no scout out gets a scout due
  (`scoutSent = false`); `tickNightPlan` sends a due scout any day someone is home, not only on planned nights. New bases start
  with no scout due.
- `raidsWhenAway` (false). Off: day raids need a member within 128 (as before); a night raid waits at dusk until a member is
  near the target home (all night, and the nights after, with a fresh scout each day); full raids call off as before. On: day
  and night raids launch at bases with members even if nobody is home or online; a RAID/NIGHT squad within 48 of its target
  keeps a region ticket (`RAID_TICKET`, 5x5 entity-ticking chunks) so it materializes and raids an empty base; on arrival
  it waits for the chunks instead of turning back (peaceful: turns back). Full raids still need a defender.
- No friendly fire (user: "artilleryman doesn't airstrike and cannons dont shoot if theres teammates nearby the target"):
  `RaidManager.friendsNear(aim, r)` = any RaiderEntity within r horizontally (±48 in height). A CBC HE shell has entity
  explosive power 12 (`munition_properties/projectiles/he_shell.json`), so it hurts out to 24 blocks; `BLAST` = 14.
  Airstrikes (`strikeAim`): up to 4 random aims around the target, the first with no raider within spread*1.5 + 14
  (26 at stage 3, 20 at stage 4); none -> no strike. `callStrike` returns false then; night strikes are not used up and
  retry every second, day/full raid squads keep `airstrike` false and retry. A flare's shells are re-checked on impact
  ("The artilleryman held his fire..."). Tower cannons: no raider within 14 of the aim, else retry in 5 s.
  `/rop fire` and `/rop airstrike` (test commands) are not checked.
Verified in game: boats (server: raiders counted as passengers of boats crossing the channel many times; client: a raider
seated in a boat, seen behind the pause menu). raidsWhenAway on, player 4000+ blocks away: `/rop raid` launched,
materialized at the empty base ("The raiders have reached your base!"). Off: `/rop night launch` with the player away
kept "night raid planned" with no squads; teleporting home at night launched it within a second.
Test-world note: the -302,2188 stage 4 camp's gate bridge had been broken by earlier explosions and its lava moat flowed
onto it, so outgoing leaders burned to death (rebuilt with `/rop rebuild`).

## Camps blend into the terrain (2026-11-18)
Request: "can you make structures blend in terrain better?" Before: `flatten` cut a flat disc (radius h+13) with
vertical edges, so camps sat on a dirt plinth or in a pit; the floor height was the ground at the centre.
- `trySite` puts the floor at the median of its 25 ground samples (less cut and fill, robust to a tree trunk sample).
- `flatten`: inside the disc as before, but it also clears ground higher than `height`. Around it a `BLEND` = 12 ring is
  eased (smoothstep) from the floor to the natural ground: cut columns are cleared (with plants and trunks on them),
  fill columns built up with the biome's under-block; each column keeps its own surface block (sand stays sand).
  `groundTop` finds the ground under trees, plants and water; it skips (leaves alone) lava, block entities and anything
  built: a column only counts if the ground is terrain (dirt/sand/stone/terracotta/snow/gravel/clay/ice tags or the
  biome's own blocks), and logs only as trees under leaves. Water: the slope continues under it as a bank (filled,
  under-block on top below the surface); lake beds are never dug out. Snow covers the ring too.
- Spacing check: camps are >= 72 apart and the ring reaches radius + 12 <= 42, so it never reaches a neighbour's walls
  (<= ~22 from its centre).
Verified in game (screenshots): the jungle camp at 1880,70,-1842 (built on a plinth by the old code) rebuilt onto an
eased hill; first try left a dirt cliff where the slope met a lake (water columns were skipped), fixed by banking into
water. Two new expansion camps (1809,72,-1899 and 1819,67,-1773) sit on gentle mounds in a jungle and a birch forest.

## Next step
Install into the user's instance (ask first; the instance is missing ritchiesprojectilelib). Push the branch when asked. Next ideas: sleep-blocking check with a real player, tunnelling under obsidian, a clean flank test on untouched ground.
