package com.zitemaker.helpers;

import com.zitemaker.nms.BlockUpdate;
import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import org.bukkit.block.data.BlockData;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Thread-safe, event-driven delta ledger that records pristine original block states
 * before modification occurs within active arena regions.
 *
 * Employs bit-packed long keys via BlockPos and primitive FastUtil collections
 * to achieve zero Bukkit Location and Long key heap allocations.
 * Supports persistent GZIP disk backing to survive server reboots.
 */
public final class DeltaLedger {

    private final Long2ObjectMap<BlockData> originalStates = new Long2ObjectOpenHashMap<>();

    public synchronized void recordOriginalState(int x, int y, int z, BlockData pristineData) {
        if (pristineData == null) {
            return;
        }
        long packedKey = BlockPos.pack(x, y, z);
        originalStates.putIfAbsent(packedKey, pristineData);
    }

    public synchronized boolean isModified(int x, int y, int z) {
        return originalStates.containsKey(BlockPos.pack(x, y, z));
    }

    public synchronized List<BlockUpdate> getDeltaUpdates() {
        if (originalStates.isEmpty()) {
            return Collections.emptyList();
        }

        List<BlockUpdate> updates = new ArrayList<>(originalStates.size());
        for (Long2ObjectMap.Entry<BlockData> entry : originalStates.long2ObjectEntrySet()) {
            long key = entry.getLongKey();
            int x = BlockPos.unpackX(key);
            int y = BlockPos.unpackY(key);
            int z = BlockPos.unpackZ(key);
            updates.add(new BlockUpdate(x, y, z, entry.getValue()));
        }
        return updates;
    }

    public synchronized void saveToFile(File deltaFile) {
        if (originalStates.isEmpty()) {
            if (deltaFile.exists()) {
                deltaFile.delete();
            }
            return;
        }

        Long2ObjectMap<BlockData> snapshot = new Long2ObjectOpenHashMap<>(originalStates);
        try (FileOutputStream fos = new FileOutputStream(deltaFile);
             BufferedOutputStream bos = new BufferedOutputStream(fos, 8192);
             GZIPOutputStream gzip = new GZIPOutputStream(bos);
             DataOutputStream dos = new DataOutputStream(gzip)) {

            dos.writeInt(snapshot.size());
            for (Long2ObjectMap.Entry<BlockData> entry : snapshot.long2ObjectEntrySet()) {
                dos.writeLong(entry.getLongKey());
                dos.writeUTF(entry.getValue().getAsString());
            }
            dos.flush();
        } catch (IOException ignored) {
        }
    }

    public synchronized void loadFromFile(File deltaFile) {
        if (!deltaFile.exists() || deltaFile.length() == 0) {
            return;
        }

        try (FileInputStream fis = new FileInputStream(deltaFile);
             BufferedInputStream bis = new BufferedInputStream(fis, 8192);
             GZIPInputStream gzip = new GZIPInputStream(bis);
             DataInputStream dis = new DataInputStream(gzip)) {

            int count = dis.readInt();
            for (int i = 0; i < count; i++) {
                long packedKey = dis.readLong();
                String dataStr = dis.readUTF();
                BlockData blockData = RegionData.getCachedBlockData(dataStr);
                originalStates.putIfAbsent(packedKey, blockData);
            }
        } catch (Exception ignored) {
        }
    }

    public synchronized int size() {
        return originalStates.size();
    }

    public synchronized boolean isEmpty() {
        return originalStates.isEmpty();
    }

    public synchronized void clear() {
        originalStates.clear();
    }
}

