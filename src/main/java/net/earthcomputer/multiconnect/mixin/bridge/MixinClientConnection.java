package net.earthcomputer.multiconnect.mixin.bridge;

import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import net.earthcomputer.multiconnect.api.ThreadSafe;
import net.earthcomputer.multiconnect.impl.DebugUtils;
import net.earthcomputer.multiconnect.impl.TestingAPI;
import net.earthcomputer.multiconnect.protocols.generic.CustomPayloadHandler;
import net.earthcomputer.multiconnect.protocols.generic.MulticonnectClientboundTranslator;
import net.earthcomputer.multiconnect.protocols.generic.MulticonnectServerboundTranslator;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.network.ClientConnection;
import net.minecraft.network.NetworkState;
import net.minecraft.network.Packet;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.network.packet.c2s.play.CustomPayloadC2SPacket;
import org.apache.logging.log4j.LogManager;
import org.jetbrains.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(ClientConnection.class)
public abstract class MixinClientConnection {

    @Shadow private Channel channel;

    @Shadow private PacketListener packetListener;

    @Inject(method = "setState", at = @At("HEAD"))
    private void onSetState(NetworkState state, CallbackInfo ci) {
        // Singleplayer doesnt include encoding
        if (state == NetworkState.PLAY && !MinecraftClient.getInstance().isIntegratedServerRunning() && !DebugUtils.SKIP_TRANSLATION) {
            channel.pipeline().addBefore("encoder", "multiconnect_serverbound_translator", new MulticonnectServerboundTranslator());
            channel.pipeline().addBefore("decoder", "multiconnect_clientbound_translator", new MulticonnectClientboundTranslator());
        } else {
            if (channel.pipeline().context("multiconnect_serverbound_translator") != null) {
                channel.pipeline().remove("multiconnect_serverbound_translator");
            }
            if (channel.pipeline().context("multiconnect_clientbound_translator") != null) {
                channel.pipeline().remove("multiconnect_clientbound_translator");
            }
        }
    }

    @Inject(method = "exceptionCaught", at = @At("HEAD"))
    @ThreadSafe
    public void onExceptionCaught(ChannelHandlerContext context, Throwable t, CallbackInfo ci) {
        if (DebugUtils.isUnexpectedDisconnect(t) && channel.isOpen()) {
            TestingAPI.onUnexpectedDisconnect(t);
            LogManager.getLogger("multiconnect").error("Unexpectedly disconnected from server!", t);
        }
    }

    // TODO: move this to the network layer
    @Inject(method = "send(Lnet/minecraft/network/Packet;Lio/netty/util/concurrent/GenericFutureListener;)V", at = @At("HEAD"), cancellable = true)
    public void onSend(Packet<?> packet, @Nullable GenericFutureListener<? extends Future<? super Void>> callback, CallbackInfo ci) {
        if (packet instanceof CustomPayloadC2SPacket customPayload
                && !customPayload.getChannel().equals(CustomPayloadC2SPacket.BRAND)) {
            if (packetListener instanceof ClientPlayNetworkHandler networkHandler) {
                PacketByteBuf dataBuf = customPayload.getData();
                byte[] data = new byte[dataBuf.readableBytes()];
                dataBuf.readBytes(data);
                CustomPayloadHandler.handleServerboundCustomPayload(networkHandler, customPayload.getChannel(), data);
            }
            ci.cancel();
        }
    }
}
