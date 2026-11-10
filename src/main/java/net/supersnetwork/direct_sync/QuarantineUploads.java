package net.supersnetwork.direct_sync;

import net.fabricmc.loader.api.Version;
import net.minecraft.network.Connection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;

/** Offers authorize one upload into a folder Fabric does not scan. */
final class QuarantineUploads {
    private static final Logger LOGGER = LoggerFactory.getLogger("Direct Sync");
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final long LIFETIME_MS = 5 * 60 * 1000;
    private static final long MAX_QUARANTINE_BYTES = 512L * 1024 * 1024;
    private final Path folder;
    private final Map<String, ModMetadata.Info> serverInfo;
    private final Map<String, Offer> offers = new HashMap<>();

    record Offer(String token, String modId, String version, long size, String sha256,
                 String remoteAddress, long expiresAt) {}

    QuarantineUploads(Path modsDir, Map<String, ModMetadata.Info> serverInfo) throws IOException {
        folder = modsDir.resolve(".directsync-quarantine");
        Files.createDirectories(folder);
        if (Files.isSymbolicLink(folder) || !Files.isDirectory(folder)) {
            throw new IOException("Invalid Direct Sync quarantine directory");
        }
        this.serverInfo = serverInfo;
    }

    synchronized String offer(Connection connection, String request) {
        try {
            String[] fields = request.split(":", -1);
            if (fields.length != 5 || !"offer".equals(fields[0])) throw new IOException("Invalid offer");
            String id = fields[1];
            String version = new String(Base64.getUrlDecoder().decode(fields[2]), StandardCharsets.UTF_8);
            long size = Long.parseLong(fields[3]);
            String hash = fields[4];
            if (!id.matches("[a-z][a-z0-9_-]{1,63}") || version.length() > 64 || version.isBlank()
                    || size < 1 || size > SyncProtocol.MAX_JAR_BYTES || !hash.matches("[0-9a-f]{64}")) {
                throw new IOException("Invalid offer metadata");
            }
            ModMetadata.Info current = serverInfo.values().stream()
                    .filter(info -> id.equals(info.modId())).findFirst().orElse(null);
            if (current == null || Version.parse(version).compareTo(Version.parse(current.version())) <= 0) {
                throw new IOException("Client version is not newer than this server mod");
            }
            long now = System.currentTimeMillis();
            offers.values().removeIf(offer -> offer.expiresAt() < now);
            if (offers.size() >= 32) throw new IOException("Upload queue is full");
            long stored = 0;
            try (var files = Files.list(folder)) {
                for (Path file : files.toList()) {
                    if (!Files.isRegularFile(file) || Files.isSymbolicLink(file)) continue;
                    stored += Files.size(file);
                }
            }
            long reserved = offers.values().stream().mapToLong(Offer::size).sum();
            if (stored + reserved + size > MAX_QUARANTINE_BYTES) {
                throw new IOException("Quarantine is full; owner must review existing uploads");
            }
            byte[] random = new byte[24];
            RANDOM.nextBytes(random);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(random);
            offers.put(token, new Offer(token, id, version, size, hash, remote(connection), now + LIFETIME_MS));
            return "directsync-offer/1|" + token;
        } catch (Exception e) {
            LOGGER.debug("Rejected client mod offer: {}", e.toString());
            return "directsync-offer/1|rejected";
        }
    }

    synchronized DirectSyncUploadListener open(Connection connection, String token) throws IOException {
        Offer offer = offers.remove(token);
        if (offer == null || offer.expiresAt() < System.currentTimeMillis()
                || !offer.remoteAddress().equals(remote(connection))) {
            throw new IOException("Unknown or expired upload token");
        }
        return new DirectSyncUploadListener(connection, folder, offer);
    }

    static String remote(Connection connection) {
        return connection.getRemoteAddress() instanceof InetSocketAddress address
                ? address.getAddress().getHostAddress() : String.valueOf(connection.getRemoteAddress());
    }

    static boolean verify(Path path, Offer offer) throws IOException {
        ModMetadata.Info info = ModMetadata.read(path);
        if (!info.modId().equals(offer.modId()) || !info.version().equals(offer.version())
                || Files.size(path) != offer.size()) return false;
        return SyncProtocol.sha256(path).equals(offer.sha256());
    }

    static void announce(Offer offer, Path path) {
        String message = "[Direct Sync] Newer client mod " + offer.modId() + " " + offer.version()
                + " is quarantined at " + path + ". Review it, install it manually, and restart the server.";
        LOGGER.warn(message);
        System.out.println("\u001b[1;33m" + message + "\u001b[0m");
    }
}
