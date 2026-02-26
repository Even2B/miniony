package dev.worldly.miniony.command;

import dev.worldly.miniony.Miniony;
import dev.worldly.miniony.minion.FarmingMinion;
import dev.worldly.miniony.minion.LumberjackMinion;
import dev.worldly.miniony.minion.LumberjackMinionManager;
import dev.worldly.miniony.minion.MinionManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.ArmorStand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;


public class MinionCommand implements CommandExecutor, TabCompleter {

    private static final String PREFIX = "§6[Miniony] §r";

    private final Miniony                plugin;
    private final MinionManager          manager;
    private final LumberjackMinionManager lumberjackManager;

    public MinionCommand(Miniony plugin, MinionManager manager,
                         LumberjackMinionManager lumberjackManager) {
        this.plugin             = plugin;
        this.manager            = manager;
        this.lumberjackManager  = lumberjackManager;
    }

    
    
    

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 0) { sendHelp(sender); return true; }

        switch (args[0].toLowerCase()) {
            case "give"   -> handleGive(sender, args);
            case "wand"   -> handleWand(sender);
            case "list"   -> handleList(sender);
            case "call"   -> handleCall(sender, args);
            case "info"   -> handleInfo(sender);
            case "reload" -> handleReload(sender);
            default       -> sendHelp(sender);
        }
        return true;
    }

    
    
    

    
    private void handleGive(CommandSender sender, String[] args) {
        
        boolean isLumberjack = false;
        int     playerArgIdx = 1; 

        if (args.length >= 2) {
            String a1 = args[1].toLowerCase();
            if (a1.equals("lumberjack")) {
                isLumberjack = true;
                playerArgIdx = 2;
            } else if (a1.equals("farming")) {
                playerArgIdx = 2;
            }
            
        }

        
        Player target;
        if (args.length > playerArgIdx) {
            
            if (!sender.hasPermission("miniony.admin")) {
                sender.sendMessage(PREFIX + "§cNo permission.");
                return;
            }
            target = Bukkit.getPlayer(args[playerArgIdx]);
            if (target == null) {
                sender.sendMessage(PREFIX + "§cPlayer '§e" + args[playerArgIdx] + "§c' not found.");
                return;
            }
        } else {
            if (!(sender instanceof Player p)) {
                sender.sendMessage(PREFIX + "§cConsole must specify a player name.");
                return;
            }
            if (!p.hasPermission("miniony.give")) {
                p.sendMessage(PREFIX + "§cNo permission.");
                return;
            }
            target = p;
        }

        
        ItemStack item    = isLumberjack ? buildLumberjackItem() : buildMinionItem();
        String    typeName = isLumberjack ? "§2Lumberjack Minion" : "§6Farming Minion";

        HashMap<Integer, ItemStack> overflow = target.getInventory().addItem(item);
        if (!overflow.isEmpty()) {
            target.getWorld().dropItemNaturally(target.getLocation(), item);
            target.sendMessage(PREFIX + "§eInventory full — " + typeName + "§e dropped at your feet!");
        } else {
            target.sendMessage(PREFIX + "§aYou received a " + typeName + "§a!");
        }
        if (!sender.equals(target)) {
            sender.sendMessage(PREFIX + "§aGave a " + typeName + " §ato §e" + target.getName() + "§a.");
        }
    }

    private void handleWand(CommandSender sender) {
        if (!(sender instanceof Player p)) { sender.sendMessage(PREFIX + "§cPlayers only."); return; }
        if (!p.hasPermission("miniony.wand")) { p.sendMessage(PREFIX + "§cNo permission."); return; }
        ItemStack wand = buildWandItem();
        p.getInventory().addItem(wand).values()
                .forEach(o -> p.getWorld().dropItemNaturally(p.getLocation(), o));
        p.sendMessage(PREFIX + "§aYou received a §6Minion Wand§a!");
        p.sendMessage("§7§oLeft-click §7= §ePos1  §7|  §oRight-click §7= §ePos2");
        p.sendMessage("§7§oThen place a Farming Minion or right-click an existing one.");
    }

    
    private void handleCall(CommandSender sender, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(PREFIX + "§cPlayers only.");
            return;
        }
        if (!p.hasPermission("miniony.use")) {
            p.sendMessage(PREFIX + "§cNo permission.");
            return;
        }

        String type = args.length >= 2 ? args[1].toLowerCase() : "all";
        boolean doFarming    = type.equals("farming")    || type.equals("all");
        boolean doLumberjack = type.equals("lumberjack") || type.equals("all");

        if (!doFarming && !doLumberjack) {
            p.sendMessage(PREFIX + "§cUsage: §e/minion call [farming|lumberjack|all]");
            return;
        }

        Location dest = p.getLocation();
        int count = 0;

        if (doFarming) {
            for (FarmingMinion m : manager.getMinionsOf(p.getUniqueId())) {
                m.setBusy(false);
                ArmorStand stand = m.getStand();
                if (stand != null && !stand.isDead()) stand.teleport(dest);
                count++;
            }
        }
        if (doLumberjack) {
            for (LumberjackMinion m : lumberjackManager.getMinionsOf(p.getUniqueId())) {
                m.setBusy(false);
                ArmorStand stand = m.getStand();
                if (stand != null && !stand.isDead()) stand.teleport(dest);
                count++;
            }
        }

        if (count == 0) {
            p.sendMessage(PREFIX + "§7You have no active " + type + " minions to call.");
        } else {
            p.sendMessage(PREFIX + "§aCalled §f" + count + " §aminion(s) to your location!");
        }
    }

    private void handleList(CommandSender sender) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage(PREFIX + "Farming: " + manager.getMinionCount()
                    + "  |  Lumberjack: " + lumberjackManager.getMinionCount());
            return;
        }

        List<FarmingMinion>    farming    = manager.getMinionsOf(p.getUniqueId());
        List<LumberjackMinion> lumberjack = lumberjackManager.getMinionsOf(p.getUniqueId());

        if (farming.isEmpty() && lumberjack.isEmpty()) {
            p.sendMessage(PREFIX + "§7You have no active minions.");
            return;
        }
        if (!farming.isEmpty()) {
            p.sendMessage(PREFIX + "§6Farming Minions §7(" + farming.size() + "):");
            for (FarmingMinion m : farming) {
                p.sendMessage("  §7• " + (m.isActive() ? "§aActive" : "§cPaused")
                        + " §7@ " + formatLoc(m.getLocation())
                        + " §7| Storage: §f" + m.getStorage().size() + "§7/§f" + m.getStorageCapacity());
            }
        }
        if (!lumberjack.isEmpty()) {
            p.sendMessage(PREFIX + "§2Lumberjack Minions §7(" + lumberjack.size() + "):");
            for (LumberjackMinion m : lumberjack) {
                p.sendMessage("  §7• " + (m.isActive() ? "§aActive" : "§cPaused")
                        + " §7@ " + formatLoc(m.getLocation())
                        + " §7| Storage: §f" + m.getStorage().size() + "§7/§f" + m.getStorageCapacity());
            }
        }
    }

    private void handleInfo(CommandSender sender) {
        sender.sendMessage("§6§l====== Miniony ======");
        sender.sendMessage("§7Version:           §f" + plugin.getDescription().getVersion());
        sender.sendMessage("§7Farming minions:   §f" + manager.getMinionCount());
        sender.sendMessage("§7Lumberjack minions:§f" + lumberjackManager.getMinionCount());
        sender.sendMessage("§7§o/minion give farming             §7— get a farming minion");
        sender.sendMessage("§7§o/minion give lumberjack          §7— get a lumberjack minion");
        sender.sendMessage("§7§o/minion call [farming|lumberjack|all] §7— call minions to you");
        sender.sendMessage("§6§l====================");
    }

    private void handleReload(CommandSender sender) {
        if (!sender.hasPermission("miniony.admin")) { sender.sendMessage(PREFIX + "§cNo permission."); return; }
        manager.unloadAll();
        manager.load();
        lumberjackManager.unloadAll();
        lumberjackManager.load();
        sender.sendMessage(PREFIX + "§aReloaded. §7("
                + manager.getMinionCount() + " farming, "
                + lumberjackManager.getMinionCount() + " lumberjack)");
    }

    
    
    

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) return Arrays.asList("give", "wand", "list", "call", "info", "reload");
        if (args.length == 2) {
            if (args[0].equalsIgnoreCase("give")) return Arrays.asList("farming", "lumberjack");
            if (args[0].equalsIgnoreCase("call")) return Arrays.asList("farming", "lumberjack", "all");
        }
        if (args.length == 3 && args[0].equalsIgnoreCase("give")
                && sender.hasPermission("miniony.admin")) return null;
        return Collections.emptyList();
    }

    
    
    

    public static ItemStack buildMinionItem() {
        ItemStack item = new ItemStack(Material.SPAWNER);
        ItemMeta  meta = item.getItemMeta();
        meta.setDisplayName("§6§lFarming Minion");
        meta.setLore(Arrays.asList(
                "§7An advanced farming minion that",
                "§7harvests and replants crops automatically.",
                "",
                "§eRight-click §7to place on a block.",
                "§8[minion:farming]"
        ));
        item.setItemMeta(meta);
        return item;
    }

    public static boolean isMinionItem(ItemStack item) {
        if (item == null || item.getType() != Material.SPAWNER) return false;
        if (!item.hasItemMeta() || !item.getItemMeta().hasLore()) return false;
        return item.getItemMeta().getLore().contains("§8[minion:farming]");
    }

    public static ItemStack buildLumberjackItem() {
        ItemStack item = new ItemStack(Material.OAK_LOG);
        ItemMeta  meta = item.getItemMeta();
        meta.setDisplayName("§2§lLumberjack Minion");
        meta.setLore(Arrays.asList(
                "§7An advanced lumberjack minion that",
                "§7finds and chops whole trees automatically.",
                "§7Intelligently avoids player-built structures.",
                "",
                "§eRight-click §7to place on a block.",
                "§8[minion:lumberjack]"
        ));
        item.setItemMeta(meta);
        return item;
    }

    public static boolean isLumberjackItem(ItemStack item) {
        if (item == null || item.getType() != Material.OAK_LOG) return false;
        if (!item.hasItemMeta() || !item.getItemMeta().hasLore()) return false;
        return item.getItemMeta().getLore().contains("§8[minion:lumberjack]");
    }

    public static ItemStack buildWandItem() {
        ItemStack item = new ItemStack(Material.BLAZE_ROD);
        ItemMeta  meta = item.getItemMeta();
        meta.setDisplayName("§6§lMinion Wand");
        meta.setLore(Arrays.asList(
                "§7Use this to define the farming area",
                "§7for your Farming Minion.",
                "",
                "§eLeft-click §7a block = §aPos1",
                "§eRight-click §7a block = §aPos2",
                "§7Then place or right-click a minion.",
                "§8[minion:wand]"
        ));
        item.setItemMeta(meta);
        return item;
    }

    public static boolean isWandItem(ItemStack item) {
        if (item == null || item.getType() != Material.BLAZE_ROD) return false;
        if (!item.hasItemMeta() || !item.getItemMeta().hasLore()) return false;
        return item.getItemMeta().getLore().contains("§8[minion:wand]");
    }

    
    
    

    private String formatLoc(Location l) {
        return "§e" + l.getWorld().getName() + " §7("
                + l.getBlockX() + ", " + l.getBlockY() + ", " + l.getBlockZ() + "§7)";
    }

    private void sendHelp(CommandSender sender) {
        sender.sendMessage("§6§l/minion §r§7commands:");
        sender.sendMessage("  §e/minion give [farming|lumberjack] §7[player]");
        sender.sendMessage("  §e/minion wand                 §8— §fregion wand (farming)");
        sender.sendMessage("  §e/minion list                 §8— §fshow your active minions");
        sender.sendMessage("  §e/minion call [farming|lumberjack|all] §8— §fcall minions to you");
        sender.sendMessage("  §e/minion info                 §8— §fplugin info");
        if (sender.hasPermission("miniony.admin"))
            sender.sendMessage("  §e/minion reload               §8— §freload plugin data");
    }
}
