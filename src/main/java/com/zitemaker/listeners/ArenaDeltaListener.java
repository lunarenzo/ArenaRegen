package com.zitemaker.listeners;

import com.zitemaker.ArenaRegen;
import com.zitemaker.helpers.RegionData;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

import java.util.Collection;
import java.util.List;

/**
 * Event-driven listener that intercepts all block modification events
 * inside registered region boundaries and records pristine original block states
 * into the region's DeltaLedger.
 *
 * Runs with MONITOR priority to capture the true initial state before modifications.
 */
public final class ArenaDeltaListener implements Listener {

    private final ArenaRegen plugin;

    public ArenaDeltaListener(ArenaRegen plugin) {
        this.plugin = plugin;
    }

    private void recordBlockIfInsideRegion(Block block, BlockData pristineData) {
        if (block == null) {
            return;
        }
        World world = block.getWorld();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();

        Collection<RegionData> regions = plugin.getRegisteredRegions().values();
        for (RegionData region : regions) {
            if (region.containsLocation(world, x, y, z)) {
                region.getDeltaLedger().recordOriginalState(x, y, z, pristineData);
            }
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        Block block = event.getBlock();
        recordBlockIfInsideRegion(block, block.getBlockData());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Block block = event.getBlock();
        recordBlockIfInsideRegion(block, event.getBlockReplacedState().getBlockData());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        List<Block> blocks = event.blockList();
        for (Block block : blocks) {
            recordBlockIfInsideRegion(block, block.getBlockData());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        List<Block> blocks = event.blockList();
        for (Block block : blocks) {
            recordBlockIfInsideRegion(block, block.getBlockData());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {
        Block block = event.getBlock();
        recordBlockIfInsideRegion(block, block.getBlockData());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockFade(BlockFadeEvent event) {
        Block block = event.getBlock();
        recordBlockIfInsideRegion(block, block.getBlockData());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockForm(BlockFormEvent event) {
        Block block = event.getBlock();
        recordBlockIfInsideRegion(block, block.getBlockData());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockFromTo(BlockFromToEvent event) {
        Block toBlock = event.getToBlock();
        recordBlockIfInsideRegion(toBlock, toBlock.getBlockData());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        List<Block> blocks = event.getBlocks();
        for (Block block : blocks) {
            recordBlockIfInsideRegion(block, block.getBlockData());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        List<Block> blocks = event.getBlocks();
        for (Block block : blocks) {
            recordBlockIfInsideRegion(block, block.getBlockData());
        }
    }
}
