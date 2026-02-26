package dev.worldly.miniony.minion;

import dev.worldly.miniony.Miniony;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.logging.Level;


public class LumberjackMinionManager {

    private final Miniony plugin;
    private final Map<UUID, LumberjackMinion> minions       = new HashMap<>();
    private final Map<UUID, UUID>             standToMinion = new HashMap<>();

    private final File            dataFile;
    private       FileConfiguration dataConfig;

    public LumberjackMinionManager(Miniony plugin) {
        this.plugin   = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "lumberjacks.yml");
    }

    
    
    

    public void load() {
        if (!dataFile.exists()) return;
        dataConfig = YamlConfiguration.loadConfiguration(dataFile);

        ConfigurationSection root = dataConfig.getConfigurationSection("minions");
        if (root == null) return;

        for (String key : root.getKeys(false)) {
            try {
                ConfigurationSection sec = root.getConfigurationSection(key);
                if (sec == null) continue;

                UUID   id        = UUID.fromString(key);
                UUID   ownerUuid = UUID.fromString(sec.getString("owner-uuid"));
                String ownerName = sec.getString("owner-name", "Unknown");

                World world = Bukkit.getWorld(sec.getString("world"));
                if (world == null) {
                    plugin.getLogger().warning("World not found for lumberjack " + id + ", skipping.");
                    continue;
                }

                Location loc = new Location(world,
                        sec.getDouble("x"), sec.getDouble("y"), sec.getDouble("z"));

                int speedLevel   = sec.getInt("speed-level",   1);
                int storageLevel = sec.getInt("storage-level", 1);

                LumberjackMinion minion = new LumberjackMinion(
                        id, ownerUuid, ownerName, loc, speedLevel, storageLevel);

                ConfigurationSection storageSec = sec.getConfigurationSection("storage");
                if (storageSec != null) {
                    for (String itemKey : storageSec.getKeys(false)) {
                        ItemStack item = storageSec.getItemStack(itemKey);
                        if (item != null) minion.getStorage().add(item);
                    }
                }

                minion.setActive(sec.getBoolean("active", true));
                spawnMinion(minion);

            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to load lumberjack " + key, e);
            }
        }
        plugin.getLogger().info("Loaded " + minions.size() + " lumberjack minion(s).");
    }

    public void save() {
        dataConfig = new YamlConfiguration();

        for (LumberjackMinion m : minions.values()) {
            String k = "minions." + m.getId();
            dataConfig.set(k + ".owner-uuid",    m.getOwnerUuid().toString());
            dataConfig.set(k + ".owner-name",    m.getOwnerName());
            dataConfig.set(k + ".world",         m.getLocation().getWorld().getName());
            dataConfig.set(k + ".x",             m.getLocation().getX());
            dataConfig.set(k + ".y",             m.getLocation().getY());
            dataConfig.set(k + ".z",             m.getLocation().getZ());
            dataConfig.set(k + ".speed-level",   m.getSpeedLevel());
            dataConfig.set(k + ".storage-level", m.getStorageLevel());
            dataConfig.set(k + ".active",        m.isActive());

            List<ItemStack> storage = m.getStorage();
            for (int i = 0; i < storage.size(); i++) {
                dataConfig.set(k + ".storage." + i, storage.get(i));
            }
        }

        try {
            dataConfig.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save lumberjacks.yml", e);
        }
    }

    public void unloadAll() {
        for (LumberjackMinion m : new ArrayList<>(minions.values())) {
            m.stopTask();
            m.stopGravityTask();
            m.removeArmorStand();
        }
        minions.clear();
        standToMinion.clear();
    }

    
    
    

    public LumberjackMinion createMinion(UUID ownerUuid, String ownerName, Location location) {
        LumberjackMinion minion = new LumberjackMinion(ownerUuid, ownerName, location);
        spawnMinion(minion);
        save();
        return minion;
    }

    private void spawnMinion(LumberjackMinion minion) {
        minion.spawnArmorStand();
        minion.startTask(plugin);
        minion.startGravityTask(plugin);
        minions.put(minion.getId(), minion);
        if (minion.getStandUuid() != null) {
            standToMinion.put(minion.getStandUuid(), minion.getId());
        }
    }

    
    public void respawnMinion(LumberjackMinion minion) {
        if (minion.getStandUuid() != null) {
            standToMinion.remove(minion.getStandUuid());
        }
        minion.stopTask();
        minion.stopGravityTask();
        minion.removeArmorStand();

        minion.spawnArmorStand();
        minion.startTask(plugin);
        minion.startGravityTask(plugin);

        if (minion.getStandUuid() != null) {
            standToMinion.put(minion.getStandUuid(), minion.getId());
        }
    }

    public void removeMinion(LumberjackMinion minion) {
        minion.stopTask();
        minion.stopGravityTask();
        if (minion.getStandUuid() != null) standToMinion.remove(minion.getStandUuid());
        minion.removeArmorStand();
        minions.remove(minion.getId());
        save();
    }

    
    
    

    public LumberjackMinion getMinionByStandUuid(UUID standUuid) {
        UUID id = standToMinion.get(standUuid);
        return id == null ? null : minions.get(id);
    }

    public LumberjackMinion getMinionById(UUID id)         { return minions.get(id); }
    public Collection<LumberjackMinion> getAllMinions()     { return Collections.unmodifiableCollection(minions.values()); }
    public int getMinionCount()                            { return minions.size(); }

    public List<LumberjackMinion> getMinionsOf(UUID ownerUuid) {
        List<LumberjackMinion> result = new ArrayList<>();
        for (LumberjackMinion m : minions.values()) {
            if (m.getOwnerUuid().equals(ownerUuid)) result.add(m);
        }
        return result;
    }
}
