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


public class MinionManager {

    private final Miniony plugin;
    private final Map<UUID, FarmingMinion> minions = new HashMap<>();   
    private final Map<UUID, UUID> standToMinion = new HashMap<>();       

    private final File dataFile;
    private FileConfiguration dataConfig;

    public MinionManager(Miniony plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "minions.yml");
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

                UUID id         = UUID.fromString(key);
                UUID ownerUuid  = UUID.fromString(sec.getString("owner-uuid"));
                String ownerName = sec.getString("owner-name", "Unknown");

                String worldName = sec.getString("world");
                World world = Bukkit.getWorld(worldName);
                if (world == null) {
                    plugin.getLogger().warning("World '" + worldName + "' not found for minion " + id + ", skipping.");
                    continue;
                }

                double x = sec.getDouble("x");
                double y = sec.getDouble("y");
                double z = sec.getDouble("z");
                Location loc = new Location(world, x, y, z);

                int speedLevel   = sec.getInt("speed-level", 1);
                int rangeLevel   = sec.getInt("range-level", 1);
                int storageLevel = sec.getInt("storage-level", 1);

                FarmingMinion minion = new FarmingMinion(id, ownerUuid, ownerName, loc,
                        speedLevel, rangeLevel, storageLevel);

                
                ConfigurationSection storageSec = sec.getConfigurationSection("storage");
                if (storageSec != null) {
                    for (String itemKey : storageSec.getKeys(false)) {
                        ItemStack item = storageSec.getItemStack(itemKey);
                        if (item != null) minion.getStorage().add(item);
                    }
                }

                boolean active = sec.getBoolean("active", true);
                minion.setActive(active);

                
                if (sec.contains("region.minX")) {
                    String regionWorld = sec.getString("region.world", worldName);
                    World rw = Bukkit.getWorld(regionWorld);
                    if (rw != null) {
                        MinionRegion region = new MinionRegion(rw,
                                sec.getInt("region.minX"), sec.getInt("region.minY"), sec.getInt("region.minZ"),
                                sec.getInt("region.maxX"), sec.getInt("region.maxY"), sec.getInt("region.maxZ"));
                        minion.setRegion(region);
                    }
                }

                spawnMinion(minion);

            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to load minion " + key, e);
            }
        }

        plugin.getLogger().info("Loaded " + minions.size() + " farming minion(s).");
    }

    
    public void save() {
        dataConfig = new YamlConfiguration();

        for (FarmingMinion minion : minions.values()) {
            String key = "minions." + minion.getId().toString();
            dataConfig.set(key + ".owner-uuid",    minion.getOwnerUuid().toString());
            dataConfig.set(key + ".owner-name",    minion.getOwnerName());
            dataConfig.set(key + ".world",         minion.getLocation().getWorld().getName());
            dataConfig.set(key + ".x",             minion.getLocation().getX());
            dataConfig.set(key + ".y",             minion.getLocation().getY());
            dataConfig.set(key + ".z",             minion.getLocation().getZ());
            dataConfig.set(key + ".speed-level",   minion.getSpeedLevel());
            dataConfig.set(key + ".range-level",   minion.getRangeLevel());
            dataConfig.set(key + ".storage-level", minion.getStorageLevel());
            dataConfig.set(key + ".active",        minion.isActive());

            
            MinionRegion region = minion.getRegion();
            if (region != null) {
                dataConfig.set(key + ".region.world",  region.getWorld().getName());
                dataConfig.set(key + ".region.minX",   region.getMinX());
                dataConfig.set(key + ".region.minY",   region.getMinY());
                dataConfig.set(key + ".region.minZ",   region.getMinZ());
                dataConfig.set(key + ".region.maxX",   region.getMaxX());
                dataConfig.set(key + ".region.maxY",   region.getMaxY());
                dataConfig.set(key + ".region.maxZ",   region.getMaxZ());
            }

            
            List<ItemStack> storage = minion.getStorage();
            for (int i = 0; i < storage.size(); i++) {
                dataConfig.set(key + ".storage." + i, storage.get(i));
            }
        }

        try {
            dataConfig.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().log(Level.SEVERE, "Could not save minions.yml", e);
        }
    }

    
    public void unloadAll() {
        for (FarmingMinion minion : new ArrayList<>(minions.values())) {
            minion.stopTask();
            minion.stopGravityTask();
            minion.removeArmorStand();
        }
        minions.clear();
        standToMinion.clear();
    }

    
    
    

    
    public FarmingMinion createMinion(UUID ownerUuid, String ownerName, Location location) {
        return createMinion(ownerUuid, ownerName, location, null);
    }

    
    public FarmingMinion createMinion(UUID ownerUuid, String ownerName, Location location, MinionRegion region) {
        FarmingMinion minion = new FarmingMinion(ownerUuid, ownerName, location);
        if (region != null) minion.setRegion(region);
        spawnMinion(minion);
        save();
        return minion;
    }

    
    private void spawnMinion(FarmingMinion minion) {
        minion.spawnArmorStand();
        minion.startTask(plugin);
        minion.startGravityTask(plugin);
        minions.put(minion.getId(), minion);
        if (minion.getStandUuid() != null) {
            standToMinion.put(minion.getStandUuid(), minion.getId());
        }
    }

    
    public void respawnMinion(FarmingMinion minion) {
        
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

    
    public void removeMinion(FarmingMinion minion) {
        minion.stopTask();
        minion.stopGravityTask();
        if (minion.getStandUuid() != null) {
            standToMinion.remove(minion.getStandUuid());
        }
        minion.removeArmorStand();
        minions.remove(minion.getId());
        save();
    }

    
    
    

    public FarmingMinion getMinionByStandUuid(UUID standUuid) {
        UUID minionId = standToMinion.get(standUuid);
        if (minionId == null) return null;
        return minions.get(minionId);
    }

    public FarmingMinion getMinionById(UUID id) {
        return minions.get(id);
    }

    public Collection<FarmingMinion> getAllMinions() {
        return Collections.unmodifiableCollection(minions.values());
    }

    public List<FarmingMinion> getMinionsOf(UUID ownerUuid) {
        List<FarmingMinion> result = new ArrayList<>();
        for (FarmingMinion m : minions.values()) {
            if (m.getOwnerUuid().equals(ownerUuid)) result.add(m);
        }
        return result;
    }

    public int getMinionCount() {
        return minions.size();
    }
}
