package net.supersnetwork.direct_sync;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.awt.Desktop;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/** Run with java -cp direct-sync.jar before launching Minecraft. */
public final class DirectSyncCli {
    private DirectSyncCli() {}

    public static void main(String[] args) {
        try {
            Options options = Options.parse(args);
            if (options.command.equals("sync")) sync(options);
            else if (options.command.equals("sync-minecraft")) syncMinecraft(options);
            else if (options.command.equals("apply")) apply(options);
            else if (options.command.equals("apply-after-exit")) applyAfterExit(options);
            else relaunchAfterExit(options);
        } catch (Exception e) {
            System.err.println("Direct Sync: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void sync(Options options) throws Exception {
        stage(options.server, options.port, options.gameDir);
        System.out.println("After Minecraft is closed, run the apply command before the next launch.");
        System.out.println("Downloaded JARs will be installed in the normal mods/ folder.");
    }

    private static void syncMinecraft(Options options) throws Exception {
        List<SyncProtocol.ModFile> manifest = MinecraftStatusSync.fetchManifest(options.server, options.port);
        stageMinecraft(options.server, options.port, options.gameDir, manifest);
        System.out.println("After Minecraft is closed, run the apply command before the next launch.");
        System.out.println("Downloaded JARs will be installed in the normal mods/ folder.");
    }

    static void stage(String server, int port, Path gameDir) throws Exception {
        stage(server, port, gameDir, fetchManifest(server, port));
    }

    static List<SyncProtocol.ModFile> fetchManifest(String server, int port) throws Exception {
        return fetchManifest(server, port, -1);
    }

    static List<SyncProtocol.ModFile> fetchManifest(String server, int port, int minecraftPort) throws Exception {
        URI base = new URI("http", null, server, port, "/", null, null);
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        String path = minecraftPort > 0 ? "manifest?mcPort=" + minecraftPort : "manifest";
        String manifestText = new String(readResponse(http, base.resolve(path),
                SyncProtocol.MAX_MANIFEST_BYTES), StandardCharsets.UTF_8);
        return SyncProtocol.parseManifest(manifestText);
    }

    static void stage(String server, int port, Path gameDir, List<SyncProtocol.ModFile> manifest) throws Exception {
        stage(server, port, gameDir, manifest, null);
    }

    static void stage(String server, int port, Path gameDir, List<SyncProtocol.ModFile> manifest,
                      Progress progress) throws Exception {
        URI base = new URI("http", null, server, port, "/", null, null);
        HttpClient http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
        stage(gameDir, manifest, (entry, destination, bytes) ->
                download(http, base.resolve(new URI(null, null, "mods/" + entry.name(), null)),
                        entry, destination, bytes), progress);
    }

    static void stageMinecraft(String server, int port, Path gameDir,
                               List<SyncProtocol.ModFile> manifest) throws Exception {
        stageMinecraft(server, port, gameDir, manifest, null);
    }

    static void stageMinecraft(String server, int port, Path gameDir,
                               List<SyncProtocol.ModFile> manifest, Progress progress) throws Exception {
        stage(gameDir, manifest, (entry, destination, bytes) ->
                downloadMinecraft(server, port, entry, destination, bytes), progress);
    }

    @FunctionalInterface
    interface Progress {
        void onUpdate(SyncProtocol.ModFile entry, long received, String phase);
    }

    @FunctionalInterface
    private interface Downloader {
        void download(SyncProtocol.ModFile entry, Path destination, java.util.function.LongConsumer bytes) throws Exception;
    }

    private static void stage(Path gameDir, List<SyncProtocol.ModFile> manifest, Downloader downloader,
                              Progress progress) throws Exception {
        Path mods = gameDir.resolve("mods");
        Path staging = mods.resolve(".directsync-staging");
        Files.createDirectories(mods);
        Files.createDirectories(staging);

        Map<String, Path> rootMods = SyncProtocol.rootMods(mods);
        int downloads = 0;
        for (SyncProtocol.ModFile entry : manifest) {
            if (progress != null) progress.onUpdate(entry, 0, "Checking");
            Path root = rootMods.get(entry.modId());
            if (root != null && SyncProtocol.matches(root, entry)) {
                if (progress != null) progress.onUpdate(entry, entry.size(), "Current");
                continue;
            }
            Path staged = staging.resolve(entry.name());
            if (Files.isSymbolicLink(staged)) throw new IOException("Symlink in staging directory: " + entry.name());
            if (!SyncProtocol.matches(staged, entry)) {
                downloader.download(entry, staged, bytes -> {
                    if (progress != null) progress.onUpdate(entry, bytes, "Downloading");
                });
                downloads++;
                System.out.println("Staged " + entry.name());
            }
            if (progress != null) progress.onUpdate(entry, entry.size(), "Staged");
        }
        Path pending = staging.resolve("manifest.txt");
        Path temp = staging.resolve("manifest.txt.tmp");
        Files.writeString(temp, SyncProtocol.manifest(manifest), StandardCharsets.UTF_8);
        replace(temp, pending);
        System.out.println("Staged " + downloads + " JAR(s); " + manifest.size() + " required mod(s) in the server manifest.");
    }

    private static void apply(Options options) throws IOException {
        Path mods = options.gameDir.resolve("mods");
        Path legacy = mods.resolve("directsync");
        Path staging = mods.resolve(".directsync-staging");
        Path pending = staging.resolve("manifest.txt");
        Path installedManifest = mods.resolve(".directsync-installed-manifest.txt");
        if (!Files.isRegularFile(pending)) throw new IOException("No staged manifest; run sync first");
        String text = Files.readString(pending, StandardCharsets.UTF_8);
        if (text.getBytes(StandardCharsets.UTF_8).length > SyncProtocol.MAX_MANIFEST_BYTES) {
            throw new IOException("Staged manifest is too large");
        }
        List<SyncProtocol.ModFile> manifest = SyncProtocol.parseManifest(text);
        Files.createDirectories(mods);
        Map<String, Path> rootMods = SyncProtocol.rootMods(mods);
        Map<String, SyncProtocol.ModFile> desired = new HashMap<>();
        for (SyncProtocol.ModFile entry : manifest) {
            desired.put(entry.modId(), entry);
            if (Files.isSymbolicLink(staging.resolve(entry.name())))
                throw new IOException("Symlink in staging directory: " + entry.name());
            Path root = rootMods.get(entry.modId());
            Path target = mods.resolve(entry.name());
            if (Files.exists(target) && !target.equals(root) && !SyncProtocol.matches(target, entry)) {
                throw new IOException("A different mod already uses the required filename: " + entry.name());
            }
            if (root == null || !SyncProtocol.matches(root, entry)) {
                Path staged = staging.resolve(entry.name());
                if (!SyncProtocol.matches(staged, entry)) {
                    throw new IOException("Missing or damaged staged JAR: " + entry.name());
                }
                SyncProtocol.requireFabricJar(staged);
            }
        }

        Set<Path> oldJars = new java.util.LinkedHashSet<>();
        for (Map.Entry<String, Path> root : rootMods.entrySet()) {
            SyncProtocol.ModFile entry = desired.get(root.getKey());
            if (entry != null && !SyncProtocol.matches(root.getValue(), entry)) oldJars.add(root.getValue());
        }
        if (Files.isRegularFile(installedManifest)) {
            List<SyncProtocol.ModFile> old = SyncProtocol.parseManifest(Files.readString(installedManifest));
            for (SyncProtocol.ModFile entry : old) {
                Path root = rootMods.get(entry.modId());
                if (!desired.containsKey(entry.modId()) && root != null && SyncProtocol.matches(root, entry)) {
                    oldJars.add(root);
                }
            }
        }
        if (Files.exists(legacy)) {
            if (!Files.isDirectory(legacy) || Files.isSymbolicLink(legacy)) {
                throw new IOException("Invalid legacy managed mods directory");
            }
            try (var paths = Files.list(legacy)) {
                for (Path path : paths.toList()) {
                String name = path.getFileName().toString();
                if (!SyncProtocol.validName(name) || !Files.isRegularFile(path) || Files.isSymbolicLink(path)) {
                    throw new IOException("Unexpected file in legacy managed mods directory: " + name);
                }
                oldJars.add(path);
                }
            }
        }

        String stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS")
                .withZone(ZoneOffset.UTC).format(Instant.now()) + "-" + UUID.randomUUID().toString().substring(0, 8);
        Path backup = mods.resolve(".directsync-backups").resolve(stamp);
        Map<Path, Path> movedOld = new HashMap<>();
        List<Path> movedNew = new ArrayList<>();
        try {
            if (!oldJars.isEmpty()) Files.createDirectories(backup);
            for (Path old : oldJars) {
                Path saved = backup.resolve(old.getParent().equals(legacy) ? "legacy" : "root")
                        .resolve(old.getFileName());
                Files.createDirectories(saved.getParent());
                Files.move(old, saved);
                movedOld.put(old, saved);
            }
            for (SyncProtocol.ModFile entry : manifest) {
                Path root = rootMods.get(entry.modId());
                if (root != null && SyncProtocol.matches(root, entry)) continue;
                Path target = mods.resolve(entry.name());
                Files.move(staging.resolve(entry.name()), target);
                movedNew.add(target);
            }
            Path manifestTemp = installedManifest.resolveSibling(installedManifest.getFileName() + ".tmp");
            Files.writeString(manifestTemp, text, StandardCharsets.UTF_8);
            replace(manifestTemp, installedManifest);
            Files.delete(pending);
        } catch (IOException e) {
            for (Path target : movedNew) {
                try { Files.move(target, staging.resolve(target.getFileName()), StandardCopyOption.REPLACE_EXISTING); }
                catch (IOException rollback) { e.addSuppressed(rollback); }
            }
            for (Map.Entry<Path, Path> saved : movedOld.entrySet()) {
                try { Files.move(saved.getValue(), saved.getKey()); }
                catch (IOException rollback) { e.addSuppressed(rollback); }
            }
            throw e;
        }
        System.out.println("Applied " + movedNew.size() + " JAR(s); moved " + oldJars.size() + " replaced/stale JAR(s) to backup.");
        if (!oldJars.isEmpty()) System.out.println("Backup: " + backup);
        if (Files.isDirectory(legacy)) Files.deleteIfExists(legacy);
        System.out.println("Shared and client-only JARs are installed in the normal mods/ folder.");
    }

    private static void applyAfterExit(Options options) throws Exception {
        waitForParent(options.pid);
        IOException last = null;
        boolean applied = false;
        for (int attempt = 0; attempt < 10; attempt++) {
            try {
                apply(options);
                applied = true;
                break;
            } catch (IOException e) {
                last = e;
                Thread.sleep(1000);
            }
        }
        if (!applied) throw last;
        relaunchIfRequested(options.gameDir);
    }

    private static void relaunchAfterExit(Options options) throws Exception {
        waitForParent(options.pid);
        relaunchIfRequested(options.gameDir);
    }

    private static void waitForParent(long pid) throws Exception {
        var parent = ProcessHandle.of(pid);
        if (parent.isPresent()) parent.get().onExit().get();
    }

    static Path relaunchRequest(Path gameDir) {
        return gameDir.resolve("mods").resolve(".directsync-staging").resolve("relaunch.properties");
    }

    private static void relaunchIfRequested(Path gameDir) throws Exception {
        Path request = relaunchRequest(gameDir);
        if (!Files.isRegularFile(request)) return;
        Properties properties = new Properties();
        try (var in = Files.newInputStream(request)) {
            properties.load(in);
        }
        String url = properties.getProperty("url", "");
        URI uri = URI.create(url);
        if (!"modrinth".equalsIgnoreCase(uri.getScheme()) || !"launch".equalsIgnoreCase(uri.getHost())
                || !uri.getPath().startsWith("/instance/")) {
            throw new IOException("Invalid Modrinth relaunch URL");
        }
        Thread.sleep(2000); // Give the launcher time to mark the old game process as stopped.
        String executable = properties.getProperty("executable", "");
        if (!executable.isBlank() && Files.isRegularFile(Path.of(executable))) {
            new ProcessBuilder(executable, url).start();
        } else if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            Desktop.getDesktop().browse(uri);
        } else {
            throw new IOException("Could not open the Modrinth launcher");
        }
        Files.deleteIfExists(request);
        System.out.println("Requested Modrinth instance relaunch and server reconnect.");
    }

    private static void download(HttpClient http, URI uri, SyncProtocol.ModFile entry, Path destination,
                                 java.util.function.LongConsumer progress) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(3)).GET().build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200) {
            response.body().close();
            throw new IOException("HTTP " + response.statusCode() + " for " + entry.name());
        }
        Path temp = destination.resolveSibling(destination.getFileName() + ".part");
        long received = 0;
        try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(temp)) {
            byte[] buffer = new byte[64 * 1024];
            int count;
            while ((count = in.read(buffer)) != -1) {
                received += count;
                if (received > entry.size()) throw new IOException("Download exceeds declared size: " + entry.name());
                out.write(buffer, 0, count);
                if (progress != null) progress.accept(received);
            }
        } catch (Exception e) {
            Files.deleteIfExists(temp);
            throw e;
        }
        try {
            if (!SyncProtocol.matches(temp, entry)) throw new IOException("SHA-256 mismatch: " + entry.name());
            SyncProtocol.requireFabricJar(temp);
            replace(temp, destination);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static void downloadMinecraft(String server, int port, SyncProtocol.ModFile entry,
                                          Path destination, java.util.function.LongConsumer progress) throws Exception {
        Path temp = destination.resolveSibling(destination.getFileName() + ".part");
        try {
            try (OutputStream out = Files.newOutputStream(temp)) {
                MinecraftStatusSync.download(server, port, entry, out, progress);
            }
            if (!SyncProtocol.matches(temp, entry)) throw new IOException("SHA-256 mismatch: " + entry.name());
            SyncProtocol.requireFabricJar(temp);
            replace(temp, destination);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    private static byte[] readResponse(HttpClient http, URI uri, int maxBytes) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).GET().build();
        HttpResponse<InputStream> response = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = response.body()) {
            if (response.statusCode() != 200) throw new IOException("HTTP " + response.statusCode() + " for manifest");
            byte[] bytes = in.readNBytes(maxBytes + 1);
            if (bytes.length > maxBytes) throw new IOException("Manifest is too large");
            return bytes;
        }
    }

    private static void replace(Path from, Path to) throws IOException {
        try {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(from, to, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private record Options(String command, String server, int port, Path gameDir, long pid) {
        static Options parse(String[] args) {
            if (args.length == 0 || (!args[0].equals("sync") && !args[0].equals("sync-minecraft") && !args[0].equals("apply")
                    && !args[0].equals("apply-after-exit") && !args[0].equals("relaunch-after-exit"))) {
                throw new IllegalArgumentException("Usage: sync-minecraft --server HOST --port MINECRAFT_PORT [--game-dir PATH] | apply [--game-dir PATH]");
            }
            String server = null;
            int port = 25566;
            long pid = -1;
            Path gameDir = Path.of(".").toAbsolutePath().normalize();
            Set<String> seen = new HashSet<>();
            for (int i = 1; i < args.length; i += 2) {
                if (i + 1 >= args.length || !seen.add(args[i])) throw new IllegalArgumentException("Invalid option: " + args[i]);
                switch (args[i]) {
                    case "--server" -> server = args[i + 1];
                    case "--port" -> port = Integer.parseInt(args[i + 1]);
                    case "--game-dir" -> gameDir = Path.of(args[i + 1]).toAbsolutePath().normalize();
                    case "--pid" -> pid = Long.parseLong(args[i + 1]);
                    default -> throw new IllegalArgumentException("Unknown option: " + args[i]);
                }
            }
            if ((args[0].equals("sync") || args[0].equals("sync-minecraft")) && (server == null || server.isBlank())) {
                throw new IllegalArgumentException("sync requires --server HOST");
            }
            if (port < 1 || port > 65535) throw new IllegalArgumentException("port must be 1..65535");
            if ((args[0].equals("apply-after-exit") || args[0].equals("relaunch-after-exit")) && pid <= 0) {
                throw new IllegalArgumentException(args[0] + " requires --pid");
            }
            return new Options(args[0], server, port, gameDir, pid);
        }
    }
}
