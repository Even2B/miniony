package dev.worldly.miniony.minion;

import dev.worldly.miniony.Miniony;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.data.Ageable;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.EulerAngle;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;


public class FarmingMinionTask extends BukkitRunnable {

    
    static final Map<Material, Material> CROP_SEED_MAP = new LinkedHashMap<>();

    static {
        CROP_SEED_MAP.put(Material.WHEAT,       Material.WHEAT_SEEDS);
        CROP_SEED_MAP.put(Material.CARROTS,     Material.CARROT);
        CROP_SEED_MAP.put(Material.POTATOES,    Material.POTATO);
        CROP_SEED_MAP.put(Material.BEETROOTS,   Material.BEETROOT_SEEDS);
        CROP_SEED_MAP.put(Material.NETHER_WART, Material.NETHER_WART);
    }

    private static final Random RANDOM = new Random();
    
    private static final int MAX_CROPS_PER_TRIP = 20;

    private static final long STORAGE_FULL_NOTIFY_INTERVAL = 60_000L; 

    private final FarmingMinion minion;
    private final Miniony plugin;

    
    private long busySince = 0;
    
    private long lastStorageFullMessage = 0;

    public FarmingMinionTask(FarmingMinion minion, Miniony plugin) {
        this.minion = minion;
        this.plugin = plugin;
    }

    
    
    

    @Override
    public void run() {
        
        if (minion.isBusy()) {
            if (busySince > 0 && System.currentTimeMillis() - busySince > 60_000L) {
                minion.setBusy(false);
                busySince = 0;
            } else {
                return;
            }
        }

        if (!minion.isActive()) return;

        Location home = minion.getHomeLocation();
        if (home.getWorld() == null || !home.getChunk().isLoaded()) return;

        
        if (minion.isStorageFull()) {
            long now = System.currentTimeMillis();
            if (now - lastStorageFullMessage >= STORAGE_FULL_NOTIFY_INTERVAL) {
                lastStorageFullMessage = now;
                Player owner = Bukkit.getPlayer(minion.getOwnerUuid());
                if (owner != null) {
                    owner.sendMessage("§6[Miniony] §eYour §6Farming Minion §eat §7("
                            + home.getBlockX() + ", " + home.getBlockY() + ", " + home.getBlockZ()
                            + ")§e is §cfull§e! Use §a/minion call farming §eor collect its storage.");
                }
            }
            return;
        }

        
        List<Block> crops = findAllMatureCrops(home);
        if (!crops.isEmpty()) {
            List<Block> route = buildNearestNeighborRoute(crops, home);
            if (route.size() > MAX_CROPS_PER_TRIP) {
                route = new ArrayList<>(route.subList(0, MAX_CROPS_PER_TRIP));
            }
            final List<Block> finalRoute = route;
            startBusy();
            harvestChain(finalRoute, 0, home);
            return;
        }

        
        if (hasBoneMealInStorage()) {
            Block bonemealTarget = findCropToFertilize(home);
            if (bonemealTarget != null) {
                startBusy();
                Location walkDest = groundLevel(bonemealTarget.getLocation(), home);
                walkTo(walkDest, () -> {
                    applyBonemealTo(bonemealTarget);
                    setIdlePose();
                    endBusy();
                });
                return;
            }
        }

        
        PlantTarget plantTarget = findEmptyPlantSpot(home);
        if (plantTarget != null) {
            startBusy();
            Location walkDest = groundLevel(plantTarget.soil().getLocation(), home);
            walkTo(walkDest, () -> {
                plantSeed(plantTarget);
                setIdlePose();
                endBusy();
            });
        }
    }

    private void startBusy() {
        minion.setBusy(true);
        busySince = System.currentTimeMillis();
    }

    private void endBusy() {
        minion.setBusy(false);
        busySince = 0;
    }

    
    private Location groundLevel(Location target, Location home) {
        int tx = target.getBlockX();
        int tz = target.getBlockZ();
        int refY = target.getBlockY(); 
        for (int dy = 2; dy >= -4; dy--) {
            Block feet  = home.getWorld().getBlockAt(tx, refY + dy, tz);
            Block below = feet.getRelative(0, -1, 0);
            if (!feet.getType().isSolid() && below.getType().isSolid()) {
                return new Location(home.getWorld(), tx + 0.5, refY + dy, tz + 0.5);
            }
        }
        
        return new Location(home.getWorld(), tx + 0.5, refY, tz + 0.5);
    }

    
    private int scanGroundY(int bx, int bz, int referenceY) {
        for (int dy = 4; dy >= -6; dy--) {
            Block feet  = minion.getStand().getWorld().getBlockAt(bx, referenceY + dy, bz);
            Block below = feet.getRelative(0, -1, 0);
            if (!feet.getType().isSolid() && below.getType().isSolid()) {
                return referenceY + dy;
            }
        }
        return referenceY;
    }

    
    
    

    
    private void harvestChain(List<Block> route, int index, Location home) {
        if (index >= route.size()) {
            
            setIdlePose();
            endBusy();
            return;
        }

        Block target = route.get(index);
        Location walkDest = groundLevel(target.getLocation(), home);

        walkTo(walkDest, () -> {
            if (isMatureCrop(target)) {
                harvestBlock(target);
            }
            setIdlePose();
            
            harvestChain(route, index + 1, home);
        });
    }

    
    
    

    
    private List<Block> findAllMatureCrops(Location home) {
        List<Block> candidates = new ArrayList<>();

        MinionRegion region = minion.getRegion();
        if (region != null) {
            for (Block b : region.getAllBlocks()) {
                if (b.getChunk().isLoaded() && isMatureCrop(b)) candidates.add(b);
            }
        } else {
            int r = minion.getRadius();
            int cx = home.getBlockX(), cy = home.getBlockY(), cz = home.getBlockZ();
            for (int x = cx - r; x <= cx + r; x++) {
                for (int z = cz - r; z <= cz + r; z++) {
                    for (int dy = -2; dy <= 4; dy++) {
                        Block b = home.getWorld().getBlockAt(x, cy + dy, z);
                        if (isMatureCrop(b)) candidates.add(b);
                    }
                }
            }
        }
        return candidates;
    }

    
    private List<Block> buildNearestNeighborRoute(List<Block> blocks, Location start) {
        List<Block> remaining = new ArrayList<>(blocks);
        List<Block> route = new ArrayList<>();
        Location current = start;

        while (!remaining.isEmpty()) {
            final Location cur = current;
            Block nearest = remaining.stream()
                    .min(Comparator.comparingDouble(b -> b.getLocation().distanceSquared(cur)))
                    .orElseThrow();
            route.add(nearest);
            remaining.remove(nearest);
            current = nearest.getLocation();
        }
        return route;
    }

    private boolean isMatureCrop(Block block) {
        if (!CROP_SEED_MAP.containsKey(block.getType())) return false;
        if (!(block.getBlockData() instanceof Ageable ageable)) return false;
        return ageable.getAge() == ageable.getMaximumAge();
    }

    private boolean isImmatureCrop(Block block) {
        if (!CROP_SEED_MAP.containsKey(block.getType())) return false;
        if (!(block.getBlockData() instanceof Ageable ageable)) return false;
        return ageable.getAge() < ageable.getMaximumAge();
    }

    
    
    

    private void harvestBlock(Block block) {
        Material cropType = block.getType();
        if (!CROP_SEED_MAP.containsKey(cropType)) return;

        List<ItemStack> drops = getManualDrops(cropType);

        
        Ageable ageable = (Ageable) block.getBlockData();
        ageable.setAge(0);
        block.setBlockData(ageable);

        
        for (ItemStack drop : drops) {
            ItemStack remainder = minion.addToStorage(drop);
            if (remainder != null) {
                block.getWorld().dropItemNaturally(
                        block.getLocation().add(0.5, 0.5, 0.5), remainder);
            }
        }

        
        block.getWorld().spawnParticle(Particle.COMPOSTER,
                block.getLocation().add(0.5, 1.2, 0.5), 6, 0.25, 0.25, 0.25, 0);
        block.getWorld().playSound(block.getLocation(), Sound.BLOCK_CROP_BREAK, 0.7f, 1.1f);

        
        ArmorStand s = minion.getStand();
        if (s != null && !s.isDead()) {
            s.setRightArmPose(new EulerAngle(-1.2, 0, 0));
        }
    }

    
    private List<ItemStack> getManualDrops(Material cropType) {
        List<ItemStack> drops = new ArrayList<>();
        switch (cropType) {
            case WHEAT -> {
                drops.add(new ItemStack(Material.WHEAT, 1));
                int seeds = RANDOM.nextInt(3); 
                if (seeds > 0) drops.add(new ItemStack(Material.WHEAT_SEEDS, seeds));
            }
            case CARROTS  -> drops.add(new ItemStack(Material.CARROT,  1 + RANDOM.nextInt(4)));
            case POTATOES -> drops.add(new ItemStack(Material.POTATO,  1 + RANDOM.nextInt(4)));
            case BEETROOTS -> {
                drops.add(new ItemStack(Material.BEETROOT, 1));
                drops.add(new ItemStack(Material.BEETROOT_SEEDS, 1 + RANDOM.nextInt(2)));
            }
            case NETHER_WART -> drops.add(new ItemStack(Material.NETHER_WART, 2 + RANDOM.nextInt(3)));
            default -> {}
        }
        return drops;
    }

    
    
    

    
    private Block findCropToFertilize(Location home) {
        List<Block> candidates = new ArrayList<>();

        MinionRegion region = minion.getRegion();
        if (region != null) {
            for (Block b : region.getAllBlocks()) {
                if (b.getChunk().isLoaded() && isImmatureCrop(b)) candidates.add(b);
            }
        } else {
            int r = minion.getRadius();
            int cx = home.getBlockX(), cy = home.getBlockY(), cz = home.getBlockZ();
            for (int x = cx - r; x <= cx + r; x++) {
                for (int z = cz - r; z <= cz + r; z++) {
                    for (int dy = -2; dy <= 4; dy++) {
                        Block b = home.getWorld().getBlockAt(x, cy + dy, z);
                        if (isImmatureCrop(b)) candidates.add(b);
                    }
                }
            }
        }

        if (candidates.isEmpty()) return null;
        return candidates.stream()
                .min(Comparator.comparingDouble(b -> b.getLocation().distanceSquared(home)))
                .orElse(null);
    }

    private boolean hasBoneMealInStorage() {
        for (ItemStack item : minion.getStorage()) {
            if (item.getType() == Material.BONE_MEAL && item.getAmount() > 0) return true;
        }
        return false;
    }

    
    private void applyBonemealTo(Block block) {
        if (!isImmatureCrop(block)) return;

        
        Iterator<ItemStack> it = minion.getStorage().iterator();
        boolean consumed = false;
        while (it.hasNext()) {
            ItemStack item = it.next();
            if (item.getType() == Material.BONE_MEAL) {
                if (item.getAmount() > 1) {
                    item.setAmount(item.getAmount() - 1);
                } else {
                    it.remove();
                }
                consumed = true;
                break;
            }
        }
        if (!consumed) return;

        
        Ageable ageable = (Ageable) block.getBlockData();
        int newAge = Math.min(ageable.getAge() + 2 + RANDOM.nextInt(3), ageable.getMaximumAge());
        ageable.setAge(newAge);
        block.setBlockData(ageable);

        
        block.getWorld().spawnParticle(Particle.HAPPY_VILLAGER,
                block.getLocation().add(0.5, 0.8, 0.5), 8, 0.3, 0.3, 0.3, 0);
        block.getWorld().playSound(block.getLocation(), Sound.ITEM_BONE_MEAL_USE, 0.7f, 1f);

        
        ArmorStand s = minion.getStand();
        if (s != null && !s.isDead()) {
            s.setRightArmPose(new EulerAngle(-0.5, 0.3, 0));
        }
    }

    
    
    

    private record PlantTarget(Block soil, Material cropType, Material seedType) {}

    private PlantTarget findEmptyPlantSpot(Location home) {
        List<Block> soils = new ArrayList<>();

        MinionRegion region = minion.getRegion();
        if (region != null) {
            for (Block b : region.getAllBlocks()) {
                if (!b.getChunk().isLoaded()) continue;
                if (b.getType() == Material.FARMLAND || b.getType() == Material.SOUL_SAND) {
                    if (b.getRelative(0, 1, 0).getType() == Material.AIR) soils.add(b);
                }
            }
        } else {
            int r = minion.getRadius();
            int cx = home.getBlockX(), cy = home.getBlockY(), cz = home.getBlockZ();
            for (int x = cx - r; x <= cx + r; x++) {
                for (int z = cz - r; z <= cz + r; z++) {
                    for (int dy = -2; dy <= 4; dy++) {
                        Block b = home.getWorld().getBlockAt(x, cy + dy, z);
                        if ((b.getType() == Material.FARMLAND || b.getType() == Material.SOUL_SAND)
                                && b.getRelative(0, 1, 0).getType() == Material.AIR) {
                            soils.add(b);
                        }
                    }
                }
            }
        }

        
        soils.sort(Comparator.comparingDouble(b -> b.getLocation().distanceSquared(home)));

        for (Block soil : soils) {
            if (soil.getType() == Material.SOUL_SAND) {
                if (hasSeedInStorage(Material.NETHER_WART))
                    return new PlantTarget(soil, Material.NETHER_WART, Material.NETHER_WART);
            } else {
                
                for (Map.Entry<Material, Material> entry : CROP_SEED_MAP.entrySet()) {
                    if (entry.getKey() == Material.NETHER_WART) continue;
                    if (hasSeedInStorage(entry.getValue()))
                        return new PlantTarget(soil, entry.getKey(), entry.getValue());
                }
            }
        }
        return null;
    }

    private boolean hasSeedInStorage(Material seedType) {
        for (ItemStack item : minion.getStorage()) {
            if (item.getType() == seedType && item.getAmount() > 0) return true;
        }
        return false;
    }

    private void plantSeed(PlantTarget target) {
        Block soil = target.soil();
        Block above = soil.getRelative(0, 1, 0);
        if (above.getType() != Material.AIR) return; 

        
        Iterator<ItemStack> it = minion.getStorage().iterator();
        boolean consumed = false;
        while (it.hasNext()) {
            ItemStack item = it.next();
            if (item.getType() == target.seedType()) {
                if (item.getAmount() > 1) {
                    item.setAmount(item.getAmount() - 1);
                } else {
                    it.remove();
                }
                consumed = true;
                break;
            }
        }
        if (!consumed) return;

        
        above.setType(target.cropType());
        Ageable ageable = (Ageable) above.getBlockData();
        ageable.setAge(0);
        above.setBlockData(ageable);

        
        above.getWorld().spawnParticle(Particle.HAPPY_VILLAGER,
                above.getLocation().add(0.5, 0.5, 0.5), 5, 0.2, 0.2, 0.2, 0);
        above.getWorld().playSound(above.getLocation(), Sound.ITEM_CROP_PLANT, 0.8f, 1.0f);

        ArmorStand s = minion.getStand();
        if (s != null && !s.isDead()) s.setRightArmPose(new EulerAngle(-0.8, 0, 0.3));
    }

    
    
    

    
    private void walkTo(Location to, Runnable onArrival) {
        ArmorStand stand = minion.getStand();
        if (stand == null || stand.isDead()) {
            if (onArrival != null) onArrival.run();
            return;
        }

        Location from = stand.getLocation().clone();
        double dist = Math.sqrt(
                Math.pow(to.getX() - from.getX(), 2) +
                Math.pow(to.getZ() - from.getZ(), 2));

        if (dist < 0.3) {
            stand.teleport(applyYaw(to.clone(), stand));
            if (onArrival != null) onArrival.run();
            return;
        }

        float yaw = yawToward(from, to);
        int steps = Math.max(1, (int) Math.ceil(dist / 0.35));
        double dx = (to.getX() - from.getX()) / steps;
        double dz = (to.getZ() - from.getZ()) / steps;

        
        
        AtomicInteger currentY = new AtomicInteger(from.getBlockY());
        AtomicBoolean done     = new AtomicBoolean(false);

        for (int i = 1; i <= steps; i++) {
            final int step = i;
            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                if (done.get()) return;

                ArmorStand s = minion.getStand();
                if (s == null || s.isDead()) {
                    if (done.compareAndSet(false, true) && onArrival != null) onArrival.run();
                    return;
                }

                
                Location pos     = from.clone().add(dx * step, 0, dz * step);
                int      groundY = scanGroundY(pos.getBlockX(), pos.getBlockZ(), currentY.get());
                currentY.set(groundY); 
                pos.setY(groundY);
                pos.setYaw(yaw);
                s.teleport(pos);

                
                double swing = Math.sin(step * 0.9) * 0.7;
                s.setRightArmPose(new EulerAngle(swing, 0, 0));
                s.setLeftArmPose(new EulerAngle(-swing, 0, 0));

                if (step == steps && done.compareAndSet(false, true) && onArrival != null) {
                    onArrival.run();
                }
            }, (long) i * 2);
        }
    }

    private void setIdlePose() {
        ArmorStand s = minion.getStand();
        if (s == null || s.isDead()) return;
        s.setRightArmPose(new EulerAngle(-0.4, 0, 0));
        s.setLeftArmPose(EulerAngle.ZERO);
    }

    
    
    

    private float yawToward(Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }

    private Location applyYaw(Location loc, ArmorStand stand) {
        loc.setYaw(stand.getLocation().getYaw());
        return loc;
    }
}
