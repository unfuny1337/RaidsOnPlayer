package com.raidsonplayer;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {
    public enum LootAmount { LOW, MEDIUM, HIGH }

    private static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue RAID_MIN_DAYS = B
            .comment("Minimum raider points between day raids from a camp, in days' worth (a day of time is 20 points; sleeping and kills add more)")
            .defineInRange("raidIntervalMinDays", 2, 1, 1000);
    public static final ModConfigSpec.IntValue RAID_MAX_DAYS = B
            .comment("Maximum, same unit")
            .defineInRange("raidIntervalMaxDays", 4, 1, 1000);
    public static final ModConfigSpec.EnumValue<LootAmount> LOOT = B
            .comment("How much loot raider base chests hold")
            .defineEnum("lootAmount", LootAmount.MEDIUM);
    public static final ModConfigSpec.IntValue CAMP_MIN_DIST = B
            .comment("Minimum distance in blocks between a player base (bed) and its raider base")
            .defineInRange("baseDistanceMin", 100, 32, 100000);
    public static final ModConfigSpec.IntValue CAMP_MAX_DIST = B
            .comment("Maximum distance in blocks between a player base (bed) and its raider base")
            .defineInRange("baseDistanceMax", 300, 32, 100000);

    public static final ModConfigSpec.BooleanValue BREAK_BLOCKS = B
            .comment("Raiders break doors and dirt, miners dig tunnels and demomen blow walls (also needs the mobGriefing gamerule)")
            .define("raidersBreakBlocks", true);
    public static final ModConfigSpec.IntValue NIGHT_PERIOD_DAYS = B
            .comment("Night raid cooldown in days' worth of raider points; after it, a roll every morning until a night raid is planned")
            .defineInRange("nightRaidPeriodDays", 7, 1, 1000);
    public static final ModConfigSpec.DoubleValue NIGHT_CHANCE = B
            .comment("Chance of the first morning roll after the cooldown (a scout comes that day, the raid at dusk)")
            .defineInRange("nightRaidChance", 0.2, 0.0, 1.0);
    public static final ModConfigSpec.DoubleValue NIGHT_CHANCE_STEP = B
            .comment("Added to the night raid chance after each morning it misses, so one is guaranteed in the end")
            .defineInRange("nightRaidChanceStep", 0.2, 0.0, 1.0);
    public static final ModConfigSpec.IntValue KILL_POINTS = B
            .comment("Raider points per raider a player kills (a leader counts triple). Points bring day raids, new camps, garrison regrowth and night raids closer")
            .defineInRange("killPoints", 2, 0, 1000);
    public static final ModConfigSpec.DoubleValue FULL_NIGHT_CHANCE = B
            .comment("Chance that a night raid whose scout got away is a full-sized raid instead: waves of squads, stage + 1 waves")
            .defineInRange("fullRaidNightChance", 0.5, 0.0, 1.0);
    public static final ModConfigSpec.DoubleValue FULL_DAY_CHANCE = B
            .comment("Chance that a day raid on a base a scout has reported is a full-sized raid instead (day raids are far more frequent than night raids)")
            .defineInRange("fullRaidDayChance", 0.01, 0.0, 1.0);
    public static final ModConfigSpec.DoubleValue PATROL_CHANCE = B
            .comment("Chance each in-game day that a raider patrol wanders near a base")
            .defineInRange("patrolChance", 0.3, 0.0, 1.0);
    public static final ModConfigSpec.DoubleValue SCOUT_CHANCE = B
            .comment("Chance each morning that a scout comes to spy on a base even with no night raid planned (planned nights always send one)."
                    + " A scout that gets away marks the base: full-sized raids, airstrikes and night raids need that")
            .defineInRange("scoutChance", 0.4, 0.0, 1.0);
    public static final ModConfigSpec.BooleanValue RAIDS_WHEN_AWAY = B
            .comment("Raid bases when none of their players is home (within 128 blocks), even while they are offline: the raiders load the area,"
                    + " break in and loot. false = day and night raids wait until someone is home. Full-sized raids always need a defender")
            .define("raidsWhenAway", false);
    public static final ModConfigSpec.DoubleValue DEMO_CHANCE_2 = B
            .comment("Chance that a free night raid slot is a demoman instead of a raider, stage 2")
            .defineInRange("demomanChanceStage2", 0.15, 0.0, 1.0);
    public static final ModConfigSpec.DoubleValue DEMO_CHANCE_3 = B
            .comment("Same, stage 3")
            .defineInRange("demomanChanceStage3", 0.2, 0.0, 1.0);
    public static final ModConfigSpec.DoubleValue DEMO_CHANCE_4 = B
            .comment("Same, stage 4")
            .defineInRange("demomanChanceStage4", 0.35, 0.0, 1.0);
    public static final ModConfigSpec.IntValue EXPAND_DAYS = B
            .comment("The raiders build another camp around a lived-in player base every this many days' worth of raider points (0 = never)")
            .defineInRange("expansionDays", 2, 0, 1000);
    public static final ModConfigSpec.IntValue MAX_CAMPS = B
            .comment("Most raider camps around one player base, the first one included")
            .defineInRange("maxCampsPerBase", 8, 1, 64);
    public static final ModConfigSpec.DoubleValue MAP_CHANCE = B
            .comment("Chance that a squad leader killed by a player drops a map to his camp")
            .defineInRange("leaderMapChance", 1.0, 0.0, 1.0);

    public static final ModConfigSpec SPEC = B.build();

    static double demomanChance(int stage) {
        return switch (stage) { case 2 -> DEMO_CHANCE_2.get(); case 3 -> DEMO_CHANCE_3.get(); case 4 -> DEMO_CHANCE_4.get(); default -> 0; };
    }

    private Config() {}
}
