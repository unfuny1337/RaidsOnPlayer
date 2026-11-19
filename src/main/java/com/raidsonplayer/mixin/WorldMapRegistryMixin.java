package com.raidsonplayer.mixin;

import com.raidsonplayer.client.xaero.WorldMapTerritory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import xaero.map.highlight.HighlighterRegistry;

/** The World Map builds its highlighter list per session and seals it with end(); add raider land just before. Skipped without the World Map. */
@Pseudo
@Mixin(targets = "xaero.map.highlight.HighlighterRegistry", remap = false)
public class WorldMapRegistryMixin {
    @Inject(method = "end", at = @At("HEAD"), remap = false)
    private void raidsonplayer$territory(CallbackInfo ci) {
        ((HighlighterRegistry) (Object) this).register(new WorldMapTerritory());
    }
}
