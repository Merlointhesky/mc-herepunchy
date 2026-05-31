package com.herepunchy.herepunchy.setup;

import org.bukkit.Location;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class SetupManager {

    private final Plugin plugin;
    private final File setupDir;
    private final Map<UUID, SetupConfiguration> configs = new HashMap<>();
    private final Map<UUID, SetupState> states = new HashMap<>();
    private final Map<UUID, Long> timeouts = new HashMap<>();

    public enum SetupState {
        NONE,
        WAITING_FOR_WEAPONS,
        WAITING_FOR_LOOT
    }

    public SetupManager(Plugin plugin) {
        this.plugin = plugin;
        this.setupDir = new File(plugin.getDataFolder(), "setups");
        if (!setupDir.exists()) {
            setupDir.mkdirs();
        }
    }

    public boolean isInSetup(UUID playerId) {
        return states.getOrDefault(playerId, SetupState.NONE) != SetupState.NONE;
    }

    public SetupState getSetupState(UUID playerId) {
        return states.getOrDefault(playerId, SetupState.NONE);
    }

    public void setSetupState(UUID playerId, SetupState state) {
        if (state == SetupState.NONE) {
            states.remove(playerId);
            timeouts.remove(playerId);
        } else {
            states.put(playerId, state);
            timeouts.put(playerId, System.currentTimeMillis() + 60000); // 60s timeout
        }
    }

    public SetupConfiguration getSetupConfig(UUID playerId) {
        if (!configs.containsKey(playerId)) {
            loadSetupConfig(playerId);
        }
        return configs.computeIfAbsent(playerId, k -> new SetupConfiguration());
    }

    public void clearSetupConfig(UUID playerId) {
        configs.remove(playerId);
        states.remove(playerId);
        timeouts.remove(playerId);
        File file = new File(setupDir, playerId + ".yml");
        if (file.exists()) {
            file.delete();
        }
    }

    public boolean checkTimeout(UUID playerId) {
        if (!isInSetup(playerId)) return false;
        Long exp = timeouts.get(playerId);
        if (exp != null && System.currentTimeMillis() > exp) {
            setSetupState(playerId, SetupState.NONE);
            return true;
        }
        return false;
    }

    public void refreshTimeout(UUID playerId) {
        if (isInSetup(playerId)) {
            timeouts.put(playerId, System.currentTimeMillis() + 60000);
        }
    }

    public void saveSetupConfig(UUID playerId) {
        SetupConfiguration config = configs.get(playerId);
        if (config == null) return;

        File file = new File(setupDir, playerId + ".yml");
        FileConfiguration yaml = new YamlConfiguration();

        if (config.getBackupWeaponsBox() != null) {
            yaml.set("backupWeaponsBox", config.getBackupWeaponsBox());
        }
        if (config.getLootDropBox() != null) {
            yaml.set("lootDropBox", config.getLootDropBox());
        }

        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save setup config for " + playerId + ": " + e.getMessage());
        }
    }

    private void loadSetupConfig(UUID playerId) {
        File file = new File(setupDir, playerId + ".yml");
        if (!file.exists()) return;

        FileConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        SetupConfiguration config = new SetupConfiguration();

        config.setBackupWeaponsBox(yaml.getLocation("backupWeaponsBox"));
        config.setLootDropBox(yaml.getLocation("lootDropBox"));

        configs.put(playerId, config);
    }
}
