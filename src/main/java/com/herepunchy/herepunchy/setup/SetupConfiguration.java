package com.herepunchy.herepunchy.setup;

import org.bukkit.Location;

public class SetupConfiguration {
    private Location backupWeaponsBox;
    private Location lootDropBox;

    public Location getBackupWeaponsBox() {
        return backupWeaponsBox;
    }

    public void setBackupWeaponsBox(Location backupWeaponsBox) {
        this.backupWeaponsBox = backupWeaponsBox;
    }

    public Location getLootDropBox() {
        return lootDropBox;
    }

    public void setLootDropBox(Location lootDropBox) {
        this.lootDropBox = lootDropBox;
    }

    public boolean isComplete() {
        return backupWeaponsBox != null && lootDropBox != null;
    }
}
