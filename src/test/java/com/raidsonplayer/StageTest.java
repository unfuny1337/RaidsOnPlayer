package com.raidsonplayer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class StageTest {
    @Test
    void moreThanHalfOfOnlinePlayersAdvancesTheStage() {
        assertEquals(2, Progression.nextStage(1, 1, s -> s == 2 ? 1 : 0)); // solo: one player is >50%
        assertEquals(1, Progression.nextStage(1, 2, s -> 1));               // 1 of 2 is exactly 50%: not enough
        assertEquals(4, Progression.nextStage(1, 3, s -> 2));               // 2 of 3 pass everything
        assertEquals(2, Progression.nextStage(1, 1, s -> s == 3 ? 0 : 1)); // stops at the first stage not met
        assertEquals(3, Progression.nextStage(3, 0, s -> 0));               // nobody online / nobody passing: never goes down
    }
}
