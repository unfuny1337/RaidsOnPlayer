package com.raidsonplayer;

/** Raider classes. A squad is a list of these; each live raider entity has one. */
public enum Role {
    RAIDER, MINER, LEADER, ARTILLERYMAN, DEMOMAN, SCOUT;

    static Role of(int i) { return i >= 0 && i < values().length ? values()[i] : RAIDER; }
}
