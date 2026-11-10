package net.supersnetwork.direct_sync;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;

/** Resolves Modrinth's official instance launch link without retaining Minecraft credentials. */
final class ModrinthRelaunch {
    private static final Logger LOGGER = LoggerFactory.getLogger("Direct Sync");

    record Plan(String url, Path executable) {}

    private ModrinthRelaunch() {}

    static Optional<Plan> find(Path gameDir, String serverAddress) {
        Path profile = gameDir.toAbsolutePath().normalize();
        Path profiles = profile.getParent();
        if (profiles == null || !"profiles".equalsIgnoreCase(profiles.getFileName().toString())
                || serverAddress == null || serverAddress.isBlank()) return Optional.empty();
        Path appData = profiles.getParent();
        if (appData == null || !"ModrinthApp".equalsIgnoreCase(appData.getFileName().toString())) {
            return Optional.empty();
        }
        Path database = appData.resolve("app.db");
        if (!Files.isRegularFile(database)) return Optional.empty();
        try {
            Class.forName("org.sqlite.JDBC");
            String jdbc = "jdbc:sqlite:" + database.toUri() + "?mode=ro";
            try (var connection = DriverManager.getConnection(jdbc);
                 PreparedStatement statement = connection.prepareStatement(
                         "SELECT id FROM instances WHERE path = ? LIMIT 1")) {
                statement.setString(1, profile.getFileName().toString());
                try (ResultSet result = statement.executeQuery()) {
                    if (!result.next()) return Optional.empty();
                    String id = result.getString(1);
                    if (id == null || !id.matches("[A-Za-z0-9:_-]{1,128}")) return Optional.empty();
                    String url = "modrinth://launch/instance/"
                            + URLEncoder.encode(id, StandardCharsets.UTF_8)
                            + "?server=" + URLEncoder.encode(serverAddress, StandardCharsets.UTF_8);
                    return Optional.of(new Plan(url, findExecutable()));
                }
            }
        } catch (ClassNotFoundException | SQLException e) {
            LOGGER.warn("Could not identify the Modrinth instance for automatic relaunch: {}", e.toString());
            return Optional.empty();
        }
    }

    private static Path findExecutable() {
        String localAppData = System.getenv("LOCALAPPDATA");
        if (localAppData != null) {
            Path candidate = Path.of(localAppData, "Modrinth App", "Modrinth App.exe");
            if (Files.isRegularFile(candidate)) return candidate;
        }
        for (ProcessHandle process = ProcessHandle.current(); process != null; process = process.parent().orElse(null)) {
            Optional<String> command = process.info().command();
            if (command.isPresent()) {
                Path candidate = Path.of(command.get());
                if ("Modrinth App.exe".equalsIgnoreCase(candidate.getFileName().toString())
                        && Files.isRegularFile(candidate)) return candidate;
            }
        }
        return null;
    }
}
