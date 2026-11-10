package net.supersnetwork.direct_sync;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.jar.JarFile;

/** Display metadata is separate from the hash manifest for wire compatibility. */
final class ModMetadata {
    static final int MAX_METADATA_BYTES = 256 * 1024;
    static final int MAX_ICON_BYTES = 1024 * 1024;
    private static final String HEADER = "directsync-meta/1";

    record Info(String fileName, String modId, String name, String version, String developer, String iconPath) {}

    private ModMetadata() {}

    static Info read(Path path) throws IOException {
        try (JarFile jar = new JarFile(path.toFile())) {
            JsonObject json = metadata(jar);
            String filename = path.getFileName().toString();
            String id = field(json, "id", "");
            String name = field(json, "name", id);
            String version = field(json, "version", "unknown");
            String developer = developers(json);
            String icon = iconPath(json);
            if (!SyncProtocol.validName(filename) || !id.matches("[a-z][a-z0-9_-]{1,63}")) {
                throw new IOException("Invalid Fabric metadata in " + filename);
            }
            return new Info(filename, id, trim(name, 96), trim(version, 64), trim(developer, 128), icon);
        } catch (RuntimeException e) {
            throw new IOException("Invalid Fabric metadata in " + path.getFileName(), e);
        }
    }

    static byte[] icon(Path path, Info info) throws IOException {
        if (info.iconPath().isEmpty()) return new byte[0];
        try (JarFile jar = new JarFile(path.toFile())) {
            var entry = jar.getJarEntry(info.iconPath());
            if (entry == null || entry.isDirectory() || entry.getSize() > MAX_ICON_BYTES) return new byte[0];
            try (var in = jar.getInputStream(entry)) {
                byte[] bytes = in.readNBytes(MAX_ICON_BYTES + 1);
                return bytes.length > MAX_ICON_BYTES ? new byte[0] : bytes;
            }
        }
    }

    static String serialize(Iterable<Info> infos) {
        StringBuilder out = new StringBuilder(HEADER).append('\n');
        for (Info info : infos) {
            out.append(info.fileName()).append('\t').append(info.modId()).append('\t')
                    .append(encode(info.name())).append('\t').append(encode(info.version())).append('\t')
                    .append(encode(info.developer())).append('\t').append(!info.iconPath().isEmpty()).append('\n');
        }
        return out.toString();
    }

    static Map<String, Info> parse(String text) throws IOException {
        if (text.getBytes(StandardCharsets.UTF_8).length > MAX_METADATA_BYTES) throw new IOException("Metadata is too large");
        String[] lines = text.split("\\r?\\n");
        if (lines.length == 0 || !HEADER.equals(lines[0])) throw new IOException("Unsupported metadata format");
        Map<String, Info> infos = new HashMap<>();
        for (int i = 1; i < lines.length; i++) {
            if (lines[i].isEmpty()) continue;
            String[] fields = lines[i].split("\\t", -1);
            if (fields.length != 6 || !SyncProtocol.validName(fields[0])
                    || !fields[1].matches("[a-z][a-z0-9_-]{1,63}")) throw new IOException("Invalid metadata line");
            try {
                Info info = new Info(fields[0], fields[1], decode(fields[2]), decode(fields[3]),
                        decode(fields[4]), "true".equals(fields[5]) ? "icon" : "");
                if (infos.putIfAbsent(info.fileName(), info) != null) throw new IOException("Duplicate metadata entry");
            } catch (IllegalArgumentException e) {
                throw new IOException("Invalid metadata encoding", e);
            }
        }
        return Map.copyOf(infos);
    }

    private static JsonObject metadata(JarFile jar) throws IOException {
        var entry = jar.getJarEntry("fabric.mod.json");
        if (entry == null || entry.getSize() > 1024 * 1024) throw new IOException("Missing Fabric metadata");
        try (var in = jar.getInputStream(entry)) {
            byte[] bytes = in.readNBytes(1024 * 1024 + 1);
            if (bytes.length > 1024 * 1024) throw new IOException("Fabric metadata is too large");
            return JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        }
    }

    private static String developers(JsonObject json) {
        JsonElement authors = json.get("authors");
        if (authors == null) return "Unknown developer";
        if (authors.isJsonPrimitive()) return authors.getAsString();
        if (!authors.isJsonArray()) return "Unknown developer";
        JsonArray array = authors.getAsJsonArray();
        StringBuilder out = new StringBuilder();
        for (JsonElement item : array) {
            String name = item.isJsonPrimitive() ? item.getAsString()
                    : item.isJsonObject() ? field(item.getAsJsonObject(), "name", "") : "";
            if (name.isBlank()) continue;
            if (!out.isEmpty()) out.append(", ");
            out.append(name);
            if (out.length() >= 128) break;
        }
        return out.isEmpty() ? "Unknown developer" : out.toString();
    }

    private static String iconPath(JsonObject json) {
        JsonElement icon = json.get("icon");
        String path = "";
        if (icon != null && icon.isJsonPrimitive()) path = icon.getAsString();
        if (icon != null && icon.isJsonObject()) {
            JsonObject sizes = icon.getAsJsonObject();
            if (sizes.has("128")) path = sizes.get("128").getAsString();
            else if (sizes.has("64")) path = sizes.get("64").getAsString();
            else if (!sizes.entrySet().isEmpty()) path = sizes.entrySet().iterator().next().getValue().getAsString();
        }
        return path.endsWith(".png") && !path.startsWith("/") && !path.contains("..") && path.length() <= 256
                ? path : "";
    }

    private static String field(JsonObject object, String key, String fallback) {
        JsonElement value = object.get(key);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : fallback;
    }

    private static String trim(String value, int limit) {
        return value.length() <= limit ? value : value.substring(0, limit);
    }

    private static String encode(String text) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    private static String decode(String text) {
        return new String(Base64.getUrlDecoder().decode(text), StandardCharsets.UTF_8);
    }
}
