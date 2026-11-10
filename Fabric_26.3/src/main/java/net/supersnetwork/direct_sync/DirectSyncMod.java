package net.supersnetwork.direct_sync;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.fabricmc.fabric.api.networking.v1.ServerLoginConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerLoginNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.api.EnvType;
import net.minecraft.network.chat.Component;
import net.minecraft.network.Connection;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.jar.JarFile;

public final class DirectSyncMod implements ModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Direct Sync");
    static final Identifier LOGIN_CHANNEL = Identifier.fromNamespaceAndPath("directsync", "login");
    private static volatile DirectSyncMod instance;
    private HttpServer httpServer;
    private ExecutorService workers;
    private volatile List<SyncProtocol.ModFile> sharedFiles = List.of();
    private volatile Map<String, Path> servedPaths = Map.of();
    private volatile Map<String, ModMetadata.Info> servedInfo = Map.of();
    private volatile QuarantineUploads quarantine;
    private volatile Set<String> blacklistedIds = Set.of();
    private volatile int syncPort = 25566;
    private volatile boolean ready;

    @Override
    public void onInitialize() {
        instance = this;
        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER) {
            ServerLifecycleEvents.SERVER_STARTED.register(this::start);
            ServerLifecycleEvents.SERVER_STOPPING.register(server -> stop());
            ServerLoginConnectionEvents.QUERY_START.register((handler, server, sender, synchronizer) -> {
                if (!ready) {
                    handler.disconnect(Component.literal("Direct Sync is unavailable on this server."));
                    return;
                }
                var buf = new FriendlyByteBuf(Unpooled.buffer());
                buf.writeUtf(SyncProtocol.manifest(sharedFiles), SyncProtocol.MAX_MANIFEST_BYTES);
                buf.writeVarInt(syncPort);
                buf.writeVarInt(blacklistedIds.size());
                for (String id : blacklistedIds.stream().sorted().toList()) buf.writeUtf(id, 128);
                sender.sendPacket(LOGIN_CHANNEL, buf);
            });
            ServerLoginNetworking.registerGlobalReceiver(LOGIN_CHANNEL, (server, handler, understood, buf, synchronizer, sender) -> {
                if (!understood || buf == null) {
                    handler.disconnect(Component.literal("Install Direct Sync on your client before joining."));
                    return;
                }
                try {
                    boolean matches = buf.readBoolean();
                    int count = buf.readVarInt();
                    if (count < 0 || count > 4096) throw new IllegalArgumentException("Too many client mods");
                    Set<String> clientIds = new HashSet<>();
                    for (int i = 0; i < count; i++) clientIds.add(buf.readUtf(128));
                    for (String id : blacklistedIds) {
                        if (clientIds.contains(id)) {
                            handler.disconnect(Component.literal("Blacklisted mod: " + id + ". Remove it and restart Minecraft."));
                            return;
                        }
                    }
                    if (!matches) {
                        handler.disconnect(Component.literal("Server modpack mismatch. Check the Direct Sync notice, resolve the update, then restart Minecraft before reconnecting."));
                    }
                } catch (RuntimeException e) {
                    handler.disconnect(Component.literal("Invalid Direct Sync response."));
                }
            });
        }
    }

    public static DirectSyncStatusListener openStatusTransfer(Connection connection, String request) {
        DirectSyncMod mod = instance;
        if (mod == null || !mod.ready) {
            return new DirectSyncStatusListener(connection, "Direct Sync is unavailable".getBytes(StandardCharsets.UTF_8));
        }
        if ("manifest".equals(request)) {
            return new DirectSyncStatusListener(connection,
                    SyncProtocol.manifest(mod.sharedFiles).getBytes(StandardCharsets.UTF_8));
        }
        if ("metadata".equals(request)) {
            return new DirectSyncStatusListener(connection,
                    ModMetadata.serialize(mod.servedInfo.values().stream()
                            .sorted(Comparator.comparing(ModMetadata.Info::fileName)).toList())
                            .getBytes(StandardCharsets.UTF_8));
        }
        if (request.startsWith("offer:")) {
            return new DirectSyncStatusListener(connection,
                    (mod.quarantine == null ? "directsync-offer/1|rejected"
                            : mod.quarantine.offer(connection, request))
                            .getBytes(StandardCharsets.UTF_8));
        }
        if (request.startsWith("icon:")) {
            String name = request.substring(5);
            Path path = mod.servedPaths.get(name);
            ModMetadata.Info info = mod.servedInfo.get(name);
            if (path != null && info != null) {
                try {
                    return new DirectSyncStatusListener(connection, ModMetadata.icon(path, info));
                } catch (IOException e) {
                    LOGGER.warn("Could not serve mod icon for {}: {}", name, e.toString());
                }
            }
            return new DirectSyncStatusListener(connection, new byte[0]);
        }
        if (request.startsWith("jar:")) {
            String name = request.substring(4);
            for (SyncProtocol.ModFile entry : mod.sharedFiles) {
                if (!entry.name().equals(name)) continue;
                Path path = mod.servedPaths.get(name);
                try {
                    if (path == null || Files.isSymbolicLink(path) || !Files.isRegularFile(path)) break;
                    return new DirectSyncStatusListener(connection, path, entry.size());
                } catch (IOException e) {
                    LOGGER.warn("Shared JAR unavailable for status transfer: {}", name, e);
                    break;
                }
            }
        }
        return new DirectSyncStatusListener(connection, "Direct Sync resource unavailable".getBytes(StandardCharsets.UTF_8));
    }

    public static DirectSyncUploadListener openUpload(Connection connection, String token) throws IOException {
        DirectSyncMod mod = instance;
        if (mod == null || !mod.ready || mod.quarantine == null) throw new IOException("Uploads unavailable");
        return mod.quarantine.open(connection, token);
    }

    private void start(MinecraftServer server) {
        Path gameDir = FabricLoader.getInstance().getGameDir();
        Path configDir = FabricLoader.getInstance().getConfigDir();
        try {
            Files.createDirectories(configDir);
            Path configPath = configDir.resolve("directsync.properties");
            Properties config = new Properties();
            if (Files.notExists(configPath)) {
                config.setProperty("bind", "0.0.0.0");
                config.setProperty("port", "auto");
                config.setProperty("selection", "auto");
                try (var out = Files.newOutputStream(configPath)) {
                    config.store(out, "Direct Sync HTTP service");
                }
            } else {
                try (var in = Files.newInputStream(configPath)) {
                    config.load(in);
                }
                if (!config.containsKey("selection") && server.getPort() != 25565
                        && "25566".equals(config.getProperty("port"))) {
                    config.setProperty("port", "auto");
                    config.setProperty("selection", "auto");
                    try (var out = Files.newOutputStream(configPath)) {
                        config.store(out, "Direct Sync HTTP service; migrated default port to auto");
                    }
                    LOGGER.info("Migrated legacy sync port 25566 to auto for Minecraft port {}", server.getPort());
                }
            }

            Path allowlist = configDir.resolve("directsync-server-mods.txt");
            if (Files.notExists(allowlist)) {
                Files.writeString(allowlist, "# Used when selection=manual in directsync.properties. One shared JAR filename per line.\n", StandardCharsets.UTF_8);
            }
            Path exclusions = configDir.resolve("directsync-excluded-mods.txt");
            if (Files.notExists(exclusions)) {
                Files.writeString(exclusions, "# JAR filenames to exclude from automatic selection, one per line.\n", StandardCharsets.UTF_8);
            }
            Path modsDir = gameDir.resolve("mods");
            Files.createDirectories(modsDir);
            Path clientDir = modsDir.resolve("client");
            Path serverDir = modsDir.resolve("server");
            Files.createDirectories(clientDir);
            Files.createDirectories(serverDir);
            Map<String, SyncProtocol.ModFile> shared = loadFiles(modsDir, allowlist, exclusions,
                    config.getProperty("selection", "auto"));
            Map<String, SyncProtocol.ModFile> clientOnly = loadClientFiles(clientDir);
            Map<String, SyncProtocol.ModFile> files = new HashMap<>(shared);
            Set<String> ids = new HashSet<>();
            long totalSize = 0;
            for (SyncProtocol.ModFile entry : shared.values()) {
                ids.add(entry.modId());
                totalSize += entry.size();
            }
            for (SyncProtocol.ModFile entry : clientOnly.values()) {
                if (files.containsKey(entry.name()) || !ids.add(entry.modId())) {
                    throw new IOException("Client-only JAR duplicates a shared filename or mod ID: " + entry.name());
                }
                files.put(entry.name(), entry);
                totalSize += entry.size();
            }
            if (files.size() > SyncProtocol.MAX_MODS || totalSize > SyncProtocol.MAX_TOTAL_BYTES) {
                throw new IOException("Combined shared and client-only mod list exceeds Direct Sync limits");
            }
            Map<String, Path> paths = new HashMap<>();
            for (String name : shared.keySet()) paths.put(name, modsDir.resolve(name));
            for (String name : clientOnly.keySet()) paths.put(name, clientDir.resolve(name));
            Map<String, ModMetadata.Info> info = new HashMap<>();
            for (Map.Entry<String, Path> path : paths.entrySet()) {
                ModMetadata.Info entry = ModMetadata.read(path.getValue());
                if (!entry.modId().equals(files.get(path.getKey()).modId())) {
                    throw new IOException("Fabric mod ID changed during scan: " + path.getKey());
                }
                info.put(path.getKey(), entry);
            }
            try (var serverJars = Files.list(serverDir)) {
                for (Path jar : serverJars.toList()) {
                    if (!jar.getFileName().toString().endsWith(".jar")) continue;
                    String id = SyncProtocol.readModId(jar);
                    if (FabricLoader.getInstance().getModContainer(id).isEmpty()) {
                        LOGGER.warn("Server-only mod {} is not loaded. Add mods/server to the server's fabric.addMods JVM property and restart.", jar.getFileName());
                    }
                }
            }
            Set<String> blacklist = loadBlacklist(modsDir.resolve("Blacklist"));
            String configuredPort = config.getProperty("port", "auto");
            int port = "auto".equalsIgnoreCase(configuredPort)
                    ? server.getPort() + 1 : Integer.parseInt(configuredPort);
            if (port < 1 || port > 65535) throw new IOException("port must be 1..65535");
            String bind = config.getProperty("bind", "0.0.0.0");
            try {
                httpServer = HttpServer.create(new InetSocketAddress(bind, port), 0);
                httpServer.createContext("/", exchange -> handle(exchange, files, paths, server.getPort()));
                workers = Executors.newFixedThreadPool(4, runnable -> {
                    Thread thread = new Thread(runnable, "directsync-http");
                    thread.setDaemon(true);
                    return thread;
                });
                httpServer.setExecutor(workers);
                httpServer.start();
            } catch (IOException e) {
                LOGGER.warn("HTTP sync on {}:{} is unavailable; Minecraft-port sync remains available: {}",
                        bind, port, e.toString());
            }
            sharedFiles = files.values().stream().sorted(Comparator.comparing(SyncProtocol.ModFile::name)).toList();
            servedPaths = Map.copyOf(paths);
            servedInfo = Map.copyOf(info);
            quarantine = new QuarantineUploads(modsDir, servedInfo);
            blacklistedIds = blacklist;
            syncPort = port;
            ready = true;
            LOGGER.info("Serving {} shared and {} client-only mods; blocking {} mod IDs; Minecraft-port sync on {}; HTTP sync {}",
                    shared.size(), clientOnly.size(), blacklist.size(), server.getPort(),
                    httpServer == null ? "unavailable" : bind + ":" + port);
        } catch (Exception e) {
            stop();
            LOGGER.error("Direct Sync could not start. Check config/directsync.properties and the allowlist.", e);
        }
    }

    private Map<String, SyncProtocol.ModFile> loadClientFiles(Path clientDir) throws IOException {
        Map<String, SyncProtocol.ModFile> files = new HashMap<>();
        try (var paths = Files.list(clientDir)) {
            for (Path path : paths.toList()) {
                String name = path.getFileName().toString();
                if (!name.endsWith(".jar")) continue;
                if (!SyncProtocol.validName(name) || !Files.isRegularFile(path) || Files.isSymbolicLink(path)) {
                    throw new IOException("Invalid client-only JAR: " + path);
                }
                long size = Files.size(path);
                if (size > SyncProtocol.MAX_JAR_BYTES) throw new IOException("Client-only JAR is too large: " + name);
                String id = SyncProtocol.readModId(path);
                if ("directsync".equals(id) || "fabric-api".equals(id)) {
                    throw new IOException("Install Direct Sync and Fabric API directly in client mods/: " + name);
                }
                files.put(name, new SyncProtocol.ModFile(name, id, size, SyncProtocol.sha256(path)));
            }
        }
        return Map.copyOf(files);
    }

    private Set<String> loadBlacklist(Path folder) throws IOException {
        Files.createDirectories(folder);
        Set<String> ids = new HashSet<>();
        try (var paths = Files.list(folder)) {
            for (Path path : paths.toList()) {
                if (!path.getFileName().toString().endsWith(".jar")) continue;
                if (!Files.isRegularFile(path) || Files.isSymbolicLink(path)) {
                    throw new IOException("Invalid blacklist JAR: " + path);
                }
                try (JarFile jar = new JarFile(path.toFile())) {
                    var entry = jar.getJarEntry("fabric.mod.json");
                    if (entry == null) throw new IOException("Blacklist JAR is not a Fabric mod: " + path);
                    try (var reader = new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8)) {
                        JsonObject metadata = JsonParser.parseReader(reader).getAsJsonObject();
                        String id = metadata.get("id").getAsString();
                        if (!id.matches("[a-z][a-z0-9_-]{1,63}")) {
                            throw new IOException("Invalid mod ID in " + path + ": " + id);
                        }
                        ids.add(id);
                    }
                } catch (RuntimeException e) {
                    throw new IOException("Invalid Fabric metadata in " + path, e);
                }
                if (ids.size() > SyncProtocol.MAX_MODS) throw new IOException("Too many blacklisted mods");
            }
        }
        return Set.copyOf(ids);
    }

    private Map<String, SyncProtocol.ModFile> loadFiles(Path modsDir, Path allowlist, Path exclusions,
                                                          String selection) throws IOException {
        Map<String, SyncProtocol.ModFile> files = new HashMap<>();
        Path realModsDir = modsDir.toRealPath();
        Set<String> excluded = readNames(exclusions);
        List<String> selected;
        if ("manual".equals(selection)) {
            selected = new ArrayList<>(readNames(allowlist));
        } else if ("auto".equals(selection)) {
            try (var paths = Files.list(modsDir)) {
                selected = new ArrayList<>();
                for (Path path : paths.toList()) {
                    String name = path.getFileName().toString();
                    if (!name.endsWith(".jar") || excluded.contains(name) || !Files.isRegularFile(path)) continue;
                    if (!SyncProtocol.validName(name)) {
                        LOGGER.warn("Skipping shared JAR with unsupported filename: {}", name);
                        continue;
                    }
                    if (isAutomaticallyShared(path)) selected.add(name);
                }
            }
        } else {
            throw new IOException("selection must be auto or manual");
        }
        long total = 0;
        Set<String> ids = new HashSet<>();
        for (String name : selected) {
            if (excluded.contains(name)) continue;
            if (!SyncProtocol.validName(name) || files.containsKey(name)) {
                throw new IOException("Invalid or duplicate JAR name in " + allowlist + ": " + name);
            }
            Path path = modsDir.resolve(name);
            if (!Files.isRegularFile(path) || !path.toRealPath().getParent().equals(realModsDir)) {
                throw new IOException("Allowlisted JAR is missing or outside mods/: " + name);
            }
            long size = Files.size(path);
            if (size > SyncProtocol.MAX_JAR_BYTES) throw new IOException("JAR too large: " + name);
            String modId = SyncProtocol.readModId(path);
            if (!ids.add(modId)) throw new IOException("Duplicate shared mod ID: " + modId);
            total += size;
            if (files.size() >= SyncProtocol.MAX_MODS || total > SyncProtocol.MAX_TOTAL_BYTES) {
                throw new IOException("Shared mod list exceeds the Direct Sync limits");
            }
            files.put(name, new SyncProtocol.ModFile(name, modId, size, SyncProtocol.sha256(path)));
        }
        return Map.copyOf(files);
    }

    private Set<String> readNames(Path file) throws IOException {
        Set<String> names = new HashSet<>();
        for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
            String name = raw.trim();
            if (name.isEmpty() || name.startsWith("#")) continue;
            if (!SyncProtocol.validName(name)) throw new IOException("Invalid JAR filename in " + file + ": " + name);
            names.add(name);
        }
        return names;
    }

    private boolean isAutomaticallyShared(Path path) throws IOException {
        try (JarFile jar = new JarFile(path.toFile())) {
            var entry = jar.getJarEntry("fabric.mod.json");
            if (entry == null) return false;
            try (var reader = new InputStreamReader(jar.getInputStream(entry), StandardCharsets.UTF_8)) {
                JsonObject metadata = JsonParser.parseReader(reader).getAsJsonObject();
                String id = metadata.get("id").getAsString();
                String environment = metadata.has("environment")
                        ? metadata.get("environment").getAsString() : "*";
                return !id.equals("fabric-api")
                        && !environment.equals("server") && !environment.equals("client");
            }
        } catch (RuntimeException e) {
            throw new IOException("Invalid Fabric metadata in " + path, e);
        }
    }

    private void handle(HttpExchange exchange, Map<String, SyncProtocol.ModFile> files,
                        Map<String, Path> paths,
                        int minecraftPort) throws IOException {
        try (exchange) {
            if (!"GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 405, "GET required\n".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String query = exchange.getRequestURI().getRawQuery();
            if (query != null && query.startsWith("mcPort=")
                    && !query.equals("mcPort=" + minecraftPort)) {
                respond(exchange, 404, new byte[0]);
                return;
            }
            String path = exchange.getRequestURI().getRawPath();
            if ("/manifest".equals(path)) {
                List<SyncProtocol.ModFile> sorted = new ArrayList<>(files.values());
                sorted.sort(Comparator.comparing(SyncProtocol.ModFile::name));
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                respond(exchange, 200, SyncProtocol.manifest(sorted).getBytes(StandardCharsets.UTF_8));
                return;
            }
            if (path.startsWith("/mods/")) {
                String name = path.substring("/mods/".length());
                SyncProtocol.ModFile entry = files.get(name);
                if (entry == null || !SyncProtocol.validName(name)) {
                    respond(exchange, 404, new byte[0]);
                    return;
                }
                Path file = paths.get(name);
                if (!SyncProtocol.matches(file, entry)) {
                    respond(exchange, 409, "Server JAR changed; restart the server\n".getBytes(StandardCharsets.UTF_8));
                    return;
                }
                exchange.getResponseHeaders().set("Content-Type", "application/java-archive");
                exchange.sendResponseHeaders(200, entry.size());
                try (var in = Files.newInputStream(file); var out = exchange.getResponseBody()) {
                    in.transferTo(out);
                }
                return;
            }
            respond(exchange, 404, new byte[0]);
        } catch (IOException e) {
            LOGGER.warn("Direct Sync request failed: {}", e.toString());
        }
    }

    private void respond(HttpExchange exchange, int status, byte[] bytes) throws IOException {
        exchange.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length != 0) exchange.getResponseBody().write(bytes);
    }

    private void stop() {
        ready = false;
        sharedFiles = List.of();
        servedPaths = Map.of();
        servedInfo = Map.of();
        quarantine = null;
        blacklistedIds = Set.of();
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
        if (workers != null) {
            workers.shutdownNow();
            workers = null;
        }
    }
}
