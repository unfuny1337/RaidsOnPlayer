package com.raidsonplayer;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static com.raidsonplayer.Role.*;

/** Who goes into which squad, per stage (prompt2.txt). Pure Java so it can be unit tested. */
final class Composition {
    static final int[] GARRISON_CAP = {0, 4, 6, 8, 10};

    private Composition() {}

    /** Camp garrison at full strength. The first raider is the watchman; the artilleryman stays home on raids. */
    static List<Role> garrison(int stage) {
        List<Role> r = new ArrayList<>(List.of(LEADER, RAIDER, MINER));
        if (stage >= 2) r.add(DEMOMAN);
        if (stage >= 3) r.add(ARTILLERYMAN);
        if (stage >= 4) r.add(MINER);
        int size = GARRISON_CAP[stage];
        while (r.size() < size) r.add(RAIDER);
        return r;
    }

    /**
     * Night raid squads. Stage 1: 2 squads of 4 (leader, miner); stage 2: 3-4 of 5 (leader, miner); stage 3: 4 of 6
     * (leader, 2 miners, artilleryman); stage 4: 4 of 7 (same). Free slots are raiders, each one a demoman by `demoChance` from stage 2.
     */
    static List<List<Role>> night(int stage, Random rnd, double demoChance) {
        int squads = switch (stage) { case 1 -> 2; case 2 -> 3 + rnd.nextInt(2); default -> 4; };
        List<List<Role>> out = new ArrayList<>();
        for (int i = 0; i < squads; i++) out.add(squad(stage, rnd, demoChance));
        return out;
    }

    /** One assault squad of stage + 3 (the night raid squad above; full-sized raid waves use it too). */
    static List<Role> squad(int stage, Random rnd, double demoChance) {
        List<Role> s = new ArrayList<>(List.of(LEADER, MINER));
        if (stage >= 3) { s.add(MINER); s.add(ARTILLERYMAN); }
        while (s.size() < stage + 3) s.add(stage >= 2 && rnd.nextDouble() < demoChance ? DEMOMAN : RAIDER);
        return s;
    }

    /** Full-sized raid: stage + 1 waves. */
    static int waves(int stage) { return stage + 1; }

    /** Squads in wave `w` (1-based): one in the first half, two after that, three in the last wave from stage 3. */
    static int waveSquads(int stage, int w) {
        int n = waves(stage);
        return w == n && stage >= 3 ? 3 : w > n / 2 ? 2 : 1;
    }

    /** Day patrol: a leader and 2-3 raiders. */
    static List<Role> patrol(Random rnd) {
        List<Role> s = new ArrayList<>(List.of(LEADER, RAIDER, RAIDER));
        if (rnd.nextBoolean()) s.add(RAIDER);
        return s;
    }

    /** The roles a garrison is missing compared to full strength (used for daily regrowth and rebuilds). */
    static List<Role> missing(List<Role> have, int stage) {
        List<Role> want = garrison(stage);
        for (Role r : have) want.remove(r);
        return want;
    }
}
