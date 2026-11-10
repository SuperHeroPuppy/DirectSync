package net.supersnetwork.direct_sync;

import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.status.ServerStatusPacketListener;

/** Serverbound status packet ID 2, reserved by Direct Sync for authorized uploads. */
public final class DirectSyncUploadPacket implements Packet<ServerStatusPacketListener> {
    public static final int MAX_CHUNK = 16 * 1024;
    private final byte[] bytes;

    public DirectSyncUploadPacket(FriendlyByteBuf buf) {
        if (buf.readableBytes() < 1 || buf.readableBytes() > MAX_CHUNK) {
            throw new IllegalArgumentException("Invalid Direct Sync upload chunk");
        }
        bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
    }

    @Override
    public void write(FriendlyByteBuf buf) {
        buf.writeBytes(bytes);
    }

    @Override
    public void handle(ServerStatusPacketListener listener) {
        if (!(listener instanceof DirectSyncUploadListener upload)) {
            throw new IllegalStateException("Unrequested Direct Sync upload");
        }
        upload.receive(bytes);
    }
}
