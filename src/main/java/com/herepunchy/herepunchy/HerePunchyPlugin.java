package com.herepunchy.herepunchy;

import com.herepunchy.herepunchy.auraskills.AuraSkillsHelper;
import com.herepunchy.herepunchy.command.HerePunchyCommand;
import com.herepunchy.herepunchy.config.PunchyConfigListener;
import com.herepunchy.herepunchy.config.PunchyConfigManager;
import com.herepunchy.herepunchy.config.PunchyConfigUI;
import com.herepunchy.herepunchy.listener.PunchListener;
import com.herepunchy.herepunchy.map.ScanManager;
import com.herepunchy.herepunchy.selection.SelectionManager;
import com.herepunchy.herepunchy.setup.SetupManager;
import com.herepunchy.herepunchy.task.PunchTaskManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

public final class HerePunchyPlugin extends JavaPlugin {

    private static HerePunchyPlugin instance;
    private SelectionManager selectionManager;
    private PunchTaskManager punchTaskManager;
    private ScanManager scanManager;
    private SetupManager setupManager;
    private PunchyConfigManager configManager;
    private PunchyConfigUI configUI;
    private AuraSkillsHelper auraSkillsHelper;

    @Override
    public void onEnable() {
        instance = this;

        // Initialize Managers and UIs
        this.selectionManager = new SelectionManager(this);
        this.punchTaskManager = new PunchTaskManager(this);
        this.scanManager = new ScanManager(this);
        this.setupManager = new SetupManager(this);
        this.configManager = new PunchyConfigManager(this);
        this.configUI = new PunchyConfigUI(configManager);
        this.auraSkillsHelper = new AuraSkillsHelper();
        this.auraSkillsHelper.init();

        // Register Command
        getCommand("herepunchy").setExecutor(new HerePunchyCommand(selectionManager, punchTaskManager, scanManager, setupManager, configUI, auraSkillsHelper));

        // Register Listeners
        getServer().getPluginManager().registerEvents(new PunchListener(selectionManager, punchTaskManager, scanManager, setupManager), this);
        getServer().getPluginManager().registerEvents(new PunchyConfigListener(configUI, configManager), this);

        // Start Setup Wizard Timeout Checker
        new BukkitRunnable() {
            @Override
            public void run() {
                for (org.bukkit.entity.Player player : getServer().getOnlinePlayers()) {
                    if (setupManager.checkTimeout(player.getUniqueId())) {
                        player.sendMessage(net.kyori.adventure.text.Component.text("Setup wizard timed out due to inactivity.").color(net.kyori.adventure.text.format.NamedTextColor.RED));
                    }
                }
            }
        }.runTaskTimer(this, 0, 20); // Check every second

        getLogger().info("HerePunchy successfully enabled!");
    }

    @Override
    public void onDisable() {
        punchTaskManager.stopAllTasks();
        getLogger().info("HerePunchy successfully disabled!");
    }

    public static HerePunchyPlugin getInstance() {
        return instance;
    }

    public SelectionManager getSelectionManager() {
        return selectionManager;
    }

    public PunchTaskManager getPunchTaskManager() {
        return punchTaskManager;
    }

    public ScanManager getScanManager() {
        return scanManager;
    }

    public SetupManager getSetupManager() {
        return setupManager;
    }

    public PunchyConfigManager getConfigManager() {
        return configManager;
    }

    public PunchyConfigUI getConfigUI() {
        return configUI;
    }

    public AuraSkillsHelper getAuraSkillsHelper() {
        return auraSkillsHelper;
    }
}
