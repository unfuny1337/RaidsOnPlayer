package com.raidsonplayer.client.xaero;

import com.raidsonplayer.client.ClientTerritory;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import xaero.common.XaeroMinimapSession;
import xaero.common.minimap.highlight.ChunkHighlighter;
import xaero.common.minimap.highlight.DimensionHighlighterHandler;
import xaero.hud.minimap.info.render.compile.InfoDisplayCompiler;

/** Raider land on Xaero's Minimap (registered by MinimapRegistryMixin; only loaded when the Minimap is installed). */
public class MinimapTerritory extends ChunkHighlighter {
    public MinimapTerritory() { super(false); } // only over map the player has explored

    /** Land changed: have the minimap redraw its highlights. */
    public static void refresh() {
        XaeroMinimapSession s = XaeroMinimapSession.getCurrentSession();
        if (s == null || s.getMinimapProcessor() == null || s.getMinimapProcessor().getMinimapWriter() == null) return;
        DimensionHighlighterHandler h = s.getMinimapProcessor().getMinimapWriter().getDimensionHighlightHandler();
        if (h != null) h.requestRefresh();
    }

    @Override public boolean regionHasHighlights(ResourceKey<Level> dim, int rx, int rz) { return dim == Level.OVERWORLD && ClientTerritory.inRegion(rx, rz); }
    @Override public boolean chunkIsHighlit(ResourceKey<Level> dim, int cx, int cz) { return dim == Level.OVERWORLD && ClientTerritory.campAt(cx, cz) != null; }
    @Override protected int[] getColors(ResourceKey<Level> dim, int cx, int cz) { return dim == Level.OVERWORLD ? ClientTerritory.colors(cx, cz) : null; }

    @Override
    public void addChunkHighlightTooltips(InfoDisplayCompiler out, ResourceKey<Level> dim, int cx, int cz, int width) {
        Component t = dim == Level.OVERWORLD ? ClientTerritory.tooltip(cx, cz) : null;
        if (t != null) out.addLine(t);
    }
}
