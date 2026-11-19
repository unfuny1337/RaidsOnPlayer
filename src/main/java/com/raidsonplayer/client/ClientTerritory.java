package com.raidsonplayer.client;

import com.raidsonplayer.TerritoryPayload;
import com.raidsonplayer.TerritoryPayload.Camp;
import com.raidsonplayer.client.xaero.MinimapTerritory;
import com.raidsonplayer.client.xaero.WorldMapTerritory;
import net.minecraft.network.chat.Component;
import net.neoforged.fml.ModList;

import javax.annotation.Nullable;
import java.util.List;

/**
 * The client's copy of raider land (sent by the server whenever it changes) and the queries the map highlighters ask.
 * Common classes only, so the payload handler can name it on a dedicated server.
 */
public final class ClientTerritory {
    private static int radius;
    private static List<Camp> camps = List.of();
    private static final int[] COLORS = new int[5];

    private ClientTerritory() {}

    public static void set(TerritoryPayload p) {
        radius = p.radius();
        camps = p.camps();
        if (ModList.get().isLoaded("xaeroworldmap")) WorldMapTerritory.refresh();
        if (ModList.get().isLoaded("xaerominimap")) MinimapTerritory.refresh();
    }

    /** The camp whose land the chunk's centre is on (the nearest within reach), or null. */
    @Nullable public static Camp campAt(int cx, int cz) {
        double x = cx * 16 + 8, z = cz * 16 + 8, best = (double) radius * radius;
        Camp found = null;
        for (Camp c : camps) {
            double d = (c.x() - x) * (c.x() - x) + (c.z() - z) * (c.z() - z);
            if (d < best) { best = d; found = c; }
        }
        return found;
    }

    /** Any raider land in map region (rx, rz): 32x32 chunks, 512 blocks, as both Xaero maps count them. */
    public static boolean inRegion(int rx, int rz) {
        for (Camp c : camps) if (touches(c, rx, rz)) return true;
        return false;
    }

    /** Changes whenever the land in this region does, so the world map knows to redraw it. */
    public static int regionHash(int rx, int rz) {
        int h = radius;
        for (Camp c : camps) if (touches(c, rx, rz)) h = 31 * h + c.hashCode();
        return h;
    }

    private static boolean touches(Camp c, int rx, int rz) {
        double nx = Math.clamp(c.x(), rx * 512, rx * 512 + 511), nz = Math.clamp(c.z(), rz * 512, rz * 512 + 511); // nearest point of the region
        return (c.x() - nx) * (c.x() - nx) + (c.z() - nz) * (c.z() - nz) < (double) (radius + 16) * (radius + 16);
    }

    /**
     * Xaero's chunk colours: centre, then the north, east, south and west edges, as 0xBBGGRRAA. Land is tinted red
     * (orange around an empty camp a player can take), with a stronger line where raider land ends. Null off their land.
     */
    @Nullable public static int[] colors(int cx, int cz) {
        Camp c = campAt(cx, cz);
        if (c == null) return null;
        int rgb = c.empty() ? 0xFF9900 : 0xDD2222;
        int bgr = (rgb & 255) << 24 | (rgb >> 8 & 255) << 16 | (rgb >> 16 & 255) << 8, fill = bgr | 0x50, edge = bgr | 0xD0;
        COLORS[0] = fill;
        COLORS[1] = campAt(cx, cz - 1) == null ? edge : fill;
        COLORS[2] = campAt(cx + 1, cz) == null ? edge : fill;
        COLORS[3] = campAt(cx, cz + 1) == null ? edge : fill;
        COLORS[4] = campAt(cx - 1, cz) == null ? edge : fill;
        return COLORS;
    }

    @Nullable public static Component tooltip(int cx, int cz) {
        Camp c = campAt(cx, cz);
        return c == null ? null : Component.literal(c.empty() ? "Raider land (empty camp: hold it to take it)" : "Raider land");
    }
}
