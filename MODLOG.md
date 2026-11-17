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

## Next step
Milestone 2: outpost (concept2.png) for stages 2-4, biome wood, Create mechanisms and farm (stage 2), lava moat + CBC cannon (stage 3),
industrial gate + 2-4 cannons + airstrike (stage 4). Install into the user's instance once they say so.
