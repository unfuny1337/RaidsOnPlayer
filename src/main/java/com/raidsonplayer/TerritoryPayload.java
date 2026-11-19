package com.raidsonplayer;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.List;

/** Server -> client: where raider land is (every camp in the overworld and how far its land reaches), for map mods. */
public record TerritoryPayload(int radius, List<Camp> camps) implements CustomPacketPayload {
    public record Camp(int x, int z, boolean empty) {
        static final StreamCodec<ByteBuf, Camp> CODEC = StreamCodec.composite(
                ByteBufCodecs.VAR_INT, Camp::x, ByteBufCodecs.VAR_INT, Camp::z, ByteBufCodecs.BOOL, Camp::empty, Camp::new);
    }

    public static final Type<TerritoryPayload> TYPE = new Type<>(ResourceLocation.fromNamespaceAndPath(RaidsOnPlayer.MODID, "territory"));
    public static final StreamCodec<ByteBuf, TerritoryPayload> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, TerritoryPayload::radius, Camp.CODEC.apply(ByteBufCodecs.list()), TerritoryPayload::camps, TerritoryPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
