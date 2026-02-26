package dev.worldly.miniony.minion;

import dev.worldly.miniony.Miniony;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.EntityType;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.util.*;

public class LumberjackMinion {

    public static final int  STORAGE_SIZE          = 27;
    public static final int  DEFAULT_RADIUS         = 10;
    public static final long DEFAULT_INTERVAL_TICKS = 200L; // 10 s

    private final UUID id;
    private final UUID ownerUuid;
    private String ownerName;

    private final Location location;
    private ArmorStand stand;

    private final List<ItemStack> storage = new ArrayList<>();
    private int  radius;
    private long intervalTicks;
    private boolean active = true;
    private boolean busy   = false;
    private LumberjackMinionTask task;

    private int speedLevel   = 1;
    private int storageLevel = 1;
    private BukkitTask gravityTask = null;

    // New-minion constructor
    public LumberjackMinion(UUID ownerUuid, String ownerName, Location location) {
        this.id          = UUID.randomUUID();
        this.ownerUuid   = ownerUuid;
        this.ownerName   = ownerName;
        this.location    = location.clone();
        applyUpgradeStats();
    }

    // Load-from-config constructor
    public LumberjackMinion(UUID id, UUID ownerUuid, String ownerName, Location location,
                            int speedLevel, int storageLevel) {
        this.id           = id;
        this.ownerUuid    = ownerUuid;
        this.ownerName    = ownerName;
        this.location     = location.clone();
        this.speedLevel   = speedLevel;
        this.storageLevel = storageLevel;
        applyUpgradeStats();
    }

    // -------------------------------------------------------------------------
    // Spawn / Despawn
    // -------------------------------------------------------------------------

    public void spawnArmorStand() {
        Location spawnLoc = findGroundSpawn();
        spawnLoc.setYaw(0);

        stand = (ArmorStand) location.getWorld().spawnEntity(spawnLoc, EntityType.ARMOR_STAND);
        stand.setCustomName(buildNametag());
        stand.setCustomNameVisible(true);
        stand.setGravity(false);
        stand.setInvulnerable(true);
        stand.setSmall(true);
        stand.setArms(true);
        stand.setBasePlate(false);
        stand.setPersistent(false);

        // Lumberjack outfit — woodsy browns + iron axe
        stand.getEquipment().setHelmet(coloredLeather(Material.LEATHER_HELMET,     Color.fromRGB(101, 67,  33)));
        stand.getEquipment().setChestplate(coloredLeather(Material.LEATHER_CHESTPLATE, Color.fromRGB(139, 90,  43)));
        stand.getEquipment().setLeggings(coloredLeather(Material.LEATHER_LEGGINGS,    Color.fromRGB(101, 67,  33)));
        stand.getEquipment().setBoots(coloredLeather(Material.LEATHER_BOOTS,          Color.fromRGB(62,  39,  17)));
        stand.getEquipment().setItemInMainHand(new ItemStack(Material.IRON_AXE));
    }

    private Location findGroundSpawn() {
        Location home = getHomeLocation();
        for (int dy = 0; dy >= -5; dy--) {
            Block feet  = home.getWorld().getBlockAt(home.getBlockX(), home.getBlockY() + dy, home.getBlockZ());
            Block below = feet.getRelative(0, -1, 0);
            if (!feet.getType().isSolid() && below.getType().isSolid()) {
                return new Location(home.getWorld(), home.getX(), home.getY() + dy, home.getZ());
            }
        }
        return home;
    }

    private ItemStack coloredLeather(Material material, Color color) {
        ItemStack item = new ItemStack(material);
        LeatherArmorMeta meta = (LeatherArmorMeta) item.getItemMeta();
        meta.setColor(color);
        item.setItemMeta(meta);
        return item;
    }

    public void removeArmorStand() {
        if (stand != null && !stand.isDead()) {
            stand.remove();
        }
        stand = null;
    }

    // -------------------------------------------------------------------------
    // Task
    // -------------------------------------------------------------------------

    public void startTask(Miniony plugin) {
        stopTask();
        task = new LumberjackMinionTask(this, plugin);
        task.runTaskTimer(plugin, 20L, intervalTicks);
    }

    public void stopTask() {
        if (task != null) {
            task.cancel();
            task = null;
        }
    }

    /**
     * Starts a lightweight gravity runnable (every 10 ticks).
     * When the minion is idle and floating in air, it snaps down to the
     * nearest solid ground within 20 blocks — simulating gravity.
     * Logs and leaves are not counted as ground.
     */
    public void startGravityTask(Miniony plugin) {
        stopGravityTask();
        gravityTask = new BukkitRunnable() {
            @Override
            public void run() {
                ArmorStand s = stand;
                if (s == null || s.isDead()) { cancel(); return; }
                if (busy) return; // walkTo already handles Y while moving

                Location loc   = s.getLocation();
                Block    feet  = loc.getBlock();
                Block    below = feet.getRelative(0, -1, 0);
                // Only act if both current block AND the one below are non-solid (floating)
                if (!feet.getType().isSolid() && !below.getType().isSolid()) {
                    for (int dy = -2; dy >= -20; dy--) {
                        Block b  = s.getWorld().getBlockAt(
                                loc.getBlockX(), loc.getBlockY() + dy, loc.getBlockZ());
                        Block bb = b.getRelative(0, -1, 0);
                        if (!b.getType().isSolid()
                                && bb.getType().isSolid()
                                && !LumberjackMinionTask.LOG_TYPES.contains(bb.getType())) {
                            Location dest = loc.clone();
                            dest.setY(loc.getBlockY() + dy);
                            s.teleport(dest);
                            break;
                        }
                    }
                }
            }
        }.runTaskTimer(plugin, 10L, 10L);
    }

    public void stopGravityTask() {
        if (gravityTask != null) {
            gravityTask.cancel();
            gravityTask = null;
        }
    }

    // -------------------------------------------------------------------------
    // Storage
    // -------------------------------------------------------------------------

    public int getStorageCapacity() {
        return STORAGE_SIZE + (storageLevel - 1) * 9;
    }

    public boolean isStorageFull() {
        return storage.size() >= getStorageCapacity();
    }

    public ItemStack addToStorage(ItemStack item) {
        if (item == null || item.getType().isAir()) return null;
        int maxPerStack = item.getMaxStackSize();
        for (ItemStack stored : storage) {
            if (stored.isSimilar(item) && stored.getAmount() < stored.getMaxStackSize()) {
                int space = stored.getMaxStackSize() - stored.getAmount();
                int toAdd  = Math.min(space, item.getAmount());
                stored.setAmount(stored.getAmount() + toAdd);
                item.setAmount(item.getAmount() - toAdd);
                if (item.getAmount() <= 0) return null;
            }
        }
        while (item.getAmount() > 0 && storage.size() < getStorageCapacity()) {
            int toAdd = Math.min(item.getAmount(), maxPerStack);
            ItemStack ns = item.clone();
            ns.setAmount(toAdd);
            storage.add(ns);
            item.setAmount(item.getAmount() - toAdd);
        }
        return item.getAmount() > 0 ? item : null;
    }

    // -------------------------------------------------------------------------
    // Upgrades
    // -------------------------------------------------------------------------

    public void applyUpgradeStats() {
        this.intervalTicks = Math.max(40L, DEFAULT_INTERVAL_TICKS - (speedLevel - 1) * 30L);
        this.radius        = DEFAULT_RADIUS + (speedLevel - 1) * 2;
    }

    public boolean upgradeSpeed() {
        if (speedLevel >= 5) return false;
        speedLevel++;
        applyUpgradeStats();
        if (task != null) startTask(Miniony.getPlugin(Miniony.class));
        return true;
    }

    public boolean upgradeStorage() {
        if (storageLevel >= 5) return false;
        storageLevel++;
        return true;
    }

    // -------------------------------------------------------------------------
    // Nametag
    // -------------------------------------------------------------------------

    public void refreshNametag() {
        if (stand != null && !stand.isDead()) stand.setCustomName(buildNametag());
    }

    private String buildNametag() {
        String status = active ? "§a●" : "§c●";
        return "§2§lLumberjack Minion " + status + " §7[" + ownerName + "]";
    }

    // -------------------------------------------------------------------------
    // Getters / Setters
    // -------------------------------------------------------------------------

    public Location getHomeLocation() { return location.clone().add(0.5, 0, 0.5); }

    public UUID   getId()             { return id; }
    public UUID   getOwnerUuid()      { return ownerUuid; }
    public String getOwnerName()      { return ownerName; }
    public Location getLocation()     { return location.clone(); }
    public ArmorStand getStand()      { return stand; }
    public UUID   getStandUuid()      { return stand != null ? stand.getUniqueId() : null; }
    public List<ItemStack> getStorage() { return storage; }
    public int  getRadius()           { return radius; }
    public long getIntervalTicks()    { return intervalTicks; }
    public boolean isActive()         { return active; }
    public void setActive(boolean v)  { this.active = v; refreshNametag(); }
    public boolean isBusy()           { return busy; }
    public void setBusy(boolean v)    { this.busy = v; }
    public int  getSpeedLevel()       { return speedLevel; }
    public int  getStorageLevel()     { return storageLevel; }
}
