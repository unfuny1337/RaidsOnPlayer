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

## Next step
Install into the user's instance (ask first; the instance is missing ritchiesprojectilelib). Push the branch when asked. Next ideas: sleep-blocking check with a real player, tunnelling under obsidian, a clean flank test on untouched ground.
