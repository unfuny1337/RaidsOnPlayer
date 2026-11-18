package com.raidsonplayer;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;
import java.util.Random;

import static com.raidsonplayer.Role.*;
import static org.junit.jupiter.api.Assertions.*;

class CompositionTest {
    @Test
    void nightSquadsMatchTheSpec() {
        Random r = new Random(1);
        for (int run = 0; run < 200; run++) {
            for (int stage = 1; stage <= 4; stage++) {
                List<List<Role>> squads = Composition.night(stage, r, stage == 4 ? 0.35 : 0.2);
                int n = squads.size();
                switch (stage) {
                    case 1 -> assertEquals(2, n);
                    case 2 -> assertTrue(n == 3 || n == 4);
                    default -> assertEquals(4, n);
                }
                for (List<Role> s : squads) {
                    assertEquals(stage + 3, s.size());
                    assertEquals(1, Collections.frequency(s, LEADER));
                    assertEquals(stage >= 3 ? 2 : 1, Collections.frequency(s, MINER));
                    assertEquals(stage >= 3 ? 1 : 0, Collections.frequency(s, ARTILLERYMAN));
                    if (stage == 1) assertEquals(0, Collections.frequency(s, DEMOMAN));
                    assertEquals(0, Collections.frequency(s, SCOUT));
                }
            }
        }
    }

    @Test
    void demomenShowUpFromStage2() {
        Random r = new Random(2);
        int demo = 0;
        for (int i = 0; i < 100; i++) for (List<Role> s : Composition.night(2, r, 0.5)) demo += Collections.frequency(s, DEMOMAN);
        assertTrue(demo > 50);
    }

    @Test
    void garrisonFillsToTheCapAndRegrowsMissingRoles() {
        for (int stage = 1; stage <= 4; stage++) {
            List<Role> g = Composition.garrison(stage);
            assertEquals(Composition.GARRISON_CAP[stage], g.size());
            assertEquals(stage >= 3, g.contains(ARTILLERYMAN));
            assertTrue(Composition.missing(g, stage).isEmpty());
        }
        assertEquals(List.of(LEADER), Composition.missing(List.of(RAIDER, MINER, RAIDER), 1));
    }

    @Test
    void fullRaidWavesGrowTowardTheEnd() {
        int[][] want = {{}, {1, 2}, {1, 2, 2}, {1, 1, 2, 3}, {1, 1, 2, 2, 3}};
        for (int stage = 1; stage <= 4; stage++) {
            assertEquals(want[stage].length, Composition.waves(stage));
            for (int w = 1; w <= Composition.waves(stage); w++) assertEquals(want[stage][w - 1], Composition.waveSquads(stage, w), "stage " + stage + " wave " + w);
        }
    }
}
