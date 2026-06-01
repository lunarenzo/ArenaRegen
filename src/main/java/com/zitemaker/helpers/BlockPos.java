package com.zitemaker.helpers;

import org.bukkit.Location;
import org.bukkit.World;

/**
 * Utility class for packing block coordinates into a single long value.
 * This provides O(1) HashMap lookups instead of O(log n) with Location keys.
 *
 * Bit layout (64 bits total):
 * - Bits 0-25 (26 bits): X coordinate - supports -33,554,432 to 33,554,431
 * - Bits 26-51 (26 bits): Z coordinate - supports -33,554,432 to 33,554,431
 * - Bits 52-63 (12 bits): Y coordinate - supports 0 to 4095
 *
 * This covers the Minecraft world limits:
 * - X/Z: -30,000,000 to 30,000,000 (world border)
 * - Y: -64 to 320 (current build height) - stored as offset from -2048
 */
public final class BlockPos {

    private static final int X_BITS = 26;
    private static final int Z_BITS = 26;
    private static final int Y_BITS = 12;

    private static final long X_MASK = (1L << X_BITS) - 1;
    private static final long Z_MASK = (1L << Z_BITS) - 1;
    private static final long Y_MASK = (1L << Y_BITS) - 1;

    private static final int Y_OFFSET = 2048;

    private BlockPos() {
    }

    public static long pack(int x, int y, int z) {
        int offsetY = y + Y_OFFSET;

        return ((long) (x & X_MASK)) |
                (((long) (z & Z_MASK)) << X_BITS) |
                (((long) (offsetY & Y_MASK)) << (X_BITS + Z_BITS));
    }

    public static long pack(Location loc) {
        return pack(loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    public static int unpackX(long packed) {
        int raw = (int) (packed & X_MASK);
        return (raw << (32 - X_BITS)) >> (32 - X_BITS);
    }

    public static int unpackY(long packed) {
        int offsetY = (int) ((packed >> (X_BITS + Z_BITS)) & Y_MASK);
        return offsetY - Y_OFFSET;
    }

    public static int unpackZ(long packed) {
        int raw = (int) ((packed >> X_BITS) & Z_MASK);
        return (raw << (32 - Z_BITS)) >> (32 - Z_BITS);
    }

    public static Location toLocation(long packed, World world) {
        return new Location(world, unpackX(packed), unpackY(packed), unpackZ(packed));
    }

    public static Location toLocationCentered(long packed, World world) {
        return new Location(world,
                unpackX(packed) + 0.5,
                unpackY(packed),
                unpackZ(packed) + 0.5);
    }

    public static long packChunk(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) | (chunkZ & 0xFFFFFFFFL);
    }

    public static int unpackChunkX(long packed) {
        return (int) (packed >> 32);
    }

    public static int unpackChunkZ(long packed) {
        return (int) packed;
    }

    public static int toChunkCoord(int blockCoord) {
        return blockCoord >> 4;
    }
}
