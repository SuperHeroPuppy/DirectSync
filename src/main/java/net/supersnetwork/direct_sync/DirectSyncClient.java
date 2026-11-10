package net.supersnetwork.direct_sync;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientLoginNetworking;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import io.netty.buffer.Unpooled;
import net.minecraft.network.FriendlyByteBuf;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.metadata.ModOrigin;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Properties;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class DirectSyncClient implements ClientModInitializer {
    private static final Logger LOGGER = LoggerFactory.getLogger("Direct Sync");
    private static final ExecutorService DOWNLOADS = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "directsync-download");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public void onInitializeClient() {
        ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
            Path pending = FabricLoader.getInstance().getGameDir().resolve("mods")
                    .resolve(".directsync-staging").resolve("manifest.txt");
            if (Files.isRegularFile(pending)) {
                toast(client, "Update pending. Close Minecraft and run the apply command.");
            }
        });
        ClientLoginNetworking.registerGlobalReceiver(DirectSyncMod.LOGIN_CHANNEL,
                (client, handler, buf, listenerAdder) -> {
                    try {
                        String manifestText = buf.readUtf(SyncProtocol.MAX_MANIFEST_BYTES);
                        int port = buf.readVarInt();
                        if (port < 1 || port > 65535) throw new IOException("Invalid sync port");
                        int blacklistCount = buf.readVarInt();
                        if (blacklistCount < 0 || blacklistCount > SyncProtocol.MAX_MODS) {
                            throw new IOException("Invalid blacklist size");
                        }
                        Set<String> blacklist = new HashSet<>();
                        for (int i = 0; i < blacklistCount; i++) blacklist.add(buf.readUtf(128));
                        List<SyncProtocol.ModFile> manifest = SyncProtocol.parseManifest(manifestText);
                        ServerData server = client.getCurrentServer();
                        String host = server == null ? null : ServerAddress.parseString(server.ip).getHost();
                        return CompletableFuture.supplyAsync(() -> {
                            try {
                                List<String> loadedIds = FabricLoader.getInstance().getAllMods().stream()
                                        .map(mod -> mod.getMetadata().getId()).distinct().sorted().toList();
                                if (loadedIds.size() > 4096) throw new IOException("Too many loaded mods");
                                PackCheck check = checkPack(manifest);
                                boolean filesMatch = check.filesMatch();
                                boolean matches = check.loadedMatch();
                                if (!matches && loadedIds.stream().noneMatch(blacklist::contains)) {
                                    if (filesMatch) {
                                        toast(client, "Required JARs are present. Restart Minecraft to load them.");
                                    } else if (host == null) {
                                        toast(client, "Update required. Use the Direct Sync command to download it.");
                                    } else {
                                        int minecraftPort = ServerAddress.parseString(server.ip).getPort();
                                        DOWNLOADS.submit(() -> stage(client, host, minecraftPort, port));
                                    }
                                }
                                var response = new FriendlyByteBuf(Unpooled.buffer());
                                response.writeBoolean(matches);
                                response.writeVarInt(loadedIds.size());
                                for (String id : loadedIds) response.writeUtf(id, 128);
                                return response;
                            } catch (Exception e) {
                                LOGGER.error("Could not check the server modpack", e);
                                toast(client, "Could not check modpack; see the game log.");
                                return null;
                            }
                        });
                    } catch (Exception e) {
                        LOGGER.error("Invalid Direct Sync login query", e);
                        toast(client, "Invalid server modpack query; see the game log.");
                        return CompletableFuture.completedFuture(null);
                    }
                });
    }

    record PackCheck(boolean filesMatch, boolean loadedMatch) {}

    static PackCheck checkPack(List<SyncProtocol.ModFile> manifest) throws IOException {
        Path modsDir = FabricLoader.getInstance().getGameDir().resolve("mods");
        boolean filesMatch = SyncProtocol.installedMatches(modsDir, manifest);
        if (!filesMatch) return new PackCheck(false, false);
        Set<Path> loadedPaths = new HashSet<>();
        FabricLoader.getInstance().getAllMods().forEach(mod -> {
            if (mod.getOrigin().getKind() == ModOrigin.Kind.PATH) {
                mod.getOrigin().getPaths().forEach(path -> loadedPaths.add(path.toAbsolutePath().normalize()));
            }
        });
        var root = SyncProtocol.rootMods(modsDir);
        for (SyncProtocol.ModFile entry : manifest) {
            Path rootPath = root.get(entry.modId());
            boolean rootLoaded = rootPath != null && loadedPaths.contains(rootPath.toAbsolutePath().normalize())
                    && SyncProtocol.matches(rootPath, entry);
            if (!rootLoaded) return new PackCheck(true, false);
        }
        return new PackCheck(true, true);
    }

    private static void stage(net.minecraft.client.Minecraft client, String host, int minecraftPort, int httpPort) {
        try {
            Path gameDir = FabricLoader.getInstance().getGameDir();
            List<SyncProtocol.ModFile> manifest = null;
            try {
                manifest = MinecraftStatusSync.fetchManifest(host, minecraftPort);
            } catch (IOException statusFailure) {
                LOGGER.debug("Minecraft-port sync unavailable; trying HTTP: {}", statusFailure.toString());
            }
            if (manifest != null) {
                Map<String, ModMetadata.Info> metadata = Map.of();
                try {
                    byte[] raw = MinecraftStatusSync.fetchResource(host, minecraftPort,
                            "metadata", ModMetadata.MAX_METADATA_BYTES);
                    metadata = ModMetadata.parse(new String(raw, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    LOGGER.debug("No Direct Sync metadata for update offer: {}", e.toString());
                }
                int offered = DirectSyncPreflight.offerNewer(host, minecraftPort, manifest, metadata);
                if (offered > 0) {
                    toast(client, "Newer mods sent for owner review. Wait for server restart.");
                    return;
                }
                DirectSyncCli.stageMinecraft(host, minecraftPort, gameDir, manifest);
            } else {
                DirectSyncCli.stage(host, httpPort, gameDir);
            }
            launchApplyHelper(gameDir);
            toast(client, "Update downloaded. Close Minecraft, then relaunch after it applies.");
        } catch (Exception e) {
            LOGGER.error("Could not download the server modpack", e);
            toast(client, "Update failed. Check the log or use the sync/apply commands.");
        }
    }

    static void launchApplyHelper(Path gameDir) throws Exception {
        Files.deleteIfExists(DirectSyncCli.relaunchRequest(gameDir));
        launchHelper(gameDir, "apply-after-exit");
    }

    static void launchRelaunchHelper(Path gameDir) throws Exception {
        launchHelper(gameDir, "relaunch-after-exit");
    }

    static void requestRelaunch(Path gameDir, ModrinthRelaunch.Plan plan) throws IOException {
        Path request = DirectSyncCli.relaunchRequest(gameDir);
        Files.createDirectories(request.getParent());
        Properties properties = new Properties();
        properties.setProperty("url", plan.url());
        if (plan.executable() != null) properties.setProperty("executable", plan.executable().toString());
        Path temp = request.resolveSibling("relaunch.properties.tmp");
        try (var out = Files.newOutputStream(temp)) {
            properties.store(out, "Direct Sync relaunch request");
        }
        try {
            Files.move(temp, request, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException e) {
            Files.move(temp, request, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static void launchHelper(Path gameDir, String command) throws Exception {
        Path code = Path.of(DirectSyncCli.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        Path staging = gameDir.resolve("mods").resolve(".directsync-staging");
        Files.createDirectories(staging);
        if (Files.isRegularFile(code)) {
            Path helper = staging.resolve("helper.jar");
            Files.copy(code, helper, StandardCopyOption.REPLACE_EXISTING);
            code = helper;
        }
        String executable = System.getProperty("os.name").startsWith("Windows") ? "javaw.exe" : "java";
        Path java = Path.of(System.getProperty("java.home"), "bin", executable);
        if (!Files.isRegularFile(java)) java = Path.of(System.getProperty("java.home"), "bin", "java");
        Path log = gameDir.resolve("mods").resolve(".directsync-staging").resolve("apply.log");
        new ProcessBuilder(java.toString(), "-cp", code.toString(), DirectSyncCli.class.getName(),
                command, "--pid", Long.toString(ProcessHandle.current().pid()),
                "--game-dir", gameDir.toString())
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.appendTo(log.toFile()))
                .start();
    }

    private static void toast(net.minecraft.client.Minecraft client, String message) {
        client.execute(() -> SystemToast.add(client.gui.toastManager(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                Component.literal("Direct Sync"), Component.literal(message)));
    }
}
