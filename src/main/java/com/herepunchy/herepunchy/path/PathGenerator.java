package com.herepunchy.herepunchy.path;

import com.herepunchy.herepunchy.map.ScanResult;
import org.bukkit.Location;
import org.bukkit.World;

import java.util.ArrayList;
import java.util.List;

public class PathGenerator {

    public static int findClosestIndex(List<Location> path, Location target) {
        if (path.isEmpty() || target == null) {
            return 0;
        }
        int bestIndex = 0;
        double bestDistSq = Double.MAX_VALUE;
        for (int i = 0; i < path.size(); i++) {
            Location loc = path.get(i);
            if (loc.getWorld() != target.getWorld()) {
                continue;
            }
            double dx = loc.getX() - target.getX();
            double dz = loc.getZ() - target.getZ();
            double distSq = dx * dx + dz * dz;
            if (distSq < bestDistSq) {
                bestDistSq = distSq;
                bestIndex = i;
            }
        }
        return bestIndex;
    }

    public static List<Location> generateSafePath(ScanResult scanResult) {
        List<Location> path = new ArrayList<>();

        Location pointA = scanResult.getPointA();
        Location pointB = scanResult.getPointB();
        World world = pointA.getWorld();

        int minX = Math.min(pointA.getBlockX(), pointB.getBlockX());
        int maxX = Math.max(pointA.getBlockX(), pointB.getBlockX());
        int minZ = Math.min(pointA.getBlockZ(), pointB.getBlockZ());
        int maxZ = Math.max(pointA.getBlockZ(), pointB.getBlockZ());

        boolean goingRight = true;
        for (int z = minZ; z <= maxZ; z++) {
            if (goingRight) {
                for (int x = minX; x <= maxX; x++) {
                    if (scanResult.isPassable(x, z)) {
                        path.add(new Location(world, x + 0.5, scanResult.getGroundY(x, z), z + 0.5));
                    }
                }
            } else {
                for (int x = maxX; x >= minX; x--) {
                    if (scanResult.isPassable(x, z)) {
                        path.add(new Location(world, x + 0.5, scanResult.getGroundY(x, z), z + 0.5));
                    }
                }
            }
            goingRight = !goingRight;
        }

        return path;
    }
}
