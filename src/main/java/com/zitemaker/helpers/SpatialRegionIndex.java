package com.zitemaker.helpers;

import org.bukkit.World;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe spatial index mapping packed chunk coordinates to RegionData instances.
 * Reduces spatial boundary queries from O(N) linear region iteration to O(1) instant hash lookups.
 */
public final class SpatialRegionIndex {

    private final Map<Long, Set<RegionData>> chunkMap = new ConcurrentHashMap<>();

    /**
     * Indexes all chunks covered by the region bounding box.
     *
     * @param region region to index
     */
    public void registerRegion(RegionData region) {
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
                chunkMap.computeIfAbsent(packedChunk, k -> ConcurrentHashMap.newKeySet()).add(region);
            }
        }
    }

    /**
     * Removes all chunk index entries for a region.
     *
     * @param region region to remove
     */
    public void unregisterRegion(RegionData region) {
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
    public void reindexAll(Collection<RegionData> regions) {
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
     *
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     * @return set of regions in the specified chunk, or empty set
     */
    public Set<RegionData> getRegionsInChunk(int chunkX, int chunkZ) {
        Set<RegionData> regions = chunkMap.get(BlockPos.packChunk(chunkX, chunkZ));
        return regions != null ? regions : Collections.emptySet();
    }

    /**
     * Clears all spatial index mappings.
     */
    public void clear() {
        chunkMap.clear();
    }
}
