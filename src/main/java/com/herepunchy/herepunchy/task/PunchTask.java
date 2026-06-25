package com.herepunchy.herepunchy.task;

import com.herepunchy.herepunchy.HerePunchyPlugin;
import com.herepunchy.herepunchy.auraskills.AuraSkillsHelper;
import com.herepunchy.herepunchy.hereroleplay.HereRolePlayHelper;
import com.herepunchy.herepunchy.config.PlayerPunchyConfig;
import com.herepunchy.herepunchy.config.PunchyConfigManager;
import com.herepunchy.herepunchy.map.ScanManager;
import com.herepunchy.herepunchy.map.ScanResult;
import com.herepunchy.herepunchy.path.PathGenerator;
import com.herepunchy.herepunchy.selection.SelectionManager;
import com.herepunchy.herepunchy.setup.SetupConfiguration;
import com.herepunchy.herepunchy.setup.SetupManager;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.Container;
import org.bukkit.entity.*;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.ItemStack;
import java.util.ArrayList;
import org.bukkit.inventory.meta.Damageable;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.util.Vector;

import java.util.*;

public class PunchTask extends BukkitRunnable implements Listener {

    private static final double SPEED = 0.22;
    private static final double SNAP_DISTANCE = 0.35;
    private static final double MAX_DIRECT_STEP_DISTANCE = 2.0;
    private static final int STUCK_TICK_THRESHOLD = 10;
    private static final int NO_DAMAGE_TICK_TIMEOUT = 80; // 8 seconds without taking damage
    private static final int TOTAL_COMBAT_TIMEOUT_TICKS = 160; // 16 seconds total fight time
    private static final long BLACKLIST_DURATION_MS = 10000; // Ignore unreachable entity for 10 seconds

    private final HerePunchyPlugin plugin;
    private final Player player;
    private final List<Location> path;
    private final ScanManager scanManager;
    private final SelectionManager selectionManager;
    private final SetupManager setupManager;
    private final PunchyConfigManager configManager;
    private final AuraSkillsHelper auraSkillsHelper;
    private final HereRolePlayHelper hereRolePlayHelper;
    private ScanResult scanResult;

    private int currentIndex = 0;
    private boolean movingForward = true; // true = traversing 0 -> size-1, false = size-1 -> 0
    private int stuckTicks = 0;
    private Location lastTarget = null;
    private int lastTargetIndex = -1;
    private double lastDist = Double.MAX_VALUE;

    // Combat & Actions Ticks
    private int actionCooldown = 0; // standard action ticks cooldown
    private int bowChargeTicks = 0; // charge ticks for Bow/Crossbow
    private boolean isChargingBow = false;
    private LivingEntity combatTarget = null;

    // Restock & Deposit States
    private enum TaskState {
        FIGHTING,
        WALKING_TO_WEAPONS_CHEST,
        WALKING_TO_LOOT_CHEST
    }
    private TaskState state = TaskState.FIGHTING;

    // Initial Inventory Protection
    private final Set<Material> startingInventoryTypes = new HashSet<>();

    // Auto Defense Warning Flag
    private boolean autoDefenseWarningSent = false;

    // Advanced Inactivity displacement tracking fields
    private Location baselineLocation = null;
    private int baselineTicks = 0;
    private int inactiveTicks = 0;

    // Keep track of blacklisted targets to avoid getting stuck in "mexican standoffs"
    private final Map<UUID, Long> temporaryBlacklistedTargets = new HashMap<>();
    private UUID currentTargetId = null;
    private double lastTargetHealth = 0.0;
    private int targetTicks = 0;
    private int noDamageTicks = 0;

    // Cache of collected items (Material -> Quantity)
    private final Map<Material, Integer> collectedCounts = new HashMap<>();

    public PunchTask(HerePunchyPlugin plugin, Player player, List<Location> path,
                     ScanManager scanManager, SelectionManager selectionManager, ScanResult scanResult,
                     AuraSkillsHelper auraSkillsHelper, HereRolePlayHelper hereRolePlayHelper) {
        this.plugin = plugin;
        this.player = player;
        this.path = path;
        this.scanManager = scanManager;
        this.selectionManager = selectionManager;
        this.setupManager = plugin.getSetupManager();
        this.configManager = plugin.getConfigManager();
        this.scanResult = scanResult;
        this.auraSkillsHelper = auraSkillsHelper;
        this.hereRolePlayHelper = hereRolePlayHelper;

        // Record initial inventory to protect items from being dropped
        for (ItemStack item : player.getInventory().getContents()) {
            if (item != null && item.getType() != Material.AIR) {
                startingInventoryTypes.add(item.getType());
            }
        }
        // Always protect weapons, ammo, shield, and food categories
        startingInventoryTypes.add(Material.SHIELD);
        startingInventoryTypes.add(Material.ARROW);
        startingInventoryTypes.add(Material.SPECTRAL_ARROW);
        startingInventoryTypes.add(Material.TIPPED_ARROW);
        startingInventoryTypes.add(Material.FIREWORK_ROCKET);
        startingInventoryTypes.add(Material.BOW);
        startingInventoryTypes.add(Material.CROSSBOW);
        startingInventoryTypes.add(Material.TRIDENT);
        startingInventoryTypes.add(Material.MACE);
    }

    public int getCurrentIndex() {
        return currentIndex;
    }

    public void setCurrentIndex(int currentIndex) {
        this.currentIndex = currentIndex;
    }

    public void setMovingForward(boolean movingForward) {
        this.movingForward = movingForward;
    }

    @Override
    public void run() {
        if (!player.isOnline()) {
            cancel();
            return;
        }
        if (player.isDead()) {
            return;
        }

        // Force collect nearby loot drops
        collectNearbyDrops();

        Location current = player.getLocation();

        // Advanced Inactivity Detection based on net displacement over 10 ticks (5 runs of run())
        if (baselineLocation == null || baselineLocation.getWorld() != current.getWorld()) {
            baselineLocation = current.clone();
            baselineTicks = 0;
            inactiveTicks = 0;
        } else {
            baselineTicks++;
            if (baselineTicks >= 5) { // 5 runs of run() = 10 ticks
                double netDisplacement = current.distance(baselineLocation);
                
                // If player hasn't made real progress (moved less than 0.5 blocks net over 10 ticks)
                // and we are not in action cooldown and not currently charging/pulling a bow/crossbow
                if (netDisplacement < 0.5 && actionCooldown <= 0 && !isChargingBow) {
                    inactiveTicks += 10;
                } else {
                    inactiveTicks = 0;
                }
                
                baselineLocation = current.clone();
                baselineTicks = 0;
            }
        }

        // Check for inactivity threshold (normally 20 ticks = 2.0 seconds, but 100 ticks = 10.0 seconds when pulling/charging a bow)
        int threshold = isChargingBow ? 100 : 20;
        if (inactiveTicks >= threshold) {
            inactiveTicks = 0;
            stuckTicks = 0;

            if (!path.isEmpty()) {
                advanceCurrentIndex();
                Location nextTarget = path.get(currentIndex);
                teleportToTarget(current, nextTarget);
            }

            triggerRescan();

            // Reset baseline to teleported location
            baselineLocation = player.getLocation();
            baselineTicks = 0;
        }

        // 1. Tick Cooldowns
        if (actionCooldown > 0) {
            actionCooldown--;
            return;
        }

        // 2. Health & Food consumption
        if (handleFoodEating()) {
            actionCooldown = 32; // pause for 1.6s to eat
            return;
        }

        // 3. Combat targeting
        combatTarget = findNearbyTarget();
        if (combatTarget != null) {
            updateCombatTargetTracking(combatTarget);
            
            // Check if we should blacklist the target
            if (shouldBlacklistTarget()) {
                player.sendMessage(Component.text("⚠ Target is unreachable or immune! Temporarily ignoring to resume patrol...").color(NamedTextColor.YELLOW));
                temporaryBlacklistedTargets.put(combatTarget.getUniqueId(), System.currentTimeMillis() + BLACKLIST_DURATION_MS);
                combatTarget = null;
                currentTargetId = null;
                isChargingBow = false;
                bowChargeTicks = 0;
            } else {
                handleCombat(combatTarget);
                return;
            }
        } else {
            currentTargetId = null;
            isChargingBow = false;
            bowChargeTicks = 0;
        }

        // If no combat targets are active, hold shield up in offhand to stay safe
        raiseShield();

        // 4. restock / chest routines if needed
        if (state == TaskState.WALKING_TO_WEAPONS_CHEST) {
            handleWalkToWeaponsChest();
            return;
        } else if (state == TaskState.WALKING_TO_LOOT_CHEST) {
            handleWalkToLootChest();
            return;
        }

        // Check if inventory full or weapons depleted
        if (isInventoryFull()) {
            SetupConfiguration setup = setupManager.getSetupConfig(player.getUniqueId());
            if (setup != null && setup.getLootDropBox() != null) {
                state = TaskState.WALKING_TO_LOOT_CHEST;
                player.sendMessage(Component.text("Inventory is full of loot! Walking to loot chest to deposit...").color(NamedTextColor.YELLOW));
                return;
            } else {
                emptyBagsByDropping();
                if (isInventoryFull()) {
                    plugin.getPunchTaskManager().stopTask(player);
                    player.sendMessage(Component.text("HerePunchy stopped — inventory is full of protected items and cannot be emptied.").color(NamedTextColor.RED));
                    return;
                }
            }
        }
        if (isPreferredWeaponsDepleted()) {
            SetupConfiguration setup = setupManager.getSetupConfig(player.getUniqueId());
            if (setup != null && setup.getBackupWeaponsBox() != null) {
                state = TaskState.WALKING_TO_WEAPONS_CHEST;
                player.sendMessage(Component.text("Preferred combat items depleted! Walking to backup weapons chest...").color(NamedTextColor.YELLOW));
                return;
            }
        }

        // 5. Default traversing traversal
        if (path.isEmpty()) {
            return;
        }

        Location target = path.get(currentIndex);

        if (current.getWorld() != target.getWorld()) {
            cancel();
            player.sendMessage(Component.text("HerePunchy stopped — you left the patrol zone.").color(NamedTextColor.RED));
            return;
        }

        double dx = target.getX() - current.getX();
        double dz = target.getZ() - current.getZ();
        double horizontalDist = Math.sqrt(dx * dx + dz * dz);

        if (currentIndex != lastTargetIndex) {
            lastTargetIndex = currentIndex;
            lastDist = horizontalDist;
            stuckTicks = 0;
        } else {
            if (horizontalDist >= lastDist - 0.02) {
                stuckTicks++;
            } else {
                stuckTicks = 0;
            }
            lastDist = horizontalDist;
        }

        if (horizontalDist > MAX_DIRECT_STEP_DISTANCE || stuckTicks >= STUCK_TICK_THRESHOLD) {
            if (stuckTicks >= STUCK_TICK_THRESHOLD) {
                // Teleport to the next block in the path (bypass stuck block)
                advanceCurrentIndex();
                Location nextTarget = path.get(currentIndex);
                teleportToTarget(current, nextTarget);
                player.sendMessage(Component.text("Bypassed stuck coordinate and continuing to next patrol point...").color(NamedTextColor.YELLOW));
            } else {
                teleportToTarget(current, target);
            }
            stuckTicks = 0;
            return;
        }

        if (horizontalDist < SNAP_DISTANCE) {
            teleportToTarget(current, target);
            advanceCurrentIndex();
        } else {
            // Move toward waypoint
            Vector direction = new Vector(dx, 0, dz).normalize();
            Vector velocity = direction.multiply(SPEED);
            velocity.setY(0);
            player.setVelocity(velocity);

            // Face target waypoint
            Location look = player.getLocation();
            look.setDirection(new Vector(dx, 0, dz).normalize());
            player.teleport(look);
        }
    }

    private void teleportToTarget(Location current, Location target) {
        Location snap = target.clone();
        snap.setY(target.getY() + 1.0);
        snap.setPitch(current.getPitch());
        snap.setYaw(current.getYaw());
        player.teleport(snap);
    }

    private void advanceCurrentIndex() {
        if (movingForward) {
            currentIndex++;
            if (currentIndex >= path.size()) {
                // Reached B: switch direction, walk backward, and re-scan
                currentIndex = path.size() - 1;
                movingForward = false;
                player.sendMessage(Component.text("Reached patrol Point B. Walking backward to Point A...").color(NamedTextColor.GREEN));
                triggerRescan();
            }
        } else {
            currentIndex--;
            if (currentIndex < 0) {
                // Reached A: switch direction, walk forward, and re-scan
                currentIndex = 0;
                movingForward = true;
                player.sendMessage(Component.text("Reached patrol Point A. Walking forward to Point B...").color(NamedTextColor.GREEN));
                triggerRescan();
            }
        }
    }

    private void updateCombatTargetTracking(LivingEntity target) {
        UUID targetId = target.getUniqueId();
        double currentHealth = target.getHealth();

        if (currentTargetId == null || !currentTargetId.equals(targetId)) {
            currentTargetId = targetId;
            lastTargetHealth = currentHealth;
            targetTicks = 0;
            noDamageTicks = 0;
        } else {
            targetTicks += 2; // runs every 2 ticks
            if (currentHealth < lastTargetHealth) {
                lastTargetHealth = currentHealth;
                noDamageTicks = 0;
            } else {
                noDamageTicks += 2;
            }
        }
    }

    private boolean shouldBlacklistTarget() {
        if (currentTargetId == null) return false;
        return (noDamageTicks >= NO_DAMAGE_TICK_TIMEOUT) || (targetTicks >= TOTAL_COMBAT_TIMEOUT_TICKS);
    }

    private void triggerRescan() {
        if (scanManager == null || selectionManager == null || !selectionManager.hasCompleteSelection(player.getUniqueId())) {
            return;
        }
        Location pointA = selectionManager.getPointA(player.getUniqueId());
        Location pointB = selectionManager.getPointB(player.getUniqueId());
        scanManager.scanAreaAsync(player.getUniqueId(), pointA, pointB, result -> {
            scanResult = result;
            List<Location> newPath = PathGenerator.generateSafePath(result);
            if (!newPath.isEmpty()) {
                path.clear();
                path.addAll(newPath);
                // Ensure index is within range
                if (currentIndex >= path.size()) {
                    currentIndex = path.size() - 1;
                }
                if (currentIndex < 0) {
                    currentIndex = 0;
                }
            }
        });
    }

    // ==========================================
    // COMBAT ROUTINES
    // ==========================================

    private LivingEntity findNearbyTarget() {
        double attackRadius = 15.0; // wider range for bows/crossbows!
        Location loc = player.getLocation();
        LivingEntity closest = null;
        double closestDistSq = Double.MAX_VALUE;

        if (loc.getWorld() == null) return null;

        long now = System.currentTimeMillis();

        for (Entity entity : loc.getWorld().getNearbyEntities(loc, attackRadius, 6.0, attackRadius)) {
            if (entity instanceof Monster ||
                entity instanceof Slime ||
                entity instanceof Phantom ||
                entity instanceof Spider) {

                if (entity instanceof LivingEntity living) {
                    if (!living.isDead() && player.hasLineOfSight(living)) {
                        // Check if temporarily blacklisted
                        if (temporaryBlacklistedTargets.containsKey(living.getUniqueId())) {
                            long expiry = temporaryBlacklistedTargets.get(living.getUniqueId());
                            if (now < expiry) {
                                continue; // Ignore this entity
                            } else {
                                temporaryBlacklistedTargets.remove(living.getUniqueId());
                            }
                        }

                        double distSq = loc.distanceSquared(living.getLocation());
                        if (distSq < closestDistSq) {
                            closestDistSq = distSq;
                            closest = living;
                        }
                    }
                }
            }
        }
        return closest;
    }

    private void handleCombat(LivingEntity target) {
        Location currentLoc = player.getLocation();
        double dist = currentLoc.distance(target.getLocation());

        // Dynamic priority selection
        PlayerPunchyConfig.WeaponType bestWeapon = findBestAvailableWeapon(target);

        if (bestWeapon == null) {
            // Auto Defense Mode triggered (bare fists / tools)
            if (!autoDefenseWarningSent) {
                player.sendMessage(Component.text("⚠ WARNING: All preferred weapons and backup ammo depleted! Entering Auto-Defense Mode (fists/tools only).").color(NamedTextColor.RED));
                autoDefenseWarningSent = true;
            }
            int altSlot = findAnyMeleeAlternative();
            if (altSlot != -1) {
                player.getInventory().setHeldItemSlot(altSlot);
            } else {
                player.getInventory().setHeldItemSlot(findEmptySlotOrFirst());
            }
            handleMeleeAttack(target, null);
            return;
        }

        // Weapon is available, reset auto-defense warning
        autoDefenseWarningSent = false;

        // Equip the weapon immediately before moving or shooting!
        int slot = findWeaponInHotbar(bestWeapon);
        if (slot != -1) {
            player.getInventory().setHeldItemSlot(slot);
        }

        // Choose attack method depending on weapon type
        switch (bestWeapon) {
            case BOW, CROSSBOW -> {
                handleRangedAttack(target, bestWeapon);
            }
            case TRIDENT -> {
                if (dist > 4.0 && dist <= 16.0 && !(target instanceof Enderman)) {
                    handleTridentThrow(target);
                } else {
                    handleMeleeAttack(target, bestWeapon);
                }
            }
            default -> handleMeleeAttack(target, bestWeapon);
        }
    }

    private void handleMeleeAttack(LivingEntity target, PlayerPunchyConfig.WeaponType weaponType) {
        // Face target
        faceTarget(target);

        double meleeReach = 4.0;
        if (player.getLocation().distance(target.getLocation()) > meleeReach) {
            // Step closer to the entity
            Vector dir = target.getLocation().toVector().subtract(player.getLocation().toVector()).normalize();
            player.setVelocity(dir.multiply(SPEED * 1.3));
            return;
        }

        // Equip weapon
        int slot = -1;
        if (weaponType != null && weaponType != PlayerPunchyConfig.WeaponType.BOW && weaponType != PlayerPunchyConfig.WeaponType.CROSSBOW) {
            slot = findWeaponInHotbar(weaponType);
        }
        if (slot == -1 && weaponType == null) {
            slot = findAnyMeleeAlternative();
        }

        if (slot != -1) {
            player.getInventory().setHeldItemSlot(slot);
        }

        // Attack swing
        player.attack(target);
        player.playSound(player.getLocation(), Sound.ENTITY_PLAYER_ATTACK_SWEEP, 1.0f, 1.0f);
        actionCooldown = 11; // melee attack tick delay
    }

    private void handleRangedAttack(LivingEntity target, PlayerPunchyConfig.WeaponType weaponType) {
        faceTarget(target);
        player.setVelocity(new Vector(0, 0, 0)); // Stop moving when shooting

        int slot = findWeaponInHotbar(weaponType);
        if (slot != -1) {
            player.getInventory().setHeldItemSlot(slot);
        }

        ItemStack heldItem = player.getInventory().getItemInMainHand();
        int quickChargeLevel = 0;
        if (heldItem != null && heldItem.hasItemMeta()) {
            quickChargeLevel = heldItem.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.QUICK_CHARGE);
            if (quickChargeLevel == 0) {
                org.bukkit.enchantments.Enchantment qcKey = org.bukkit.Registry.ENCHANTMENT.get(org.bukkit.NamespacedKey.minecraft("quick_charge"));
                if (qcKey != null) {
                    quickChargeLevel = heldItem.getEnchantmentLevel(qcKey);
                }
            }
        }

        int maxChargeTicks = 20;
        if (weaponType == PlayerPunchyConfig.WeaponType.CROSSBOW) {
            maxChargeTicks = Math.max(5, 20 - quickChargeLevel * 5);
        }

        if (!isChargingBow) {
            isChargingBow = true;
            bowChargeTicks = 0;
            player.sendMessage(Component.text("🏹 Charging " + weaponType.getDisplayName() + "...").color(NamedTextColor.YELLOW));
            // player.startHandInteraction(EquipmentSlot.HAND);
        }

        bowChargeTicks++;
        if (bowChargeTicks >= maxChargeTicks) {
            // Shoot projectile arrow
            Arrow arrow = player.launchProjectile(Arrow.class);
            Vector toTarget = target.getEyeLocation().toVector().subtract(player.getEyeLocation().toVector()).normalize();

            // Apply projectile damage and properties depending on held weapon meta and enchantments
            int powerLevel = 0;
            int punchLevel = 0;
            int flameLevel = 0;
            int infinityLevel = 0;
            int multishotLevel = 0;
            int piercingLevel = 0;
            if (heldItem != null && heldItem.hasItemMeta()) {
                powerLevel = heldItem.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.POWER);
                if (powerLevel == 0) {
                    org.bukkit.enchantments.Enchantment powerKey = org.bukkit.Registry.ENCHANTMENT.get(org.bukkit.NamespacedKey.minecraft("power"));
                    if (powerKey != null) {
                        powerLevel = heldItem.getEnchantmentLevel(powerKey);
                    }
                }
                punchLevel = heldItem.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.PUNCH);
                if (punchLevel == 0) {
                    org.bukkit.enchantments.Enchantment punchKey = org.bukkit.Registry.ENCHANTMENT.get(org.bukkit.NamespacedKey.minecraft("punch"));
                    if (punchKey != null) {
                        punchLevel = heldItem.getEnchantmentLevel(punchKey);
                    }
                }
                flameLevel = heldItem.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.FLAME);
                if (flameLevel == 0) {
                    org.bukkit.enchantments.Enchantment flameKey = org.bukkit.Registry.ENCHANTMENT.get(org.bukkit.NamespacedKey.minecraft("flame"));
                    if (flameKey != null) {
                        flameLevel = heldItem.getEnchantmentLevel(flameKey);
                    }
                }
                infinityLevel = heldItem.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.INFINITY);
                if (infinityLevel == 0) {
                    org.bukkit.enchantments.Enchantment infinityKey = org.bukkit.Registry.ENCHANTMENT.get(org.bukkit.NamespacedKey.minecraft("infinity"));
                    if (infinityKey != null) {
                        infinityLevel = heldItem.getEnchantmentLevel(infinityKey);
                    }
                }
                multishotLevel = heldItem.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.MULTISHOT);
                if (multishotLevel == 0) {
                    org.bukkit.enchantments.Enchantment msKey = org.bukkit.Registry.ENCHANTMENT.get(org.bukkit.NamespacedKey.minecraft("multishot"));
                    if (msKey != null) {
                        multishotLevel = heldItem.getEnchantmentLevel(msKey);
                    }
                }
                piercingLevel = heldItem.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.PIERCING);
                if (piercingLevel == 0) {
                    org.bukkit.enchantments.Enchantment pierceKey = org.bukkit.Registry.ENCHANTMENT.get(org.bukkit.NamespacedKey.minecraft("piercing"));
                    if (pierceKey != null) {
                        piercingLevel = heldItem.getEnchantmentLevel(pierceKey);
                    }
                }
            }

            double baseDamage = 2.0;
            double velocityMultiplier = 2.2;

            if (weaponType == PlayerPunchyConfig.WeaponType.CROSSBOW) {
                baseDamage = 2.5;
                velocityMultiplier = 3.15; // standard crossbow projectile speed is slightly faster
            } else {
                velocityMultiplier = 3.0; // fully charged bow shot speed is normally 3.0
                if (powerLevel > 0) {
                    baseDamage = 2.0 * (1.0 + 0.25 * (powerLevel + 1));
                }
            }

            boolean hasInfinity = (weaponType == PlayerPunchyConfig.WeaponType.BOW && infinityLevel > 0);
            boolean pickupAllowed = !hasInfinity && (multishotLevel == 0);

            arrow.setVelocity(toTarget.multiply(velocityMultiplier));
            setupArrowProperties(arrow, baseDamage, punchLevel, flameLevel, piercingLevel, pickupAllowed);

            if (weaponType == PlayerPunchyConfig.WeaponType.CROSSBOW && multishotLevel > 0) {
                // Fire left arrow (rotated by +10 degrees around Y axis)
                double leftRad = Math.toRadians(10.0);
                double lx = toTarget.getX() * Math.cos(leftRad) - toTarget.getZ() * Math.sin(leftRad);
                double lz = toTarget.getX() * Math.sin(leftRad) + toTarget.getZ() * Math.cos(leftRad);
                Vector leftDir = new Vector(lx, toTarget.getY(), lz).normalize();
                Arrow arrow2 = player.launchProjectile(Arrow.class);
                arrow2.setVelocity(leftDir.multiply(velocityMultiplier));
                setupArrowProperties(arrow2, baseDamage, punchLevel, flameLevel, piercingLevel, pickupAllowed);

                // Fire right arrow (rotated by -10 degrees around Y axis)
                double rightRad = Math.toRadians(-10.0);
                double rx = toTarget.getX() * Math.cos(rightRad) - toTarget.getZ() * Math.sin(rightRad);
                double rz = toTarget.getX() * Math.sin(rightRad) + toTarget.getZ() * Math.cos(rightRad);
                Vector rightDir = new Vector(rx, toTarget.getY(), rz).normalize();
                Arrow arrow3 = player.launchProjectile(Arrow.class);
                arrow3.setVelocity(rightDir.multiply(velocityMultiplier));
                setupArrowProperties(arrow3, baseDamage, punchLevel, flameLevel, piercingLevel, pickupAllowed);
            }

            // Consume ammo if not protected by Infinity
            if (!hasInfinity) {
                if (weaponType == PlayerPunchyConfig.WeaponType.CROSSBOW && hasItem(Material.FIREWORK_ROCKET) && !hasItem(Material.ARROW)) {
                    removeOneItem(Material.FIREWORK_ROCKET);
                } else {
                    removeOneItem(Material.ARROW);
                }
            }

            player.playSound(player.getLocation(), Sound.ENTITY_ARROW_SHOOT, 1.0f, 1.0f);
            isChargingBow = false;
            bowChargeTicks = 0;
            actionCooldown = 12; // shooting cooldown ticks
        }
    }

    @SuppressWarnings("removal")
    private void setupArrowProperties(Arrow arrow, double baseDamage, int punchLevel, int flameLevel, int piercingLevel, boolean pickupAllowed) {
        arrow.setDamage(baseDamage);
        arrow.setCritical(true);
        arrow.setShooter(player);

        if (!pickupAllowed) {
            try {
                arrow.setPickupStatus(org.bukkit.entity.AbstractArrow.PickupStatus.CREATIVE_ONLY);
            } catch (Throwable t) {
                try {
                    arrow.setPickupStatus(org.bukkit.entity.AbstractArrow.PickupStatus.DISALLOWED);
                } catch (Throwable ignored) {}
            }
        }

        if (punchLevel > 0) {
            arrow.setKnockbackStrength(punchLevel);
        }
        if (flameLevel > 0) {
            arrow.setFireTicks(100);
            try {
                arrow.setVisualFire(true);
            } catch (Throwable ignored) {}
        }
        if (piercingLevel > 0) {
            try {
                arrow.setPierceLevel(piercingLevel);
            } catch (Throwable ignored) {}
        }
    }

    private void handleTridentThrow(LivingEntity target) {
        faceTarget(target);
        player.setVelocity(new Vector(0, 0, 0));

        int slot = findWeaponInHotbar(PlayerPunchyConfig.WeaponType.TRIDENT);
        if (slot != -1) {
            player.getInventory().setHeldItemSlot(slot);
        }

        if (!isChargingBow) {
            isChargingBow = true;
            bowChargeTicks = 0;
            // player.startHandInteraction(EquipmentSlot.HAND);
        }

        bowChargeTicks++;
        if (bowChargeTicks >= 15) { // trident charging is slightly faster
            Trident trident = player.launchProjectile(Trident.class);
            Vector toTarget = target.getEyeLocation().toVector().subtract(player.getEyeLocation().toVector()).normalize();
            trident.setVelocity(toTarget.multiply(1.8));
            trident.setShooter(player);
            try {
                trident.setPickupStatus(org.bukkit.entity.AbstractArrow.PickupStatus.DISALLOWED);
            } catch (Throwable ignored) {}

            // Apply Impaling and Channeling enchantments manually
            ItemStack heldItem = player.getInventory().getItemInMainHand();
            int impalingLevel = 0;
            int channelingLevel = 0;
            if (heldItem != null && heldItem.hasItemMeta()) {
                impalingLevel = heldItem.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.IMPALING);
                if (impalingLevel == 0) {
                    org.bukkit.enchantments.Enchantment impKey = org.bukkit.Registry.ENCHANTMENT.get(org.bukkit.NamespacedKey.minecraft("impaling"));
                    if (impKey != null) {
                        impalingLevel = heldItem.getEnchantmentLevel(impKey);
                    }
                }
                channelingLevel = heldItem.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.CHANNELING);
                if (channelingLevel == 0) {
                    org.bukkit.enchantments.Enchantment chanKey = org.bukkit.Registry.ENCHANTMENT.get(org.bukkit.NamespacedKey.minecraft("channeling"));
                    if (chanKey != null) {
                        channelingLevel = heldItem.getEnchantmentLevel(chanKey);
                    }
                }
            }

            double baseDamage = 8.0; // Thrown trident base damage in Minecraft is 8.0
            if (impalingLevel > 0) {
                // Impaling adds 1.25 damage per level to water mobs
                if (target instanceof Drowned || target instanceof Guardian || target instanceof WaterMob || target instanceof Dolphin) {
                    baseDamage += impalingLevel * 1.25;
                }
            }
            trident.setDamage(baseDamage);

            // Channeling: strikes target with lightning in storms if exposed to the sky
            if (channelingLevel > 0 && player.getWorld().isThundering()) {
                Location targetLoc = target.getLocation();
                if (targetLoc.getY() >= target.getWorld().getHighestBlockYAt(targetLoc)) {
                    target.getWorld().strikeLightning(targetLoc);
                }
            }

            // durabilities damage to original trident (loyalty simulated)
            damageHeldItem();

            player.playSound(player.getLocation(), Sound.ITEM_TRIDENT_THROW, 1.0f, 1.0f);
            isChargingBow = false;
            bowChargeTicks = 0;
            actionCooldown = 16;
        }
    }

    private PlayerPunchyConfig.WeaponType findBestAvailableWeapon(LivingEntity target) {
        PlayerPunchyConfig config = configManager.getPlayerConfig(player.getUniqueId());
        boolean isEnderman = target instanceof Enderman;
        for (PlayerPunchyConfig.WeaponType weapon : config.getWeaponPriority()) {
            if (isEnderman && (weapon == PlayerPunchyConfig.WeaponType.BOW || weapon == PlayerPunchyConfig.WeaponType.CROSSBOW)) {
                continue; // Endermen are immune to projectiles
            }
            if (isWeaponAvailable(weapon)) {
                return weapon;
            }
        }
        return null;
    }

    private PlayerPunchyConfig.WeaponType findBestAvailableMeleeWeapon() {
        PlayerPunchyConfig config = configManager.getPlayerConfig(player.getUniqueId());
        for (PlayerPunchyConfig.WeaponType weapon : config.getWeaponPriority()) {
            if (weapon != PlayerPunchyConfig.WeaponType.BOW && weapon != PlayerPunchyConfig.WeaponType.CROSSBOW) {
                if (isWeaponAvailable(weapon)) {
                    return weapon;
                }
            }
        }
        return null;
    }

    private int findEmptySlotOrFirst() {
        for (int i = 0; i < 9; i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (item == null || item.getType() == Material.AIR) {
                return i;
            }
        }
        return 0;
    }

    private boolean isWeaponAvailable(PlayerPunchyConfig.WeaponType type) {
        int slot = findWeaponInHotbar(type);
        if (slot == -1) return false;

        ItemStack item = player.getInventory().getItem(slot);
        if (item == null) return false;

        // Durability check (safety switch under 5 points)
        if (item.getItemMeta() instanceof Damageable dmg) {
            if (item.getType().getMaxDurability() - dmg.getDamage() < 5) {
                return false;
            }
        }

        // Ranged ammo verification
        if (type == PlayerPunchyConfig.WeaponType.BOW) {
            return hasItem(Material.ARROW);
        }
        if (type == PlayerPunchyConfig.WeaponType.CROSSBOW) {
            return hasItem(Material.ARROW) || hasItem(Material.FIREWORK_ROCKET);
        }

        return true;
    }

    private int findWeaponInHotbar(PlayerPunchyConfig.WeaponType type) {
        for (int i = 0; i < 9; i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (item == null || item.getAmount() == 0) continue;

            Material mat = item.getType();
            switch (type) {
                case BOW -> {
                    if (mat == Material.BOW) return i;
                }
                case CROSSBOW -> {
                    if (mat == Material.CROSSBOW) return i;
                }
                case TRIDENT -> {
                    if (mat == Material.TRIDENT) return i;
                }
                case MACE -> {
                    if (mat == Material.MACE) return i;
                }
                case SWORD -> {
                    if (mat.name().contains("SWORD")) return i;
                }
                case AXE -> {
                    if (mat.name().contains("AXE") && !mat.name().contains("PICKAXE")) return i;
                }
            }
        }
        return -1;
    }

    private int findAnyMeleeAlternative() {
        for (int i = 0; i < 9; i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (item == null || item.getAmount() == 0) continue;
            Material mat = item.getType();
            if (mat.name().contains("SWORD") || mat.name().contains("AXE") || mat.name().contains("PICKAXE") || mat.name().contains("SHOVEL") || mat == Material.TRIDENT || mat == Material.MACE) {
                return i;
            }
        }
        return -1;
    }

    private void faceTarget(LivingEntity target) {
        Location targetEye = target.getEyeLocation();
        Location playerEye = player.getEyeLocation();
        Vector dir = targetEye.toVector().subtract(playerEye.toVector()).normalize();

        Location look = player.getLocation();
        look.setDirection(dir);
        player.teleport(look);
    }

    private void damageHeldItem() {
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getItemMeta() instanceof Damageable dmg) {
            dmg.setDamage(dmg.getDamage() + 1);
            item.setItemMeta(dmg);
            if (dmg.getDamage() >= item.getType().getMaxDurability()) {
                player.getInventory().setItemInMainHand(null);
                player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
            }
        }
    }

    // ==========================================
    // SHIELD ACTIVE DEFENSE
    // ==========================================

    private void raiseShield() {
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (offhand != null && offhand.getType() == Material.SHIELD) {
            // player.startHandInteraction(EquipmentSlot.OFF_HAND);
        }
    }

    @EventHandler
    public void onPlayerDamage(EntityDamageByEntityEvent event) {
        if (!event.getEntity().getUniqueId().equals(player.getUniqueId())) return;

        // Front blocking shield damage absorber
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (offhand != null && offhand.getType() == Material.SHIELD) {
            Entity attacker = event.getDamager();
            Vector toAttacker = attacker.getLocation().toVector().subtract(player.getLocation().toVector()).normalize();
            Vector playerFacing = player.getLocation().getDirection().normalize();

            // Calculate angle between player facing direction and attacker location
            double dot = toAttacker.dot(playerFacing);
            if (dot > 0.5) { // less than ~60 degree frontal cone angle
                event.setCancelled(true);
                // Damage shield durability
                if (offhand.getItemMeta() instanceof Damageable dmg) {
                    dmg.setDamage(dmg.getDamage() + (int) event.getDamage());
                    offhand.setItemMeta(dmg);
                    if (dmg.getDamage() >= offhand.getType().getMaxDurability()) {
                        player.getInventory().setItemInOffHand(null);
                        player.playSound(player.getLocation(), Sound.ENTITY_ITEM_BREAK, 1.0f, 1.0f);
                    }
                }
                player.playSound(player.getLocation(), Sound.ITEM_SHIELD_BLOCK, 1.0f, 1.0f);
                player.sendMessage(Component.text("🛡 Auto-Defense: Blocked frontal attack!").color(NamedTextColor.GREEN));
                if (auraSkillsHelper != null && auraSkillsHelper.isAvailable()) {
                    auraSkillsHelper.addDefenseXp(player, 15.0);
                }
            }
        }
    }

    @EventHandler
    public void onPlayerTakeDamage(EntityDamageEvent event) {
        if (!event.getEntity().getUniqueId().equals(player.getUniqueId())) return;
        if (event.isCancelled()) return;
        if (event instanceof EntityDamageByEntityEvent) {
            if (auraSkillsHelper != null && auraSkillsHelper.isAvailable()) {
                auraSkillsHelper.addDefenseXp(player, 5.0);
            }
        }
    }

    @EventHandler
    public void onEntityDeath(org.bukkit.event.entity.EntityDeathEvent event) {
        LivingEntity dead = event.getEntity();
        if (dead.getKiller() != null && dead.getKiller().getUniqueId().equals(player.getUniqueId())) {
            if (dead instanceof Monster || dead instanceof Slime || dead instanceof Phantom || dead instanceof Spider) {
                ItemStack mainHand = player.getInventory().getItemInMainHand();
                boolean isBow = (mainHand != null && (mainHand.getType() == Material.BOW || mainHand.getType() == Material.CROSSBOW));
                if (auraSkillsHelper != null && auraSkillsHelper.isAvailable()) {
                    if (isBow) {
                        auraSkillsHelper.addArcheryXp(player, 25.0);
                    } else {
                        auraSkillsHelper.addFightingXp(player, 18.0);
                    }
                }
            }
        }
    }

    // ==========================================
    // HUNGER & HEALTH FOOD CONSUMING
    // ==========================================

    private boolean handleFoodEating() {
        if (player.getFoodLevel() > 14 && player.getHealth() > 14.0) {
            return false;
        }

        // Scan inventory for food items
        int foodSlot = findFoodItemSlot();
        if (foodSlot == -1) return false;

        // If food is in the main inventory (9-35), swap it into hotbar slot 8 to raise it securely
        if (foodSlot >= 9) {
            ItemStack foodItem = player.getInventory().getItem(foodSlot);
            ItemStack hotbarItem = player.getInventory().getItem(8);
            player.getInventory().setItem(8, foodItem != null ? foodItem.clone() : null);
            player.getInventory().setItem(foodSlot, hotbarItem);
            foodSlot = 8;
        }

        ItemStack food = player.getInventory().getItem(foodSlot);
        if (food == null) return false;

        // Consume food instantly with sound
        player.getInventory().setHeldItemSlot(foodSlot);
        player.playSound(player.getLocation(), Sound.ENTITY_GENERIC_EAT, 1.0f, 1.0f);

        // Feed player saturation/hunger depending on food item details
        int hungerRestore = 6;
        float saturationRestore = 8.0f;

        switch (food.getType()) {
            case COOKED_BEEF, COOKED_PORKCHOP, PUMPKIN_PIE -> {
                hungerRestore = 8;
                saturationRestore = 12.8f;
            }
            case GOLDEN_CARROT -> {
                hungerRestore = 6;
                saturationRestore = 14.4f;
            }
            case COOKED_MUTTON, COOKED_SALMON -> {
                hungerRestore = 6;
                saturationRestore = 9.6f;
            }
            case COOKED_CHICKEN -> {
                hungerRestore = 6;
                saturationRestore = 7.2f;
            }
            case COOKED_COD, BAKED_POTATO, BREAD -> {
                hungerRestore = 5;
                saturationRestore = 6.0f;
            }
            case GOLDEN_APPLE -> {
                hungerRestore = 4;
                saturationRestore = 9.6f;
                player.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.REGENERATION, 100, 1));
                player.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.ABSORPTION, 2400, 0));
            }
            case ENCHANTED_GOLDEN_APPLE -> {
                hungerRestore = 4;
                saturationRestore = 9.6f;
                player.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.REGENERATION, 400, 1)); // Regen II (20s)
                player.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.ABSORPTION, 2400, 3)); // Absorption IV (2m)
                player.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.RESISTANCE, 6000, 0)); // Resistance I (5m)
                player.addPotionEffect(new org.bukkit.potion.PotionEffect(org.bukkit.potion.PotionEffectType.FIRE_RESISTANCE, 6000, 0)); // Fire Resistance I (5m)
            }
            case APPLE, CARROT -> {
                hungerRestore = 4;
                saturationRestore = 2.4f;
            }
            case COOKED_RABBIT -> {
                hungerRestore = 5;
                saturationRestore = 6.0f;
            }
            case MELON_SLICE, COOKIE, SWEET_BERRIES, GLOW_BERRIES -> {
                hungerRestore = 2;
                saturationRestore = 1.2f;
            }
            default -> {}
        }

        player.setFoodLevel(Math.min(20, player.getFoodLevel() + hungerRestore));
        player.setSaturation(Math.min(20f, player.getSaturation() + saturationRestore));
        if (player.getHealth() < player.getMaxHealth()) {
            player.setHealth(Math.min(player.getMaxHealth(), player.getHealth() + 3.0));
        }

        // Consume one food item
        removeOneItem(food.getType());

        player.sendMessage(Component.text("🍎 Health/hunger low. Ate " + food.getType().name().toLowerCase().replace("_", " ") + "!").color(NamedTextColor.GREEN));
        return true;
    }

    private int findFoodItemSlot() {
        for (int i = 0; i < 36; i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (item == null || item.getAmount() == 0) continue;
            if (item.getType().isEdible()) {
                return i;
            }
        }
        return -1;
    }

    // ==========================================
    // WEAPONS CHEST RESTOCK & DEPOSIT
    // ==========================================

    private void handleWalkToWeaponsChest() {
        Location chestLoc = setupManager.getSetupConfig(player.getUniqueId()).getBackupWeaponsBox();
        if (chestLoc == null) {
            state = TaskState.FIGHTING;
            return;
        }

        double chestDist = player.getLocation().distance(chestLoc);
        if (chestDist > 1.5) {
            // Walk towards the chest
            Vector dir = chestLoc.toVector().subtract(player.getLocation().toVector()).normalize();
            player.setVelocity(dir.multiply(SPEED * 1.2));
            faceLocation(chestLoc);
        } else {
            // Arrived at the chest! Restock inventory
            restockBackupWeapons(chestLoc);
            state = TaskState.FIGHTING;
            player.sendMessage(Component.text("Successfully restocked combat gears from chest. Resuming patrol!").color(NamedTextColor.GREEN));
        }
    }

    private void handleWalkToLootChest() {
        Location chestLoc = setupManager.getSetupConfig(player.getUniqueId()).getLootDropBox();
        if (chestLoc == null) {
            state = TaskState.FIGHTING;
            return;
        }

        double chestDist = player.getLocation().distance(chestLoc);
        if (chestDist > 1.5) {
            Vector dir = chestLoc.toVector().subtract(player.getLocation().toVector()).normalize();
            player.setVelocity(dir.multiply(SPEED * 1.2));
            faceLocation(chestLoc);
        } else {
            // Arrived at chest! Deposit collected loot items
            depositLootItems(chestLoc);
            state = TaskState.FIGHTING;
            player.sendMessage(Component.text("Successfully deposited collected mob loot to chest. Resuming patrol!").color(NamedTextColor.GREEN));
        }
    }

    private void restockBackupWeapons(Location chestLoc) {
        Block block = chestLoc.getBlock();
        if (!(block.getState() instanceof Container container)) return;

        // Try to retrieve bows, swords, axes, shield, arrows, food
        for (int i = 0; i < container.getInventory().getSize(); i++) {
            ItemStack item = container.getInventory().getItem(i);
            if (item == null || item.getAmount() == 0) continue;

            Material mat = item.getType();
            if (mat == Material.ARROW || mat.name().contains("SWORD") || mat.name().contains("AXE") || mat == Material.BOW || mat == Material.CROSSBOW || mat == Material.MACE || mat == Material.TRIDENT || mat == Material.SHIELD || mat.isEdible()) {
                // Add item to player inventory
                Map<Integer, ItemStack> remaining = player.getInventory().addItem(item.clone());
                if (remaining.isEmpty()) {
                    container.getInventory().setItem(i, null);
                } else {
                    ItemStack rem = remaining.values().iterator().next();
                    item.setAmount(rem.getAmount());
                    container.getInventory().setItem(i, item);
                    break; // Inventory is full, stop restocking
                }
            }
        }
        player.updateInventory();
    }

    private void depositLootItems(Location chestLoc) {
        Block block = chestLoc.getBlock();
        if (!(block.getState() instanceof Container container)) return;

        // 1. Deposit according to collectedCounts
        for (Map.Entry<Material, Integer> entry : new HashMap<>(collectedCounts).entrySet()) {
            Material material = entry.getKey();
            int toDeposit = entry.getValue();
            if (toDeposit <= 0) continue;

            for (int i = 0; i < player.getInventory().getSize(); i++) {
                ItemStack item = player.getInventory().getItem(i);
                if (item == null || item.getType() != material) continue;

                int countInStack = item.getAmount();
                int depositAmount = Math.min(toDeposit, countInStack);

                ItemStack toMove = item.clone();
                toMove.setAmount(depositAmount);

                Map<Integer, ItemStack> remaining = container.getInventory().addItem(toMove);
                int deposited = depositAmount;
                if (!remaining.isEmpty()) {
                    ItemStack rem = remaining.values().iterator().next();
                    deposited = depositAmount - rem.getAmount();
                }

                if (deposited > 0) {
                    if (deposited == countInStack) {
                        player.getInventory().setItem(i, null);
                    } else {
                        item.setAmount(countInStack - deposited);
                        player.getInventory().setItem(i, item);
                    }
                    toDeposit -= deposited;
                    collectedCounts.put(material, Math.max(0, collectedCounts.get(material) - deposited));
                }

                if (toDeposit <= 0) break;
            }
        }

        // 2. Deposit any remaining items that are NOT in startingInventoryTypes
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (item == null || item.getAmount() == 0) continue;

            if (!startingInventoryTypes.contains(item.getType())) {
                ItemStack toMove = item.clone();
                Map<Integer, ItemStack> remaining = container.getInventory().addItem(toMove);
                if (remaining.isEmpty()) {
                    player.getInventory().setItem(i, null);
                } else {
                    ItemStack rem = remaining.values().iterator().next();
                    item.setAmount(rem.getAmount());
                    player.getInventory().setItem(i, item);
                }
            }
        }

        collectedCounts.clear();
        player.updateInventory();
    }

    private void emptyBagsByDropping() {
        player.sendMessage(Component.text("Inventory is full! Dropping collected loot on the ground to free up space...").color(NamedTextColor.YELLOW));

        // 1. Drop according to collectedCounts (excluding startingInventoryTypes to be absolutely safe)
        for (Map.Entry<Material, Integer> entry : new HashMap<>(collectedCounts).entrySet()) {
            Material material = entry.getKey();
            if (startingInventoryTypes.contains(material)) {
                continue; // Never drop protected/starting inventory types
            }
            int toDrop = entry.getValue();
            if (toDrop <= 0) continue;

            for (int i = 0; i < player.getInventory().getSize(); i++) {
                ItemStack item = player.getInventory().getItem(i);
                if (item == null || item.getType() != material) continue;

                int countInStack = item.getAmount();
                int dropAmount = Math.min(toDrop, countInStack);

                ItemStack dropStack = item.clone();
                dropStack.setAmount(dropAmount);
                Item droppedItem = player.getWorld().dropItemNaturally(player.getLocation(), dropStack);
                droppedItem.setPickupDelay(80); // 4 seconds delay

                if (dropAmount == countInStack) {
                    player.getInventory().setItem(i, null);
                } else {
                    item.setAmount(countInStack - dropAmount);
                    player.getInventory().setItem(i, item);
                }

                toDrop -= dropAmount;
                collectedCounts.put(material, Math.max(0, collectedCounts.get(material) - dropAmount));

                if (toDrop <= 0) break;
            }
        }

        // 2. Fallback: Drop any remaining items that are NOT in startingInventoryTypes
        for (int i = 0; i < player.getInventory().getSize(); i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (item == null || item.getAmount() == 0) continue;

            if (!startingInventoryTypes.contains(item.getType())) {
                ItemStack dropStack = item.clone();
                Item droppedItem = player.getWorld().dropItemNaturally(player.getLocation(), dropStack);
                droppedItem.setPickupDelay(80); // 4 seconds delay
                player.getInventory().setItem(i, null);
            }
        }

        collectedCounts.clear();
        player.updateInventory();
    }

    private void collectNearbyDrops() {
        double collectRadius = 4.0;
        if (player.getWorld() == null) return;
        for (Entity entity : player.getNearbyEntities(collectRadius, 3.0, collectRadius)) {
            if (entity instanceof Item item && !item.isDead()) {
                ItemStack stack = item.getItemStack();
                int originalAmount = stack.getAmount();

                // Try to add to player's inventory
                Map<Integer, ItemStack> remaining = player.getInventory().addItem(stack);
                int addedAmount = originalAmount;
                if (!remaining.isEmpty()) {
                    ItemStack remStack = remaining.values().iterator().next();
                    addedAmount = originalAmount - remStack.getAmount();
                    if (addedAmount > 0) {
                        stack.setAmount(remStack.getAmount());
                        item.setItemStack(stack);
                    }
                } else {
                    item.remove();
                }

                if (addedAmount > 0) {
                    player.playSound(player.getLocation(), Sound.ENTITY_ITEM_PICKUP, 0.5f, 1.5f);
                    Material material = stack.getType();
                    collectedCounts.put(material, collectedCounts.getOrDefault(material, 0) + addedAmount);
                }
            } else if (entity instanceof ExperienceOrb orb && !orb.isDead()) {
                int xp = orb.getExperience();
                int leftoverXp = applySharedMendingRepair(xp);
                if (leftoverXp > 0) {
                    player.giveExp(leftoverXp);
                }
                player.playSound(player.getLocation(), Sound.ENTITY_EXPERIENCE_ORB_PICKUP, 0.4f, 1.2f);
                orb.remove();
            }
        }
    }

    private int applySharedMendingRepair(int xp) {
        if (xp <= 0) return 0;
        int remainingXp = xp;
        while (remainingXp > 0) {
            List<ItemStack> eligible = new ArrayList<>();
            ItemStack mainHand = player.getInventory().getItemInMainHand();
            if (isMendableAndDamaged(mainHand)) eligible.add(mainHand);
            ItemStack offHand = player.getInventory().getItemInOffHand();
            if (isMendableAndDamaged(offHand)) eligible.add(offHand);
            ItemStack helmet = player.getInventory().getHelmet();
            if (isMendableAndDamaged(helmet)) eligible.add(helmet);
            ItemStack chest = player.getInventory().getChestplate();
            if (isMendableAndDamaged(chest)) eligible.add(chest);
            ItemStack leggings = player.getInventory().getLeggings();
            if (isMendableAndDamaged(leggings)) eligible.add(leggings);
            ItemStack boots = player.getInventory().getBoots();
            if (isMendableAndDamaged(boots)) eligible.add(boots);

            if (eligible.isEmpty()) {
                break;
            }

            // Pick a random eligible item to repair
            ItemStack toRepair = eligible.get(new java.util.Random().nextInt(eligible.size()));
            if (toRepair.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable dmg) {
                int damage = dmg.getDamage();
                int xpToUse = Math.min(remainingXp, (int) Math.ceil(damage / 2.0));
                int repairAmount = Math.min(damage, xpToUse * 2);
                
                dmg.setDamage(damage - repairAmount);
                toRepair.setItemMeta(dmg);
                player.updateInventory();
                remainingXp -= xpToUse;
            } else {
                break;
            }
        }
        return remainingXp;
    }

    private boolean isMendableAndDamaged(ItemStack item) {
        if (item == null || item.getAmount() <= 0) return false;
        if (item.getEnchantmentLevel(org.bukkit.enchantments.Enchantment.MENDING) <= 0) {
            org.bukkit.enchantments.Enchantment mending = org.bukkit.Registry.ENCHANTMENT.get(org.bukkit.NamespacedKey.minecraft("mending"));
            if (mending == null || item.getEnchantmentLevel(mending) <= 0) {
                return false;
            }
        }
        if (item.getItemMeta() instanceof org.bukkit.inventory.meta.Damageable dmg) {
            return dmg.getDamage() > 0;
        }
        return false;
    }

    @EventHandler
    public void onPlayerPickupItem(EntityPickupItemEvent event) {
        if (event.getEntity().getUniqueId().equals(player.getUniqueId())) {
            ItemStack itemStack = event.getItem().getItemStack();
            Material material = itemStack.getType();
            int amount = itemStack.getAmount();
            collectedCounts.put(material, collectedCounts.getOrDefault(material, 0) + amount);
        }
    }

    private void faceLocation(Location target) {
        Vector dir = target.toVector().subtract(player.getLocation().toVector()).normalize();
        Location look = player.getLocation();
        look.setDirection(dir);
        player.teleport(look);
    }

    // ==========================================
    // UTILITY CHECKS
    // ==========================================

    private boolean isInventoryFull() {
        return player.getInventory().firstEmpty() == -1;
    }

    private boolean isPreferredWeaponsDepleted() {
        PlayerPunchyConfig config = configManager.getPlayerConfig(player.getUniqueId());
        for (PlayerPunchyConfig.WeaponType weapon : config.getWeaponPriority()) {
            if (isWeaponAvailable(weapon)) {
                return false;
            }
        }
        return true;
    }

    private boolean hasItem(Material material) {
        return player.getInventory().contains(material) || player.getInventory().getItemInOffHand().getType() == material;
    }

    private void removeOneItem(Material material) {
        if (player.getGameMode() == org.bukkit.GameMode.CREATIVE) return;

        // Check offhand first
        ItemStack offhand = player.getInventory().getItemInOffHand();
        if (offhand != null && offhand.getType() == material && offhand.getAmount() > 0) {
            offhand.setAmount(offhand.getAmount() - 1);
            player.getInventory().setItemInOffHand(offhand.getAmount() <= 0 ? null : offhand);
            player.updateInventory();
            return;
        }

        // Check main inventory
        for (int i = 0; i < 36; i++) {
            ItemStack item = player.getInventory().getItem(i);
            if (item != null && item.getType() == material && item.getAmount() > 0) {
                item.setAmount(item.getAmount() - 1);
                player.getInventory().setItem(i, item.getAmount() <= 0 ? null : item);
                player.updateInventory();
                return;
            }
        }
    }
}
