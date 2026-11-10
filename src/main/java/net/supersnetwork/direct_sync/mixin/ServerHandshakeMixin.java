package net.supersnetwork.direct_sync.mixin;

import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.handshake.ClientIntentionPacket;
import net.minecraft.server.network.ServerHandshakePacketListenerImpl;
import net.supersnetwork.direct_sync.DirectSyncMod;
import net.supersnetwork.direct_sync.DirectSyncStatusListener;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import java.io.IOException;

@Mixin(ServerHandshakePacketListenerImpl.class)
public abstract class ServerHandshakeMixin {
    @Shadow @Final private Connection connection;

    @Inject(method = "handleIntention", at = @At("HEAD"), cancellable = true)
    private void directsync$statusTransfer(ClientIntentionPacket packet, CallbackInfo ci) {
        if (packet.getIntention() != ConnectionProtocol.STATUS
                || !packet.getHostName().startsWith(DirectSyncStatusListener.PREFIX)) return;
        String request = packet.getHostName().substring(DirectSyncStatusListener.PREFIX.length());
        connection.setProtocol(ConnectionProtocol.STATUS);
        if (request.startsWith("upload:")) {
            try {
                connection.setListener(DirectSyncMod.openUpload(connection, request.substring(7)));
            } catch (IOException e) {
                connection.disconnect(Component.literal("Invalid Direct Sync upload offer"));
            }
        } else {
            connection.setListener(DirectSyncMod.openStatusTransfer(connection, request));
        }
        ci.cancel();
    }
}
