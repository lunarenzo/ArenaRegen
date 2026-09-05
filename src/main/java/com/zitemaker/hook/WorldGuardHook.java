package com.zitemaker.hook;

import org.bukkit.World;

public interface WorldGuardHook {
    boolean isExcluded(World world, int x, int y, int z);
    boolean isEnabled();
}
