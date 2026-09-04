package com.zitemaker.nms;

import org.bukkit.block.data.BlockData;

/**
 * Immutable data carrier for high-throughput NMS block updates.
 */
public record BlockUpdate(int x, int y, int z, BlockData blockData) {
    public int getX() { return x; }
    public int getY() { return y; }
    public int getZ() { return z; }
    public BlockData getBlockData() { return blockData; }
}