package com.herepunchy.herepunchy.map;

import org.bukkit.Location;

import java.util.Map;

public class ScanResult {

    private final Location pointA;
    private final Location pointB;
    private final Map<String, BlockClassification> classifications;
    private final Map<String, Integer> groundYLevels;
    private final int passableCount;
    private final int hazardCount;
    private final int obstructedCount;

    public ScanResult(Location pointA, Location pointB, Map<String, BlockClassification> classifications, Map<String, Integer> groundYLevels) {
        this.pointA = pointA;
        this.pointB = pointB;
        this.classifications = classifications;
        this.groundYLevels = groundYLevels;
        
        int passable = 0, hazard = 0, obstructed = 0;
        for (BlockClassification bc : classifications.values()) {
            switch (bc) {
                case PASSABLE -> passable++;
                case HAZARD -> hazard++;
                case OBSTRUCTED -> obstructed++;
            }
        }
        this.passableCount = passable;
        this.hazardCount = hazard;
        this.obstructedCount = obstructed;
    }

    public Location getPointA() {
        return pointA;
    }

    public Location getPointB() {
        return pointB;
    }

    public BlockClassification getClassification(int x, int z) {
        return classifications.getOrDefault(key(x, z), BlockClassification.OBSTRUCTED);
    }

    public boolean isPassable(int x, int z) {
        return getClassification(x, z) == BlockClassification.PASSABLE;
    }

    public boolean isHazard(int x, int z) {
        return getClassification(x, z) == BlockClassification.HAZARD;
    }

    public boolean isObstructed(int x, int z) {
        return getClassification(x, z) == BlockClassification.OBSTRUCTED;
    }

    public int getPassableCount() {
        return passableCount;
    }

    public int getHazardCount() {
        return hazardCount;
    }

    public int getObstructedCount() {
        return obstructedCount;
    }

    public int getGroundY(int x, int z) {
        return groundYLevels.getOrDefault(key(x, z), pointA.getBlockY());
    }

    public int getTotalWalkable() {
        return passableCount;
    }

    private static String key(int x, int z) {
        return x + "," + z;
    }
}
