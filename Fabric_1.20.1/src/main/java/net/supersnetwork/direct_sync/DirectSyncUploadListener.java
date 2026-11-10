package net.supersnetwork.direct_sync;

import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.status.ClientboundStatusResponsePacket;
import net.minecraft.network.protocol.status.ServerStatus;
import net.minecraft.network.protocol.status.ServerStatusPacketListener;
import net.minecraft.network.protocol.status.ServerboundPingRequestPacket;
import net.minecraft.network.protocol.status.ServerboundStatusRequestPacket;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Receives one bounded JAR and never moves it into an active mods directory. */
public final class DirectSyncUploadListener implements ServerStatusPacketListener {
    private static final Logger LOGGER = LoggerFactory.getLogger("Direct Sync");
    private final Connection connection;
    private final QuarantineUploads.Offer offer;
    private final Path pending;
    private final Path finalPath;
    private final OutputStream out;
    private long received;
    private boolean finished;

    DirectSyncUploadListener(Connection connection, Path folder, QuarantineUploads.Offer offer) throws IOException {
        this.connection = connection;
        this.offer = offer;
        String basename = offer.modId() + "-" + offer.token();
        pending = folder.resolve(basename + ".upload.jar");
        finalPath = folder.resolve(basename + ".jar");
        out = Files.newOutputStream(pending, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
    }

    void receive(byte[] bytes) {
        if (finished) return;
        try {
            if (bytes.length < 1 || bytes.length > DirectSyncUploadPacket.MAX_CHUNK
                    || bytes.length > offer.size() - received) throw new IOException("Upload exceeds offer");
            out.write(bytes);
            received += bytes.length;
            if (received != offer.size()) return;
            out.close();
            if (!QuarantineUploads.verify(pending, offer)) throw new IOException("Uploaded JAR failed verification");
            try {
                Files.move(pending, finalPath, StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException e) {
                Files.move(pending, finalPath);
            }
            finished = true;
            QuarantineUploads.announce(offer, finalPath);
            respond("directsync-upload/1|quarantined");
        } catch (Exception e) {
            LOGGER.warn("Rejected client upload for {}: {}", offer.modId(), e.toString());
            finished = true;
            try { out.close(); } catch (IOException ignored) {}
            try { Files.deleteIfExists(pending); } catch (IOException ignored) {}
            respond("directsync-upload/1|rejected");
        }
    }

    private void respond(String text) {
        ServerStatus status = new ServerStatus(Component.literal(text), Optional.empty(),
                Optional.empty(), Optional.empty(), false);
        connection.send(new ClientboundStatusResponsePacket(status));
    }

    @Override public void handleStatusRequest(ServerboundStatusRequestPacket packet) {
        respond("directsync-upload/1|rejected");
    }
    @Override public void handlePingRequest(ServerboundPingRequestPacket packet) {}
    @Override public boolean isAcceptingMessages() { return connection.isConnected(); }
    @Override public void onDisconnect(Component reason) {
        if (finished) return;
        try { out.close(); } catch (IOException ignored) {}
        try { Files.deleteIfExists(pending); } catch (IOException ignored) {}
    }
}
