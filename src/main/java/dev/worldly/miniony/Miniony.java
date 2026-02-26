package dev.worldly.miniony;

import dev.worldly.miniony.command.MinionCommand;
import dev.worldly.miniony.listener.MinionListener;
import dev.worldly.miniony.minion.LumberjackMinionManager;
import dev.worldly.miniony.minion.MinionManager;
import org.bukkit.plugin.java.JavaPlugin;

public final class Miniony extends JavaPlugin {

    private MinionManager          minionManager;
    private LumberjackMinionManager lumberjackManager;

    @Override
    public void onEnable() {
        if (!getDataFolder().exists()) getDataFolder().mkdirs();

        
        minionManager = new MinionManager(this);
        minionManager.load();

        
        lumberjackManager = new LumberjackMinionManager(this);
        lumberjackManager.load();

        
        MinionCommand cmd = new MinionCommand(this, minionManager, lumberjackManager);
        getCommand("minion").setExecutor(cmd);
        getCommand("minion").setTabCompleter(cmd);

        
        getServer().getPluginManager().registerEvents(
                new MinionListener(minionManager, lumberjackManager), this);

        getLogger().info("Miniony enabled! "
                + minionManager.getMinionCount()     + " farming minion(s), "
                + lumberjackManager.getMinionCount() + " lumberjack minion(s) loaded.");
    }

    @Override
    public void onDisable() {
        if (minionManager != null) {
            minionManager.save();
            minionManager.unloadAll();
        }
        if (lumberjackManager != null) {
            lumberjackManager.save();
            lumberjackManager.unloadAll();
        }
        getLogger().info("Miniony disabled. All minions saved.");
    }

    public MinionManager          getMinionManager()     { return minionManager; }
    public LumberjackMinionManager getLumberjackManager() { return lumberjackManager; }
}
