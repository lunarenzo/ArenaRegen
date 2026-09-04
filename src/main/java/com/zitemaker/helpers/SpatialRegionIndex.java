package com.zitemaker.helpers;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Thread-safe spatial index mapping packed chunk coordinates to RegionData instances.
 * Reduces spatial boundary queries from O(N) linear region iteration to O(1) instant hash lookups.
 * Employs unboxed primitive FastUtil Long2ObjectOpenHashMap to eliminate Long key heap allocations.
 */
public final class SpatialRegionIndex {

    private final Long2ObjectMap<Set<RegionData>> chunkMap = new Long2ObjectOpenHashMap<>();

    /**
     * Indexes all chunks covered by the region bounding box.
     *
     * @param region region to index
     */
    public synchronized void registerRegion(RegionData region) {
        if (region == null || region.getWorldName() == null) {
            return;
        }

        int minChunkX = region.getMinX() >> 4;
        int maxChunkX = region.getMaxX() >> 4;
        int minChunkZ = region.getMinZ() >> 4;
        int maxChunkZ = region.getMaxZ() >> 4;

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                long packedChunk = BlockPos.packChunk(cx, cz);
                chunkMap.computeIfAbsent(packedChunk, k -> new HashSet<>()).add(region);
            }
        }
    }

    /**
     * Removes all chunk index entries for a region.
     *
     * @param region region to remove
     */
    public synchronized void unregisterRegion(RegionData region) {
        if (region == null) {
            return;
        }

        int minChunkX = region.getMinX() >> 4;
        int maxChunkX = region.getMaxX() >> 4;
        int minChunkZ = region.getMinZ() >> 4;
        int maxChunkZ = region.getMaxZ() >> 4;

        for (int cx = minChunkX; cx <= maxChunkX; cx++) {
            for (int cz = minChunkZ; cz <= maxChunkZ; cz++) {
                long packedChunk = BlockPos.packChunk(cx, cz);
                Set<RegionData> regions = chunkMap.get(packedChunk);
                if (regions != null) {
                    regions.remove(region);
                    if (regions.isEmpty()) {
                        chunkMap.remove(packedChunk);
                    }
                }
            }
        }
    }

    /**
     * Clears existing index and re-indexes all active regions.
     *
     * @param regions active region collection
     */
    public synchronized void reindexAll(Collection<RegionData> regions) {
        chunkMap.clear();
        if (regions == null || regions.isEmpty()) {
            return;
        }
        for (RegionData region : regions) {
            registerRegion(region);
        }
    }

    /**
     * Fast O(1) lookup of regions occupying a specific chunk.
     * Unboxed long key prevents java.lang.Long heap allocation.
     *
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     * @return set of regions in the specified chunk, or empty set
     */
    public synchronized Set<RegionData> getRegionsInChunk(int chunkX, int chunkZ) {
        Set<RegionData> regions = chunkMap.get(BlockPos.packChunk(chunkX, chunkZ));
        return regions != null ? regions : Collections.emptySet();
    }

    /**
     * Clears all spatial index mappings.
     */
    public synchronized void clear() {
        chunkMap.clear();
    }
}
