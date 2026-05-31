package com.herepunchy.herepunchy.task;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class PunchTaskManager {

    private final Plugin plugin;
    private final Map<UUID, PunchTask> activeTasks = new HashMap<>();
    private final Map<UUID, Integer> lastStopIndex = new HashMap<>();
    private final Map<UUID, Boolean> lastDirection = new HashMap<>();

    public PunchTaskManager(Plugin plugin) {
        this.plugin = plugin;
    }

    public void startTask(Player player, PunchTask task) {
        stopTask(player);
        task.runTaskTimer(plugin, 0, 2); // runs every 2 ticks (10 times a second)
        Bukkit.getPluginManager().registerEvents(task, plugin);
        activeTasks.put(player.getUniqueId(), task);
    }

    public void stopTask(Player player) {
        PunchTask task = activeTasks.remove(player.getUniqueId());
        if (task != null) {
            task.cancel();
            HandlerList.unregisterAll(task);
        }
    }

    public boolean isPatrolling(Player player) {
        return activeTasks.containsKey(player.getUniqueId());
    }

    public void stopAllTasks() {
        for (PunchTask task : activeTasks.values()) {
            task.cancel();
            HandlerList.unregisterAll(task);
        }
        activeTasks.clear();
    }

    public void recordStop(Player player, int index, boolean movingForward) {
        lastStopIndex.put(player.getUniqueId(), index);
        lastDirection.put(player.getUniqueId(), movingForward);
        activeTasks.remove(player.getUniqueId());
    }

    public int getLastStopIndex(Player player) {
        return lastStopIndex.getOrDefault(player.getUniqueId(), -1);
    }

    public boolean getLastDirection(Player player) {
        return lastDirection.getOrDefault(player.getUniqueId(), true);
    }

    public boolean hasLastStop(Player player) {
        return lastStopIndex.containsKey(player.getUniqueId());
    }

    public void clearLastStop(Player player) {
        lastStopIndex.remove(player.getUniqueId());
        lastDirection.remove(player.getUniqueId());
    }
}
