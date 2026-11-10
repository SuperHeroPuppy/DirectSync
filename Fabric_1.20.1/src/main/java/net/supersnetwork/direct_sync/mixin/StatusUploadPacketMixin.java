package net.supersnetwork.direct_sync.mixin;

import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.supersnetwork.direct_sync.DirectSyncUploadPacket;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ConnectionProtocol.class)
public abstract class StatusUploadPacketMixin {
    @Inject(method = "createPacket", at = @At("HEAD"), cancellable = true)
    private void directsync$upload(PacketFlow flow, int id, FriendlyByteBuf buf,
                                   CallbackInfoReturnable<Packet<?>> cir) {
        if ((Object) this == ConnectionProtocol.STATUS && flow == PacketFlow.SERVERBOUND && id == 2) {
            cir.setReturnValue(new DirectSyncUploadPacket(buf));
        }
    }
}
