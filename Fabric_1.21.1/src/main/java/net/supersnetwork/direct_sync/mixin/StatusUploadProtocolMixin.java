package net.supersnetwork.direct_sync.mixin;

import net.minecraft.network.protocol.ProtocolInfoBuilder;
import net.minecraft.network.protocol.status.StatusProtocols;
import net.supersnetwork.direct_sync.DirectSyncUploadPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(StatusProtocols.class)
public abstract class StatusUploadProtocolMixin {
    @Inject(method = "method_56030", at = @At("TAIL"), remap = false)
    private static void directsync$registerUpload(ProtocolInfoBuilder<?, ?> builder, CallbackInfo ci) {
        ((ProtocolInfoBuilder) builder).addPacket(DirectSyncUploadPacket.TYPE, DirectSyncUploadPacket.STREAM_CODEC);
    }
}
