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

## Leaders take their boats back; goal timing bug (2026-11-18)
Request: "make leader take the boats after the water was crossed". The leader keeps the boats he put in (`fleet`;
boats that were already floating there are reused but not taken). `PackBoatsGoal` (priority 0, MOVE): once a boat of
his is empty and no squad mate still has it as theirs, and nobody is within 12 to fight, he walks to it (within 16
blocks) and takes it (discarded, pickup sound); one he can't reach in 10 s is left.
Bug found on the way (gotcha 28): `Mob.serverAiStep` checks goals (`canUse`) and ticks running goals only when
`tickCount + getId()` is even, so `tickCount % n == 0` in goal code never matched for odd-id raiders. That had silently
disabled stealing (StealGoal), the "wall ahead" breach check and the demoman's run-from-bomb for half the raiders.
`RaiderEntity.every(n)` = `(tickCount + getId()) % n == 0` replaces them all (entity `tick()` code is fine as it was).
Test site: the old channel near -289,2221 is cratered by earlier shelling, so a fresh one: a home on the west bank of a
river at 3019,70,3060; `/rop expand` until a camp's path crossed water (camp 2891,123,3277, two water stretches).
Verified: before the fix 6 + 5 boats stayed behind; after it, 4 boats taken back within 15 s of the first crossing and
4-5 after the second, the squad moved on with none left.

## First camp took minutes on rough land (2026-11-18)
User: "placed and clicked on bed, slept through few nights, increased tickrate, no camp spawned". Their debug.log
showed the base at 52,66,380 (17:03:43) and the camp at 115,67,324 only at 17:07:48: every 30 s all 12 candidate sites
were rejected. Reproduced on the dev server with their seed (read from their level.dat, read-only) in throwaway worlds
`run/seedtest`, `run/seedtest2` and their distances (75-150): the ring around their home is ocean, river, ponds and
mountain foothills. New log line per failed attempt with the reasons, e.g. `{ocean/river=5, steep 22=1, water=7}`.
Fix: `trySite` measures ground under trees (`groundSurface`); up to 3 outer samples may be water (the 3x3 middle must
be dry); and each failed attempt adds slack (`Base.placeFails`, not saved): rise allowed 8 + 4*slack, water 3 + slack,
slack up to 4. Retries every 10 s for the first 4 failures, then 30 s. On their terrain the camp now comes on the 4th
attempt (~1.5 min with 30 s retries; ~40 s with 10 s).

## Territory (2026-11-19)
Request: raider land around camps; on it you get attacked more, a home on it gets more scouting and raids; more camps =
more land; a camp whose squad was killed can be taken, but if no player takes it the raiders come back for it; the root
camp can be taken too, and then new root camps (more than one) appear after a few days and grow on their own.
User chose: take a camp by holding it 60 s; my picks for the rest (land radius by stage, kill points taken back, expansion delay).
Built (RaidManager unless noted):
- Land = within `TERRITORY` {64, 96, 128, 160} by stage of any camp (`landOf`, computed, nothing saved). Empty camps keep
  their land until taken. Camps sit 100-300 from home, so from stage 3 your own home can fall inside.
- On their land: once a minute, 10% per player, the camp sends a hunting party (`Kind.HUNT`, the patrol make-up, one per camp)
  that follows the nearest player within land + 32 and goes home when there is none, when losing, or after 5 min.
- Home on their land: day raid interval halved (`raidInterval(level, d, b)`), scout and patrol chances doubled.
- Empty camp (`empty`: garrison 0 and no squad of it in the field; a retake squad doesn't count): `Base.emptySince`
  (points), no regrowth, no rebuild, no root duties. A day of points later a `Kind.RETAKE` squad (night-raid make-up)
  marches from the nearest manned camp of the same home (none: from 96 blocks out) and becomes the garrison on arrival
  (`returnHome`); wiped out, the next one comes a day later. A player within 16 of the centre with no raider there for 60 s
  (action bar counter) takes it (`takeCamp`): the kill points its defenders earned (`Base.killed`, garrison/retake kills)
  come off the bonus, every root's next expansion moves back a cycle, a retake squad still on the way turns into a hunt
  from the nearest camp. An expansion camp's record is deleted (ruins and chests stay); the first camp's record is the
  player base, so only its camp is cleared.
- Roots: the first camp, plus `Base.root` expansions. Roots run night raids, scouts, patrols and expansion (expansions
  always attach to the player base: `expand` uses `main()`). When the last root falls, `regroupAt` = 3 days of points
  later: the first camp is placed again and a second root camp is added (`regroup`). While other roots stand,
  a taken first camp waits (`regroupAt = MAX`). Scout reports are stored against the player base (`main`).
- RaiderEntity: `getMaxFallDistance` = 3. Vanilla allows ~16 for a mob with a target; hunters chasing a player down from a
  hilltop camp jumped off the cliff and died (seen in the first hunt test: 2 of 3 dead from falls, fire -1).
- `/rop hunt` (a hunting party from the land you stand on); `/rop info` shows EMPTY/retake/hold, regroup and root camps.
Verified in game (dev server + client, home 3019,70,3060, stage 3):
1. `/rop hunt` 68 blocks from a camp: the party left the camp and reached the player. After the fall fix, two hunts from
   hilltop camps (y 128 and 123) reached the player with no losses.
2. Killed an expansion camp's garrison with the player inside: EMPTY, hold counted up, after 60 s "Dev took the expansion
   camp", the camp left `/rop info`, next camp 32 -> 71 pts.
3. Emptied another camp, player moved away, `/time add 24000`: a retake squad of 6 set out from the nearest camp, walked in
   as data and became the garrison (regrowth resumed: a demoman joined).
4. Took the root camp: "no root camps left", regroup in 60 pts; `/time add 72000`: the first camp was rebuilt at
   2882,128,2865 (after two site retries) and a root camp at 2765,67,3037 a second later.
Not verified: the kill-point refund (all test kills were /kill, which gives no points); hunts that keep going at a home
on their land; a materialized retake squad fighting its way in; a retake squad turning into a hunt on takeover.
Seen once, not chased: a garrison raider at 0.5 HP that died a moment later at the 2891,123,3277 camp (not a hunter).

## Raider land on Xaero's maps (2026-11-19)
Request: "add xaero mini/world map support so you can actually see the territory".
- Sync: `TerritoryPayload` (stage reach + every camp's x/z/empty) goes to all players when it changes (checked every 5 s
  in `RaidManager.tick`) and to each player on login. `client/ClientTerritory` keeps it and answers the map queries
  (common classes only, so the handler can name it on a dedicated server).
- Xaero has no public highlighter API. Both mods build a `HighlighterRegistry` per session, register Open Parties and
  Claims' highlighter and seal the list with `end()`. `mixin/WorldMapRegistryMixin` and `MinimapRegistryMixin` (@Pseudo,
  `required: false`, client only) register ours at the head of `end()`, so without Xaero nothing loads. Compiled against
  Xaero from the Modrinth maven (`compileOnly`, World Map 1.47.0, Minimap 26.6.0; XaeroLib ships inside both jars).
- `client/xaero/WorldMapTerritory` / `MinimapTerritory` extend each mod's `ChunkHighlighter`: chunk centre within reach
  of a camp; colours 0xBBGGRRAA (red, orange for an empty camp; fill alpha 0x50, outer border 0xD0, read from Xaero's
  claims highlighter bytecode: [0] centre, [1-4] north/east/south/west edges); tooltip "Raider land". Only drawn over
  explored map (`coveringOutsideDiscovered` false), overworld only. Both maps use 32x32-chunk regions.
  Refresh on change: World Map `MapWorld.clearAllCachedHighlightHashes()` (region hash = the camps touching it), Minimap
  `DimensionHighlighterHandler.requestRefresh()`. The World Map's own bridge to the minimap
  (`SupportXaeroMinimap.registerMinimapHighlighters`) is empty in 1.47, so the minimap needs its own highlighter.
- Dev client: both Xaero jars copied into `run-client/mods`.
Verified in game (screenshots): the minimap shows "Raider land" under the coordinates; the world map tints the land red
with a red line where it ends and shows the tooltip; killing a camp's garrison turned its land orange on the open map
within the 5 s sync.
Not verified: minimap colours up close (small window); a dedicated server without Xaero on some clients (should be
fine: the mixins are client-only and skip without Xaero); the minimap alone without the World Map.

## Expansion camps grow next to each other (2026-11-19)
Request: "expansion camps should be closer to each other so there's no empty land between two camps".
`placeCamp`: an expansion camp now picks a random camp of the same base and samples CAMP_SPACING (72) to 1.6x the
stage's reach from it (+0.1x per failed attempt, up to 2x, where the two lands would only touch), still within
baseDistanceMin-Max of the home ("off the ring" otherwise). The first camp, or an expansion with no camp to grow from,
still goes anywhere on the ring. Camps already standing don't move.
Verified: 6 days of `/time add` on home 3019,70,3060 (stage 3, reach 128) built 4 camps, each 72-190 blocks from an
existing camp (lands overlap below 256). "off the ring" was the most common rejection (7-9 of 12), so a site took 1-2
extra 10 s retries.

## Raider airships and the pilot (2026-11-19, version 1.2.0; 1.2.1 holds new ships still until warm; 1.2.2 shells; 1.2.3 plot 0)
Request: raiders fly the user's airship (`airship_mk2.excraft`, made with the Create Aeronautics Toolgun addon); link
signals randomized per ship with the typewriter bindings matching; new class "pilot" (tier 3: read as stage 3+),
aviator's goggles, a bow, full-sized raids only, drops the 2 armed HE shells on the base and leaves. Mid-way: "shells are
already armed and will explode on impact" and "would be more chaotic if airship lost control if pilot died/fell".
The ship (decoded from the blueprint, `src/main/resources/data/raidsonplayer/airships/airship_mk2.excraft`): 5 Sable
sub-levels: hull with balloon, adjustable burner, creative motor, two propeller bearings, sail rudder, seat; and two
bomb rigs (oak beam, rope, impact-fuzed HE shell) held under the hull by two Create stickers. Controls:
- Throttle lever 1 -> link -> two `simulated:analog_transmission` in front of the propellers. They BRAKE:
  speed = 1 - (signal + 1) / 16, so signal 0 is full power and 15 stops them (the blueprint was saved parked at 15).
- Throttle lever 2 -> link -> burner (0-15 heat).
- Linked typewriter: D/A -> directional gearshift -> torsion spring -> sail rudder (only bites at speed; NOT
  differential thrust, both propellers always run together); F -> both stickers -> the bomb rigs drop.
Built (`Airships`, all reflection; optional, needs `aeronautics` + `create_aeronautics_toolgun`):
- Blueprint NBT rewritten per ship: every frequency pair (`FrequencyFirst/Last` on links, `FirstItem/SecondItem` on
  typewriter keys) maps to a fresh pair of random items, the same everywhere in that ship. The creative motor goes
  16 -> 64 rpm (16 rpm = 1.2 blocks/s, too slow for the rudder; 64 = ~5.5 blocks/s).
- Placed with the Toolgun's `PortableStructurePrinterPlacement.placeBlueprint` (package-private, no player needed). New
  sub-levels = the diff of Sable's list before/after. Controls found by scanning the plot chunks' block entities:
  `LinkedTypewriterBlockEntity`; the two `ThrottleLeverBlockEntity` told apart by the link next to each (diagonally
  below) carrying the remapped burner frequency; the seat by block id. Pilot: `RaidManager.equip(PILOT)` + Create
  `SeatBlock.sitDown`. Driven with the mods' public methods: `ThrottleLeverBlockEntity.setSignal`,
  `LinkedTypewriterBlockEntity.pressKey/releaseKey`.
- Sable gotcha 29: on a dedicated server ANY Java reflection on `SubLevelContainer` (even `getMethod` with exact types)
  lists all its methods and loads its `getContainer(ClientLevel)` overload -> "invalid dist DEDICATED_SERVER". The
  container calls go through `MethodHandles` (`findStatic/findVirtual`), which bind one method only.
- Gotcha 30: a freshly placed balloon is cold and can't carry the ship until the burner has heated it, and how long
  that takes varies: dropped from y 300 the ship settled at ~y 124 on the dev server, but in the user's singleplayer
  world it fell 226 blocks to the ground and its armed shells went off there (their debug.log, 2026-11-19 16:10; the
  blast also spilled the camp's lava moat). Fix (`steady`): the ship appears CRUISE + 10 above the highest ground on
  its way in and is held still: every tick each part's linear and angular velocity is cancelled through Sable's
  `RigidBodyHandle` (`of`, `getLinearVelocity`, `addLinearAndAngularVelocity`). How much the hull sank since the last
  tick is the lift test (-0.56 blocks/s = full gravity); a second under 0.05 and it is let go. 90 s without that: the
  ship is removed instead. Aeronautics also thins the air with height (warm ceiling ~y 150): no airship when the
  approach ground + 40 is above it.
- The blueprint always comes out nose south and the rudder turns it only slowly, so the ship appears 140 blocks NORTH
  of the target, flies straight over it and keeps going south. Altitude: burner = 8 + 0.5 x height error - 4 x climb
  rate, cruise 30 above the highest ground 16 ahead. Heading: rudder key on turn error minus 1.5 s of yaw rate.
- Bombs: aim from `strikeAim` (a defender of the raided base, clear of raiders; around the target itself for
  `/rop airship`); F when within 10 of the aim or within 20 having just passed it, and no raider within BLAST of it
  (pilots don't count: `friendsNear` skips them). `Artillery.markZone` there. The bomb rigs then leave the ship's part
  list. Leaves when 160 from every player (or 3 min).
- 1.2.2, shells: in the user's singleplayer game (1.2.1 installed) the shells still went off the moment the ship was
  placed: their debug.log showed the hold's lift stalling at -0.138 (a holed balloon) where every dev run reached
  -0.04. Not reproduced on the dev server, even with a copy of their world (`New World (5)`), the same full raid
  (`/rop full 5`) and their server-side mods (Lithium, ModernFix, FerriteCore, ...): 2 shells all the way. So the
  blueprint's shells are disarmed (`disarm`: the `Fuze` tag comes off every `createbigcannons:fuzed_block`) and never
  armed as blocks: on the drop the pilot still presses F, and `release` swaps the bomb rigs for impact-fuzed HE shell
  projectiles (`Artillery.spawnShell`) at each shell's spot with the ship's speed, removing the rigs.
- Gotcha 31: with the pilot killed, the falling ship hung the dev server until the watchdog killed it: Sable's
  `FragileBlockCallback` broke a block inside `Rapier3D.step`, the neighbour updates changed another block, and
  `handleBlockChange` called back into native Rapier (`newVoxelCollider`) mid-step (crash report says "make sure this
  is not caused by Sable"). So no raider ship part ever comes down on the ground as a physics body: the drop uses
  projectiles (above), and an out-of-control ship (phase FALLING: rudder jammed, propellers on, burner off) is
  watched every tick and blows up (`release` + explosion 4, parts removed) 14 blocks above the ground or after 60 s.
- 1.2.3, plot 0 (gotcha 32): in the user's fresh world the ship spawned fine but hung in place, spinning. Every
  printed ship gets the Create gear network of the blueprint's own plot (the user built it in plot 0: absolute
  coordinates 20480000+), with the creative motor driven instead of driving. In any other plot that sorts itself out
  when the Toolgun applies the motor's saved 64 rpm a few ticks after placing it (default 16). But the first ship in
  a world lands in plot 0 itself, where the network is named after the motor's own position, so Create took the speed
  change for a gear loop and broke the motor on tick 3. Nothing turned (every kinetic part `!OS`, the motor gone).
  That ship also got shoved ~11 blocks and 27 degrees while held. Reproduced with a copy of their world: the first
  ship after every restart (the cleanup freed plot 0), never later ones. Fix: `homePlot` (the motor's `network` ==
  its own position) prints a copy 48 blocks south, then removes the first. A copy on the same spot read ~0 sink all
  through the hold (something left of the first held it up), was judged warm at 4 s and fell or got shoved 20
  blocks. The lift test also waits 200 ticks now (warm-up took 13-15 s every time).
- `RaidData.airships`: parts under a pilot's control, removed on the next start (pilots are not saved: a loaded pilot
  is `stale` and discarded). Full-sized raids at stage 3+: an airship with the final wave. `/rop airship` (at you).
Verified on the dev server + client (stage 3): the flight log tuned over ~15 launches; 1.2.2 in the copy of the
user's world: full raid at wave 5 -> airship held 13 s, "dropped 2 shells" over the home, crater and smoke there in a
screenshot; pilot killed -> "Out-of-control airship blew up at 8,90,-68 (2 shells)", server fine. 1.2.1: held at y 125 for 13 s
while the sink went -0.56 -> -0.04 per tick, then cruised at ~122, dropped 6-10 blocks from its aim (explosion smoke on the target in a screenshot), flew off
and was removed; killing the pilot gave "out of control" and the ship came down and blew up by a village (smoke seen);
leftover ships removed after a restart ("Removed 5 parts"); the terrain check refused the base home here (a y 126-140
hill north of it).
1.2.3: the first ship after a restart in a copy of the user's new world: "came out in its blueprint's own plot;
flying a copy instead", sink -0.56 -> -0.05 over 15 s, flew straight (nose 88-94) and "dropped 2 shells" onto the
home. Then in a brand-new dev world (stage 4, bed, camp built, `/rop full 5`): copy printed, warm after 12 s,
"dropped 2 shells at -4,105,11 onto -11,70,17" (bed at -7,70,21).
`/rop full [wave]` starts a full-sized raid at that wave (checked against the stage's wave count): `/rop full 4` at
stage 3 sent the final wave (3 squads, 18 raiders) and its airship hook ran (refused here by the terrain check).
Not verified: a pilot shot down with a bow from the ground; the pilot falling out by other means; the dedicated server without
Aeronautics installed (all calls are guarded by `Airships.available()`).

## Rings, lines, airstrikes on the base (2026-11-19, version 1.3.0)
- Two or more squads sent at once (`sendOut`: full-raid waves, night raids) close a ring: each takes its own side, up
  to 120 degrees either way from the camp's direction (3 squads: camp side and +-120), heads for a rally point
  `RALLY` = 56 blocks out (`Squad.rally`; `dest` returns it until `go`) and waits. `surround`: when every squad
  still on its way is in place (or one has waited 60 s, or a squad has marched 3 min) all get `go`, chat names the
  sides, and at stage 3+ against a scouted base `callStrike` fires on the base as they go in. Their target is 4
  blocks out from the home on their own side. Squads beaten on the road drop out of the ring. A single squad goes
  straight in as before.
- Lines: a follower's march goal is his place in the leader's line (`MarchGoal.slot`): abreast of him at 2.5-block
  spacing across the way they go, facing the base once the leader halts (within 8 of where he's headed),
  artillerymen 5 behind. Followers repath every 15 ticks (the slot moves with him).
- Artillerymen: their post (`WatchGoal.post`) is on their squad's side of the base (its rally point, else the camp),
  22 blocks out, and 8 behind the rally point while the ring closes.
- Airstrikes aim at the base: `strikeAim` takes the home area (no more players near home or the player the
  artilleryman sees). The airship's bombs use the same aim. `RaiderEntity.spotted` is gone.
Verified (dev server + client, fresh world, stage 4, bed, camp, `/rop full 5`, 3 squads), 4 runs: "FULL squad in place" at
39,-19 / 20,76 / -53,12 (NE, S, W of the home at 2,23), then "Ring of 3 squads closed ... all in place: true". While
waiting, the NE squad's positions: the leader at the rally and 5 men within ~1 block of one line 15 blocks wide,
facing the home; both artillerymen 9 and 14 blocks behind it.
Not verified: the strike when the ring closes (needs a scouted base; two scouts left without a report here, one
at nightfall); a squad stuck on its way (the 60 s / 3 min release); lines in forests and on rough ground.

## Airships come from the raider camp, farther out (2026-11-20, version 1.4.0)
- `Airships.launch`: the ship appears `START` = 200 blocks (was 140, always north) from its target on the line from
  the raider camp, turned to face along it (`turn`: every part teleported through Sable's `RigidBodyHandle.teleport`
  to its pose turned about a vertical axis through the hull, in the same tick, so ropes and stickers hold), and
  flies on past the target the same way (`away`). The plot-0 copy moves 48 blocks along the line toward the target.
  Chat names the camp's side ("from the south-east").
- The far end of the track can be past the players' loaded chunks (the user plays at render and simulation
  distance 12, about 192 blocks): launch loads the track's chunks (`getChunk`) before the terrain check, and
  every second each ship refreshes a `RAID_TICKET` (distance 4: 5x5 chunks entity-ticking) around its hull.
Verified (fresh world, stage 4, camp 136,220 south-east of the home 5,15, `/rop full 5`): launched on the camp line
(plot-0 copy, 147 out), nose -122 wanted / -122 after `turn` / -123 held through warm-up, warm after 13 s,
"dropped 2 shells ... onto 7,69,14". In the first session's ships the nose sat 8-11 degrees off by the first flight log
line, not seen again in the next session (2 launches, logged every 4 ticks); the pilot steered it out either way.
Not verified: a camp straight north/west (any angle is the same code path); a 200-block spawn where the track's far
chunks had never been generated (sync generation on the launch tick may hitch).

## Shells and chunk loading (2026-11-20, no change)
- Asked: make cannon and airstrike shells load chunks around themselves. Not needed: CBC's required library
  Ritchie's Projectile Library already keeps chunks loaded around its shells. Test: Dev 40 blocks behind the camp,
  `/rop fire` at a home 237 blocks off (277 from Dev, past view distance 10): with a per-tick region ticket around each
  raider shell, 3 shells burst at the home; with the ticket disabled, the 1 shell fired also burst at the home. Reverted.
- Follow-up ("shells stopped flying if you flew far enough"): airstrike on the player, who teleports 120 blocks
  away (simulation distance 5) or 300 (past view distance) at once; every shell logged each second (height above the
  heightmap, speed, entity-ticking). 6 runs, 80 samples, no extra chunk loading: none stopped in mid-air. Every one flew
  on and burst, or came to rest on a surface (the "stuck" ones: CBC's 33% impact-fuze dud, lying in the ground until
  CBC discards it 400 ticks later). One sample right after a teleport showed its chunk not entity-ticking; a second
  later the shell had moved 25 blocks on.

## Nemesis leaders and tribute (2026-11-20, version 1.5.0)
Request: "make first and second idea" from a list of fun ideas: (1) a leader who kills you or gets away earns a name,
comes back with better gear and taunts you, drops his banner and loot when killed; (2) pay tribute at the camp's gate to
put the next raid off, refuse too often and the raid is bigger. (Idea 4, boarding a raider airship, waits until the user
builds a new airship for it.)
- Nemesis (one per player base, on its record: `Base.nemesis/nemesisRank/nemesisKills`): `nemesisWon` when an assault
  squad comes home with its leader after a fight (lost men or stole something) or any non-garrison squad kills a player
  (`onPlayerKilled`, from LivingDeathEvent). No nemesis yet: that squad's leader becomes it (random name). Else his own
  squad ranks him up, one rank per outing (`Squad.ranked`: a patrol spawn-camping the dev player took him from nothing to
  rank 3 in 10 s before that). Titles by rank: Name, Name the Scarred, Name the Dreaded, Warlord Name.
  `RaiderEntity.makeNemesis`: purple name tag, 20 + 10/rank health, Sharpness = rank, diamond chestplate with
  Protection rank-1 from rank 2, banner drop chance 2 (always). Killed by a player: also the camp map (always) and
  4 x rank emeralds; the record is cleared and the next leader to win takes his place.
- He never sits in a garrison: `claimNemesis` puts him at the head of the next assault on his player base (day raid,
  night raid, full-raid wave; a leader is added if the squad has none) unless he is out already, with a taunt in chat
  (escape or kill lines). Coming home, he is discarded instead of joining the garrison and `Squad.nemesis` goes with
  the squad, so he is free for the next raid. A squad lost with him (camp taken) leaves the record: he comes back anyway.
- Tribute (stage 2+, camps with a gate): a day before a camp's day raid it demands `DEMAND` = 12/24/48 iron's worth
  (iron 1, gold 2, emerald 3, diamond 8, blocks 9x) and the raid waits at least half a day. The first open demand of a
  player base explains the rules, the others are one line. Thrown valuables (ItemEntity with a player thrower) within 6
  of the watchman's spot outside the gate are taken every second; paid in full the raid is put off a whole raid interval
  and refusals reset. While a demand is open the garrison does not target a player holding tribute in the main hand
  (`parley`) unless he comes within 4 of a camp chest or hits them; no hunting party for him either.
- Refusal (`refuse`): a day raid that sets out unpaid adds 2 x refusals raiders (max 6, they don't come out of the
  garrison); the third refusal in a row makes it a full-sized raid when the base is scouted.
- `/rop nemesis` (name one / rank him up), `/rop tribute` (your camp demands now); `/rop info` shows both.
Verified (dev server + client, old test world, stage 3, base 3019,70,3060 with 8 camps): coming home made 5 camps demand
at once (the long text 5 times, hence the one-line rule, seen working after the restart); 2 diamonds -> "16 of 24", 3
emeralds -> "Tribute paid", next raid 37 -> 69 pts; an unthrown iron ingot was left lying. Survival Dev with emeralds in
hand 8 blocks from the watchman, 11 raiders around: full health for 25 s; with a stone sword: killed in under 12 s.
Unpaid raids: "2 more of them" (11 marched), "4 more" (14), then "Three times you refused ..." and a full raid. The patrol
at home killed the respawning Dev 3 times: "has a name: Tarvek", "Tarvek the Scarred killed you", "Tarvek the Dreaded"
(health 50, Sharpness 3, Protection 2 chestplate, head drop 2.0); damage by Dev killed him: "You killed Warlord Tarvek!",
banner, camp map and 12 emeralds on the ground. `/rop nemesis` -> Orsik; wave 2 of a full raid: `Orsik is coming for you:
"Last time I ran. This time you will."`, he spawned with 30 health and Sharpness 1; 3 of his 6 killed by Dev, Dev
teleported away, the squad marched home: "Orsik the Scarred got away again" (rank 2).
Not verified: a night raid led by the nemesis (same `sendOut` path as the full-raid wave); tribute thrown by a real
player with Q (tested with summoned items carrying Dev's UUID as thrower); parley against a real player looting.
Known: with many camps on independent timers, refusals stack up fast (three unpaid camps in a row = a full raid).

## Next step
Install 1.5.0 into the user's instance (ask first; they already ran 1.2.1 there, so its libraries are in). Next ideas: boarding a raider airship (idea 4, once the user has built a ship for it), sleep-blocking check with a real player, tunnelling under obsidian, a clean flank test on untouched ground.
