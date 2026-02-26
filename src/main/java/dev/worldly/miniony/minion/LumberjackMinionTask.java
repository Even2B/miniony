package dev.worldly.miniony.minion;

import dev.worldly.miniony.Miniony;
import org.bukkit.Axis;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.block.data.Orientable;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.EulerAngle;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Drives one LumberjackMinion each tick.
 *
 * Each cycle:
 *  1. Scans for all log blocks within radius.
 *  2. Flood-fills each candidate into a full tree group (connected logs).
 *  3. Validates the group is a NATURAL TREE and not a player-built structure.
 *  4. Picks the nearest valid tree, walks to its base, then chops every log
 *     from bottom to top (2 ticks/log) storing drops directly in minion storage.
 *  5. Returns home.
 *
 * === Natural-tree detection rules ===
 *  • At least 2 connected logs.
 *  • At least 1 leaf block within 2 blocks of the log bounding box.
 *  • The block directly below the lowest log is a natural soil type
 *    (grass, dirt, podzol, netherrack for nether trees, etc.).
 *  • No more than 4 logs at the base Y level  →  max 2×2 trunk (dark oak).
 *  • 60 % or more of the logs have vertical (Y) axis  →  buildings mix all axes.
 */
public class LumberjackMinionTask extends BukkitRunnable {

    // -------------------------------------------------------------------------
    // Static sets
    // -------------------------------------------------------------------------

    /** Natural (un-stripped) log types that can form trees. */
    static final Set<Material> LOG_TYPES = EnumSet.of(
            Material.OAK_LOG,       Material.SPRUCE_LOG,    Material.BIRCH_LOG,
            Material.JUNGLE_LOG,    Material.ACACIA_LOG,    Material.DARK_OAK_LOG,
            Material.MANGROVE_LOG,  Material.CHERRY_LOG,
            Material.CRIMSON_STEM,  Material.WARPED_STEM
    );

    /** Leaf types used to confirm a log cluster is a living tree. */
    private static final Set<Material> LEAF_TYPES = EnumSet.of(
            Material.OAK_LEAVES,        Material.SPRUCE_LEAVES,     Material.BIRCH_LEAVES,
            Material.JUNGLE_LEAVES,     Material.ACACIA_LEAVES,     Material.DARK_OAK_LEAVES,
            Material.MANGROVE_LEAVES,   Material.CHERRY_LEAVES,
            Material.AZALEA_LEAVES,     Material.FLOWERING_AZALEA_LEAVES
    );

    /** Blocks that appear naturally beneath tree trunks. */
    private static final Set<Material> NATURAL_GROUND = EnumSet.of(
            Material.GRASS_BLOCK,   Material.DIRT,          Material.COARSE_DIRT,
            Material.PODZOL,        Material.MYCELIUM,      Material.ROOTED_DIRT,
            Material.MUD,           Material.MUDDY_MANGROVE_ROOTS,
            Material.NETHERRACK,    Material.CRIMSON_NYLIUM, Material.WARPED_NYLIUM
    );

    /** Hard cap: never flood-fill more than this many logs (prevents lag on huge builds). */
    private static final int MAX_TREE_LOGS = 150;

    // -------------------------------------------------------------------------
    // Instance
    // -------------------------------------------------------------------------

    private static final long STORAGE_FULL_NOTIFY_INTERVAL = 60_000L;

    private final LumberjackMinion minion;
    private final Miniony          plugin;
    private long busySince = 0;
    private long lastStorageFullMessage = 0;

    public LumberjackMinionTask(LumberjackMinion minion, Miniony plugin) {
        this.minion = minion;
        this.plugin = plugin;
    }

    // =========================================================================
    // Main tick
    // =========================================================================

    @Override
    public void run() {
        // Safety: force-unlock if stuck busy for > 60 s
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
                    owner.sendMessage("§6[Miniony] §eYour §2Lumberjack Minion §eat §7("
                            + home.getBlockX() + ", " + home.getBlockY() + ", " + home.getBlockZ()
                            + ")§e is §cfull§e! Use §a/minion call lumberjack §eor collect its storage.");
                }
            }
            return;
        }

        TreeResult tree = findNearestTree(home);
        if (tree == null) return;

        minion.setBusy(true);
        busySince = System.currentTimeMillis();

        // Walk to the base of the tree, then chop the whole thing
        Location dest = treeBaseWalkDest(tree.base(), home);
        walkTo(dest, () -> chopTree(tree.logs()));
    }

    // =========================================================================
    // Tree scanning
    // =========================================================================

    private record TreeResult(Block base, List<Block> logs) {}

    /**
     * Scans the area around {@code home} for valid natural trees.
     * Returns the one whose base is closest to home, or null if none found.
     */
    private TreeResult findNearestTree(Location home) {
        int r  = minion.getRadius();
        int cx = home.getBlockX(), cy = home.getBlockY(), cz = home.getBlockZ();

        Set<Block> globalVisited = new HashSet<>();
        TreeResult nearest    = null;
        double     nearestSq  = Double.MAX_VALUE;

        for (int x = cx - r; x <= cx + r; x++) {
            for (int y = cy - 3; y <= cy + 24; y++) {
                for (int z = cz - r; z <= cz + r; z++) {

                    Block b = home.getWorld().getBlockAt(x, y, z);
                    if (!LOG_TYPES.contains(b.getType())) continue;
                    if (globalVisited.contains(b))         continue;
                    if (!b.getChunk().isLoaded())           continue;

                    List<Block> logs = floodFillLogs(b, globalVisited);
                    if (!isNaturalTree(logs))               continue;

                    Block base = logs.stream()
                            .min(Comparator.comparingInt(Block::getY))
                            .orElse(b);

                    double sq = base.getLocation().distanceSquared(home);
                    if (sq < nearestSq) {
                        nearestSq = sq;
                        nearest   = new TreeResult(base, logs);
                    }
                }
            }
        }
        return nearest;
    }

    /**
     * BFS from {@code start}, collecting all connected logs.
     * Each found block is also added to {@code globalVisited} so the
     * outer scan loop never re-processes the same cluster.
     */
    private List<Block> floodFillLogs(Block start, Set<Block> globalVisited) {
        List<Block>  result       = new ArrayList<>();
        Set<Block>   localVisited = new HashSet<>();
        Queue<Block> queue        = new LinkedList<>();

        queue.add(start);
        localVisited.add(start);

        int[][] dirs = {{1,0,0},{-1,0,0},{0,1,0},{0,-1,0},{0,0,1},{0,0,-1}};

        while (!queue.isEmpty() && result.size() < MAX_TREE_LOGS) {
            Block cur = queue.poll();
            result.add(cur);
            globalVisited.add(cur);

            for (int[] d : dirs) {
                Block nb = cur.getRelative(d[0], d[1], d[2]);
                if (!localVisited.contains(nb) && LOG_TYPES.contains(nb.getType())) {
                    localVisited.add(nb);
                    queue.add(nb);
                }
            }
        }
        return result;
    }

    // =========================================================================
    // Natural-tree validation
    // =========================================================================

    /**
     * Returns true only if the log cluster looks like a natural tree and not
     * a player-placed wooden structure.
     *
     * Checks (all must pass):
     *  1. ≥ 2 logs.
     *  2. Vertical extent ≥ 1 (not a flat carpet of logs).
     *  3. ≤ 4 logs at the lowest Y level  →  no wider than 2×2.
     *  4. At least one natural-ground block directly below a base log.
     *  5. At least one leaf block within 2 blocks of the log bounding box.
     *  6. ≥ 60 % of orientable logs have vertical (Y) axis.
     */
    private boolean isNaturalTree(List<Block> logs) {
        if (logs.size() < 2) return false;

        // --- Bounding box ---
        int minX = logs.stream().mapToInt(Block::getX).min().orElse(0);
        int minY = logs.stream().mapToInt(Block::getY).min().orElse(0);
        int minZ = logs.stream().mapToInt(Block::getZ).min().orElse(0);
        int maxX = logs.stream().mapToInt(Block::getX).max().orElse(0);
        int maxY = logs.stream().mapToInt(Block::getY).max().orElse(0);
        int maxZ = logs.stream().mapToInt(Block::getZ).max().orElse(0);

        // 1. Must have vertical extent
        if (maxY - minY < 1) return false;

        // 2. Trunk width at base  (2×2 = 4 max for dark oak)
        long baseCount = logs.stream().filter(b -> b.getY() == minY).count();
        if (baseCount > 4) return false;

        // 3. Natural ground below at least one base log
        boolean hasNaturalGround = logs.stream()
                .filter(b -> b.getY() == minY)
                .anyMatch(b -> NATURAL_GROUND.contains(b.getRelative(0, -1, 0).getType()));
        if (!hasNaturalGround) return false;

        // 4. Leaves within 2 blocks of the bounding box
        org.bukkit.World world = logs.get(0).getWorld();
        boolean hasLeaves = false;
        outer:
        for (int lx = minX - 2; lx <= maxX + 2; lx++) {
            for (int ly = minY; ly <= maxY + 3; ly++) {
                for (int lz = minZ - 2; lz <= maxZ + 2; lz++) {
                    if (LEAF_TYPES.contains(world.getBlockAt(lx, ly, lz).getType())) {
                        hasLeaves = true;
                        break outer;
                    }
                }
            }
        }
        if (!hasLeaves) return false;

        // 5. Axis check — 60 % or more must be vertical (Y)
        long total = 0, yCount = 0;
        for (Block log : logs) {
            if (log.getBlockData() instanceof Orientable o) {
                total++;
                if (o.getAxis() == Axis.Y) yCount++;
            }
        }
        if (total > 0 && (double) yCount / total < 0.6) return false;

        return true;
    }

    // =========================================================================
    // Chopping
    // =========================================================================

    /**
     * Schedules rapid log-by-log breaking from bottom to top (2 ticks per log).
     * The minion stays at the tree base the whole time — it chops everything
     * from where it stands, like a magical woodcutter.
     * After the last log, it stays in place and clears the busy flag.
     */
    private void chopTree(List<Block> logs) {
        // Sort upward so it looks like a real chop-from-base
        List<Block> sorted = new ArrayList<>(logs);
        sorted.sort(Comparator.comparingInt(Block::getY));

        // Make stand face the tree with axe raised
        setChopPose();

        for (int i = 0; i < sorted.size(); i++) {
            final Block   block    = sorted.get(i);
            final boolean last     = (i == sorted.size() - 1);
            final int     logIndex = i; // must be effectively final for lambda

            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                breakLog(block, logIndex);
                if (last) {
                    // All logs chopped — stay in place, clear busy
                    setIdlePose();
                    minion.setBusy(false);
                    busySince = 0;
                }
            }, (long)(i + 1) * 2);
        }
    }

    /** Breaks one log: stores the drop, plays effects, alternates axe swing pose. */
    private void breakLog(Block block, int index) {
        if (!LOG_TYPES.contains(block.getType())) return;

        Material logType = block.getType();

        // Store the log itself
        ItemStack drop = new ItemStack(logType, 1);
        ItemStack remainder = minion.addToStorage(drop);
        if (remainder != null) {
            block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), remainder);
        }

        // Block-break particles (looks like the log shatters)
        block.getWorld().spawnParticle(
                Particle.BLOCK,
                block.getLocation().add(0.5, 0.5, 0.5),
                12, 0.3, 0.3, 0.3, 0,
                block.getBlockData()
        );
        block.getWorld().playSound(
                block.getLocation(),
                Sound.BLOCK_WOOD_BREAK,
                0.7f, 0.8f + (float)(Math.random() * 0.3)
        );

        block.setType(Material.AIR);

        // Oscillating axe-swing animation on the stand
        ArmorStand s = minion.getStand();
        if (s != null && !s.isDead()) {
            double angle = (index % 2 == 0) ? -1.4 : -0.5;
            s.setRightArmPose(new EulerAngle(angle, 0, 0));
        }
    }

    // =========================================================================
    // Movement  (mirrors FarmingMinionTask.walkTo with terrain snapping)
    // =========================================================================

    /**
     * Ground-level destination right beside the base log.
     * Scans downward from 1 above base Y, skipping log/leaf surfaces, so the
     * minion lands on the natural soil next to the trunk, not on top of it.
     */
    private Location treeBaseWalkDest(Block base, Location home) {
        int bx = base.getX(), bz = base.getZ(), refY = base.getY();
        for (int dy = 1; dy >= -5; dy--) {
            Block feet  = home.getWorld().getBlockAt(bx, refY + dy, bz);
            Block below = feet.getRelative(0, -1, 0);
            if (!feet.getType().isSolid()
                    && below.getType().isSolid()
                    && !LOG_TYPES.contains(below.getType())
                    && !LEAF_TYPES.contains(below.getType())) {
                return new Location(home.getWorld(), bx + 0.5, refY + dy, bz + 0.5);
            }
        }
        return new Location(home.getWorld(), bx + 0.5, refY, bz + 0.5);
    }

    /**
     * Terrain-snapped ground Y at column (bx, bz) near referenceY.
     * Scans ±6 blocks so steep hills and deep drop-offs are handled correctly.
     *
     * Logs and leaves are deliberately excluded from being "ground" — without
     * this exclusion the minion would climb to the top of the very tree it is
     * about to chop, since log tops look like valid "air-above-solid" surfaces.
     */
    private int scanGroundY(int bx, int bz, int referenceY) {
        ArmorStand s = minion.getStand();
        if (s == null) return referenceY;
        for (int dy = 4; dy >= -6; dy--) {
            Block feet  = s.getWorld().getBlockAt(bx, referenceY + dy, bz);
            Block below = feet.getRelative(0, -1, 0);
            if (!feet.getType().isSolid()
                    && below.getType().isSolid()
                    && !LOG_TYPES.contains(below.getType())
                    && !LEAF_TYPES.contains(below.getType())) {
                return referenceY + dy;
            }
        }
        return referenceY;
    }

    /**
     * Smoothly walks the armor stand to {@code to}, then calls {@code onArrival}.
     * Y follows actual terrain at each step — no flying over slopes.
     * AtomicBoolean ensures onArrival fires exactly once even if stand dies mid-walk.
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
            stand.teleport(to.clone());
            if (onArrival != null) onArrival.run();
            return;
        }

        float yaw   = yawToward(from, to);
        int   steps = Math.max(1, (int) Math.ceil(dist / 0.35));
        double dx   = (to.getX() - from.getX()) / steps;
        double dz   = (to.getZ() - from.getZ()) / steps;

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

                Location pos    = from.clone().add(dx * step, 0, dz * step);
                int      groundY = scanGroundY(pos.getBlockX(), pos.getBlockZ(), currentY.get());
                currentY.set(groundY); // advance reference so next step finds *its* ground
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

    // =========================================================================
    // Poses
    // =========================================================================

    private void setChopPose() {
        ArmorStand s = minion.getStand();
        if (s == null || s.isDead()) return;
        s.setRightArmPose(new EulerAngle(-1.4, 0, 0));
        s.setLeftArmPose(new EulerAngle(-0.3, 0, 0.3));
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
}
