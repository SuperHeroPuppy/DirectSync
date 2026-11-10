package net.supersnetwork.direct_sync;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Talks to Direct Sync through Minecraft's status protocol, before login and registry sync. */
final class MinecraftStatusSync {
    private static final int MINECRAFT_PROTOCOL = 763;
    private static final int MAX_PACKET = 64 * 1024;
    private static final Pattern TEXT = Pattern.compile("\\\"text\\\"\\s*:\\s*\\\"(directsync[^\\\"]*)\\\"");

    private MinecraftStatusSync() {}

    static List<SyncProtocol.ModFile> fetchManifest(String host, int port) throws IOException {
        byte[] bytes = fetchResource(host, port, "manifest", SyncProtocol.MAX_MANIFEST_BYTES);
        return SyncProtocol.parseManifest(new String(bytes, StandardCharsets.UTF_8));
    }

    static byte[] fetchResource(String host, int port, String resource, long limit) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        transfer(host, port, resource, limit, bytes, null);
        return bytes.toByteArray();
    }

    static void download(String host, int port, SyncProtocol.ModFile entry, OutputStream out) throws IOException {
        download(host, port, entry, out, null);
    }

    static void download(String host, int port, SyncProtocol.ModFile entry, OutputStream out,
                         java.util.function.LongConsumer progress) throws IOException {
        transfer(host, port, "jar:" + entry.name(), entry.size(), out, progress);
    }

    static void uploadNewer(String host, int port, Path jar, ModMetadata.Info info) throws IOException {
        long size = Files.size(jar);
        if (size < 1 || size > SyncProtocol.MAX_JAR_BYTES) throw new IOException("Client JAR is too large");
        String hash = SyncProtocol.sha256(jar);
        String version = Base64.getUrlEncoder().withoutPadding().encodeToString(
                info.version().getBytes(StandardCharsets.UTF_8));
        String request = "offer:" + info.modId() + ":" + version + ":" + size + ":" + hash;
        String offer = new String(fetchResource(host, port, request, 128), StandardCharsets.UTF_8);
        if (!offer.startsWith("directsync-offer/1|")) throw new IOException("Server did not request the newer client JAR");
        String token = offer.substring("directsync-offer/1|".length());
        if (!token.matches("[A-Za-z0-9_-]{32}")) throw new IOException("Server rejected the newer client JAR");
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(30000);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream wire = new DataOutputStream(socket.getOutputStream());
            ByteArrayOutputStream handshake = new ByteArrayOutputStream();
            DataOutputStream packet = new DataOutputStream(handshake);
            writeVarInt(packet, 0);
            writeVarInt(packet, MINECRAFT_PROTOCOL);
            writeUtf(packet, DirectSyncStatusListener.PREFIX + "upload:" + token);
            packet.writeShort(port);
            writeVarInt(packet, 1);
            send(wire, handshake.toByteArray());
            try (InputStream jarIn = Files.newInputStream(jar)) {
                byte[] buffer = new byte[DirectSyncUploadPacket.MAX_CHUNK];
                long written = 0;
                int count;
                while ((count = jarIn.read(buffer)) >= 0) {
                    if (count == 0) continue;
                    ByteArrayOutputStream chunk = new ByteArrayOutputStream(count + 2);
                    DataOutputStream body = new DataOutputStream(chunk);
                    writeVarInt(body, 2);
                    body.write(buffer, 0, count);
                    send(wire, chunk.toByteArray());
                    written += count;
                    if (written > size) throw new IOException("Client JAR changed during upload");
                }
                if (written != size) throw new IOException("Client JAR changed during upload");
            }
            if (!"directsync-upload/1|quarantined".equals(response(in))) {
                throw new IOException("Server rejected the client JAR");
            }
        }
    }

    private static void transfer(String host, int port, String resource, long limit, OutputStream out,
                                 java.util.function.LongConsumer progress) throws IOException {
        String requestHost = DirectSyncStatusListener.PREFIX + resource;
        if (requestHost.length() > 255) throw new IOException("Direct Sync request is too long");
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(host, port), 5000);
            socket.setSoTimeout(15000);
            DataInputStream in = new DataInputStream(socket.getInputStream());
            DataOutputStream wire = new DataOutputStream(socket.getOutputStream());

            ByteArrayOutputStream handshake = new ByteArrayOutputStream();
            DataOutputStream packet = new DataOutputStream(handshake);
            writeVarInt(packet, 0);
            writeVarInt(packet, MINECRAFT_PROTOCOL);
            writeUtf(packet, requestHost);
            packet.writeShort(port);
            writeVarInt(packet, 1); // STATUS
            send(wire, handshake.toByteArray());
            send(wire, new byte[] { 0 }); // status request

            long position = 0;
            long expected = -1;
            do {
                String payload = response(in);
                String[] fields = payload.split("\\|", 4);
                if (fields.length != 4 || !"directsync/4".equals(fields[0])) {
                    throw new IOException("Server does not support Direct Sync status transfer");
                }
                long total;
                long offset;
                try {
                    total = Long.parseLong(fields[1]);
                    offset = Long.parseLong(fields[2]);
                } catch (NumberFormatException e) {
                    throw new IOException("Invalid Direct Sync transfer header", e);
                }
                if (total < 0 || total > limit || offset != position || (expected >= 0 && total != expected)) {
                    throw new IOException("Invalid Direct Sync transfer length or offset");
                }
                expected = total;
                byte[] chunk;
                try {
                    chunk = Base64.getUrlDecoder().decode(fields[3]);
                } catch (IllegalArgumentException e) {
                    throw new IOException("Invalid Direct Sync transfer data", e);
                }
                if (chunk.length > DirectSyncStatusListener.CHUNK_SIZE || chunk.length > total - position
                        || (chunk.length == 0 && position < total)) {
                    throw new IOException("Invalid Direct Sync chunk size");
                }
                out.write(chunk);
                position += chunk.length;
                if (progress != null) progress.accept(position);
                if (position < total) {
                    ByteArrayOutputStream ping = new ByteArrayOutputStream();
                    DataOutputStream pingPacket = new DataOutputStream(ping);
                    writeVarInt(pingPacket, 1);
                    pingPacket.writeLong(position);
                    send(wire, ping.toByteArray());
                }
            } while (position < expected);
        }
    }

    private static String response(DataInputStream wire) throws IOException {
        int length = readVarInt(wire);
        if (length < 1 || length > MAX_PACKET) throw new IOException("Invalid Minecraft status packet length");
        byte[] frame = wire.readNBytes(length);
        if (frame.length != length) throw new IOException("Truncated Minecraft status packet");
        DataInputStream packet = new DataInputStream(new ByteArrayInputStream(frame));
        if (readVarInt(packet) != 0) throw new IOException("Unexpected Minecraft status packet");
        int jsonLength = readVarInt(packet);
        if (jsonLength < 1 || jsonLength > MAX_PACKET || jsonLength > packet.available()) {
            throw new IOException("Invalid Minecraft status response length");
        }
        byte[] json = packet.readNBytes(jsonLength);
        Matcher text = TEXT.matcher(new String(json, StandardCharsets.UTF_8));
        if (!text.find()) throw new IOException("Server does not support Direct Sync status transfer");
        return text.group(1);
    }

    private static void send(DataOutputStream wire, byte[] packet) throws IOException {
        writeVarInt(wire, packet.length);
        wire.write(packet);
        wire.flush();
    }

    private static void writeUtf(DataOutputStream out, String text) throws IOException {
        byte[] bytes = text.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.write(bytes);
    }

    private static void writeVarInt(DataOutputStream out, int value) throws IOException {
        while ((value & ~0x7f) != 0) {
            out.writeByte(value & 0x7f | 0x80);
            value >>>= 7;
        }
        out.writeByte(value);
    }

    private static int readVarInt(DataInputStream in) throws IOException {
        int value = 0;
        for (int i = 0; i < 5; i++) {
            int next = in.readUnsignedByte();
            value |= (next & 0x7f) << (i * 7);
            if ((next & 0x80) == 0) return value;
        }
        throw new IOException("Minecraft VarInt is too long");
    }
}
