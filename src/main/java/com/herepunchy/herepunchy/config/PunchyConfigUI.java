package com.herepunchy.herepunchy.config;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

public class PunchyConfigUI {

    private final PunchyConfigManager configManager;
    public static final String PRIORITY_MENU_TITLE = "HerePunchy - Weapon Priority";

    public PunchyConfigUI(PunchyConfigManager configManager) {
        this.configManager = configManager;
    }

    public void openPriorityMenu(Player player) {
        Inventory inventory = Bukkit.createInventory(null, 27, Component.text(PRIORITY_MENU_TITLE)
                .color(NamedTextColor.GOLD));

        PlayerPunchyConfig config = configManager.getPlayerConfig(player.getUniqueId());
        List<PlayerPunchyConfig.WeaponType> priority = config.getWeaponPriority();

        // Populate slots 10 to 15 with weapons in prioritized order
        for (int i = 0; i < priority.size(); i++) {
            PlayerPunchyConfig.WeaponType weapon = priority.get(i);
            ItemStack item = new ItemStack(weapon.getDisplayMaterial());
            ItemMeta meta = item.getItemMeta();
            if (meta != null) {
                meta.displayName(Component.text((i + 1) + ". " + weapon.getDisplayName())
                        .color(NamedTextColor.YELLOW)
                        .decorate(TextDecoration.BOLD));

                List<Component> lore = new ArrayList<>();
                lore.add(Component.text("Left-click to move UP").color(NamedTextColor.GRAY));
                lore.add(Component.text("Right-click to move DOWN").color(NamedTextColor.GRAY));

                meta.lore(lore);
                item.setItemMeta(meta);
            }
            inventory.setItem(10 + i, item);
        }

        // Close button (Slot 26)
        ItemStack closeItem = new ItemStack(Material.ARROW);
        ItemMeta closeMeta = closeItem.getItemMeta();
        if (closeMeta != null) {
            closeMeta.displayName(Component.text("Close Menu").color(NamedTextColor.RED));
            closeItem.setItemMeta(closeMeta);
        }
        inventory.setItem(26, closeItem);

        player.openInventory(inventory);
    }
}
