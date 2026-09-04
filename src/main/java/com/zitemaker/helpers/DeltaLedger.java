package com.zitemaker.helpers;

import com.zitemaker.nms.BlockUpdate;
import org.bukkit.block.data.BlockData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe, event-driven delta ledger that records pristine original block states
 * before modification occurs within active arena regions.
 *
 * Employs bit-packed long keys via BlockPos to achieve zero Bukkit Location heap allocations
 * and O(1) lock-free updates via ConcurrentHashMap.
 */
public final class DeltaLedger {

    private final Map<Long, BlockData> originalStates = new ConcurrentHashMap<>();

    /**
     * Records the pristine original block state before any modification.
     * Uses putIfAbsent to ensure only the FIRST state prior to any edits is preserved.
     *
     * @param x block X coordinate
     * @param y block Y coordinate
     * @param z block Z coordinate
     * @param pristineData original BlockData prior to edit
     */
    public void recordOriginalState(int x, int y, int z, BlockData pristineData) {
        if (pristineData == null) {
            return;
        }
        long packedKey = BlockPos.pack(x, y, z);
        originalStates.putIfAbsent(packedKey, pristineData);
    }

    /**
     * Checks if a block coordinate has been modified in this delta session.
     *
     * @param x block X coordinate
     * @param y block Y coordinate
     * @param z block Z coordinate
     * @return true if an original state is recorded
     */
    public boolean isModified(int x, int y, int z) {
        return originalStates.containsKey(BlockPos.pack(x, y, z));
    }

    /**
     * Converts recorded delta entries into lightweight BlockUpdate transfer objects
     * for direct batch restoration via NMS.
     *
     * @return unmodifiable list of BlockUpdates to execute
     */
    public List<BlockUpdate> getDeltaUpdates() {
        if (originalStates.isEmpty()) {
            return Collections.emptyList();
        }

        List<BlockUpdate> updates = new ArrayList<>(originalStates.size());
        for (Map.Entry<Long, BlockData> entry : originalStates.entrySet()) {
            long key = entry.getKey();
            int x = BlockPos.unpackX(key);
            int y = BlockPos.unpackY(key);
            int z = BlockPos.unpackZ(key);
            updates.add(new BlockUpdate(x, y, z, entry.getValue()));
        }
        return updates;
    }

    /**
     * Returns total count of modified blocks tracked in this ledger.
     *
     * @return number of tracked delta block changes
     */
    public int size() {
        return originalStates.size();
    }

    /**
     * Checks if the ledger is empty.
     *
     * @return true if no modifications are logged
     */
    public boolean isEmpty() {
        return originalStates.isEmpty();
    }

    /**
     * Clears all logged delta states, freeing heap references instantly.
     */
    public void clear() {
        originalStates.clear();
    }
}
