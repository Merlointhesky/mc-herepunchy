package com.herepunchy.herepunchy.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;

import java.util.List;

public class PunchyConfigListener implements Listener {

    private final PunchyConfigUI configUI;
    private final PunchyConfigManager configManager;

    public PunchyConfigListener(PunchyConfigUI configUI, PunchyConfigManager configManager) {
        this.configUI = configUI;
        this.configManager = configManager;
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) return;

        Component titleComponent = event.getView().title();
        String titleStr = PlainTextComponentSerializer.plainText().serialize(titleComponent);
        if (!titleStr.contains("HerePunchy")) {
            return;
        }

        event.setCancelled(true);

        ItemStack clicked = event.getCurrentItem();
        if (clicked == null || clicked.getType() == Material.AIR) return;

        if (titleStr.contains("Weapon Priority")) {
            handlePriorityMenuClick(player, clicked, event.getSlot(), event.isLeftClick());
        }
    }

    private void handlePriorityMenuClick(Player player, ItemStack clicked, int slot, boolean isLeftClick) {
        if (clicked.getType() == Material.ARROW || slot == 26) {
            player.closeInventory();
            return;
        }

        int priorityIndex = slot - 10;
        PlayerPunchyConfig config = configManager.getPlayerConfig(player.getUniqueId());
        List<PlayerPunchyConfig.WeaponType> priority = config.getWeaponPriority();

        if (priorityIndex >= 0 && priorityIndex < priority.size()) {
            if (isLeftClick) {
                // Move up (toward front, index decreases)
                if (priorityIndex > 0) {
                    PlayerPunchyConfig.WeaponType temp = priority.get(priorityIndex);
                    priority.set(priorityIndex, priority.get(priorityIndex - 1));
                    priority.set(priorityIndex - 1, temp);
                }
            } else {
                // Move down (toward back, index increases)
                if (priorityIndex < priority.size() - 1) {
                    PlayerPunchyConfig.WeaponType temp = priority.get(priorityIndex);
                    priority.set(priorityIndex, priority.get(priorityIndex + 1));
                    priority.set(priorityIndex + 1, temp);
                }
            }
            config.setWeaponPriority(priority);
            configManager.saveConfiguration(player.getUniqueId());
            configUI.openPriorityMenu(player);
        }
    }

    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        Component titleComponent = event.getView().title();
        String titleStr = PlainTextComponentSerializer.plainText().serialize(titleComponent);
        if (titleStr.contains("HerePunchy")) {
            event.setCancelled(true);
        }
    }
}
