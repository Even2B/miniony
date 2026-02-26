package dev.worldly.miniony.listener;

import dev.worldly.miniony.command.MinionCommand;
import dev.worldly.miniony.minion.*;
import org.bukkit.Chunk;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.Sound;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class MinionListener implements Listener {

    private static final String PREFIX = "§6[Miniony] §r";

    private final MinionManager           farmingManager;
    private final LumberjackMinionManager lumberjackManager;

    
    private final Map<UUID, FarmingMinionInventory>    openFarmingGuis    = new HashMap<>();
    private final Map<UUID, LumberjackMinionInventory> openLumberjackGuis = new HashMap<>();

    
    private final Map<UUID, Location> wandPos1 = new HashMap<>();
    private final Map<UUID, Location> wandPos2 = new HashMap<>();

    public MinionListener(MinionManager farmingManager,
                          LumberjackMinionManager lumberjackManager) {
        this.farmingManager    = farmingManager;
        this.lumberjackManager = lumberjackManager;
    }

    
    
    

    @EventHandler(priority = EventPriority.HIGH)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;

        Player    player = event.getPlayer();
        ItemStack item   = player.getInventory().getItemInMainHand();

        
        if (MinionCommand.isWandItem(item)) {
            handleWandInteract(event, player);
            return;
        }

        
        if (MinionCommand.isMinionItem(item)) {
            if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
            event.setCancelled(true);
            placeFarmingMinion(player, item, event.getClickedBlock());
            return;
        }

        
        if (MinionCommand.isLumberjackItem(item)) {
            if (event.getAction() != Action.RIGHT_CLICK_BLOCK || event.getClickedBlock() == null) return;
            event.setCancelled(true);
            placeLumberjackMinion(player, item, event.getClickedBlock());
        }
    }

    
    
    

    private void placeFarmingMinion(Player player, ItemStack item, Block clicked) {
        if (!player.hasPermission("miniony.place")) {
            player.sendMessage(PREFIX + "§cNo permission to place minions."); return;
        }

        Location placeLoc = clicked.getLocation().add(0, 1, 0);

        for (FarmingMinion m : farmingManager.getAllMinions()) {
            if (m.getLocation().getWorld().equals(placeLoc.getWorld())
                    && m.getLocation().distanceSquared(placeLoc) < 4) {
                player.sendMessage(PREFIX + "§cThere is already a minion too close!"); return;
            }
        }

        consumeItem(player, item);

        MinionRegion region = consumeWandSelection(player, placeLoc);
        farmingManager.createMinion(player.getUniqueId(), player.getName(), placeLoc, region);

        spawnEffect(placeLoc);
        if (region != null)
            player.sendMessage(PREFIX + "§aFarming Minion placed with region §f" + region.describe() + "§a!");
        else
            player.sendMessage(PREFIX + "§aFarming Minion placed! §7Use §e/minion wand §7to set a region.");
    }

    
    
    

    private void placeLumberjackMinion(Player player, ItemStack item, Block clicked) {
        if (!player.hasPermission("miniony.place")) {
            player.sendMessage(PREFIX + "§cNo permission to place minions."); return;
        }

        Location placeLoc = clicked.getLocation().add(0, 1, 0);

        for (LumberjackMinion m : lumberjackManager.getAllMinions()) {
            if (m.getLocation().getWorld().equals(placeLoc.getWorld())
                    && m.getLocation().distanceSquared(placeLoc) < 4) {
                player.sendMessage(PREFIX + "§cThere is already a minion too close!"); return;
            }
        }

        consumeItem(player, item);
        lumberjackManager.createMinion(player.getUniqueId(), player.getName(), placeLoc);

        spawnEffect(placeLoc);
        player.sendMessage(PREFIX + "§aLumberjack Minion placed! §7He will find trees within §f"
                + LumberjackMinion.DEFAULT_RADIUS + " blocks§7.");
    }

    
    
    

    private void handleWandInteract(PlayerInteractEvent event, Player player) {
        Block clicked = event.getClickedBlock();
        if (clicked == null) return;
        event.setCancelled(true);

        if (event.getAction() == Action.LEFT_CLICK_BLOCK) {
            wandPos1.put(player.getUniqueId(), clicked.getLocation().clone());
            player.sendMessage(PREFIX + "§aPos1 set §7to §f" + formatBlock(clicked));
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.5f);
            player.spawnParticle(Particle.WAX_ON, clicked.getLocation().add(0.5, 1, 0.5), 8, 0.3, 0.3, 0.3, 0);
            printSelectionSize(player);

        } else if (event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            wandPos2.put(player.getUniqueId(), clicked.getLocation().clone());
            player.sendMessage(PREFIX + "§aPos2 set §7to §f" + formatBlock(clicked));
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 1f, 1.2f);
            player.spawnParticle(Particle.WAX_ON, clicked.getLocation().add(0.5, 1, 0.5), 8, 0.3, 0.3, 0.3, 0);
            printSelectionSize(player);
        }
    }

    private void printSelectionSize(Player player) {
        Location p1 = wandPos1.get(player.getUniqueId());
        Location p2 = wandPos2.get(player.getUniqueId());
        if (p1 == null || p2 == null) return;
        if (!p1.getWorld().equals(p2.getWorld())) {
            player.sendMessage(PREFIX + "§cPositions must be in the same world!"); return;
        }
        MinionRegion preview = new MinionRegion(p1, p2);
        player.sendMessage(PREFIX + "§7Selection: §f" + preview.describe()
                + " §7(" + preview.blockCount() + " blocks)");
        player.sendMessage(PREFIX + "§7Now §eplace a Farming Minion §7or §eright-click an existing one.");
    }

    private MinionRegion consumeWandSelection(Player player, Location near) {
        Location p1 = wandPos1.get(player.getUniqueId());
        Location p2 = wandPos2.get(player.getUniqueId());
        if (p1 == null || p2 == null) return null;
        if (!p1.getWorld().equals(p2.getWorld()) || !p1.getWorld().equals(near.getWorld())) return null;
        wandPos1.remove(player.getUniqueId());
        wandPos2.remove(player.getUniqueId());
        return new MinionRegion(p1, p2);
    }

    
    
    

    @EventHandler(priority = EventPriority.HIGH)
    public void onRightClickStand(PlayerInteractAtEntityEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        Entity entity = event.getRightClicked();
        if (entity.getType() != EntityType.ARMOR_STAND) return;

        Player player = event.getPlayer();
        UUID   standId = entity.getUniqueId();

        
        FarmingMinion farming = farmingManager.getMinionByStandUuid(standId);
        if (farming != null) {
            event.setCancelled(true);

            
            if (MinionCommand.isWandItem(player.getInventory().getItemInMainHand())) {
                assignWandRegion(player, farming);
                return;
            }
            if (player.isSneaking()) { pickupFarming(player, farming); return; }

            FarmingMinionInventory gui = new FarmingMinionInventory(farming);
            openFarmingGuis.put(player.getUniqueId(), gui);
            player.openInventory(gui.getInventory());
            player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.8f, 1f);
            return;
        }

        
        LumberjackMinion lumber = lumberjackManager.getMinionByStandUuid(standId);
        if (lumber != null) {
            event.setCancelled(true);
            if (player.isSneaking()) { pickupLumberjack(player, lumber); return; }

            LumberjackMinionInventory gui = new LumberjackMinionInventory(lumber);
            openLumberjackGuis.put(player.getUniqueId(), gui);
            player.openInventory(gui.getInventory());
            player.playSound(player.getLocation(), Sound.BLOCK_CHEST_OPEN, 0.8f, 1f);
        }
    }

    
    
    

    @EventHandler(priority = EventPriority.HIGH)
    public void onHitStand(EntityDamageByEntityEvent event) {
        if (event.getEntityType() != EntityType.ARMOR_STAND) return;
        UUID standId = event.getEntity().getUniqueId();

        boolean isFarming    = farmingManager.getMinionByStandUuid(standId)    != null;
        boolean isLumberjack = lumberjackManager.getMinionByStandUuid(standId) != null;
        if (!isFarming && !isLumberjack) return;

        event.setCancelled(true);
        if (event.getDamager() instanceof Player player) {
            player.sendMessage(PREFIX + "§7§oSneak + right-click §7to pick up  |  §oRight-click §7to open.");
        }
    }

    
    
    

    private void pickupFarming(Player player, FarmingMinion minion) {
        if (!player.getUniqueId().equals(minion.getOwnerUuid())
                && !player.hasPermission("miniony.admin")) {
            player.sendMessage(PREFIX + "§cThis belongs to §e" + minion.getOwnerName() + "§c."); return;
        }
        for (ItemStack s : minion.getStorage()) {
            player.getInventory().addItem(s.clone()).values()
                    .forEach(o -> player.getWorld().dropItemNaturally(player.getLocation(), o));
        }
        farmingManager.removeMinion(minion);
        giveItem(player, MinionCommand.buildMinionItem());
        pickupEffect(player, minion.getLocation());
        player.sendMessage(PREFIX + "§aFarming Minion picked up!");
    }

    private void pickupLumberjack(Player player, LumberjackMinion minion) {
        if (!player.getUniqueId().equals(minion.getOwnerUuid())
                && !player.hasPermission("miniony.admin")) {
            player.sendMessage(PREFIX + "§cThis belongs to §e" + minion.getOwnerName() + "§c."); return;
        }
        for (ItemStack s : minion.getStorage()) {
            player.getInventory().addItem(s.clone()).values()
                    .forEach(o -> player.getWorld().dropItemNaturally(player.getLocation(), o));
        }
        lumberjackManager.removeMinion(minion);
        giveItem(player, MinionCommand.buildLumberjackItem());
        pickupEffect(player, minion.getLocation());
        player.sendMessage(PREFIX + "§aLumberjack Minion picked up!");
    }

    
    
    

    private void assignWandRegion(Player player, FarmingMinion minion) {
        Location p1 = wandPos1.get(player.getUniqueId());
        Location p2 = wandPos2.get(player.getUniqueId());
        if (p1 == null || p2 == null) {
            player.sendMessage(PREFIX + "§cSet §ePos1 §c(left-click) and §ePos2 §c(right-click) first!"); return;
        }
        if (!p1.getWorld().equals(p2.getWorld())) {
            player.sendMessage(PREFIX + "§cBoth positions must be in the same world!"); return;
        }
        MinionRegion region = new MinionRegion(p1, p2);
        minion.setRegion(region);
        wandPos1.remove(player.getUniqueId());
        wandPos2.remove(player.getUniqueId());
        farmingManager.save();

        player.sendMessage(PREFIX + "§aRegion §f" + region.describe()
                + " §a(" + region.blockCount() + " blocks) assigned!");
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.7f, 1.4f);
        minion.getStand().getWorld().spawnParticle(Particle.WAX_ON,
                minion.getStand().getLocation().add(0, 0.5, 0), 10, 0.3, 0.3, 0.3, 0);
    }

    
    
    

    @EventHandler(priority = EventPriority.HIGH)
    public void onGuiClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        
        FarmingMinionInventory farmGui = openFarmingGuis.get(player.getUniqueId());
        if (farmGui != null && event.getInventory().equals(farmGui.getInventory())) {
            int slot = event.getRawSlot();
            if (slot >= FarmingMinionInventory.SIZE) return;
            event.setCancelled(true);
            handleFarmingGuiClick(player, farmGui, slot);
            return;
        }

        
        LumberjackMinionInventory lumbGui = openLumberjackGuis.get(player.getUniqueId());
        if (lumbGui != null && event.getInventory().equals(lumbGui.getInventory())) {
            int slot = event.getRawSlot();
            if (slot >= LumberjackMinionInventory.SIZE) return;
            event.setCancelled(true);
            handleLumberjackGuiClick(player, lumbGui, slot);
        }
    }

    private void handleFarmingGuiClick(Player player, FarmingMinionInventory gui, int slot) {
        FarmingMinion minion = gui.getMinion();

        if (slot == FarmingMinionInventory.SLOT_SPEED_UP) {
            if (minion.upgradeSpeed()) {
                player.sendMessage(PREFIX + "§eSpeed upgraded to level §f" + minion.getSpeedLevel() + "§e!");
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.5f);
            } else player.sendMessage(PREFIX + "§cSpeed is already max!");
            gui.refresh(); return;
        }
        if (slot == FarmingMinionInventory.SLOT_STORAGE_UP) {
            if (minion.upgradeStorage()) {
                player.sendMessage(PREFIX + "§6Storage upgraded to level §f" + minion.getStorageLevel() + "§6!");
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.5f);
            } else player.sendMessage(PREFIX + "§cStorage is already max!");
            gui.refresh(); return;
        }
        if (slot == FarmingMinionInventory.SLOT_TOGGLE) {
            minion.setActive(!minion.isActive());
            player.sendMessage(PREFIX + "Minion is now " + (minion.isActive() ? "§aActive" : "§cPaused") + "§r.");
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.8f, 1f);
            gui.refresh(); return;
        }
        if (slot == FarmingMinionInventory.SLOT_COLLECT_ALL) {
            collectAll(player, minion.getStorage(), gui::refresh, () -> farmingManager.save()); return;
        }
        if (FarmingMinionInventory.isStorageSlot(slot) && slot < minion.getStorageCapacity()) {
            ItemStack clicked = gui.getInventory().getItem(slot);
            if (clicked == null || clicked.getType().isAir()
                    || clicked.getType() == Material.LIGHT_GRAY_STAINED_GLASS_PANE) return;
            giveItem(player, clicked.clone());
            minion.getStorage().remove(clicked);
            gui.refresh();
        }
    }

    private void handleLumberjackGuiClick(Player player, LumberjackMinionInventory gui, int slot) {
        LumberjackMinion minion = gui.getMinion();

        if (slot == LumberjackMinionInventory.SLOT_SPEED_UP) {
            if (minion.upgradeSpeed()) {
                player.sendMessage(PREFIX + "§eSpeed upgraded to level §f" + minion.getSpeedLevel() + "§e!");
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.5f);
            } else player.sendMessage(PREFIX + "§cSpeed is already max!");
            gui.refresh(); return;
        }
        if (slot == LumberjackMinionInventory.SLOT_STORAGE_UP) {
            if (minion.upgradeStorage()) {
                player.sendMessage(PREFIX + "§6Storage upgraded to level §f" + minion.getStorageLevel() + "§6!");
                player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_LEVELUP, 0.6f, 1.5f);
            } else player.sendMessage(PREFIX + "§cStorage is already max!");
            gui.refresh(); return;
        }
        if (slot == LumberjackMinionInventory.SLOT_TOGGLE) {
            minion.setActive(!minion.isActive());
            player.sendMessage(PREFIX + "Minion is now " + (minion.isActive() ? "§aActive" : "§cPaused") + "§r.");
            player.playSound(player.getLocation(), Sound.UI_BUTTON_CLICK, 0.8f, 1f);
            gui.refresh(); return;
        }
        if (slot == LumberjackMinionInventory.SLOT_COLLECT_ALL) {
            collectAll(player, minion.getStorage(), gui::refresh, () -> lumberjackManager.save()); return;
        }
        if (LumberjackMinionInventory.isStorageSlot(slot) && slot < minion.getStorageCapacity()) {
            ItemStack clicked = gui.getInventory().getItem(slot);
            if (clicked == null || clicked.getType().isAir()
                    || clicked.getType() == Material.LIGHT_GRAY_STAINED_GLASS_PANE) return;
            giveItem(player, clicked.clone());
            minion.getStorage().remove(clicked);
            gui.refresh();
        }
    }

    
    
    

    @EventHandler
    public void onGuiClose(InventoryCloseEvent event) {
        if (!(event.getPlayer() instanceof Player player)) return;

        FarmingMinionInventory farmGui = openFarmingGuis.remove(player.getUniqueId());
        if (farmGui != null) {
            farmGui.syncStorageToMinion();
            farmingManager.save();
            player.playSound(player.getLocation(), Sound.BLOCK_CHEST_CLOSE, 0.8f, 1f);
            return;
        }

        LumberjackMinionInventory lumbGui = openLumberjackGuis.remove(player.getUniqueId());
        if (lumbGui != null) {
            lumbGui.syncStorageToMinion();
            lumberjackManager.save();
            player.playSound(player.getLocation(), Sound.BLOCK_CHEST_CLOSE, 0.8f, 1f);
        }
    }

    
    
    

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();

        for (FarmingMinion m : farmingManager.getAllMinions()) {
            Location loc = m.getLocation();
            if (loc.getWorld().equals(chunk.getWorld())
                    && (loc.getBlockX() >> 4) == chunk.getX()
                    && (loc.getBlockZ() >> 4) == chunk.getZ()) {
                if (m.getStand() == null || m.getStand().isDead()) {
                    farmingManager.respawnMinion(m);
                }
            }
        }

        for (LumberjackMinion m : lumberjackManager.getAllMinions()) {
            Location loc = m.getLocation();
            if (loc.getWorld().equals(chunk.getWorld())
                    && (loc.getBlockX() >> 4) == chunk.getX()
                    && (loc.getBlockZ() >> 4) == chunk.getZ()) {
                if (m.getStand() == null || m.getStand().isDead()) {
                    lumberjackManager.respawnMinion(m);
                }
            }
        }
    }

    
    
    

    private void collectAll(Player player, java.util.List<ItemStack> storage,
                            Runnable refresh, Runnable save) {
        if (storage.isEmpty()) { player.sendMessage(PREFIX + "§7Nothing to collect."); return; }
        int collected = 0;
        var it = storage.iterator();
        while (it.hasNext()) {
            ItemStack item = it.next();
            HashMap<Integer, ItemStack> overflow = player.getInventory().addItem(item.clone());
            if (overflow.isEmpty()) { it.remove(); collected++; }
            else {
                item.setAmount(overflow.get(0).getAmount());
                player.sendMessage(PREFIX + "§eInventory full! Some items remain in minion.");
                break;
            }
        }
        if (collected > 0) {
            player.sendMessage(PREFIX + "§aCollected §f" + collected + " §astacks!");
            player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 1.2f);
            refresh.run();
            save.run();
        }
    }

    private void consumeItem(Player player, ItemStack item) {
        if (player.getGameMode() == GameMode.CREATIVE) return;
        if (item.getAmount() > 1) item.setAmount(item.getAmount() - 1);
        else player.getInventory().setItemInMainHand(new ItemStack(Material.AIR));
    }

    private void giveItem(Player player, ItemStack item) {
        player.getInventory().addItem(item).values()
                .forEach(o -> player.getWorld().dropItemNaturally(player.getLocation(), o));
    }

    private void spawnEffect(Location loc) {
        loc.getWorld().spawnParticle(Particle.HAPPY_VILLAGER,
                loc.clone().add(0.5, 1, 0.5), 15, 0.3, 0.5, 0.3, 0);
        loc.getWorld().playSound(loc, Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 1f, 1.2f);
    }

    private void pickupEffect(Player player, Location loc) {
        loc.clone().add(0.5, 0.5, 0.5).getWorld()
                .spawnParticle(Particle.SMOKE, loc.add(0.5, 0.5, 0.5), 10, 0.2, 0.3, 0.2, 0.02);
        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 1f, 1f);
    }

    private String formatBlock(Block b) {
        return "(" + b.getX() + ", " + b.getY() + ", " + b.getZ() + ")";
    }
}
