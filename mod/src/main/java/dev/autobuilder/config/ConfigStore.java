package dev.autobuilder.config;

import dev.autobuilder.AutoBuilderMod;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Saves and restores BuilderConfig as a plain properties file.
 *
 * Reflection over the public fields rather than a hand-written mapping, so
 * adding an option to BuilderConfig doesn't silently fail to persist -- the
 * commonest way settings-saving rots.
 *
 * Deliberately not JSON: no dependency, and a properties file is something you
 * can open and edit by hand when a value needs to go outside what the GUI
 * offers (an odd price cap, a stranger regex).
 */
public class ConfigStore {

    private static final String FILE_NAME = "autobuilder.properties";

    private final Path file;

    public ConfigStore(Path configDir) {
        this.file = configDir.resolve(FILE_NAME);
    }

    public void load(BuilderConfig config) {
        if (!Files.exists(file)) return;
        Properties properties = new Properties();
        try (var in = Files.newInputStream(file)) {
            properties.load(in);
        } catch (Exception e) {
            AutoBuilderMod.LOG.warn("Couldn't read {}: {}", file, e.toString());
            return;
        }

        for (Field field : savableFields()) {
            String raw = properties.getProperty(field.getName());
            if (raw == null) continue;
            try {
                field.set(config, parse(field, raw));
            } catch (Exception e) {
                // One bad value shouldn't discard the rest of the file.
                AutoBuilderMod.LOG.warn("Ignoring bad setting {}={}", field.getName(), raw);
            }
        }
    }

    public void save(BuilderConfig config) {
        if (!config.saveSettings) return;
        Properties properties = new Properties();
        for (Field field : savableFields()) {
            try {
                Object value = field.get(config);
                if (value != null) properties.setProperty(field.getName(), String.valueOf(value));
            } catch (IllegalAccessException ignored) {
                // Skip anything unreadable rather than failing the whole save.
            }
        }
        try {
            Files.createDirectories(file.getParent());
            try (var out = Files.newOutputStream(file)) {
                properties.store(out, "Auto Litematica Builder settings");
            }
        } catch (Exception e) {
            AutoBuilderMod.LOG.warn("Couldn't write {}: {}", file, e.toString());
        }
    }

    private List<Field> savableFields() {
        List<Field> fields = new ArrayList<>();
        for (Field field : BuilderConfig.class.getDeclaredFields()) {
            if (Modifier.isStatic(field.getModifiers())) continue;
            if (!Modifier.isPublic(field.getModifiers())) continue;
            fields.add(field);
        }
        return fields;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Object parse(Field field, String raw) {
        Class<?> type = field.getType();
        if (type == boolean.class) return Boolean.parseBoolean(raw);
        if (type == int.class) return Integer.parseInt(raw.trim());
        if (type == double.class) return Double.parseDouble(raw.trim());
        if (type == String.class) return raw;
        if (type.isEnum()) return Enum.valueOf((Class<Enum>) type, raw.trim());
        throw new IllegalArgumentException("unsupported setting type " + type);
    }
}
