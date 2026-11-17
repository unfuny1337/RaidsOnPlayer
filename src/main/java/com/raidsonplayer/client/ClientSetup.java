package com.raidsonplayer.client;

import com.raidsonplayer.RaidsOnPlayer;
import net.minecraft.client.renderer.entity.PillagerRenderer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

@EventBusSubscriber(modid = RaidsOnPlayer.MODID, value = Dist.CLIENT)
public final class ClientSetup {
    @SubscribeEvent
    static void renderers(EntityRenderersEvent.RegisterRenderers e) {
        e.registerEntityRenderer(RaidsOnPlayer.RAIDER.get(), PillagerRenderer::new);
    }
}
