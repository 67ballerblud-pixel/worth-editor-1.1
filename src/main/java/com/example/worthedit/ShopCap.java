package com.example.worthedit;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.util.*;

/**
 * Knows what each item costs to BUY in the shop, so sell prices can be kept
 * below it. Buy prices come from the shop plugin's own file(s) (shop-cap.buy-files)
 * plus any manual overrides in shop-cap.buy-prices. The shop file is only read, never written.
 */
final class ShopCap {
    private static final String[] PRICE_KEYS = {"buy-price", "buyPrice", "buy_price", "buy", "price", "cost"};

    private final WorthEditor plugin;
    private final Map<Material, Double> buy = new HashMap<>();
    private final List<String> notes = new ArrayList<>();

    ShopCap(WorthEditor plugin) {
        this.plugin = plugin;
    }

    boolean isEmpty() {
        return buy.isEmpty();
    }

    int size() {
        return buy.size();
    }

    Double buy(Material m) {
        return buy.get(m);
    }

    List<String> notes() {
        return notes;
    }

    void reload() {
        buy.clear();
        notes.clear();
        var cfg = plugin.getConfig();
        File root = plugin.getDataFolder().getParentFile().getParentFile();
        String worthPath = cfg.getString("worth-file", "");

        for (String rel : cfg.getStringList("shop-cap.buy-files")) {
            if (rel == null || rel.isBlank()) continue;
            if (rel.equals(worthPath)) {
                notes.add("Skipped " + rel + " (that is the worth file, which holds SELL prices).");
                continue;
            }
            File f = new File(root, rel);
            if (!f.isFile()) {
                notes.add("Buy file not found: " + rel);
                continue;
            }
            int before = buy.size();
            scan(YamlConfiguration.loadConfiguration(f));
            notes.add("Read " + rel + ": " + (buy.size() - before) + " new buy prices.");
        }

        ConfigurationSection manual = cfg.getConfigurationSection("shop-cap.buy-prices");
        if (manual != null) {
            for (String k : manual.getKeys(false)) {
                Material m = Material.matchMaterial(k);
                if (m == null) {
                    notes.add("Unknown material in shop-cap.buy-prices: " + k);
                    continue;
                }
                buy.put(m, manual.getDouble(k)); // manual override wins
            }
        }
        for (String n : notes) plugin.getLogger().info("[shop-cap] " + n);
        if (buy.isEmpty() && cfg.getBoolean("shop-cap.enabled", true)) {
            plugin.getLogger().warning("[shop-cap] No buy prices loaded, so sell prices are NOT being capped. "
                    + "Set shop-cap.buy-files (or shop-cap.buy-prices) in config.yml.");
        } else {
            plugin.getLogger().info("[shop-cap] " + buy.size() + " buy prices loaded.");
        }
    }

    /**
     * Format-agnostic scan. Recognises:
     *   DIAMOND: 500
     *   DIAMOND: { price: 500 }            (also buy / buy-price / cost)
     *   some-id: { material: DIAMOND, price: 500 }   (also item:)
     * at any depth. The cheapest price found for a material is used.
     */
    private void scan(ConfigurationSection sec) {
        for (String k : sec.getKeys(false)) {
            Object v = sec.get(k);
            if (v instanceof ConfigurationSection child) {
                Double p = priceOf(child);
                Material m = Material.matchMaterial(k);
                if (m == null) {
                    String name = child.getString("material", child.getString("item", null));
                    if (name != null) m = Material.matchMaterial(name);
                }
                if (m != null && p != null && p > 0) put(m, p);
                scan(child);
            } else if (v instanceof Number n) {
                Material m = Material.matchMaterial(k);
                if (m != null && n.doubleValue() > 0) put(m, n.doubleValue());
            }
        }
    }

    private static Double priceOf(ConfigurationSection s) {
        for (String name : PRICE_KEYS) {
            if (s.isDouble(name) || s.isInt(name) || s.isLong(name)) return s.getDouble(name);
        }
        return null;
    }

    private void put(Material m, double p) {
        buy.merge(m, p, Math::min);
    }
}
