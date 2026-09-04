package com.zitemaker.listeners;

import com.zitemaker.ArenaRegen;
import com.zitemaker.helpers.RegionData;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerMoveListener implements Listener {

    private final ArenaRegen plugin;
    private final Map<UUID, Long> messageCooldowns = new ConcurrentHashMap<>();
    private static final long MESSAGE_COOLDOWN_MS = 3000;

    private final Map<String, BukkitTask> emptyRegenTasks = new ConcurrentHashMap<>();

    public PlayerMoveListener(ArenaRegen plugin) {
        this.plugin = plugin;
    }

    public void updateRegionBounds() {
        // SpatialRegionIndex maintains chunk mapping dynamically
    }

    @EventHandler
    public void onPlayerMove(PlayerMoveEvent event) {
        Location from = event.getFrom();
        Location to = event.getTo();
        if (to == null || (from.getBlockX() == to.getBlockX() && from.getBlockY() == to.getBlockY() && from.getBlockZ() == to.getBlockZ())) {
            return;
        }

        Player player = event.getPlayer();
        World world = player.getWorld();
        int x = to.getBlockX(), y = to.getBlockY(), z = to.getBlockZ();

        Location safeLoc = null;
        boolean shouldCancel = false;

        Set<RegionData> regions = plugin.getSpatialRegionIndex().getRegionsInChunk(x >> 4, z >> 4);
        if (!regions.isEmpty()) {
            for (RegionData region : regions) {
                if (!region.isLocked()) continue;
                if (!region.getWorldName().equals(world.getName())) continue;

                if (region.containsLocation(world, x, y, z)) {
                    long currentTime = System.currentTimeMillis();
                    UUID playerId = player.getUniqueId();
                    Long lastMessageTime = messageCooldowns.get(playerId);
                    if (lastMessageTime == null || currentTime - lastMessageTime > MESSAGE_COOLDOWN_MS) {
                        player.sendMessage(plugin.prefix + ChatColor.RED + " This arena is currently locked!");
                        messageCooldowns.put(playerId, currentTime);
                    }

                    safeLoc = findSafeLocationOutsideArena(player, region, from);
                    shouldCancel = true;
                    break;
                }
            }
        }

        if (shouldCancel) {
            player.teleport(safeLoc);
            event.setCancelled(true);
        } else {
            checkArenaOccupancy(player, from, to);
        }
    }

    @EventHandler
    public void onPlayerTeleport(PlayerTeleportEvent event) {
        if (event.isCancelled()) return;

        Location to = event.getTo();
        if (to == null) return;

        Player player = event.getPlayer();
        World world = to.getWorld();
        int x = to.getBlockX(), y = to.getBlockY(), z = to.getBlockZ();

        Set<RegionData> regions = plugin.getSpatialRegionIndex().getRegionsInChunk(x >> 4, z >> 4);
        if (!regions.isEmpty()) {
            for (RegionData region : regions) {
                if (!region.isLocked()) continue;
                if (!region.getWorldName().equals(world.getName())) continue;

                if (region.containsLocation(world, x, y, z)) {
                    event.setCancelled(true);
                    player.sendMessage(plugin.prefix + ChatColor.RED + " This arena is currently regenerating and locked! Teleportation cancelled.");
                    return;
                }
            }
        }

        checkArenaOccupancy(player, event.getFrom(), to);
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        Player player = event.getPlayer();
        checkArenaOccupancy(player, player.getLocation(), null);
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        Player player = event.getEntity();
        checkArenaOccupancy(player, player.getLocation(), null);
    }

    private String getRegionName(RegionData region) {
        File file = region.getDatcFile();
        if (file != null) {
            return file.getName().replace(".datc", "");
        }
        for (Map.Entry<String, RegionData> entry : plugin.getRegisteredRegions().entrySet()) {
            if (entry.getValue() == region) {
                return entry.getKey();
            }
        }
        return null;
    }

    private void checkArenaOccupancy(Player player, Location locFrom, Location locTo) {
        if (!plugin.regenerateOnEmpty) return;

        World world = player.getWorld();
        Set<RegionData> candidateRegions = new HashSet<>();
        if (locFrom != null) {
            candidateRegions.addAll(plugin.getSpatialRegionIndex().getRegionsInChunk(locFrom.getBlockX() >> 4, locFrom.getBlockZ() >> 4));
        }
        if (locTo != null) {
            candidateRegions.addAll(plugin.getSpatialRegionIndex().getRegionsInChunk(locTo.getBlockX() >> 4, locTo.getBlockZ() >> 4));
        }

        if (candidateRegions.isEmpty()) return;

        for (RegionData region : candidateRegions) {
            if (!region.getWorldName().equals(world.getName())) continue;

            boolean wasInside = locFrom != null && region.containsLocation(world, locFrom.getBlockX(), locFrom.getBlockY(), locFrom.getBlockZ());
            boolean isInside = locTo != null && region.containsLocation(world, locTo.getBlockX(), locTo.getBlockY(), locTo.getBlockZ());

            String regionName = getRegionName(region);
            if (regionName == null) continue;

            if (wasInside && !isInside) {
                handlePlayerLeft(regionName);
            } else if (!wasInside && isInside) {
                handlePlayerEntered(regionName);
            }
        }
    }

    private void handlePlayerLeft(String arenaName) {
        // Run 1 tick later to allow state transitions (quit/teleport) to complete
        Bukkit.getScheduler().runTaskLater(plugin, () -> {
            int count = getPlayersInArena(arenaName);
            if (count == 0 && !plugin.isArenaRegenerating(arenaName)) {
                if (!emptyRegenTasks.containsKey(arenaName)) {
                    BukkitTask task = Bukkit.getScheduler().runTaskLater(plugin, () -> {
                        emptyRegenTasks.remove(arenaName);
                        if (getPlayersInArena(arenaName) == 0 && !plugin.isArenaRegenerating(arenaName)) {
                            plugin.regenerateArena(arenaName, null);
                        }
                    }, plugin.regenerateOnEmptyDelay * 20L);

                    emptyRegenTasks.put(arenaName, task);
                    plugin.getLogger().info("Arena '" + arenaName + "' is empty. Regeneration scheduled in " + plugin.regenerateOnEmptyDelay + "s.");
                }
            }
        }, 1L);
    }

    private void handlePlayerEntered(String arenaName) {
        BukkitTask task = emptyRegenTasks.remove(arenaName);
        if (task != null) {
            task.cancel();
            plugin.getLogger().info("Player entered arena '" + arenaName + "'. Canceled scheduled empty-regeneration.");
        }
    }

    private int getPlayersInArena(String arenaName) {
        RegionData region = plugin.getRegisteredRegions().get(arenaName);
        if (region == null) return 0;
        World world = Bukkit.getWorld(region.getWorldName());
        if (world == null) return 0;

        int count = 0;
        for (Player player : world.getPlayers()) {
            Location loc = player.getLocation();
            if (region.containsLocation(world, loc.getBlockX(), loc.getBlockY(), loc.getBlockZ())) {
                count++;
            }
        }
        return count;
    }

    private Location findSafeLocationOutsideArena(Player player, RegionData region, Location from) {
        double x = from.getX(), y = from.getY(), z = from.getZ();
        float yaw = from.getYaw(), pitch = from.getPitch();

        double minX = region.getMinX();
        double maxX = region.getMaxX() + 1;
        double minZ = region.getMinZ();
        double maxZ = region.getMaxZ() + 1;

        double distToMinX = Math.abs(x - minX);
        double distToMaxX = Math.abs(x - maxX);
        double distToMinZ = Math.abs(z - minZ);
        double distToMaxZ = Math.abs(z - maxZ);
        double minDist = Math.min(Math.min(distToMinX, distToMaxX), Math.min(distToMinZ, distToMaxZ));

        if (minDist == distToMinX) x = minX - 1.5;
        else if (minDist == distToMaxX) x = maxX + 1.5;
        else if (minDist == distToMinZ) z = minZ - 1.5;
        else z = maxZ + 1.5;

        y = Math.max(region.getMinY(), Math.min(y, region.getMaxY() + 1));

        Location safeLocation = new Location(player.getWorld(), x, y, z, yaw, pitch);

        if (!(player.getWorld().getBlockAt(safeLocation.getBlockX(), safeLocation.getBlockY(), safeLocation.getBlockZ()).isPassable() &&
                player.getWorld().getBlockAt(safeLocation.getBlockX(), safeLocation.getBlockY() + 1, safeLocation.getBlockZ()).isPassable())) {
            safeLocation = findNearestSafeLocation(safeLocation);
        }

        return safeLocation;
    }

    private Location findNearestSafeLocation(Location start) {
        World world = start.getWorld();
        double x = start.getX(), z = start.getZ();
        float yaw = start.getYaw(), pitch = start.getPitch();
        int y = (int) start.getY();

        for (int i = 0; i < 5; i++) {
            int testY = y + i;
            Location testLoc = new Location(world, x, testY, z, yaw, pitch);
            if (world.getBlockAt(testLoc.getBlockX(), testY, testLoc.getBlockZ()).isPassable() &&
                    world.getBlockAt(testLoc.getBlockX(), testY + 1, testLoc.getBlockZ()).isPassable()) {
                return testLoc;
            }
        }

        return world.getSpawnLocation();
    }
}