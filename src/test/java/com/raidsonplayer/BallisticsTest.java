package com.raidsonplayer;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class BallisticsTest {
    @Test
    void solvedShotLandsOnTarget() {
        double[][][] cases = {
                {{0, 80, 0}, {120, 64, 40}},    // tower cannon 126 blocks out, firing downhill
                {{0, 64, 0}, {-30, 70, 145}},   // uphill, 148 blocks
                {{90, 130, 0}, {0, 64, 0}},     // airstrike diving in from 90 blocks out, 66 up
        };
        for (double[][] c : cases) {
            double[] v = Ballistics.solve(c[0], c[1], 60);
            double[] end = Ballistics.simulate(c[0][0], c[0][1], c[0][2], v, 60);
            for (int i = 0; i < 3; i++) assertEquals(c[1][i], end[i], 0.05);
        }
    }

    @Test
    void thrownTntLandsOnTarget() {
        double[][] cases = {{10, -1.6, 0}, {-4, 2, 3}, {12, -5, -9}};
        for (double[] c : cases) {
            int ticks = 24;
            double[] v = Ballistics.throwTnt(c[0], c[1], c[2], ticks);
            double x = 0, y = 0, z = 0;
            for (int t = 0; t < ticks; t++) { // PrimedTnt.tick in the air
                v[1] -= Ballistics.TNT_GRAVITY;
                x += v[0]; y += v[1]; z += v[2];
                for (int i = 0; i < 3; i++) v[i] *= Ballistics.TNT_DRAG;
            }
            assertEquals(c[0], x, 1e-6);
            assertEquals(c[1], y, 1e-6);
            assertEquals(c[2], z, 1e-6);
        }
    }
}
