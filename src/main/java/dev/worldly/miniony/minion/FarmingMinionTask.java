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

/**
 * Scheduled task that drives one FarmingMinion.
 *
 * Each trigger:
 * 1. Finds ALL mature crops and builds a nearest-neighbour harvest route.
 * 2. Walks to every crop in the route, harvesting each one.
 * 3. If no crops are ready but there is bone meal in storage, walks to the
 *    nearest immature crop and applies it.
 * 4. If still nothing to do and there are empty farmland/soul-sand plots plus
 *    matching seeds in storage, walks over and plants.
 * 5. Returns home after each full work cycle.
 *
 * AtomicBoolean in walkTo ensures the arrival callback fires exactly once
 * even if the stand is removed mid-walk.
 */
public class FarmingMinionTask extends BukkitRunnable {

    // Crop type → seed type used for scanning and planting
    static final Map<Material, Material> CROP_SEED_MAP = new LinkedHashMap<>();

    static {
        CROP_SEED_MAP.put(Material.WHEAT,       Material.WHEAT_SEEDS);
        CROP_SEED_MAP.put(Material.CARROTS,     Material.CARROT);
        CROP_SEED_MAP.put(Material.POTATOES,    Material.POTATO);
        CROP_SEED_MAP.put(Material.BEETROOTS,   Material.BEETROOT_SEEDS);
        CROP_SEED_MAP.put(Material.NETHER_WART, Material.NETHER_WART);
    }

    private static final Random RANDOM = new Random();
    /** Maximum crops harvested in one trip before returning home. */
    private static final int MAX_CROPS_PER_TRIP = 20;

    private static final long STORAGE_FULL_NOTIFY_INTERVAL = 60_000L; // 60 s between warnings

    private final FarmingMinion minion;
    private final Miniony plugin;

    // Used to force-reset a stuck busy state after 60 seconds
    private long busySince = 0;
    // Throttle "storage full" messages so we don't spam the owner
    private long lastStorageFullMessage = 0;

    public FarmingMinionTask(FarmingMinion minion, Miniony plugin) {
        this.minion = minion;
        this.plugin = plugin;
    }

    // =========================================================================
    // Main tick
    // =========================================================================

    @Override
    public void run() {
        // Safety: if stuck busy for >60 seconds, force reset
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

        // --- Storage full check: notify owner and skip work ---
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

        // --- Priority 1: harvest ALL mature crops in one trip ---
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

        // --- Priority 2: apply bone meal to an immature crop ---
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

        // --- Priority 3: plant seeds on empty farmland ---
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

    /**
     * Returns a walk-destination at the actual surface Y of the target X/Z column.
     * Scans a few blocks above/below the crop's own Y to find the air block
     * sitting on top of solid ground — the correct level for the stand to stand on.
     */
    private Location groundLevel(Location target, Location home) {
        int tx = target.getBlockX();
        int tz = target.getBlockZ();
        int refY = target.getBlockY(); // crop is here; farmland is 1 below
        for (int dy = 2; dy >= -4; dy--) {
            Block feet  = home.getWorld().getBlockAt(tx, refY + dy, tz);
            Block below = feet.getRelative(0, -1, 0);
            if (!feet.getType().isSolid() && below.getType().isSolid()) {
                return new Location(home.getWorld(), tx + 0.5, refY + dy, tz + 0.5);
            }
        }
        // Fallback: stand right at the crop's Y
        return new Location(home.getWorld(), tx + 0.5, refY, tz + 0.5);
    }

    /**
     * Finds the actual ground Y near {@code referenceY} at the block column (bx, bz).
     * Scans ±6 blocks so steep steps and drops are handled correctly.
     */
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

    // =========================================================================
    // Harvest chain — visits every crop in the route before returning home
    // =========================================================================

    /**
     * Recursively walks to each block in {@code route} (starting at {@code index}),
     * harvests it if still mature, then proceeds to the next one.
     * After the last crop, stays in place and clears the busy flag.
     */
    private void harvestChain(List<Block> route, int index, Location home) {
        if (index >= route.size()) {
            // All done — stay where we are
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
            // Move on to the next crop in the chain
            harvestChain(route, index + 1, home);
        });
    }

    // =========================================================================
    // Crop scanning
    // =========================================================================

    /** Returns every mature crop in the minion's area (unsorted). */
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

    /**
     * Builds a nearest-neighbour route through {@code blocks} starting from
     * {@code start}. Minimises total walking distance.
     */
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

    // =========================================================================
    // Harvesting — uses manual drops so they are always correct
    // =========================================================================

    private void harvestBlock(Block block) {
        Material cropType = block.getType();
        if (!CROP_SEED_MAP.containsKey(cropType)) return;

        List<ItemStack> drops = getManualDrops(cropType);

        // Replant: reset age to 0
        Ageable ageable = (Ageable) block.getBlockData();
        ageable.setAge(0);
        block.setBlockData(ageable);

        // Store drops (overflow drops to the ground)
        for (ItemStack drop : drops) {
            ItemStack remainder = minion.addToStorage(drop);
            if (remainder != null) {
                block.getWorld().dropItemNaturally(
                        block.getLocation().add(0.5, 0.5, 0.5), remainder);
            }
        }

        // Effects
        block.getWorld().spawnParticle(Particle.COMPOSTER,
                block.getLocation().add(0.5, 1.2, 0.5), 6, 0.25, 0.25, 0.25, 0);
        block.getWorld().playSound(block.getLocation(), Sound.BLOCK_CROP_BREAK, 0.7f, 1.1f);

        // Swing animation
        ArmorStand s = minion.getStand();
        if (s != null && !s.isDead()) {
            s.setRightArmPose(new EulerAngle(-1.2, 0, 0));
        }
    }

    /** Returns realistic drops for each crop type without relying on block.getDrops(). */
    private List<ItemStack> getManualDrops(Material cropType) {
        List<ItemStack> drops = new ArrayList<>();
        switch (cropType) {
            case WHEAT -> {
                drops.add(new ItemStack(Material.WHEAT, 1));
                int seeds = RANDOM.nextInt(3); // 0–2 bonus seeds
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

    // =========================================================================
    // Bone meal
    // =========================================================================

    /** Finds the nearest immature crop in the minion's area to fertilise. */
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

    /**
     * Consumes one bone meal from storage and advances the crop's growth
     * by 2–4 stages (capped at maximum age).
     */
    private void applyBonemealTo(Block block) {
        if (!isImmatureCrop(block)) return;

        // Consume one bone meal
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

        // Advance growth 2–4 stages
        Ageable ageable = (Ageable) block.getBlockData();
        int newAge = Math.min(ageable.getAge() + 2 + RANDOM.nextInt(3), ageable.getMaximumAge());
        ageable.setAge(newAge);
        block.setBlockData(ageable);

        // Effects
        block.getWorld().spawnParticle(Particle.HAPPY_VILLAGER,
                block.getLocation().add(0.5, 0.8, 0.5), 8, 0.3, 0.3, 0.3, 0);
        block.getWorld().playSound(block.getLocation(), Sound.ITEM_BONE_MEAL_USE, 0.7f, 1f);

        // Arm animation
        ArmorStand s = minion.getStand();
        if (s != null && !s.isDead()) {
            s.setRightArmPose(new EulerAngle(-0.5, 0.3, 0));
        }
    }

    // =========================================================================
    // Seed planting
    // =========================================================================

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

        // Sort by closest to home
        soils.sort(Comparator.comparingDouble(b -> b.getLocation().distanceSquared(home)));

        for (Block soil : soils) {
            if (soil.getType() == Material.SOUL_SAND) {
                if (hasSeedInStorage(Material.NETHER_WART))
                    return new PlantTarget(soil, Material.NETHER_WART, Material.NETHER_WART);
            } else {
                // FARMLAND — try each seed type in priority order
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
        if (above.getType() != Material.AIR) return; // spot was filled while walking

        // Consume one seed from storage
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

        // Place the crop
        above.setType(target.cropType());
        Ageable ageable = (Ageable) above.getBlockData();
        ageable.setAge(0);
        above.setBlockData(ageable);

        // Effects
        above.getWorld().spawnParticle(Particle.HAPPY_VILLAGER,
                above.getLocation().add(0.5, 0.5, 0.5), 5, 0.2, 0.2, 0.2, 0);
        above.getWorld().playSound(above.getLocation(), Sound.ITEM_CROP_PLANT, 0.8f, 1.0f);

        ArmorStand s = minion.getStand();
        if (s != null && !s.isDead()) s.setRightArmPose(new EulerAngle(-0.8, 0, 0.3));
    }

    // =========================================================================
    // Movement
    // =========================================================================

    /**
     * Smoothly walks the armor stand to {@code to} and calls {@code onArrival}.
     * Uses AtomicBoolean so the callback fires exactly once even if the stand
     * is removed during the walk.
     */
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

        // Track Y dynamically — updated every step so the scan window follows
        // the actual terrain rather than being anchored to the walk start.
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

                // X/Z follow the straight path; Y stair-steps along actual terrain
                Location pos     = from.clone().add(dx * step, 0, dz * step);
                int      groundY = scanGroundY(pos.getBlockX(), pos.getBlockZ(), currentY.get());
                currentY.set(groundY); // advance reference so next step finds *its* ground
                pos.setY(groundY);
                pos.setYaw(yaw);
                s.teleport(pos);

                // Sine-wave arm swing while walking
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

    // =========================================================================
    // Helpers
    // =========================================================================

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
