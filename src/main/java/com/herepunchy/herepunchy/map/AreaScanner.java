package com.herepunchy.herepunchy.map;

import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.HashMap;
import java.util.Map;

public class AreaScanner {

    public static ScanResult scan(Location pointA, Location pointB) {
        World world = pointA.getWorld();
        int minX = Math.min(pointA.getBlockX(), pointB.getBlockX());
        int maxX = Math.max(pointA.getBlockX(), pointB.getBlockX());
        int minZ = Math.min(pointA.getBlockZ(), pointB.getBlockZ());
        int maxZ = Math.max(pointA.getBlockZ(), pointB.getBlockZ());
        int baseY = pointA.getBlockY();

        Map<String, BlockClassification> classifications = new HashMap<>();
        Map<String, Integer> groundYLevels = new HashMap<>();

        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                var result = classifyColumn(world, x, baseY, z);
                classifications.put(x + "," + z, result.classification());
                if (result.groundY() != null) {
                    groundYLevels.put(x + "," + z, result.groundY());
                }
            }
        }

        return new ScanResult(pointA, pointB, classifications, groundYLevels);
    }

    private static ColumnResult classifyColumn(World world, int x, int baseY, int z) {
        Block ground = findGroundBlock(world, x, baseY, z);
        if (ground == null) {
            return new ColumnResult(BlockClassification.OBSTRUCTED, null);
        }

        Material groundType = ground.getType();

        // Headroom check: need at least 2 air/passable blocks above ground Y
        Block above1 = world.getBlockAt(x, ground.getY() + 1, z);
        Block above2 = world.getBlockAt(x, ground.getY() + 2, z);
        if (!isAirOrPassable(above1.getType()) || !isAirOrPassable(above2.getType())) {
            return new ColumnResult(BlockClassification.OBSTRUCTED, null);
        }

        // Unsafe ground check
        if (isUnsafeOrHazard(groundType) || isUnsafeOrHazard(above1.getType()) || isUnsafeOrHazard(above2.getType())) {
            return new ColumnResult(BlockClassification.HAZARD, null);
        }

        if (isWalkableGround(groundType)) {
            return new ColumnResult(BlockClassification.PASSABLE, ground.getY());
        }

        return new ColumnResult(BlockClassification.OBSTRUCTED, null);
    }

    private record ColumnResult(BlockClassification classification, Integer groundY) {
    }

    private static Block findGroundBlock(World world, int x, int baseY, int z) {
        for (int dy = 0; dy <= 2; dy++) {
            int[] yCandidates = dy == 0
                    ? new int[]{baseY}
                    : new int[]{baseY - dy, baseY + dy};
            for (int y : yCandidates) {
                Block block = world.getBlockAt(x, y, z);
                if (block.getType().isSolid() && !isUnsafeOrHazard(block.getType())) {
                    return block;
                }
            }
        }
        
        // Scan downwards fallback to find solid block
        for (int y = baseY; y >= Math.max(0, baseY - 5); y--) {
            Block block = world.getBlockAt(x, y, z);
            if (block.getType().isSolid() && !isUnsafeOrHazard(block.getType())) {
                return block;
            }
        }
        return null;
    }

    private static boolean isUnsafeOrHazard(Material material) {
        return switch (material) {
            case LAVA, LAVA_CAULDRON, WATER, WATER_CAULDRON, ICE, PACKED_ICE, BLUE_ICE,
                 MAGMA_BLOCK, CACTUS, FIRE, SOUL_FIRE, CAMPFIRE, SOUL_CAMPFIRE,
                 LANTERN, SOUL_LANTERN, TORCH, SOUL_TORCH, WALL_TORCH, SWEET_BERRY_BUSH, WITHER_ROSE -> true;
            default -> false;
        };
    }

    private static boolean isAirOrPassable(Material material) {
        return material.isAir()
                || material == Material.CAVE_AIR
                || material == Material.VOID_AIR
                || Tag.CROPS.isTagged(material)
                || material == Material.SUGAR_CANE
                || material == Material.NETHER_WART
                || Tag.DOORS.isTagged(material)
                || Tag.TRAPDOORS.isTagged(material)
                || Tag.FENCE_GATES.isTagged(material);
    }

    private static boolean isWalkableGround(Material material) {
        if (!material.isSolid() || material.isInteractable()) {
            return false;
        }
        if (Tag.STAIRS.isTagged(material)
                || Tag.SLABS.isTagged(material)
                || Tag.FENCES.isTagged(material)
                || Tag.WALLS.isTagged(material)
                || Tag.TRAPDOORS.isTagged(material)
                || Tag.FENCE_GATES.isTagged(material)) {
            return false;
        }
        return true;
    }
}
