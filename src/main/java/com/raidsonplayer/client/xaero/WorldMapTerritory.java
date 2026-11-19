package com.raidsonplayer.client.xaero;

import com.raidsonplayer.client.ClientTerritory;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import xaero.map.WorldMapSession;
import xaero.map.highlight.ChunkHighlighter;

import java.util.List;

/** Raider land on Xaero's World Map (registered by WorldMapRegistryMixin; only loaded when the World Map is installed). */
public class WorldMapTerritory extends ChunkHighlighter {
    public WorldMapTerritory() { super(false); } // only over map the player has explored

    /** Land changed: drop the cached region hashes so changed regions are redrawn. */
    public static void refresh() {
        WorldMapSession s = WorldMapSession.getCurrentSession();
        if (s != null && s.getMapProcessor() != null && s.getMapProcessor().getMapWorld() != null)
            s.getMapProcessor().getMapWorld().clearAllCachedHighlightHashes();
    }

    @Override public int calculateRegionHash(ResourceKey<Level> dim, int rx, int rz) { return dim == Level.OVERWORLD ? ClientTerritory.regionHash(rx, rz) : 0; }
    @Override public boolean regionHasHighlights(ResourceKey<Level> dim, int rx, int rz) { return dim == Level.OVERWORLD && ClientTerritory.inRegion(rx, rz); }
    @Override public boolean chunkIsHighlit(ResourceKey<Level> dim, int cx, int cz) { return dim == Level.OVERWORLD && ClientTerritory.campAt(cx, cz) != null; }
    @Override protected int[] getColors(ResourceKey<Level> dim, int cx, int cz) { return dim == Level.OVERWORLD ? ClientTerritory.colors(cx, cz) : null; }
    @Override public Component getChunkHighlightSubtleTooltip(ResourceKey<Level> dim, int cx, int cz) { return dim == Level.OVERWORLD ? ClientTerritory.tooltip(cx, cz) : null; }
    @Override public Component getChunkHighlightBluntTooltip(ResourceKey<Level> dim, int cx, int cz) { return null; }
    @Override public void addMinimapBlockHighlightTooltips(List<Component> list, ResourceKey<Level> dim, int x, int z, int width) { }
}
