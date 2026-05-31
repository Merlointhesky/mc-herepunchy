package com.herepunchy.herepunchy.config;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class PlayerPunchyConfig {

    public enum WeaponType {
        BOW(Material.BOW, "Bow"),
        CROSSBOW(Material.CROSSBOW, "Crossbow"),
        TRIDENT(Material.TRIDENT, "Trident"),
        SWORD(Material.DIAMOND_SWORD, "Sword"),
        AXE(Material.DIAMOND_AXE, "Axe"),
        MACE(Material.MACE, "Mace");

        private final Material displayMaterial;
        private final String displayName;

        WeaponType(Material displayMaterial, String displayName) {
            this.displayMaterial = displayMaterial;
            this.displayName = displayName;
        }

        public Material getDisplayMaterial() {
            return displayMaterial;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    private final UUID playerId;
    private List<WeaponType> weaponPriority = new ArrayList<>();

    public PlayerPunchyConfig(UUID playerId) {
        this.playerId = playerId;
        // Default priority list
        weaponPriority.add(WeaponType.MACE);
        weaponPriority.add(WeaponType.SWORD);
        weaponPriority.add(WeaponType.AXE);
        weaponPriority.add(WeaponType.TRIDENT);
        weaponPriority.add(WeaponType.CROSSBOW);
        weaponPriority.add(WeaponType.BOW);
    }

    public UUID getPlayerId() {
        return playerId;
    }

    public List<WeaponType> getWeaponPriority() {
        return weaponPriority;
    }

    public void setWeaponPriority(List<WeaponType> weaponPriority) {
        this.weaponPriority = new ArrayList<>(weaponPriority);
    }
}
