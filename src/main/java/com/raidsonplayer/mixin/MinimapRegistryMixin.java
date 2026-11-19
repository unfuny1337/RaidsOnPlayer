package com.raidsonplayer.mixin;

import com.raidsonplayer.client.xaero.MinimapTerritory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.common.minimap.highlight.HighlighterRegistry;

/** Same as WorldMapRegistryMixin, for the Minimap's own highlighter list. Skipped without the Minimap. */
@Pseudo
@Mixin(targets = "xaero.common.minimap.highlight.HighlighterRegistry", remap = false)
public class MinimapRegistryMixin {
    @Inject(method = "end", at = @At("HEAD"), remap = false)
    private void raidsonplayer$territory(CallbackInfo ci) {
        ((HighlighterRegistry) (Object) this).register(new MinimapTerritory());
    }
}
