package net.supersnetwork.direct_sync;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.HashSet;
import java.util.jar.JarFile;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Map;
import java.util.HashMap;

final class SyncProtocol {
    static final String HEADER = "directsync/2";
    static final int MAX_MODS = 256;
    static final long MAX_JAR_BYTES = 128L * 1024 * 1024;
    static final long MAX_TOTAL_BYTES = 1024L * 1024 * 1024;
    static final int MAX_MANIFEST_BYTES = 64 * 1024;

    private SyncProtocol() {}

    private static final Pattern MOD_ID = Pattern.compile("\"id\"\\s*:\\s*\"([a-z][a-z0-9_-]{1,63})\"");

    record ModFile(String name, String modId, long size, String sha256) {
        ModFile {
            if (!validName(name) || !modId.matches("[a-z][a-z0-9_-]{1,63}")
                    || size < 0 || size > MAX_JAR_BYTES || !sha256.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("Invalid mod manifest entry: " + name);
            }
        }

        String line() {
            return name + "\t" + modId + "\t" + size + "\t" + sha256;
        }
    }

    static boolean validName(String name) {
        return name != null && name.length() <= 128
                && name.matches("[A-Za-z0-9][A-Za-z0-9 ._+()\\[\\]-]*\\.jar");
    }

    static List<ModFile> parseManifest(String manifest) throws IOException {
        String[] lines = manifest.split("\\r?\\n", -1);
        if (lines.length == 0 || !HEADER.equals(lines[0])) {
            throw new IOException("Unsupported Direct Sync manifest");
        }
        List<ModFile> result = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Set<String> ids = new HashSet<>();
        long total = 0;
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isEmpty()) continue;
            String[] fields = lines[i].split("\\t", -1);
            if (fields.length != 4) throw new IOException("Malformed manifest line " + (i + 1));
            try {
                ModFile entry = new ModFile(fields[0], fields[1], Long.parseLong(fields[2]), fields[3]);
                if (!names.add(entry.name()) || !ids.add(entry.modId())) {
                    throw new IOException("Duplicate mod name or ID: " + entry.name());
                }
                total += entry.size();
                if (result.size() >= MAX_MODS || total > MAX_TOTAL_BYTES) {
                    throw new IOException("Manifest exceeds the Direct Sync limits");
                }
                result.add(entry);
            } catch (IllegalArgumentException e) {
                throw new IOException("Invalid manifest line " + (i + 1), e);
            }
        }
        return result;
    }

    static String manifest(List<ModFile> entries) {
        StringBuilder out = new StringBuilder(HEADER).append('\n');
        for (ModFile entry : entries) out.append(entry.line()).append('\n');
        return out.toString();
    }

    static String sha256(Path path) throws IOException {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        try (InputStream in = new DigestInputStream(Files.newInputStream(path), digest)) {
            in.transferTo(java.io.OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    static boolean matches(Path path, ModFile entry) throws IOException {
        return Files.isRegularFile(path) && Files.size(path) == entry.size()
                && sha256(path).equals(entry.sha256());
    }

    static boolean installedMatches(Path modsDir, List<ModFile> manifest) throws IOException {
        Map<String, Path> root = rootMods(modsDir);
        for (ModFile entry : manifest) {
            Path path = root.get(entry.modId());
            if (path == null || !matches(path, entry)) return false;
        }
        Path previous = modsDir.resolve(".directsync-installed-manifest.txt");
        if (Files.isRegularFile(previous)) {
            List<ModFile> old = parseManifest(Files.readString(previous, StandardCharsets.UTF_8));
            Set<String> wanted = new HashSet<>();
            for (ModFile entry : manifest) wanted.add(entry.modId());
            for (ModFile entry : old) {
                Path path = root.get(entry.modId());
                if (!wanted.contains(entry.modId()) && path != null && matches(path, entry)) return false;
            }
        }
        Path legacy = modsDir.resolve("directsync");
        if (Files.isDirectory(legacy)) {
            try (var paths = Files.list(legacy)) {
                if (paths.anyMatch(path -> path.getFileName().toString().endsWith(".jar"))) return false;
            }
        }
        return true;
    }

    static boolean rootMatch(Path modsDir, ModFile entry) throws IOException {
        Path path = rootMods(modsDir).get(entry.modId());
        return path != null && matches(path, entry);
    }

    static Path findRootMatch(Path modsDir, ModFile entry) throws IOException {
        Path path = rootMods(modsDir).get(entry.modId());
        return path != null && matches(path, entry) ? path : null;
    }

    static Map<String, Path> rootMods(Path modsDir) throws IOException {
        Map<String, Path> result = new HashMap<>();
        try (var paths = Files.list(modsDir)) {
            for (Path path : paths.toList()) {
                if (!Files.isRegularFile(path) || !path.getFileName().toString().endsWith(".jar")) continue;
                try {
                    result.putIfAbsent(readModId(path), path);
                } catch (IOException ignored) {
                    // Other loaders' or damaged JARs cannot satisfy a Fabric manifest entry.
                }
            }
        }
        return result;
    }

    static String readModId(Path path) throws IOException {
        try (JarFile jar = new JarFile(path.toFile())) {
            var metadata = jar.getJarEntry("fabric.mod.json");
            if (metadata == null || metadata.getSize() > 1024 * 1024) {
                throw new IOException("Not a Fabric mod JAR: " + path.getFileName());
            }
            try (var in = jar.getInputStream(metadata)) {
                byte[] bytes = in.readNBytes(1024 * 1024 + 1);
                if (bytes.length > 1024 * 1024) throw new IOException("Fabric metadata too large: " + path);
                Matcher matcher = MOD_ID.matcher(new String(bytes, StandardCharsets.UTF_8));
                if (!matcher.find()) throw new IOException("No mod ID in " + path);
                return matcher.group(1);
            }
        }
    }

    static void requireFabricJar(Path path) throws IOException {
        try (JarFile jar = new JarFile(path.toFile())) {
            if (jar.getJarEntry("fabric.mod.json") == null) {
                throw new IOException("Not a Fabric mod JAR: " + path.getFileName());
            }
        }
    }
}
