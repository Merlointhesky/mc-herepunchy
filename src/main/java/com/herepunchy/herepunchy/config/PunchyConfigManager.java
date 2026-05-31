package com.herepunchy.herepunchy.config;

import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class PunchyConfigManager {

    private final Plugin plugin;
    private final File configDir;
    private final Map<UUID, PlayerPunchyConfig> playerConfigs = new HashMap<>();

    public PunchyConfigManager(Plugin plugin) {
        this.plugin = plugin;
        this.configDir = new File(plugin.getDataFolder(), "configs");
        if (!configDir.exists()) {
            configDir.mkdirs();
        }
    }

    public PlayerPunchyConfig getPlayerConfig(UUID playerId) {
        if (!playerConfigs.containsKey(playerId)) {
            loadPlayerConfig(playerId);
        }
        return playerConfigs.computeIfAbsent(playerId, PlayerPunchyConfig::new);
    }

    public void loadPlayerConfig(UUID playerId) {
        File file = new File(configDir, playerId + ".yml");
        PlayerPunchyConfig config = new PlayerPunchyConfig(playerId);

        if (file.exists()) {
            FileConfiguration yaml = YamlConfiguration.loadConfiguration(file);
            List<String> list = yaml.getStringList("priority");
            if (!list.isEmpty()) {
                List<PlayerPunchyConfig.WeaponType> loadedPriority = new ArrayList<>();
                for (String s : list) {
                    try {
                        loadedPriority.add(PlayerPunchyConfig.WeaponType.valueOf(s));
                    } catch (IllegalArgumentException ignored) {
                    }
                }
                // Ensure all types are included (fill missing if any)
                for (PlayerPunchyConfig.WeaponType type : PlayerPunchyConfig.WeaponType.values()) {
                    if (!loadedPriority.contains(type)) {
                        loadedPriority.add(type);
                    }
                }
                config.setWeaponPriority(loadedPriority);
            }
        }
        playerConfigs.put(playerId, config);
    }

    public void saveConfiguration(UUID playerId) {
        PlayerPunchyConfig config = playerConfigs.get(playerId);
        if (config == null) return;

        File file = new File(configDir, playerId + ".yml");
        FileConfiguration yaml = new YamlConfiguration();

        List<String> list = new ArrayList<>();
        for (PlayerPunchyConfig.WeaponType type : config.getWeaponPriority()) {
            list.add(type.name());
        }
        yaml.set("priority", list);

        try {
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save punchy configuration for " + playerId + ": " + e.getMessage());
        }
    }
}
