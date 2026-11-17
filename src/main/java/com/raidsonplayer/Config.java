package com.raidsonplayer;

import net.neoforged.neoforge.common.ModConfigSpec;

public final class Config {
    public enum LootAmount { LOW, MEDIUM, HIGH }

    private static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();

    public static final ModConfigSpec.IntValue RAID_MIN_DAYS = B
            .comment("Minimum in-game days between raids on a base")
            .defineInRange("raidIntervalMinDays", 2, 1, 1000);
    public static final ModConfigSpec.IntValue RAID_MAX_DAYS = B
            .comment("Maximum in-game days between raids on a base")
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

    public static final ModConfigSpec SPEC = B.build();

    private Config() {}
}
