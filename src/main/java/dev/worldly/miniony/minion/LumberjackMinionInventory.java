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

public class LumberjackMinionInventory implements InventoryHolder {

    public static final int SIZE            = 54;
    public static final int SLOT_INFO       = 36;
    public static final int SLOT_SPEED_UP   = 38;
    // slot 40 = gap (no range upgrade for lumberjack — radius grows with speed)
    public static final int SLOT_STORAGE_UP = 42;
    public static final int SLOT_TOGGLE     = 44;
    public static final int SLOT_COLLECT_ALL = 49;

    private final LumberjackMinion minion;
    private final Inventory        inventory;

    public LumberjackMinionInventory(LumberjackMinion minion) {
        this.minion    = minion;
        this.inventory = Bukkit.createInventory(this, SIZE,
                ChatColor.DARK_GREEN + "" + ChatColor.BOLD + "Lumberjack Minion");
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
                inventory.setItem(i, emptySlot());
            } else {
                inventory.setItem(i, border(Material.RED_STAINED_GLASS_PANE, " "));
            }
        }
    }

    private void fillBorder() {
        for (int i = 27; i < 36; i++) inventory.setItem(i, border(Material.BLACK_STAINED_GLASS_PANE, " "));
        for (int i = 45; i < 54; i++) inventory.setItem(i, border(Material.BLACK_STAINED_GLASS_PANE, " "));
        for (int i = 36; i <= 44; i++) {
            if (inventory.getItem(i) == null)
                inventory.setItem(i, border(Material.GRAY_STAINED_GLASS_PANE, " "));
        }
    }

    private void fillInfo() {
        inventory.setItem(SLOT_INFO, buildInfo());

        inventory.setItem(SLOT_SPEED_UP, buildUpgradeItem(
                Material.FEATHER,
                "§e§lSpeed Upgrade",
                minion.getSpeedLevel(),
                "§7Interval: §f" + (minion.getIntervalTicks() / 20) + "s",
                "§7Radius:   §f" + minion.getRadius() + " blocks",
                "§aLeft-click to upgrade!"
        ));

        inventory.setItem(SLOT_STORAGE_UP, buildUpgradeItem(
                Material.CHEST,
                "§6§lStorage Upgrade",
                minion.getStorageLevel(),
                "§7Capacity: §f" + minion.getStorageCapacity() + " stacks",
                "§aLeft-click to upgrade!"
        ));

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
                "§7wood into your inventory."
        ));
    }

    // -------------------------------------------------------------------------
    // Item builders
    // -------------------------------------------------------------------------

    private ItemStack buildInfo() {
        ItemStack item = new ItemStack(Material.OAK_LOG);
        ItemMeta  meta = item.getItemMeta();
        meta.setDisplayName("§2§lLumberjack Minion");

        List<String> lore = new ArrayList<>();
        lore.add("§7Owner:    §f" + minion.getOwnerName());
        lore.add("§7Status:   " + (minion.isActive() ? "§aActive" : "§cPaused"));
        lore.add("§7Interval: §f" + (minion.getIntervalTicks() / 20) + "s");
        lore.add("§7Radius:   §f" + minion.getRadius() + " blocks");
        lore.add("§7Storage:  §f" + minion.getStorage().size() + "§7/§f" + minion.getStorageCapacity());
        lore.add("");
        lore.add("§7Finds and chops whole trees");
        lore.add("§7— never touches buildings!");
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildUpgradeItem(Material mat, String name, int level, String... extra) {
        ItemStack item = new ItemStack(mat);
        ItemMeta  meta = item.getItemMeta();
        meta.setDisplayName(name);

        List<String> lore = new ArrayList<>();
        lore.add("§7Level: " + levelBar(level, 5));
        lore.add("");
        for (String l : extra) lore.add(l);
        if (level >= 5) { lore.add(""); lore.add("§8MAX LEVEL"); }
        meta.setLore(lore);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack buildSimple(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta  meta = item.getItemMeta();
        meta.setDisplayName(name);
        meta.setLore(Arrays.asList(lore));
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack border(Material mat, String name) {
        ItemStack item = new ItemStack(mat);
        ItemMeta  meta = item.getItemMeta();
        meta.setDisplayName(name);
        item.setItemMeta(meta);
        return item;
    }

    private ItemStack emptySlot() {
        return border(Material.LIGHT_GRAY_STAINED_GLASS_PANE, "§7Empty");
    }

    private String levelBar(int level, int max) {
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= max; i++) sb.append(i <= level ? "§a■" : "§8■");
        return sb.toString();
    }

    // -------------------------------------------------------------------------
    // Sync / static helpers
    // -------------------------------------------------------------------------

    /** Called on GUI close to sync any items the player dragged into storage slots. */
    public void syncStorageToMinion() {
        List<ItemStack> stored = minion.getStorage();
        stored.clear();
        for (int i = 0; i < 27; i++) {
            ItemStack it = inventory.getItem(i);
            if (it != null && !it.getType().isAir()
                    && it.getType() != Material.LIGHT_GRAY_STAINED_GLASS_PANE
                    && it.getType() != Material.RED_STAINED_GLASS_PANE) {
                stored.add(it.clone());
            }
        }
    }

    public static boolean isStorageSlot(int slot) {
        return slot >= 0 && slot < 27;
    }

    public static boolean isUpgradeSlot(int slot) {
        return slot == SLOT_SPEED_UP || slot == SLOT_STORAGE_UP;
    }

    @Override public Inventory getInventory() { return inventory; }
    public LumberjackMinion getMinion()        { return minion; }
}
