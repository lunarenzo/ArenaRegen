package com.zitemaker.hook.impl;

import com.sk89q.worldedit.bukkit.BukkitAdapter;
import com.sk89q.worldedit.math.BlockVector3;
import com.sk89q.worldguard.WorldGuard;
import com.sk89q.worldguard.protection.managers.RegionManager;
import com.sk89q.worldguard.protection.regions.ProtectedRegion;
import com.sk89q.worldguard.protection.regions.RegionContainer;
import com.zitemaker.hook.WorldGuardHook;
import org.bukkit.World;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class DefaultWorldGuardHook implements WorldGuardHook {

    private final Set<String> excludedRegionNames = new HashSet<>();
    private final Map<String, Map<String, ProtectedRegion>> regionCache = new ConcurrentHashMap<>();

    public DefaultWorldGuardHook(List<String> excludedRegionNames) {
        if (excludedRegionNames != null) {
            for (String name : excludedRegionNames) {
                if (name != null && !name.isBlank()) {
                    this.excludedRegionNames.add(name.toLowerCase(Locale.ROOT));
                }
            }
        }
    }

    @Override
    public boolean isExcluded(World world, int x, int y, int z) {
        if (excludedRegionNames.isEmpty() || world == null) {
            return false;
        }

        String worldName = world.getName();
        Map<String, ProtectedRegion> worldRegions = regionCache.computeIfAbsent(worldName, k -> {
            Map<String, ProtectedRegion> map = new HashMap<>();
            try {
                RegionContainer container = WorldGuard.getInstance().getPlatform().getRegionContainer();
                RegionManager manager = container.get(BukkitAdapter.adapt(world));
                if (manager != null) {
                    for (String regionName : excludedRegionNames) {
                        ProtectedRegion region = manager.getRegion(regionName);
                        if (region != null) {
                            map.put(regionName, region);
                        }
                    }
                }
            } catch (Exception ignored) {
            }
            return map;
        });

        if (worldRegions.isEmpty()) {
            return false;
        }

        BlockVector3 pos = BlockVector3.at(x, y, z);
        for (ProtectedRegion region : worldRegions.values()) {
            if (region.contains(pos)) {
                return true;
            }
        }

        return false;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }
}
