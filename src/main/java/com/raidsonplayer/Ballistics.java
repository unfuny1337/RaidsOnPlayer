package com.raidsonplayer;

/**
 * Aiming for Create Big Cannons HE shells, using CBC's own per-tick physics
 * (data/createbigcannons/munition_properties/projectiles/he_shell.json: gravity -0.05, linear drag 0.01 * speed).
 * Plain doubles so it can be unit tested without Minecraft.
 */
final class Ballistics {
    static final double GRAVITY = -0.05, DRAG = 0.01;

    private Ballistics() {}

    /** Position after `ticks` ticks from (x,y,z) with velocity v. Returns {x,y,z}. */
    static double[] simulate(double x, double y, double z, double[] v, int ticks) {
        double vx = v[0], vy = v[1], vz = v[2];
        for (int t = 0; t < ticks; t++) {
            x += vx; y += vy; z += vz;
            vx -= vx * DRAG;
            vy += GRAVITY - vy * DRAG;
            vz -= vz * DRAG;
        }
        return new double[]{x, y, z};
    }

    /** Initial velocity that lands at `to` exactly `ticks` ticks after leaving `from`. The model is linear, so correcting the miss converges fast. */
    static double[] solve(double[] from, double[] to, int ticks) {
        double[] v = {(to[0] - from[0]) / ticks, (to[1] - from[1]) / ticks - GRAVITY * ticks / 2, (to[2] - from[2]) / ticks};
        for (int i = 0; i < 30; i++) {
            double[] end = simulate(from[0], from[1], from[2], v, ticks);
            double mx = to[0] - end[0], my = to[1] - end[1], mz = to[2] - end[2];
            if (mx * mx + my * my + mz * mz < 1e-4) break;
            v[0] += mx / ticks; v[1] += my / ticks; v[2] += mz / ticks;
        }
        return v;
    }
}
