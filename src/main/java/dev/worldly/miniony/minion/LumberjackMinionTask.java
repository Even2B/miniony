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


public class LumberjackMinionTask extends BukkitRunnable {

    static final Set<Material> LOG_TYPES = EnumSet.of(
            Material.OAK_LOG,       Material.SPRUCE_LOG,    Material.BIRCH_LOG,
            Material.JUNGLE_LOG,    Material.ACACIA_LOG,    Material.DARK_OAK_LOG,
            Material.MANGROVE_LOG,  Material.CHERRY_LOG,
            Material.CRIMSON_STEM,  Material.WARPED_STEM
    );

    
    private static final Set<Material> LEAF_TYPES = EnumSet.of(
            Material.OAK_LEAVES,        Material.SPRUCE_LEAVES,     Material.BIRCH_LEAVES,
            Material.JUNGLE_LEAVES,     Material.ACACIA_LEAVES,     Material.DARK_OAK_LEAVES,
            Material.MANGROVE_LEAVES,   Material.CHERRY_LEAVES,
            Material.AZALEA_LEAVES,     Material.FLOWERING_AZALEA_LEAVES
    );

    
    private static final Set<Material> NATURAL_GROUND = EnumSet.of(
            Material.GRASS_BLOCK,   Material.DIRT,          Material.COARSE_DIRT,
            Material.PODZOL,        Material.MYCELIUM,      Material.ROOTED_DIRT,
            Material.MUD,           Material.MUDDY_MANGROVE_ROOTS,
            Material.NETHERRACK,    Material.CRIMSON_NYLIUM, Material.WARPED_NYLIUM
    );

    
    private static final int MAX_TREE_LOGS = 150;

    
    
    

    private static final long STORAGE_FULL_NOTIFY_INTERVAL = 60_000L;

    private final LumberjackMinion minion;
    private final Miniony          plugin;
    private long busySince = 0;
    private long lastStorageFullMessage = 0;

    public LumberjackMinionTask(LumberjackMinion minion, Miniony plugin) {
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

        
        Location dest = treeBaseWalkDest(tree.base(), home);
        walkTo(dest, () -> chopTree(tree.logs()));
    }

    
    
    

    private record TreeResult(Block base, List<Block> logs) {}

    
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

    
    
    

    
    private boolean isNaturalTree(List<Block> logs) {
        if (logs.size() < 2) return false;

        
        int minX = logs.stream().mapToInt(Block::getX).min().orElse(0);
        int minY = logs.stream().mapToInt(Block::getY).min().orElse(0);
        int minZ = logs.stream().mapToInt(Block::getZ).min().orElse(0);
        int maxX = logs.stream().mapToInt(Block::getX).max().orElse(0);
        int maxY = logs.stream().mapToInt(Block::getY).max().orElse(0);
        int maxZ = logs.stream().mapToInt(Block::getZ).max().orElse(0);

        
        if (maxY - minY < 1) return false;

        
        long baseCount = logs.stream().filter(b -> b.getY() == minY).count();
        if (baseCount > 4) return false;

        
        boolean hasNaturalGround = logs.stream()
                .filter(b -> b.getY() == minY)
                .anyMatch(b -> NATURAL_GROUND.contains(b.getRelative(0, -1, 0).getType()));
        if (!hasNaturalGround) return false;

        
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

    
    
    

    
    private void chopTree(List<Block> logs) {
        
        List<Block> sorted = new ArrayList<>(logs);
        sorted.sort(Comparator.comparingInt(Block::getY));

        
        setChopPose();

        for (int i = 0; i < sorted.size(); i++) {
            final Block   block    = sorted.get(i);
            final boolean last     = (i == sorted.size() - 1);
            final int     logIndex = i; 

            plugin.getServer().getScheduler().runTaskLater(plugin, () -> {
                breakLog(block, logIndex);
                if (last) {
                    
                    setIdlePose();
                    minion.setBusy(false);
                    busySince = 0;
                }
            }, (long)(i + 1) * 2);
        }
    }

    
    private void breakLog(Block block, int index) {
        if (!LOG_TYPES.contains(block.getType())) return;

        Material logType = block.getType();

        
        ItemStack drop = new ItemStack(logType, 1);
        ItemStack remainder = minion.addToStorage(drop);
        if (remainder != null) {
            block.getWorld().dropItemNaturally(block.getLocation().add(0.5, 0.5, 0.5), remainder);
        }

        
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

        
        ArmorStand s = minion.getStand();
        if (s != null && !s.isDead()) {
            double angle = (index % 2 == 0) ? -1.4 : -0.5;
            s.setRightArmPose(new EulerAngle(angle, 0, 0));
        }
    }

    
    
    

    
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

    
    
    

    private float yawToward(Location from, Location to) {
        double dx = to.getX() - from.getX();
        double dz = to.getZ() - from.getZ();
        return (float) Math.toDegrees(Math.atan2(-dx, dz));
    }
}
