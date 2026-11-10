package net.supersnetwork.direct_sync;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.network.protocol.PacketType;
import net.minecraft.network.protocol.status.ServerStatusPacketListener;
import net.minecraft.resources.ResourceLocation;

/** Serverbound status packet ID 2, reserved by Direct Sync for authorized uploads. */
public final class DirectSyncUploadPacket implements Packet<ServerStatusPacketListener> {
    public static final int MAX_CHUNK = 16 * 1024;
    public static final PacketType<DirectSyncUploadPacket> TYPE = new PacketType<>(PacketFlow.SERVERBOUND,
            ResourceLocation.fromNamespaceAndPath("directsync", "upload"));
    public static final StreamCodec<ByteBuf, DirectSyncUploadPacket> STREAM_CODEC =
            StreamCodec.of((buf, packet) -> buf.writeBytes(packet.bytes), DirectSyncUploadPacket::new);
    private final byte[] bytes;

    private DirectSyncUploadPacket(ByteBuf buf) {
        if (buf.readableBytes() < 1 || buf.readableBytes() > MAX_CHUNK) {
            throw new IllegalArgumentException("Invalid Direct Sync upload chunk");
        }
        bytes = new byte[buf.readableBytes()];
        buf.readBytes(bytes);
    }

    @Override public PacketType<DirectSyncUploadPacket> type() { return TYPE; }

    @Override
    public void handle(ServerStatusPacketListener listener) {
        if (!(listener instanceof DirectSyncUploadListener upload)) {
            throw new IllegalStateException("Unrequested Direct Sync upload");
        }
        upload.receive(bytes);
    }
}
