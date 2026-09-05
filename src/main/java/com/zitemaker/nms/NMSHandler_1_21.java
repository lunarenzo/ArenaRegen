package com.zitemaker.nms;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.EntityBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.lighting.LevelLightEngine;
import net.minecraft.world.level.LightLayer;
import net.minecraft.core.SectionPos;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.block.data.CraftBlockData;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.logging.Logger;

public class NMSHandler_1_21 implements NMSHandler {
    private static final Logger LOGGER = Bukkit.getLogger();
    private final Plugin plugin;

    public NMSHandler_1_21(Plugin plugin) {
        this.plugin = plugin;
    }

    @Override
    public void setBlocks(World world, List<BlockUpdate> blockUpdates) {
        if (blockUpdates == null || blockUpdates.isEmpty())
            return;

        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> setBlocks(world, blockUpdates));
            return;
        }

        CraftWorld craftWorld = (CraftWorld) world;
        Long2ObjectMap<LevelChunk> chunkCache = new Long2ObjectOpenHashMap<>();

        try {
            for (BlockUpdate update : blockUpdates) {
                final int x = update.getX();
                final int y = update.getY();
                final int z = update.getZ();

                if (y < world.getMinHeight() || y >= world.getMaxHeight()) {
                    continue;
                }

                final int chunkX = x >> 4;
                final int chunkZ = z >> 4;
                if (!world.isChunkLoaded(chunkX, chunkZ)) {
                    continue;
                }

                final long chunkKey = ChunkPos.asLong(chunkX, chunkZ);
                final LevelChunk chunk = chunkCache.computeIfAbsent(chunkKey,
                        key -> craftWorld.getHandle().getChunk(chunkX, chunkZ));

                final int sectionIndex = chunk.getSectionIndex(y);
                if (sectionIndex < 0 || sectionIndex >= chunk.getSections().length) {
                    continue;
                }

                final LevelChunkSection section = chunk.getSection(sectionIndex);
                if (section == null) {
                    world.getBlockAt(x, y, z).setBlockData(update.getBlockData(), false);
                    continue;
                }

                BlockState oldState = section.getBlockState(x & 15, y & 15, z & 15);
                BlockState newState = ((CraftBlockData) update.getBlockData()).getState();
                BlockPos pos = new BlockPos(x, y, z);

                if (chunk.getBlockEntity(pos) != null) {
                    chunk.removeBlockEntity(pos);
                }

                section.setBlockState(x & 15, y & 15, z & 15, newState);

                if (oldState.getLightEmission() != newState.getLightEmission() || oldState.isSolid() != newState.isSolid()) {
                    craftWorld.getHandle().getLightEngine().checkBlock(pos);
                }

                if (newState.hasBlockEntity()) {
                    BlockEntity blockEntity = ((EntityBlock) newState.getBlock()).newBlockEntity(pos, newState);
                    if (blockEntity != null) {
                        chunk.setBlockEntity(blockEntity);
                    }
                }
            }

            for (LevelChunk chunk : chunkCache.values()) {
                world.refreshChunk(chunk.getPos().x, chunk.getPos().z);
            }
        } catch (Throwable t) {
            LOGGER.warning("NMS failed, falling back to Bukkit API: " + t.getMessage());
            new BukkitNMSHandler().setBlocks(world, blockUpdates);
        }
    }

    @Override
    public void relightChunks(World world, List<Chunk> chunks, List<BlockUpdate> blockUpdates) {
        if (chunks == null || chunks.isEmpty())
            return;

        if (!Bukkit.isPrimaryThread()) {
            Bukkit.getScheduler().runTask(plugin, () -> relightChunks(world, chunks, blockUpdates));
            return;
        }

        Set<ChunkPos> affectedChunks = new HashSet<>();
        for (Chunk chunk : chunks) {
            affectedChunks.add(new ChunkPos(chunk.getX(), chunk.getZ()));
        }

        List<ChunkPos> chunkList = new ArrayList<>(affectedChunks);
        processChunksWithLighting(world, chunkList, 0);
    }

    private void processChunksWithLighting(World world, List<ChunkPos> chunks, int index) {
        if (index >= chunks.size())
            return;

        int batchSize = Math.min(64, chunks.size() - index);

        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                if (!Bukkit.isPrimaryThread()) {
                    LOGGER.warning("Lighting operations attempted off main thread - rescheduling");
                    Bukkit.getScheduler().runTask(plugin, () -> processChunksWithLighting(world, chunks, index));
                    return;
                }

                CraftWorld craftWorld = (CraftWorld) world;
                net.minecraft.server.level.ServerLevel serverLevel = craftWorld.getHandle();
                LevelLightEngine lightEngine = serverLevel.getLightEngine();

                for (int i = 0; i < batchSize; i++) {
                    ChunkPos chunkPos = chunks.get(index + i);
                    try {
                        relightChunk(lightEngine, chunkPos);
                    } catch (Exception e) {
                        LOGGER.warning(
                                "Failed to relight chunk at " + chunkPos.x + ", " + chunkPos.z + ": " + e.getMessage());
                    }
                }

                try {
                    int maxWork = 500;
                    while (lightEngine.hasLightWork() && maxWork-- > 0) {
                        lightEngine.runLightUpdates();
                    }
                } catch (Exception ignored) {
                }

                for (int i = 0; i < batchSize; i++) {
                    ChunkPos chunkPos = chunks.get(index + i);
                    try {
                        world.refreshChunk(chunkPos.x, chunkPos.z);
                    } catch (Exception ignored) {
                    }
                }

                if (index + batchSize < chunks.size()) {
                    Bukkit.getScheduler().runTaskLater(plugin,
                            () -> processChunksWithLighting(world, chunks, index + batchSize),
                            1L);
                }
            } catch (Throwable t) {
                LOGGER.warning("Lighting engine failed, falling back to simple chunk refresh: " + t.getMessage());
                for (int i = 0; i < batchSize; i++) {
                    ChunkPos chunkPos = chunks.get(index + i);
                    try {
                        world.refreshChunk(chunkPos.x, chunkPos.z);
                    } catch (Exception ignored) {
                    }
                }

                if (index + batchSize < chunks.size()) {
                    Bukkit.getScheduler().runTaskLater(plugin,
                            () -> processChunksWithLighting(world, chunks, index + batchSize),
                            1L);
                }
            }
        });
    }

    private void relightChunk(LevelLightEngine lightEngine, ChunkPos chunkPos) {
        try {
            lightEngine.retainData(chunkPos, true);
            lightEngine.setLightEnabled(chunkPos, true);

            int minSection = Math.max(lightEngine.getMinLightSection(), -4);
            int maxSection = Math.min(lightEngine.getMaxLightSection(), 20);

            int buildMinSection = Math.max(minSection, -1);
            int buildMaxSection = Math.min(maxSection, 16);

            for (int sectionIndex = buildMinSection; sectionIndex < buildMaxSection; sectionIndex++) {
                SectionPos sectionPos = SectionPos.of(chunkPos, sectionIndex);

                lightEngine.updateSectionStatus(sectionPos, false);

                lightEngine.queueSectionData(LightLayer.BLOCK, sectionPos, null);
                lightEngine.queueSectionData(LightLayer.SKY, sectionPos, null);
            }
        } catch (Exception e) {
            throw new RuntimeException("Failed to relight chunk: " + e.getMessage(), e);
        }
    }

    /*
     * private void processLightUpdates(LevelLightEngine lightEngine) {
     * try {
     * int maxUpdates = 100;
     * int processed = 0;
     * 
     * while (lightEngine.hasLightWork() && processed < maxUpdates) {
     * lightEngine.runLightUpdates();
     * processed++;
     * }
     * } catch (Exception e) {
     * throw new RuntimeException("Failed to process light updates: " +
     * e.getMessage(), e);
     * }
     * }
     */
}