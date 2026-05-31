package com.herepunchy.herepunchy.command;

import com.herepunchy.herepunchy.HerePunchyPlugin;
import com.herepunchy.herepunchy.auraskills.AuraSkillsHelper;
import com.herepunchy.herepunchy.config.PunchyConfigUI;
import com.herepunchy.herepunchy.map.ScanManager;
import com.herepunchy.herepunchy.map.ScanResult;
import com.herepunchy.herepunchy.path.PathGenerator;
import com.herepunchy.herepunchy.selection.SelectionManager;
import com.herepunchy.herepunchy.setup.SetupManager;
import com.herepunchy.herepunchy.task.PunchTask;
import com.herepunchy.herepunchy.task.PunchTaskManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.UUID;

public class HerePunchyCommand implements CommandExecutor {

    private final SelectionManager selectionManager;
    private final PunchTaskManager punchTaskManager;
    private final ScanManager scanManager;
    private final SetupManager setupManager;
    private final PunchyConfigUI configUI;
    private final AuraSkillsHelper auraSkillsHelper;

    public HerePunchyCommand(SelectionManager selectionManager, PunchTaskManager punchTaskManager, ScanManager scanManager, SetupManager setupManager, PunchyConfigUI configUI, AuraSkillsHelper auraSkillsHelper) {
        this.selectionManager = selectionManager;
        this.punchTaskManager = punchTaskManager;
        this.scanManager = scanManager;
        this.setupManager = setupManager;
        this.configUI = configUI;
        this.auraSkillsHelper = auraSkillsHelper;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage(Component.text("This command can only be used by players!").color(NamedTextColor.RED));
            return true;
        }

        if (args.length < 1) {
            player.sendMessage(Component.text("Usage: /herepunchy <start|stop|restart|clear|setup|config|select>").color(NamedTextColor.YELLOW));
            return true;
        }

        String sub = args[0].toLowerCase();
        UUID uuid = player.getUniqueId();

        switch (sub) {
            case "start" -> {
                if (punchTaskManager.isPatrolling(player)) {
                    player.sendMessage(Component.text("Auto-patrol is already enabled!").color(NamedTextColor.YELLOW));
                    return true;
                }

                if (!selectionManager.hasCompleteSelection(uuid)) {
                    selectionManager.setSelectionMode(uuid, true);
                    player.sendMessage(Component.text("Selection missing! Selection Mode has been automatically enabled.").color(NamedTextColor.YELLOW));
                    player.sendMessage(Component.text("👉 Hold a Sword and Shift-Right-Click two blocks to set Point A and Point B.").color(NamedTextColor.GREEN));
                    return true;
                }

                if (!scanManager.hasScan(uuid)) {
                    player.sendMessage(Component.text("Scanning area... Please wait.").color(NamedTextColor.GREEN));
                    scanManager.scanAreaAsync(uuid, selectionManager.getPointA(uuid), selectionManager.getPointB(uuid),
                            result -> startPatrol(player, result));
                    return true;
                }

                startPatrol(player, scanManager.getScanResult(uuid));
            }
            case "stop" -> {
                if (!punchTaskManager.isPatrolling(player)) {
                    player.sendMessage(Component.text("Auto-patrol is not currently running!").color(NamedTextColor.YELLOW));
                } else {
                    punchTaskManager.stopTask(player);
                    player.sendMessage(Component.text("Auto-patrol combat bot disabled.").color(NamedTextColor.GREEN));
                }
            }
            case "restart" -> {
                if (!punchTaskManager.hasLastStop(player)) {
                    player.sendMessage(Component.text("No paused session to restart. Use /herepunchy start instead.").color(NamedTextColor.YELLOW));
                    return true;
                }

                if (!selectionManager.hasCompleteSelection(uuid)) {
                    selectionManager.setSelectionMode(uuid, true);
                    player.sendMessage(Component.text("Selection missing! Selection Mode automatically enabled.").color(NamedTextColor.YELLOW));
                    player.sendMessage(Component.text("👉 Hold a Sword and Shift-Right-Click two blocks to set Point A and Point B.").color(NamedTextColor.GREEN));
                    return true;
                }

                if (!scanManager.hasScan(uuid)) {
                    player.sendMessage(Component.text("Scanning area... Please wait.").color(NamedTextColor.GREEN));
                    scanManager.scanAreaAsync(uuid, selectionManager.getPointA(uuid), selectionManager.getPointB(uuid),
                            result -> restartPatrol(player, result));
                    return true;
                }

                restartPatrol(player, scanManager.getScanResult(uuid));
            }
            case "clear" -> {
                punchTaskManager.stopTask(player);
                selectionManager.clearSelection(uuid);
                scanManager.clearScan(uuid);
                punchTaskManager.clearLastStop(player);
                setupManager.clearSetupConfig(uuid);
                player.sendMessage(Component.text("Patrol selections, maps, pauses, and setups cleared.").color(NamedTextColor.GREEN));
            }
            case "setup" -> {
                setupManager.setSetupState(uuid, SetupManager.SetupState.WAITING_FOR_WEAPONS);
                player.sendMessage(Component.text("=======================================").color(NamedTextColor.GRAY));
                player.sendMessage(Component.text("⚔ HERE PUNCHY CHEST CONTAINER SETUP WIZARD ⚔").color(NamedTextColor.GOLD));
                player.sendMessage(Component.text("👉 Right-click a Chest to define your BACKUP WEAPONS container.").color(NamedTextColor.YELLOW));
                player.sendMessage(Component.text("=======================================").color(NamedTextColor.GRAY));
            }
            case "config" -> {
                configUI.openPriorityMenu(player);
            }
            case "select" -> {
                boolean cur = selectionManager.isSelectionMode(uuid);
                selectionManager.setSelectionMode(uuid, !cur);
                if (!cur) {
                    player.sendMessage(Component.text("Selection Mode ENABLED! Hold any Sword and Shift-Right-Click two blocks to set Point A and Point B.").color(NamedTextColor.GREEN));
                } else {
                    player.sendMessage(Component.text("Selection Mode DISABLED.").color(NamedTextColor.YELLOW));
                }
            }
            default -> player.sendMessage(Component.text("Usage: /herepunchy <start|stop|restart|clear|setup|config|select>").color(NamedTextColor.YELLOW));
        }

        return true;
    }

    private void startPatrol(Player player, ScanResult result) {
        List<Location> path = PathGenerator.generateSafePath(result);
        if (path.isEmpty()) {
            player.sendMessage(Component.text("No walkable blocks mapped inside your selected zone!").color(NamedTextColor.RED));
            return;
        }

        PunchTask task = new PunchTask(HerePunchyPlugin.getInstance(), player, path, scanManager, selectionManager, result, auraSkillsHelper);
        int startIndex = PathGenerator.findClosestIndex(path, result.getPointA());
        task.setCurrentIndex(startIndex);
        task.setMovingForward(true);

        punchTaskManager.startTask(player, task);
        punchTaskManager.clearLastStop(player);

        player.sendMessage(Component.text("Auto-patrol activated! Walking ")
                .color(NamedTextColor.GREEN)
                .append(Component.text(path.size()).color(NamedTextColor.YELLOW))
                .append(Component.text(" safe waypoints from starting point...").color(NamedTextColor.GREEN)));
    }

    private void restartPatrol(Player player, ScanResult result) {
        List<Location> path = PathGenerator.generateSafePath(result);
        if (path.isEmpty()) {
            player.sendMessage(Component.text("No walkable blocks mapped inside your selected zone!").color(NamedTextColor.RED));
            return;
        }

        int lastIdx = punchTaskManager.getLastStopIndex(player);
        boolean lastDir = punchTaskManager.getLastDirection(player);

        PunchTask task = new PunchTask(HerePunchyPlugin.getInstance(), player, path, scanManager, selectionManager, result, auraSkillsHelper);
        if (lastIdx >= 0 && lastIdx < path.size()) {
            task.setCurrentIndex(lastIdx);
        }
        task.setMovingForward(lastDir);

        punchTaskManager.startTask(player, task);
        punchTaskManager.clearLastStop(player);

        player.sendMessage(Component.text("Auto-patrol restarted from waypoint ")
                .color(NamedTextColor.GREEN)
                .append(Component.text(task.getCurrentIndex() + 1).color(NamedTextColor.YELLOW))
                .append(Component.text(" of ").color(NamedTextColor.GREEN))
                .append(Component.text(path.size()).color(NamedTextColor.YELLOW))
                .append(Component.text(".").color(NamedTextColor.GREEN)));
    }
}
