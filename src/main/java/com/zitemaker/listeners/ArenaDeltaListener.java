package com.zitemaker.listeners;

import com.zitemaker.ArenaRegen;
import com.zitemaker.helpers.RegionData;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.BlockState;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Chest;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.world.StructureGrowEvent;

import java.util.List;
import java.util.Set;

/**
 * High-throughput event listener that intercepts all block modification vectors
 * inside registered region boundaries and records pristine original block states into DeltaLedger.
 *
 * Employs O(1) chunk spatial index filtering to eliminate CPU overhead on non-arena block events.
 */
public final class ArenaDeltaListener implements Listener {

    private static final BlockFace[] HORIZONTAL_FACES = {BlockFace.NORTH, BlockFace.EAST, BlockFace.SOUTH, BlockFace.WEST};
    private final ArenaRegen plugin;

    public ArenaDeltaListener(ArenaRegen plugin) {
        this.plugin = plugin;
    }

    private void recordBlockIfInsideRegion(Block block, BlockData pristineData) {
        if (block == null) {
            return;
        }
        int x = block.getX();
        int z = block.getZ();

        Set<RegionData> regions = plugin.getSpatialRegionIndex().getRegionsInChunk(x >> 4, z >> 4);
        if (regions.isEmpty()) {
            return;
        }

        World world = block.getWorld();
        int y = block.getY();

        if (plugin.getWorldGuardHook().isExcluded(world, x, y, z)) {
            return;
        }

        for (RegionData region : regions) {
            if (region.containsLocation(world, x, y, z)) {
                BlockData actualPristine = region.getPristineBlockData(x, y, z);
                if (actualPristine == null) {
                    actualPristine = pristineData;
                }
                region.getDeltaLedger().recordOriginalState(x, y, z, actualPristine);

                BlockData currentData = block.getBlockData();
                BlockData chestData = (actualPristine instanceof Chest) ? actualPristine : (currentData instanceof Chest ? currentData : null);

                if (chestData instanceof Chest chest && chest.getType() != Chest.Type.SINGLE) {
                    for (BlockFace face : HORIZONTAL_FACES) {
                        Block neighbor = block.getRelative(face);
                        int nx = neighbor.getX();
                        int ny = neighbor.getY();
                        int nz = neighbor.getZ();
                        if (region.containsLocation(world, nx, ny, nz)) {
                            BlockData neighborPristine = region.getPristineBlockData(nx, ny, nz);
                            if (neighborPristine == null) {
                                neighborPristine = neighbor.getBlockData();
                            }
                            region.getDeltaLedger().recordOriginalState(nx, ny, nz, neighborPristine);
                        }
                    }
                }
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
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        Block block = event.getBlock();
        recordBlockIfInsideRegion(block, block.getBlockData());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBucketFill(PlayerBucketFillEvent event) {
        Block block = event.getBlock();
        recordBlockIfInsideRegion(block, block.getBlockData());
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
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        Block block = event.getBlock();
        recordBlockIfInsideRegion(block, block.getBlockData());
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
    public void onBlockSpread(BlockSpreadEvent event) {
        Block block = event.getBlock();
        recordBlockIfInsideRegion(block, block.getBlockData());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockGrow(BlockGrowEvent event) {
        Block block = event.getBlock();
        recordBlockIfInsideRegion(block, block.getBlockData());
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent event) {
        List<BlockState> blocks = event.getBlocks();
        for (BlockState state : blocks) {
            recordBlockIfInsideRegion(state.getBlock(), state.getBlockData());
        }
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
            Block targetBlock = block.getRelative(event.getDirection());
            recordBlockIfInsideRegion(targetBlock, targetBlock.getBlockData());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        List<Block> blocks = event.getBlocks();
        for (Block block : blocks) {
            recordBlockIfInsideRegion(block, block.getBlockData());
            Block targetBlock = block.getRelative(event.getDirection());
            recordBlockIfInsideRegion(targetBlock, targetBlock.getBlockData());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPlayerInteract(PlayerInteractEvent event) {
        Block block = event.getClickedBlock();
        if (block == null) return;

        if (event.getAction() == Action.PHYSICAL || event.getAction() == Action.RIGHT_CLICK_BLOCK) {
            recordBlockIfInsideRegion(block, block.getBlockData());
        }
    }
}
