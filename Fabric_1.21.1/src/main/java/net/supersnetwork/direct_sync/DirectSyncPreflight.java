package net.supersnetwork.direct_sync;

import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.Version;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.TransferState;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.Optional;
import java.util.Properties;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.ResourceLocation;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Checks the modpack before the vanilla login and registry sync sequence begins. */
public final class DirectSyncPreflight {
    private static final Logger LOGGER = LoggerFactory.getLogger("Direct Sync");
    private static final ExecutorService CHECKS = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "directsync-preflight");
        thread.setDaemon(true);
        return thread;
    });
    private static final ThreadLocal<Boolean> BYPASS_ONCE = ThreadLocal.withInitial(() -> false);

    private DirectSyncPreflight() {}

    public static boolean intercept(Screen parent, Minecraft client, ServerAddress address,
                                    ServerData serverData, boolean quickPlay, TransferState transferState) {
        if (BYPASS_ONCE.get()) {
            BYPASS_ONCE.remove();
            return false;
        }
        CheckScreen screen = new CheckScreen(parent);
        screen.setConnection(address, serverData, quickPlay, transferState);
        client.setScreen(screen);
        CHECKS.submit(() -> check(client, screen, parent, address, serverData, quickPlay, transferState));
        return true;
    }

    private static void check(Minecraft client, CheckScreen screen, Screen parent,
                              ServerAddress address, ServerData serverData, boolean quickPlay,
                              TransferState transferState) {
        try {
            List<SyncProtocol.ModFile> manifest = null;
            int port = -1;
            Exception last = null;
            boolean minecraftTransport = false;
            try {
                manifest = MinecraftStatusSync.fetchManifest(address.getHost(), address.getPort());
                minecraftTransport = true;
            } catch (Exception e) {
                last = e;
            }
            if (manifest == null) for (int candidate : clientSyncPorts(address.getPort())) {
                try {
                    manifest = DirectSyncCli.fetchManifest(address.getHost(), candidate, address.getPort());
                    port = candidate;
                    break;
                } catch (Exception e) {
                    if (last != null) e.addSuppressed(last);
                    last = e;
                }
            }
            if (manifest == null) throw last == null ? new IOException("No sync service found") : last;
            Map<String, ModMetadata.Info> metadata = Map.of();
            if (minecraftTransport) {
                try {
                    byte[] raw = MinecraftStatusSync.fetchResource(address.getHost(), address.getPort(),
                            "metadata", ModMetadata.MAX_METADATA_BYTES);
                    metadata = ModMetadata.parse(new String(raw, StandardCharsets.UTF_8));
                } catch (IOException e) {
                    LOGGER.debug("This server has no Direct Sync display metadata: {}", e.toString());
                }
            }
            if (screen.cancelled) return;
            if (minecraftTransport && !metadata.isEmpty()) {
                int offered = offerNewer(address.getHost(), address.getPort(), manifest, metadata);
                if (offered > 0) {
                    int count = offered;
                    client.execute(() -> screen.showReview(client, count));
                    return;
                }
            }
            DirectSyncClient.PackCheck status = DirectSyncClient.checkPack(manifest);
            if (screen.cancelled) return;
            Path gameDir = FabricLoader.getInstance().getGameDir();
            if (status.loadedMatch()) {
                client.execute(() -> {
                    if (client.screen == screen) resume(parent, client, address, serverData, quickPlay, transferState);
                });
            } else if (status.filesMatch()) {
                Optional<ModrinthRelaunch.Plan> plan = ModrinthRelaunch.find(gameDir,
                        serverData == null ? null : serverData.ip);
                client.execute(() -> screen.showSetup(client, plan.orElse(null)));
            } else {
                Map<String, ModMetadata.Info> display = metadata;
                boolean statusTransfer = minecraftTransport;
                int totalMods = manifest.size();
                Map<String, Integer> positions = new HashMap<>();
                for (int i = 0; i < manifest.size(); i++) positions.put(manifest.get(i).name(), i + 1);
                client.execute(() -> screen.showDownloading(client));
                DirectSyncCli.Progress progress = (entry, received, phase) -> {
                    ModMetadata.Info info = display.get(entry.name());
                    screen.setProgress(entry, info, positions.getOrDefault(entry.name(), 0),
                            totalMods, received, phase);
                    if ("Checking".equals(phase) && info != null && !info.iconPath().isEmpty()
                            && statusTransfer) {
                        try {
                            byte[] icon = MinecraftStatusSync.fetchResource(address.getHost(), address.getPort(),
                                    "icon:" + entry.name(), ModMetadata.MAX_ICON_BYTES);
                            client.execute(() -> screen.setIcon(client, entry.name(), icon));
                        } catch (IOException e) {
                            LOGGER.debug("Could not get mod icon for {}: {}", entry.name(), e.toString());
                        }
                    }
                };
                if (minecraftTransport) {
                    DirectSyncCli.stageMinecraft(address.getHost(), address.getPort(), gameDir, manifest, progress);
                } else {
                    DirectSyncCli.stage(address.getHost(), port, gameDir, manifest, progress);
                }
                if (screen.cancelled) return;
                DirectSyncClient.launchApplyHelper(gameDir);
                Optional<ModrinthRelaunch.Plan> plan = ModrinthRelaunch.find(gameDir,
                        serverData == null ? null : serverData.ip);
                client.execute(() -> screen.showRestart(client, plan.orElse(null)));
            }
        } catch (Exception e) {
            LOGGER.warn("Pre-connection modpack check failed for {}: {}", address, e.toString());
            client.execute(() -> screen.showUnavailable(client));
        }
    }

    static int offerNewer(String host, int port, List<SyncProtocol.ModFile> manifest,
                          Map<String, ModMetadata.Info> metadata) throws Exception {
        Map<String, Path> local = SyncProtocol.rootMods(FabricLoader.getInstance()
                .getGameDir().resolve("mods"));
        int offered = 0;
        for (SyncProtocol.ModFile entry : manifest) {
            ModMetadata.Info serverInfo = metadata.get(entry.name());
            Path jar = local.get(entry.modId());
            if (serverInfo == null || jar == null || Files.isSymbolicLink(jar)
                    || !serverInfo.modId().equals(entry.modId())) continue;
            ModMetadata.Info clientInfo = ModMetadata.read(jar);
            try {
                if (Version.parse(clientInfo.version()).compareTo(Version.parse(serverInfo.version())) <= 0) continue;
            } catch (Exception e) {
                LOGGER.debug("Cannot order mod versions for {}: {}", entry.modId(), e.toString());
                continue;
            }
            MinecraftStatusSync.uploadNewer(host, port, jar, clientInfo);
            offered++;
        }
        return offered;
    }

    private static int[] clientSyncPorts(int minecraftPort) throws IOException {
        Path config = FabricLoader.getInstance().getConfigDir().resolve("directsync-client.properties");
        Files.createDirectories(config.getParent());
        Properties properties = new Properties();
        if (Files.notExists(config)) {
            properties.setProperty("port", "auto");
            try (var out = Files.newOutputStream(config)) {
                properties.store(out, "Direct Sync client pre-connection check");
            }
        } else {
            try (var in = Files.newInputStream(config)) {
                properties.load(in);
            }
        }
        try {
            String configured = properties.getProperty("port", "auto");
            int automatic = minecraftPort + 1;
            if ("auto".equalsIgnoreCase(configured)) {
                if (automatic > 65535) throw new NumberFormatException();
                return new int[] { automatic };
            }
            int port = Integer.parseInt(configured);
            if (port < 1 || port > 65535) throw new NumberFormatException();
            if (port == 25566 && minecraftPort != 25565 && automatic <= 65535) {
                return new int[] { automatic, 25566 };
            }
            return new int[] { port };
        } catch (NumberFormatException e) {
            throw new IOException("Invalid port in " + config, e);
        }
    }

    private static void resume(Screen parent, Minecraft client, ServerAddress address,
                               ServerData serverData, boolean quickPlay, TransferState transferState) {
        BYPASS_ONCE.set(true);
        try {
            ConnectScreen.startConnecting(parent, client, address, serverData, quickPlay, transferState);
        } finally {
            BYPASS_ONCE.remove();
        }
    }

    private static final class CheckScreen extends Screen {
        private final Screen parent;
        private State state = State.CHECKING;
        private volatile boolean cancelled;
        private ModrinthRelaunch.Plan relaunchPlan;
        private boolean relaunchError;
        private boolean restarting;
        private int reviewedMods;
        private volatile ProgressSnapshot progress;
        private ResourceLocation iconTexture;
        private int iconWidth;
        private int iconHeight;
        private String iconFile;

        private record ProgressSnapshot(SyncProtocol.ModFile entry, ModMetadata.Info info, int index,
                                        int total, long received, String phase) {}

        private CheckScreen(Screen parent) {
            super(Component.literal("Direct Sync"));
            this.parent = parent;
        }

        @Override
        protected void init() {
            clearWidgets();
            int x = width / 2 - 100;
            int y = height / 2 + (state == State.DOWNLOADING ? 76 : 32);
            if (state == State.RESTART || state == State.SETUP) {
                if (relaunchPlan != null) {
                    addRenderableWidget(Button.builder(Component.literal("Restart and reconnect"), button -> restartAndReconnect())
                            .bounds(x, y, 200, 20).build());
                    y += 24;
                }
                addRenderableWidget(Button.builder(Component.literal("Quit Minecraft"), button -> minecraft.stop())
                        .bounds(x, y, 200, 20).build());
                if (state == State.SETUP) {
                    y += 24;
                    addRenderableWidget(Button.builder(Component.literal("Back"), button -> onClose())
                            .bounds(x, y, 200, 20).build());
                }
            } else if (state == State.UNAVAILABLE) {
                addRenderableWidget(Button.builder(Component.literal("Connect without Direct Sync"), button -> {
                    ServerAddress address = pendingAddress;
                    if (address != null) resume(parent, minecraft, address, pendingServerData,
                            pendingQuickPlay, pendingTransferState);
                }).bounds(x, y, 200, 20).build());
                y += 24;
                addRenderableWidget(Button.builder(Component.literal("Back"), button -> onClose())
                        .bounds(x, y, 200, 20).build());
            } else {
                addRenderableWidget(Button.builder(Component.literal("Back"), button -> onClose())
                        .bounds(x, y, 200, 20).build());
            }
        }

        private ServerAddress pendingAddress;
        private ServerData pendingServerData;
        private boolean pendingQuickPlay;
        private TransferState pendingTransferState;

        private void setConnection(ServerAddress address, ServerData data, boolean quickPlay,
                                   TransferState transferState) {
            pendingAddress = address;
            pendingServerData = data;
            pendingQuickPlay = quickPlay;
            pendingTransferState = transferState;
        }

        private void update(Minecraft client, State next) {
            if (client.screen != this) return;
            state = next;
            rebuildWidgets();
        }

        void showDownloading(Minecraft client) { update(client, State.DOWNLOADING); }
        void setProgress(SyncProtocol.ModFile entry, ModMetadata.Info info, int index, int total,
                         long received, String phase) {
            progress = new ProgressSnapshot(entry, info, index, total, received, phase);
        }

        void setIcon(Minecraft client, String filename, byte[] bytes) {
            ProgressSnapshot current = progress;
            if (client.screen != this || current == null || !filename.equals(current.entry().name())
                    || bytes.length < 24 || bytes.length > ModMetadata.MAX_ICON_BYTES) return;
            int width = ((bytes[16] & 255) << 24) | ((bytes[17] & 255) << 16)
                    | ((bytes[18] & 255) << 8) | (bytes[19] & 255);
            int height = ((bytes[20] & 255) << 24) | ((bytes[21] & 255) << 16)
                    | ((bytes[22] & 255) << 8) | (bytes[23] & 255);
            if (width < 1 || height < 1 || width > 512 || height > 512) return;
            try {
                NativeImage image = NativeImage.read(new ByteArrayInputStream(bytes));
                if (iconTexture != null) client.getTextureManager().release(iconTexture);
                iconTexture = client.getTextureManager().register("directsync_icon", new DynamicTexture(image));
                iconWidth = image.getWidth();
                iconHeight = image.getHeight();
                iconFile = filename;
            } catch (IOException e) {
                LOGGER.debug("Invalid mod icon for {}: {}", filename, e.toString());
            }
        }
        void showRestart(Minecraft client, ModrinthRelaunch.Plan plan) {
            relaunchPlan = plan;
            update(client, State.RESTART);
        }
        void showUnavailable(Minecraft client) { update(client, State.UNAVAILABLE); }
        void showReview(Minecraft client, int count) {
            reviewedMods = count;
            update(client, State.REVIEW);
        }
        void showSetup(Minecraft client, ModrinthRelaunch.Plan plan) {
            relaunchPlan = plan;
            update(client, State.SETUP);
        }

        private void restartAndReconnect() {
            if (restarting || relaunchPlan == null) return;
            Path gameDir = FabricLoader.getInstance().getGameDir();
            try {
                DirectSyncClient.requestRelaunch(gameDir, relaunchPlan);
                if (state == State.SETUP) DirectSyncClient.launchRelaunchHelper(gameDir);
                restarting = true;
                minecraft.stop();
            } catch (Exception e) {
                LOGGER.error("Could not schedule automatic Modrinth relaunch", e);
                try { Files.deleteIfExists(DirectSyncCli.relaunchRequest(gameDir)); }
                catch (IOException ignored) {}
                relaunchError = true;
            }
        }

        @Override
        public void onClose() {
            cancelled = true;
            minecraft.setScreen(parent);
        }

        @Override
        public void removed() {
            if (iconTexture != null) minecraft.getTextureManager().release(iconTexture);
        }

        @Override
        public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
            renderBackground(graphics, mouseX, mouseY, partialTick);
            if (state == State.DOWNLOADING && progress != null) {
                ProgressSnapshot item = progress;
                ModMetadata.Info info = item.info();
                String name = info == null ? item.entry().modId() : info.name();
                String developer = info == null ? "Unknown developer" : info.developer();
                String version = info == null ? "Version unknown" : "Version " + info.version();
                if (name.length() > 42) name = name.substring(0, 39) + "...";
                graphics.drawCenteredString(font, title, width / 2, height / 2 - 78, 0xFFFFFF);
                int iconX = width / 2 - 112;
                int iconY = height / 2 - 49;
                if (iconTexture != null && item.entry().name().equals(iconFile)) {
                    graphics.blit(iconTexture, iconX, iconY, 32, 32, 0, 0,
                            iconWidth, iconHeight, iconWidth, iconHeight);
                } else {
                    graphics.fill(iconX, iconY, iconX + 32, iconY + 32, 0xFF555555);
                    graphics.drawCenteredString(font, name.substring(0, 1), iconX + 16, iconY + 12, 0xFFFFFF);
                }
                graphics.drawString(font, name, width / 2 - 70, height / 2 - 45, 0xFFFFFF);
                if (developer.length() > 38) developer = developer.substring(0, 35) + "...";
                graphics.drawString(font, developer, width / 2 - 70, height / 2 - 29, 0xAAAAAA);
                graphics.drawString(font, version, width / 2 - 70, height / 2 - 16, 0xAAAAAA);
                graphics.drawCenteredString(font, item.phase() + " | " + item.index() + "/" + item.total(),
                        width / 2, height / 2 + 5, 0xDDDDDD);
                int barX = width / 2 - 110;
                int barY = height / 2 + 25;
                int filled = item.entry().size() == 0 ? 220
                        : (int) Math.min(220, Math.max(0, item.received() * 220 / item.entry().size()));
                graphics.fill(barX, barY, barX + 220, barY + 12, 0xFF444444);
                graphics.fill(barX + 1, barY + 1, barX + Math.max(1, filled - 1), barY + 11, 0xFF59C974);
                super.render(graphics, mouseX, mouseY, partialTick);
                return;
            }
            graphics.drawCenteredString(font, title, width / 2, height / 2 - 42, 0xFFFFFF);
            Component first = switch (state) {
                case CHECKING -> Component.literal("Checking the server modpack before connecting...");
                case DOWNLOADING -> Component.literal("Downloading and verifying required mods...");
                case RESTART -> Component.literal("Update downloaded. Restart Minecraft to load the mods.");
                case UNAVAILABLE -> Component.literal("Could not reach the server's Direct Sync service.");
                case REVIEW -> Component.literal("Sent " + reviewedMods + " newer client mod(s) for server owner review.");
                case SETUP -> Component.literal("Required mods are present but Minecraft has not loaded them yet.");
            };
            Component second = switch (state) {
                case CHECKING, DOWNLOADING -> Component.literal("Keep Minecraft open until this check finishes.");
                case RESTART -> Component.literal(relaunchError
                        ? "Automatic restart failed. Quit and relaunch from Modrinth."
                        : relaunchPlan == null ? "Quit Minecraft, then relaunch this pack from its launcher."
                        : "The update applies after Minecraft closes; Modrinth will rejoin.");
                case UNAVAILABLE -> Component.literal("Check the server version and network access, or connect anyway.");
                case REVIEW -> Component.literal("Wait for the owner to review them and restart the server.");
                case SETUP -> Component.literal(relaunchError
                        ? "Automatic restart failed. Quit and relaunch from Modrinth."
                        : relaunchPlan == null ? "Quit Minecraft, then relaunch this pack from its launcher."
                        : "Modrinth will relaunch this pack and rejoin the server.");
            };
            graphics.drawCenteredString(font, first, width / 2, height / 2 - 8, 0xFFFFFF);
            graphics.drawCenteredString(font, second, width / 2, height / 2 + 8, 0xAAAAAA);
            super.render(graphics, mouseX, mouseY, partialTick);
        }

        private enum State { CHECKING, DOWNLOADING, RESTART, UNAVAILABLE, SETUP, REVIEW }
    }
}
