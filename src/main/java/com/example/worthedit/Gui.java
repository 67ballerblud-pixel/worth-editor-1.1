package com.example.worthedit;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.text.DecimalFormat;
import java.util.*;

final class Gui implements Listener {
    private static final int PAGE_SIZE = 45;
    private static final DecimalFormat MONEY = new DecimalFormat("#,##0.##");

    private final WorthEditor plugin;
    private final Map<UUID, Prompt> prompts = new HashMap<>();

    private record Prompt(String cat, String key, int page, String filter, boolean search) {}

    private static final class H implements InventoryHolder {
        boolean main;
        String cat;
        int page;
        String filter = "";
        List<String> keys = List.of();
        Inventory inv;

        @Override
        public Inventory getInventory() {
            return inv;
        }
    }

    Gui(WorthEditor plugin) {
        this.plugin = plugin;
    }

    // ------------------------------------------------------------ helpers

    private static Component c(String legacy) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(legacy)
                .decoration(TextDecoration.ITALIC, false);
    }

    private static ItemStack item(Material m, String name, String... lore) {
        ItemStack is = new ItemStack(m);
        ItemMeta meta = is.getItemMeta();
        meta.displayName(c(name));
        if (lore.length > 0) {
            List<Component> l = new ArrayList<>();
            for (String s : lore) l.add(c(s));
            meta.lore(l);
        }
        is.setItemMeta(meta);
        return is;
    }

    private static ItemStack filler() {
        ItemStack is = new ItemStack(Material.GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = is.getItemMeta();
        meta.displayName(Component.empty());
        is.setItemMeta(meta);
        return is;
    }

    static String money(double v) {
        return "$" + MONEY.format(v);
    }

    private static Material categoryIcon(String cat) {
        return switch (cat) {
            case "CROPS" -> Material.WHEAT;
            case "ORES" -> Material.DIAMOND;
            case "MOBS" -> Material.BONE;
            case "NATURAL" -> Material.OAK_LOG;
            case "ARMOR_AND_TOOLS" -> Material.DIAMOND_PICKAXE;
            case "FISH" -> Material.COD;
            case "BOOK" -> Material.ENCHANTED_BOOK;
            case "POTION" -> Material.POTION;
            case "BLOCKS" -> Material.BRICKS;
            default -> Material.PAPER;
        };
    }

    // ------------------------------------------------------------ menus

    void openMain(Player p) {
        H h = new H();
        h.main = true;
        Inventory inv = Bukkit.createInventory(h, 45, c("&8Worth Editor"));
        h.inv = inv;
        for (int i = 0; i < 45; i++) inv.setItem(i, filler());

        int i = 0;
        for (String cat : plugin.worth.data.keySet()) {
            int slot = i < 7 ? 10 + i : 19 + (i - 7);
            if (slot > 34) break;
            int count = plugin.worth.data.get(cat).size();
            inv.setItem(slot, item(categoryIcon(cat), "&b" + cat.replace('_', ' '),
                    "&7" + count + " entries", "", "&eClick to edit"));
            i++;
        }

        boolean dyn = plugin.store.dynamic;
        inv.setItem(38, item(dyn ? Material.LIME_DYE : Material.GRAY_DYE,
                "&6Dynamic pricing: " + (dyn ? "&aON" : "&cOFF"),
                "&7Reprices items every " + plugin.getConfig().getInt("dynamic.interval-minutes", 60) + " min",
                "&7based on supply and demand.", "",
                "&eClick &7to toggle", "&eShift-click &7to run a cycle now"));
        inv.setItem(40, item(Material.ANVIL, "&6Auto-balance",
                "&7Recalculates prices from raw values", "&7and recipes (no loops).",
                "", "&eClick &7to stage changes",
                "&7(then press Save)"));
        int dirty = plugin.worth.dirtyCount();
        inv.setItem(42, item(Material.EMERALD, "&aSave & reload",
                dirty > 0 ? "&c" + dirty + " unsaved change(s)" : "&7No unsaved changes",
                "", "&eClick &7to write worth.yml"));
        inv.setItem(44, item(Material.BARRIER, "&cClose"));
        p.openInventory(inv);
    }

    void openCategory(Player p, String cat, int page, String filter) {
        Map<String, Double> map = plugin.worth.data.get(cat);
        if (map == null) {
            openMain(p);
            return;
        }
        H h = new H();
        h.cat = cat;
        h.filter = filter == null ? "" : filter;
        List<String> keys = new ArrayList<>();
        String f = h.filter.toLowerCase(Locale.ROOT);
        for (String k : map.keySet()) {
            if (f.isEmpty() || k.toLowerCase(Locale.ROOT).contains(f)) keys.add(k);
        }
        int pages = Math.max(1, (keys.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        h.page = Math.max(0, Math.min(page, pages - 1));
        h.keys = keys;

        String title = "&8" + cat.replace('_', ' ').toLowerCase(Locale.ROOT) + " (" + (h.page + 1) + "/" + pages + ")";
        Inventory inv = Bukkit.createInventory(h, 54, c(title));
        h.inv = inv;

        for (int i = 0; i < PAGE_SIZE; i++) {
            int idx = h.page * PAGE_SIZE + i;
            if (idx >= keys.size()) break;
            inv.setItem(i, entry(cat, keys.get(idx), map.get(keys.get(idx))));
        }
        for (int i = 45; i < 54; i++) inv.setItem(i, filler());
        if (h.page > 0) inv.setItem(45, item(Material.ARROW, "&ePrevious page"));
        if (h.page < pages - 1) inv.setItem(53, item(Material.ARROW, "&eNext page"));
        inv.setItem(47, item(Material.NAME_TAG, "&bSearch",
                h.filter.isEmpty() ? "&7Click and type in chat" : "&7Filter: &f" + h.filter,
                h.filter.isEmpty() ? "" : "&eRight-click &7to clear"));
        int dirty = plugin.worth.dirtyCount();
        inv.setItem(49, item(Material.EMERALD, "&aSave & reload",
                dirty > 0 ? "&c" + dirty + " unsaved change(s)" : "&7No unsaved changes"));
        inv.setItem(51, item(Material.SPECTRAL_ARROW, "&eBack to categories"));
        p.openInventory(inv);
    }

    private ItemStack entry(String cat, String key, double price) {
        String base = key.contains(":") ? key.substring(0, key.indexOf(':')) : key;
        Material icon = Material.matchMaterial(base);
        if (icon == null || !icon.isItem() || icon.isAir()) icon = Material.BARRIER;
        List<String> lore = new ArrayList<>();
        lore.add("&7Price: &a" + money(price));
        Double bl = plugin.store.baseline.get(key);
        if (bl != null && Math.abs(bl - price) > 1e-9) lore.add("&7Baseline: &f" + money(bl));
        lore.add("");
        lore.add("&eLeft &7+10%   &eRight &7-10%");
        lore.add("&eShift-Left &7x2   &eShift-Right &7/2");
        lore.add("&eQ &7type exact price (k/m ok)");
        return item(icon, "&f" + prettify(key), lore.toArray(new String[0]));
    }

    private static String prettify(String key) {
        StringBuilder sb = new StringBuilder();
        for (String part : key.split("[_:]")) {
            if (part.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(part.substring(0, 1).toUpperCase(Locale.ROOT)).append(part.substring(1).toLowerCase(Locale.ROOT));
        }
        return sb.toString();
    }

    // ------------------------------------------------------------ events

    @EventHandler
    public void onDrag(InventoryDragEvent e) {
        if (e.getView().getTopInventory().getHolder() instanceof H) e.setCancelled(true);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getView().getTopInventory().getHolder() instanceof H h)) return;
        e.setCancelled(true);
        if (!(e.getWhoClicked() instanceof Player p)) return;
        if (e.getClickedInventory() != e.getView().getTopInventory()) return;
        if (!p.hasPermission("worthedit.admin")) {
            p.closeInventory();
            return;
        }
        int slot = e.getSlot();
        if (h.main) mainClick(p, slot, e.getClick());
        else categoryClick(p, h, slot, e.getClick());
    }

    private void mainClick(Player p, int slot, ClickType click) {
        if (slot == 44) {
            p.closeInventory();
            return;
        }
        if (slot == 38) {
            if (click.isShiftClick()) {
                plugin.engine.runDynamic();
                p.sendMessage(c("&aDynamic pricing cycle complete."));
            } else {
                plugin.setDynamic(!plugin.store.dynamic);
            }
            openMain(p);
            return;
        }
        if (slot == 40) {
            int n = plugin.engine.stageAutoBalance();
            p.sendMessage(c("&aStaged &f" + n + " &aprice changes. Press Save to write them."));
            openMain(p);
            return;
        }
        if (slot == 42) {
            p.sendMessage(c(plugin.saveWorth()));
            openMain(p);
            return;
        }
        int i = 0;
        for (String cat : plugin.worth.data.keySet()) {
            int s = i < 7 ? 10 + i : 19 + (i - 7);
            if (s == slot) {
                openCategory(p, cat, 0, "");
                return;
            }
            i++;
        }
    }

    private void categoryClick(Player p, H h, int slot, ClickType click) {
        if (slot >= PAGE_SIZE) {
            switch (slot) {
                case 45 -> {
                    if (h.page > 0) openCategory(p, h.cat, h.page - 1, h.filter);
                }
                case 53 -> {
                    if ((h.page + 1) * PAGE_SIZE < h.keys.size()) openCategory(p, h.cat, h.page + 1, h.filter);
                }
                case 47 -> {
                    if (click.isRightClick() && !h.filter.isEmpty()) {
                        openCategory(p, h.cat, 0, "");
                    } else {
                        prompts.put(p.getUniqueId(), new Prompt(h.cat, null, 0, h.filter, true));
                        p.closeInventory();
                        p.sendMessage(c("&bType a search term in chat (or &fcancel&b)."));
                    }
                }
                case 49 -> {
                    p.sendMessage(c(plugin.saveWorth()));
                    openCategory(p, h.cat, h.page, h.filter);
                }
                case 51 -> openMain(p);
                default -> {}
            }
            return;
        }
        int idx = h.page * PAGE_SIZE + slot;
        if (idx >= h.keys.size()) return;
        String key = h.keys.get(idx);
        Double cur = plugin.worth.get(h.cat, key);
        if (cur == null) return;

        switch (click) {
            case LEFT -> edit(p, h, key, cur * 1.10);
            case RIGHT -> edit(p, h, key, cur / 1.10);
            case SHIFT_LEFT -> edit(p, h, key, cur * 2);
            case SHIFT_RIGHT -> edit(p, h, key, cur / 2);
            case DROP, CONTROL_DROP -> {
                prompts.put(p.getUniqueId(), new Prompt(h.cat, key, h.page, h.filter, false));
                p.closeInventory();
                p.sendMessage(c("&bType the new price for &f" + prettify(key) + " &b(current " + money(cur)
                        + "). Use k/m/b suffixes, or type &fcancel&b."));
            }
            default -> {}
        }
    }

    private void edit(Player p, H h, String key, double target) {
        double cur = plugin.worth.get(h.cat, key);
        double v = PriceEngine.round(Math.max(0.01, target));
        if (Math.abs(v - cur) < 1e-9) v = PriceEngine.round(target > cur ? cur + 0.01 : Math.max(0.01, cur - 0.01));
        applyManual(h.cat, key, v);
        p.playSound(p.getLocation(), Sound.UI_BUTTON_CLICK, 0.5f, 1.4f);
        openCategory(p, h.cat, h.page, h.filter);
    }

    /** Manual edits are temporary: the next auto-balance / dynamic cycle recalculates every item. */
    private void applyManual(String cat, String key, double v) {
        plugin.worth.set(cat, key, v);
        if (key.indexOf(':') < 0) plugin.store.baseline.put(key, v);
        plugin.store.save();
    }

    @EventHandler
    public void onChat(AsyncChatEvent e) {
        Prompt pr = prompts.get(e.getPlayer().getUniqueId());
        if (pr == null) return;
        e.setCancelled(true);
        String msg = PlainTextComponentSerializer.plainText().serialize(e.message()).trim();
        Player p = e.getPlayer();
        prompts.remove(p.getUniqueId());
        Bukkit.getScheduler().runTask(plugin, () -> {
            if (msg.equalsIgnoreCase("cancel")) {
                openCategory(p, pr.cat(), pr.page(), pr.filter());
                return;
            }
            if (pr.search()) {
                openCategory(p, pr.cat(), 0, msg);
                return;
            }
            Double v = parsePrice(msg);
            if (v == null || v <= 0) {
                p.sendMessage(c("&cThat is not a valid price."));
            } else {
                applyManual(pr.cat(), pr.key(), PriceEngine.round(v));
                p.sendMessage(c("&aSet &f" + prettify(pr.key()) + " &ato &f" + money(v) + "&a (until the next price update)."));
            }
            openCategory(p, pr.cat(), pr.page(), pr.filter());
        });
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        prompts.remove(e.getPlayer().getUniqueId());
    }

    static Double parsePrice(String s) {
        s = s.replace("$", "").replace(",", "").trim().toLowerCase(Locale.ROOT);
        double mult = 1;
        if (s.endsWith("k")) { mult = 1e3; s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("m")) { mult = 1e6; s = s.substring(0, s.length() - 1); }
        else if (s.endsWith("b")) { mult = 1e9; s = s.substring(0, s.length() - 1); }
        try {
            return Double.parseDouble(s) * mult;
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
