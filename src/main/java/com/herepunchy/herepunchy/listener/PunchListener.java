package com.herepunchy.herepunchy.listener;

import com.herepunchy.herepunchy.HerePunchyPlugin;
import com.herepunchy.herepunchy.map.ScanManager;
import com.herepunchy.herepunchy.selection.SelectionManager;
import com.herepunchy.herepunchy.setup.SetupConfiguration;
import com.herepunchy.herepunchy.setup.SetupManager;
import com.herepunchy.herepunchy.task.PunchTaskManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;

public class PunchListener implements Listener {

    private final SelectionManager selectionManager;
    private final PunchTaskManager punchTaskManager;
    private final ScanManager scanManager;
    private final SetupManager setupManager;

    public PunchListener(SelectionManager selectionManager, PunchTaskManager punchTaskManager, ScanManager scanManager, SetupManager setupManager) {
        this.selectionManager = selectionManager;
        this.punchTaskManager = punchTaskManager;
        this.scanManager = scanManager;
        this.setupManager = setupManager;
    }

    @EventHandler
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) return;
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Player player = event.getPlayer();
        Block clickedBlock = event.getClickedBlock();
        if (clickedBlock == null) return;

        // 1. Check Setup Wizard Clicking
        if (setupManager.isInSetup(player.getUniqueId())) {
            event.setCancelled(true);
            if (!(clickedBlock.getState() instanceof Container)) {
                player.sendMessage(Component.text("That is not a chest or container block! Please click a chest.").color(NamedTextColor.RED));
                return;
            }

            Location blockLoc = clickedBlock.getLocation();
            SetupManager.SetupState state = setupManager.getSetupState(player.getUniqueId());
            SetupConfiguration config = setupManager.getSetupConfig(player.getUniqueId());

            if (state == SetupManager.SetupState.WAITING_FOR_WEAPONS) {
                config.setBackupWeaponsBox(blockLoc);
                setupManager.saveSetupConfig(player.getUniqueId());
                setupManager.setSetupState(player.getUniqueId(), SetupManager.SetupState.WAITING_FOR_LOOT);
                player.sendMessage(Component.text("1. Backup Weapons Chest successfully set!").color(NamedTextColor.GREEN));
                player.sendMessage(Component.text("👉 Now, right-click another chest to set the LOOT DROP-OFF box.").color(NamedTextColor.YELLOW));
            } else if (state == SetupManager.SetupState.WAITING_FOR_LOOT) {
                config.setLootDropBox(blockLoc);
                setupManager.saveSetupConfig(player.getUniqueId());
                setupManager.setSetupState(player.getUniqueId(), SetupManager.SetupState.NONE);
                player.sendMessage(Component.text("2. Loot Drop-off Chest successfully set!").color(NamedTextColor.GREEN));
                player.sendMessage(Component.text("🎉 Setup complete! You are ready to start. Use /herepunchy start!").color(NamedTextColor.GREEN));
            }
            return;
        }

        // 2. Check Coordinates Selection
        if (!player.isSneaking()) return;

        ItemStack item = player.getInventory().getItemInMainHand();
        if (!isSword(item.getType())) return;

        // Only allow selection if the player is explicitly in Selection Mode
        if (!selectionManager.isSelectionMode(player.getUniqueId())) {
            return;
        }

        event.setCancelled(true);
        Location clicked = clickedBlock.getLocation();

        if (selectionManager.getPointA(player.getUniqueId()) == null) {
            selectionManager.setPointA(player.getUniqueId(), clicked);
            player.sendMessage(Component.text("Point A set at ")
                    .color(NamedTextColor.GREEN)
                    .append(Component.text(formatLocation(clicked)).color(NamedTextColor.YELLOW))
                    .append(Component.text(". Shift-right-click again with your sword to set Point B.").color(NamedTextColor.GREEN)));
        } else if (selectionManager.getPointB(player.getUniqueId()) == null) {
            selectionManager.setPointB(player.getUniqueId(), clicked);
            selectionManager.setSelectionMode(player.getUniqueId(), false); // Disable selection mode once selection is complete
            player.sendMessage(Component.text("Point B set at ")
                    .color(NamedTextColor.GREEN)
                    .append(Component.text(formatLocation(clicked)).color(NamedTextColor.YELLOW))
                    .append(Component.text(". Selection complete, selection mode disabled! Scanning patrol area...").color(NamedTextColor.GREEN)));

            scanManager.scanAreaAsync(player.getUniqueId(),
                    selectionManager.getPointA(player.getUniqueId()),
                    selectionManager.getPointB(player.getUniqueId()),
                    result -> {
                        player.sendMessage(Component.text("Patrol area mapped: ")
                                .color(NamedTextColor.GREEN)
                                .append(Component.text(result.getPassableCount() + " safe waypoints").color(NamedTextColor.YELLOW))
                                .append(Component.text(", ").color(NamedTextColor.GREEN))
                                .append(Component.text(result.getHazardCount() + " hazards bypassed").color(NamedTextColor.YELLOW))
                                .append(Component.text(", ").color(NamedTextColor.GREEN))
                                .append(Component.text(result.getObstructedCount() + " blocks obstructed").color(NamedTextColor.YELLOW))
                                .append(Component.text(". Ready to execute ").color(NamedTextColor.GREEN))
                                .append(Component.text("/herepunchy start").color(NamedTextColor.YELLOW))
                                .append(Component.text("!").color(NamedTextColor.GREEN)));
                    });
        } else {
            selectionManager.clearSelection(player.getUniqueId());
            selectionManager.setPointA(player.getUniqueId(), clicked);
            player.sendMessage(Component.text("Selection reset. Point A set at ")
                    .color(NamedTextColor.GREEN)
                    .append(Component.text(formatLocation(clicked)).color(NamedTextColor.YELLOW))
                    .append(Component.text(". Shift-right-click again with your sword to set Point B.").color(NamedTextColor.GREEN)));
        }
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        punchTaskManager.stopTask(player);
        scanManager.clearScan(player.getUniqueId());
    }

    private boolean isSword(Material material) {
        return material.name().contains("SWORD");
    }

    private String formatLocation(Location loc) {
        return String.format("(%d, %d, %d)", loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }
}
