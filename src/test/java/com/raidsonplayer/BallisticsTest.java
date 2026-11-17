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
}
