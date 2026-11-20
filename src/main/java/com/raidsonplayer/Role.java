package com.raidsonplayer;

/** Raider classes. A squad is a list of these; each live raider entity has one. */
public enum Role {
    RAIDER, MINER, LEADER, ARTILLERYMAN, DEMOMAN, SCOUT,
    /** Flies a raider airship (Airships), stage 3+, full-sized raids only; never part of a squad. */
    PILOT;

    static Role of(int i) { return i >= 0 && i < values().length ? values()[i] : RAIDER; }
}
