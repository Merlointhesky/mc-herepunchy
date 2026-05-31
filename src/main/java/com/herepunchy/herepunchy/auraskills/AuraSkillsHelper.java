package com.herepunchy.herepunchy.auraskills;

import dev.aurelium.auraskills.api.AuraSkillsApi;
import dev.aurelium.auraskills.api.skill.Skills;
import dev.aurelium.auraskills.api.user.SkillsUser;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public class AuraSkillsHelper {

    private boolean auraSkillsAvailable = false;

    public void init() {
        auraSkillsAvailable = Bukkit.getPluginManager().getPlugin("AuraSkills") != null;
    }

    public boolean isAvailable() {
        return auraSkillsAvailable;
    }

    public int getArcheryLevel(Player player) {
        if (!auraSkillsAvailable) return 0;
        try {
            AuraSkillsApi api = AuraSkillsApi.get();
            SkillsUser user = api.getUser(player.getUniqueId());
            if (user != null) {
                return user.getSkillLevel(Skills.ARCHERY);
            }
        } catch (Exception e) {
            // ignore
        }
        return 0;
    }

    public int getFightingLevel(Player player) {
        if (!auraSkillsAvailable) return 0;
        try {
            AuraSkillsApi api = AuraSkillsApi.get();
            SkillsUser user = api.getUser(player.getUniqueId());
            if (user != null) {
                return user.getSkillLevel(Skills.FIGHTING);
            }
        } catch (Exception e) {
            // ignore
        }
        return 0;
    }

    public int getDefenseLevel(Player player) {
        if (!auraSkillsAvailable) return 0;
        try {
            AuraSkillsApi api = AuraSkillsApi.get();
            SkillsUser user = api.getUser(player.getUniqueId());
            if (user != null) {
                return user.getSkillLevel(Skills.DEFENSE);
            }
        } catch (Exception e) {
            // ignore
        }
        return 0;
    }

    public void addArcheryXp(Player player, double baseXp) {
        if (!auraSkillsAvailable) return;
        try {
            AuraSkillsApi api = AuraSkillsApi.get();
            SkillsUser user = api.getUser(player.getUniqueId());
            if (user != null) {
                int level = getArcheryLevel(player);
                double xpAmount = baseXp * (1.0 + level * 0.02);
                user.addSkillXp(Skills.ARCHERY, xpAmount);
            }
        } catch (Exception e) {
            // ignore
        }
    }

    public void addFightingXp(Player player, double baseXp) {
        if (!auraSkillsAvailable) return;
        try {
            AuraSkillsApi api = AuraSkillsApi.get();
            SkillsUser user = api.getUser(player.getUniqueId());
            if (user != null) {
                int level = getFightingLevel(player);
                double xpAmount = baseXp * (1.0 + level * 0.02);
                user.addSkillXp(Skills.FIGHTING, xpAmount);
            }
        } catch (Exception e) {
            // ignore
        }
    }

    public void addDefenseXp(Player player, double baseXp) {
        if (!auraSkillsAvailable) return;
        try {
            AuraSkillsApi api = AuraSkillsApi.get();
            SkillsUser user = api.getUser(player.getUniqueId());
            if (user != null) {
                int level = getDefenseLevel(player);
                double xpAmount = baseXp * (1.0 + level * 0.02);
                user.addSkillXp(Skills.DEFENSE, xpAmount);
            }
        } catch (Exception e) {
            // ignore
        }
    }
}
