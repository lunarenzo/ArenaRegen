package com.zitemaker.hook.impl;

import com.zitemaker.hook.WorldGuardHook;
import org.bukkit.World;

public final class NoOpWorldGuardHook implements WorldGuardHook {

    @Override
    public boolean isExcluded(World world, int x, int y, int z) {
        return false;
    }

    @Override
    public boolean isEnabled() {
        return false;
    }
}
