package com.example.worthedit;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.logging.Logger;

/**
 * Reads the TYPE section of worth.yml and writes changes back by patching only
 * the changed lines, so comments and every other section stay untouched.
 */
final class WorthFile {
    private final File file;
    private final Logger log;
    /** category -> (key -> price). Keys keep file order. */
    final Map<String, LinkedHashMap<String, Double>> data = new LinkedHashMap<>();
    private final Map<String, Double> dirty = new LinkedHashMap<>();
    private boolean backedUp = false;

    WorthFile(File file, Logger log) {
        this.file = file;
        this.log = log;
    }

    boolean exists() {
        return file.isFile();
    }

    void load() {
        data.clear();
        dirty.clear();
        if (!file.isFile()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection type = y.getConfigurationSection("TYPE");
        if (type == null) {
            log.warning("No TYPE section found in " + file.getName());
            return;
        }
        for (String cat : type.getKeys(false)) {
            ConfigurationSection sec = type.getConfigurationSection(cat);
            if (sec == null) continue;
            LinkedHashMap<String, Double> map = new LinkedHashMap<>();
            for (String key : sec.getKeys(false)) {
                if (sec.isConfigurationSection(key)) continue;
                map.put(key, sec.getDouble(key));
            }
            data.put(cat, map);
        }
    }

    int dirtyCount() {
        return dirty.size();
    }

    Double get(String cat, String key) {
        Map<String, Double> m = data.get(cat);
        return m == null ? null : m.get(key);
    }

    /** First price found for a plain material key in any category. */
    Double firstPrice(Material m) {
        for (Map<String, Double> map : data.values()) {
            Double v = map.get(m.name());
            if (v != null) return v;
        }
        return null;
    }

    /** Stages a change in memory. Returns true if the value actually changed. */
    boolean set(String cat, String key, double value) {
        Map<String, Double> m = data.get(cat);
        if (m == null || !m.containsKey(key)) return false;
        if (Math.abs(m.get(key) - value) < 1e-9) return false;
        m.put(key, value);
        dirty.put(cat + "\0" + key, value);
        return true;
    }

    /** Writes staged changes to disk (with a one-time .bak backup). Returns number of lines patched. */
    int save() throws IOException {
        if (dirty.isEmpty() || !file.isFile()) return 0;
        if (!backedUp) {
            File bak = new File(file.getParentFile(), file.getName() + ".bak");
            Files.copy(file.toPath(), bak.toPath(), StandardCopyOption.REPLACE_EXISTING);
            backedUp = true;
        }
        List<String> lines = new ArrayList<>(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8));
        boolean inType = false;
        String cat = null;
        int patched = 0;
        for (int i = 0; i < lines.size(); i++) {
            String l = lines.get(i);
            String t = l.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            int indent = l.length() - l.stripLeading().length();
            if (indent == 0) {
                inType = t.equals("TYPE:");
                cat = null;
                continue;
            }
            if (!inType) continue;
            if (indent == 2) {
                cat = t.endsWith(":") ? t.substring(0, t.length() - 1) : null;
                continue;
            }
            if (indent == 4 && cat != null) {
                int idx = t.indexOf(": ");
                if (idx < 0) continue;
                String key = t.substring(0, idx);
                Double nv = dirty.get(cat + "\0" + key);
                if (nv != null) {
                    lines.set(i, " ".repeat(indent) + key + ": " + format(nv));
                    patched++;
                }
            }
        }
        Files.write(file.toPath(), lines, StandardCharsets.UTF_8);
        dirty.clear();
        return patched;
    }

    static String format(double v) {
        if (v == Math.rint(v) && Math.abs(v) < 1e12) return String.format(Locale.US, "%.1f", v);
        return new java.math.BigDecimal(v).setScale(2, java.math.RoundingMode.HALF_UP)
                .stripTrailingZeros().toPlainString();
    }
}
