package com.example.worthedit;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class WorthEditor extends JavaPlugin implements TabExecutor {
    WorthFile worth;
    DataStore store;
    PriceEngine engine;
    ShopCap shopCap;
    Gui gui;
    private BukkitTask task;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        store = new DataStore(this);
        store.load();
        shopCap = new ShopCap(this);
        engine = new PriceEngine(this);
        engine.reloadConfig();
        gui = new Gui(this);
        getServer().getPluginManager().registerEvents(gui, this);
        loadWorth();
        scheduleDynamic();
        var cmd = getCommand("worthedit");
        if (cmd != null) {
            cmd.setExecutor(this);
            cmd.setTabCompleter(this);
        }
    }

    @Override
    public void onDisable() {
        if (task != null) task.cancel();
        store.save();
    }

    // ------------------------------------------------------------ core

    void loadWorth() {
        String rel = getConfig().getString("worth-file", "plugins/UltimateDonutSmp/worth.yml");
        File root = getDataFolder().getParentFile().getParentFile();
        File f = new File(root, rel);
        worth = new WorthFile(f, getLogger());
        if (!f.isFile()) {
            getLogger().warning("worth file not found: " + f.getAbsolutePath() + " (set worth-file in config.yml)");
            return;
        }
        worth.load();
        getLogger().info("Loaded " + worth.data.size() + " categories from " + f.getName());
    }

    /** Writes staged changes and runs the reload command. Returns a chat message. */
    String saveWorth() {
        if (!worth.exists()) return "&cWorth file not found. Check worth-file in config.yml.";
        try {
            int n = worth.save();
            String cmd = getConfig().getString("reload-command", "");
            if (!cmd.isBlank()) Bukkit.dispatchCommand(Bukkit.getConsoleSender(), cmd);
            store.save();
            return n == 0 ? "&7Nothing to save." : "&aSaved &f" + n + " &aprices"
                    + (cmd.isBlank() ? " &7(no reload-command set, reload your shop plugin manually)." : " &aand reloaded the shop.");
        } catch (IOException e) {
            getLogger().warning("Save failed: " + e.getMessage());
            return "&cSave failed: " + e.getMessage();
        }
    }

    void setDynamic(boolean on) {
        store.dynamic = on;
        store.save();
        scheduleDynamic();
    }

    private void scheduleDynamic() {
        if (task != null) task.cancel();
        task = null;
        if (!store.dynamic) return;
        long ticks = Math.max(1, getConfig().getLong("dynamic.interval-minutes", 60)) * 60L * 20L;
        task = Bukkit.getScheduler().runTaskTimer(this, () -> engine.runDynamic(), ticks, ticks);
    }

    // ------------------------------------------------------------ commands

    @Override
    public boolean onCommand(CommandSender s, Command command, String label, String[] a) {
        String sub = a.length == 0 ? "gui" : a[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "gui" -> {
                if (!(s instanceof Player p)) {
                    s.sendMessage("Players only.");
                    return true;
                }
                if (!worth.exists()) {
                    s.sendMessage("Worth file not found. Set worth-file in config.yml and run /worthedit reload.");
                    return true;
                }
                gui.openMain(p);
            }
            case "save" -> s.sendMessage(color(saveWorth()));
            case "reload" -> {
                reloadConfig();
                store.load();
                engine.reloadConfig();
                loadWorth();
                scheduleDynamic();
                s.sendMessage("Reloaded.");
            }
            case "shopcheck" -> {
                s.sendMessage("Buy prices loaded: " + shopCap.size());
                for (String n : shopCap.notes()) s.sendMessage(" - " + n);
                List<String> bad = engine.shopViolations();
                s.sendMessage(bad.isEmpty() ? "No item sells above the cap." : bad.size() + " item(s) sell above the cap (run /worthedit autobalance or dynamic run, then save):");
                for (int i = 0; i < Math.min(15, bad.size()); i++) s.sendMessage(" - " + bad.get(i));
            }
            case "autobalance" -> {
                int n = engine.stageAutoBalance();
                s.sendMessage("Staged " + n + " changes. Run /worthedit save to write them.");
            }
            case "dynamic" -> {
                if (a.length < 2) {
                    s.sendMessage("Dynamic pricing is " + (store.dynamic ? "ON" : "OFF") + ". Use /worthedit dynamic <on|off|run>");
                    return true;
                }
                switch (a[1].toLowerCase(Locale.ROOT)) {
                    case "on" -> { setDynamic(true); s.sendMessage("Dynamic pricing ON."); }
                    case "off" -> { setDynamic(false); s.sendMessage("Dynamic pricing OFF."); }
                    case "run" -> { engine.runDynamic(); s.sendMessage("Cycle complete."); }
                    default -> s.sendMessage("Use /worthedit dynamic <on|off|run>");
                }
            }
            case "sold" -> {
                // /worthedit sold <material> <amount>  (hook this into your shop's sell action)
                if (a.length < 3) {
                    s.sendMessage("Usage: /worthedit sold <material> <amount>");
                    return true;
                }
                Material m = Material.matchMaterial(a[1]);
                if (m == null) {
                    s.sendMessage("Unknown material.");
                    return true;
                }
                try {
                    store.addPressure(m.name(), Double.parseDouble(a[2]));
                } catch (NumberFormatException e) {
                    s.sendMessage("Amount must be a number.");
                }
            }
            case "set" -> {
                if (a.length < 3) {
                    s.sendMessage("Usage: /worthedit set <material> <price>");
                    return true;
                }
                Double v = Gui.parsePrice(a[2]);
                String key = a[1].toUpperCase(Locale.ROOT);
                if (v == null || v <= 0) {
                    s.sendMessage("Invalid price.");
                    return true;
                }
                int n = 0;
                for (Map.Entry<String, ? extends Map<String, Double>> cat : worth.data.entrySet()) {
                    if (cat.getValue().containsKey(key) && worth.set(cat.getKey(), key, PriceEngine.round(v))) n++;
                }
                if (n > 0) {
                    store.baseline.put(key, v);
                    store.save();
                }
                s.sendMessage(n == 0 ? "No such item (or unchanged)." : "Staged " + key + " = " + v + ". Run /worthedit save.");
            }
            default -> s.sendMessage("/worthedit [gui|save|reload|autobalance|shopcheck|dynamic <on|off|run>|set <material> <price>|sold <material> <amount>]");
        }
        return true;
    }

    private static String color(String legacy) {
        return legacy.replaceAll("&([0-9a-fk-or])", "\u00a7$1");
    }

    @Override
    public List<String> onTabComplete(CommandSender s, Command c, String l, String[] a) {
        List<String> out = new ArrayList<>();
        if (a.length == 1) {
            for (String o : List.of("gui", "save", "reload", "autobalance", "dynamic", "set", "sold", "shopcheck")) {
                if (o.startsWith(a[0].toLowerCase(Locale.ROOT))) out.add(o);
            }
        } else if (a.length == 2 && a[0].equalsIgnoreCase("dynamic")) {
            out.addAll(List.of("on", "off", "run"));
        }
        return out;
    }
}
