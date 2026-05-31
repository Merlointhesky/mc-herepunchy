package com.herepunchy.herepunchy.map;

import org.bukkit.Location;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

public class ScanManager {

    private final Plugin plugin;
    private final Map<UUID, ScanResult> scans = new HashMap<>();

    public ScanManager(Plugin plugin) {
        this.plugin = plugin;
    }

    public void scanAreaAsync(UUID playerId, Location pointA, Location pointB, Consumer<ScanResult> callback) {
        new BukkitRunnable() {
            @Override
            public void run() {
                ScanResult result = AreaScanner.scan(pointA, pointB);
                new BukkitRunnable() {
                    @Override
                    public void run() {
                        scans.put(playerId, result);
                        if (callback != null) {
                            callback.accept(result);
                        }
                    }
                }.runTask(plugin);
            }
        }.runTaskAsynchronously(plugin);
    }

    public ScanResult getScanResult(UUID playerId) {
        return scans.get(playerId);
    }

    public boolean hasScan(UUID playerId) {
        return scans.containsKey(playerId);
    }

    public void clearScan(UUID playerId) {
        scans.remove(playerId);
    }

    public void clearAll() {
        scans.clear();
    }
}
