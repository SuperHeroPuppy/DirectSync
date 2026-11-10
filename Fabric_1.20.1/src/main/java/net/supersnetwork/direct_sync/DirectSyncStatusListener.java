package net.supersnetwork.direct_sync;

import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.status.ClientboundStatusResponsePacket;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.network.protocol.status.ServerStatusPacketListener;
import net.minecraft.network.protocol.status.ServerboundPingRequestPacket;
import net.minecraft.network.protocol.status.ServerboundStatusRequestPacket;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.Optional;

/** A small, read-only transfer over the already published Minecraft status TCP port. */
public final class DirectSyncStatusListener implements ServerStatusPacketListener {
    public static final String PREFIX = "directsync:4:";
    public static final int CHUNK_SIZE = 16 * 1024;

    private final Connection connection;
    private final byte[] memory;
    private final FileChannel file;
    private final long size;
    private boolean started;

    public DirectSyncStatusListener(Connection connection, byte[] memory) {
        this.connection = connection;
        this.memory = memory;
        this.file = null;
        this.size = memory.length;
    }

    public DirectSyncStatusListener(Connection connection, Path path, long size) throws IOException {
        this.connection = connection;
        this.memory = null;
        this.file = FileChannel.open(path, StandardOpenOption.READ);
        this.size = size;
        if (file.size() != size) {
            file.close();
            throw new IOException("Shared JAR changed; restart the server");
        }
    }

    @Override
    public void handleStatusRequest(ServerboundStatusRequestPacket packet) {
        if (started) return;
        started = true;
        sendChunk(0);
    }

    @Override
    public void handlePingRequest(ServerboundPingRequestPacket packet) {
        if (started) sendChunk(packet.getTime());
    }

    private void sendChunk(long offset) {
        if (offset < 0 || offset > size) {
            connection.disconnect(Component.literal("Invalid Direct Sync offset"));
            return;
        }
        try {
            int count = (int) Math.min(CHUNK_SIZE, size - offset);
            byte[] bytes = new byte[count];
            if (memory != null) {
                System.arraycopy(memory, (int) offset, bytes, 0, count);
            } else {
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                long position = offset;
                while (buffer.hasRemaining()) {
                    int read = file.read(buffer, position);
                    if (read <= 0) throw new IOException("Shared JAR changed during transfer");
                    position += read;
                }
            }
            String payload = "directsync/4|" + size + "|" + offset + "|" + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            ServerStatus status = new ServerStatus(Component.literal(payload), Optional.empty(),
                    Optional.empty(), Optional.empty(), false);
            connection.send(new ClientboundStatusResponsePacket(status));
        } catch (IOException e) {
            connection.disconnect(Component.literal("Direct Sync transfer failed"));
        }
    }

    @Override
    public void onDisconnect(Component reason) {
        if (file != null) {
            try { file.close(); } catch (IOException ignored) {}
        }
    }

    @Override
    public boolean isAcceptingMessages() {
        return connection.isConnected();
    }
}
