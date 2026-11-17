package com.raidsonplayer;

import java.util.function.IntUnaryOperator;

/** The >50% rule, kept free of Minecraft classes so it can be unit tested. */
final class Progression {
    private Progression() {}

    /** Highest stage reachable from `current` when more than half of `players` pass each next stage's test. Never goes down. */
    static int nextStage(int current, int players, IntUnaryOperator passing) {
        int s = current;
        while (s < 4 && players > 0 && passing.applyAsInt(s + 1) * 2 > players) s++;
        return s;
    }
}
