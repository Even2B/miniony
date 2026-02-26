package dev.worldly.miniony.minion;

import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * GUI for a FarmingMinion.
 *
 * Layout (54 slots = 6 rows):
 * Row 0-2  (slots  0-26): Storage (27 slots)
 * Row 3    (slots 27-35): Border (glass panes)
 * Row 4    (slots 36-44): Info + Upgrades
 * Row 5    (slots 45-53): Border + Collect All button
 *
 * Info row:
 *   36 = Minion Info (paper)
 *   38 = Speed Upgrade
 *   40 = Range Upgrade
 *   42 = Storage Upgrade
 *   44 = Toggle Active
 *
 * Action row:
 *   49 = Collect All (chest)
 */
public class FarmingMinionInventory implements InventoryHolder {

    public static final int SIZE = 54;

    // Slot constants
    public static final int SLOT_INFO        = 36;
    public static final int SLOT_SPEED_UP    = 38;
    public static final int SLOT_REGION_INFO = 40;   // read-only region display (replaces range upgrade)
    public static final int SLOT_STORAGE_UP  = 42;
    public static final int SLOT_TOGGLE      = 44;
    public static final int SLOT_COLLECT_ALL = 49;

    private final FarmingMinion minion;
    private final Inventory inventory;

    public FarmingMinionInventory(FarmingMinion minion) {
        this.minion = minion;
        this.inventory = Bukkit.createInventory(this, SIZE,
                ChatColor.GOLD + "" + ChatColor.BOLD + "Farming Minion");
        refresh();
    }

    // -------------------------------------------------------------------------
    // Build / Refresh
    // -------------------------------------------------------------------------

    public void refresh() {
        inventory.clear();
        fillStorage();
        fillBorder();
        fillInfo();
        fillActions();
    }

    private void fillStorage() {
        List<ItemStack> stored = minion.getStorage();
        int cap = minion.getStorageCapacity();
        for (int i = 0; i < 27; i++) {
            if (i < stored.size()) {
                inventory.setItem(i, stored.get(i).clone());
            } else if (i < cap) {
                inventory.setItem(i, emptyStorageSlot());
            } else {
                inventory.setItem(i, border(Material.RED_STAINED_GLASS_PANE, " "));
            }
        }
    }

    private void fillBorder() {
        for (int i = 27; i < 36; i++) {
            inventory.setItem(i, border(Material.BLACK_STAINED_GLASS_PANE, " "));
        }
        for (int i = 45; i < 54; i++) {
            inventory.setItem(i, border(Material.BLACK_STAINED_GLASS_PANE, " "));
        }
        // fill gaps in info row
        for (int i = 36; i <= 44; i++) {
            if (inventory.getItem(i) == null) {
                inventory.setItem(i, border(Material.GRAY_STAINED_GLASS_PANE, " "));
            }
        }
    }

    private void fillInfo() {
        // Info
        inventory.setItem(SLOT_INFO, buildInfo());

        // Speed upgrade
        inventory.setItem(SLOT_SPEED_UP, buildUpgradeItem(
                Material.FEATHER,
                "§e§lSpeed Upgrade",
                minion.getSpeedLevel(),
                "§7Interval: §f" + (minion.getIntervalTicks() / 20) + "s",
                "§aLeft-click to upgrade!"
        ));

        // Region info (read-only — wand is used to change it)
        inventory.setItem(SLOT_REGION_INFO, buildRegionInfo());

        // Storage upgrade
        inventory.setItem(SLOT_STORAGE_UP, buildUpgradeItem(
                Material.CHEST,
                "§6§lStorage Upgrade",
                minion.getStorageLevel(),
                "§7Capacity: §f" + minion.getStorageCapacity() + " stacks",
                "§aLeft-click to upgrade!"
        ));

        // Toggle
        boolean active = minion.isActive();
        inventory.setItem(SLOT_TOGGLE, buildSimple(
                active ? Material.LIME_DYE : Material.GRAY_DYE,
                active ? "§a§lMinion Active" : "§c§lMinion Paused",
                "§7Click to " + (active ? "§cpause" : "§aresume") + "§7 this minion."
        ));
    }

    private void fillActions() {
        inventory.setItem(SLOT_COLLECT_ALL, buildSimple(
                Material.CHEST,
                "§e§lCollect All",
                "§7Click to collect all stored",
                "§7items into your inventory."
        ));
    }

    // -------------------------------------------------------------------------
    // Item builders
    // -------------------------------------------------------------------------

    private ItemStack buildInfo() {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§6§lFarming Minion");

        List<String> lore = new ArrayList<>();
        lore.add("§7Owner: §f" + minion.getOwnerName());
        lore.add("§7Status: " + (minion.isActive() ? "§aActive" : "§cPaused"));
        lore.add("§7Interval: §f" + (minion.getIntervalTicks() / 20) + "s");
        lore.add("§7Storage: §f" + minion.getStorage().size() + "§7/§f" + minion.getStorageCapacity());
        lore.add("");

        MinionRegion region = minion.getRegion();
        if (region != null) {
            lore.add("§b§lFarming Region:");
            lore.add("§7Size: §f" + region.describe() + " §7(" + region.blockCount() + " blocks)");
            lore.add("§7Use §e/minion wand §7to change the region.");
        } else {
            lore.add("§7Radius: §f" + minion.getRadius() + " blocks");
            lore.add("§7Use §e/minion wand §7to set a custom region.");
        }

        lore.add("");
        lore.add("§eRight-click §7the minion to open this GUI.");
        lore.add("§eLeft-click §7the minion to pick it up.");

        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildRegionInfo() {
        MinionRegion region = minion.getRegion();
        ItemStack item = new ItemStack(region != null ? Material.MAP : Material.COMPASS);
        ItemMeta meta = item.getItemMeta();

        List<String> lore = new ArrayList<>();
        if (region != null) {
            meta.setDisplayName("§b§lFarming Region");
            lore.add("§7Size: §f" + region.describe());
            lore.add("§7Blocks: §f" + region.blockCount());
            lore.add("");
            lore.add("§7Right-click the minion while");
            lore.add("§7holding a §e/minion wand §7to change.");
        } else {
            meta.setDisplayName("§7No Region Set");
            lore.add("§7Fallback radius: §f" + minion.getRadius() + " blocks");
            lore.add("");
            lore.add("§7Get a §e/minion wand§7, select");
            lore.add("§7Pos1 + Pos2, then right-click");
            lore.add("§7this minion to set a region.");
        }
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildUpgradeItem(Material mat, String name, int level, String... extraLore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        List<String> lore = new ArrayList<>();
        lore.add(buildLevelBar(level));
        lore.add("§7Level: §f" + level + "§7/§f5");
        for (String line : extraLore) lore.add(line);
        if (level >= 5) {
            lore.add("§c§lMAX LEVEL");
        }
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildSimple(Material mat, String name, String... loreLines) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(Arrays.asList(loreLines));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack border(Material mat, String name) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName(name);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack emptyStorageSlot() {
        ItemStack item = new ItemStack(Material.LIGHT_GRAY_STAINED_GLASS_PANE);
        ItemMeta meta = item.getItemMeta();
        meta.setDisplayName("§7Empty");
        item.setItemMeta(meta);
        return item;
    }

    private String buildLevelBar(int level) {
        StringBuilder bar = new StringBuilder("§7[");
        for (int i = 1; i <= 5; i++) {
            bar.append(i <= level ? "§a■" : "§8■");
        }
        bar.append("§7]");
        return bar.toString();
    }

    // -------------------------------------------------------------------------
    // Storage sync (GUI → minion)
    // -------------------------------------------------------------------------

    /**
     * Syncs the storage slots back from the GUI into the minion's storage list.
     * Called when the GUI is closed.
     */
    public void syncStorageToMinion() {
        minion.getStorage().clear();
        for (int i = 0; i < 27 && i < minion.getStorageCapacity(); i++) {
            ItemStack item = inventory.getItem(i);
            if (item != null && !item.getType().isAir()
                    && item.getType() != Material.LIGHT_GRAY_STAINED_GLASS_PANE) {
                minion.getStorage().add(item.clone());
            }
        }
    }

    // -------------------------------------------------------------------------
    // Slot utilities
    // -------------------------------------------------------------------------

    public static boolean isStorageSlot(int slot) {
        return slot >= 0 && slot < 27;
    }

    public static boolean isUpgradeSlot(int slot) {
        return slot == SLOT_SPEED_UP || slot == SLOT_STORAGE_UP;
    }

    public FarmingMinion getMinion() { return minion; }

    @Override
    public Inventory getInventory() { return inventory; }
}
