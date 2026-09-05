package com.zitemaker.helpers;

import com.zitemaker.ArenaRegen;
import org.bukkit.*;
import org.bukkit.block.BlockState;
import org.bukkit.block.Chest;
import org.bukkit.block.ChiseledBookshelf;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.Banner;
import org.bukkit.block.Sign;
import org.bukkit.block.banner.Pattern;
import org.bukkit.block.banner.PatternType;
import org.bukkit.inventory.BlockInventoryHolder;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.NamespacedKey;
import org.bukkit.plugin.java.JavaPlugin;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.io.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

public class RegionData {
    private static final Logger LOGGER = JavaPlugin.getPlugin(ArenaRegen.class).getLogger();
    private static final String FILE_FORMAT_VERSION = "4";
    private static final int GZIP_COMPRESSION_LEVEL = 6;
    private static final byte[] GZIP_MAGIC = new byte[] { (byte) 0x1F, (byte) 0x8B };

    
    
    private static final Map<String, BlockData> BLOCK_DATA_CACHE = new ConcurrentHashMap<>();
    private static final int MAX_CACHE_SIZE = 10000; 

    
    public static BlockData getCachedBlockData(String blockDataString) {
        
        BlockData cached = BLOCK_DATA_CACHE.get(blockDataString);
        if (cached != null) {
            return cached;
        }

        
        BlockData blockData = Bukkit.createBlockData(blockDataString);

        
        if (BLOCK_DATA_CACHE.size() < MAX_CACHE_SIZE) {
            BLOCK_DATA_CACHE.put(blockDataString, blockData);
        }

        return blockData;
    }

    
    public static void clearBlockDataCache() {
        BLOCK_DATA_CACHE.clear();
        LOGGER.info("[ArenaRegen] BlockData cache cleared.");
    }

    
    public static int getBlockDataCacheSize() {
        return BLOCK_DATA_CACHE.size();
    }

    private static final List<String> KNOWN_PATTERN_IDENTIFIERS = Arrays.asList(
            "base", "square_bottom_left", "square_bottom_right", "square_top_left", "square_top_right",
            "stripe_bottom", "stripe_top", "stripe_left", "stripe_right", "stripe_center", "stripe_middle",
            "stripe_downright", "stripe_downleft", "small_stripes", "cross", "straight_cross",
            "triangle_bottom", "triangle_top", "triangles_bottom", "triangles_top",
            "diagonal_left", "diagonal_right", "diagonal_up_left", "diagonal_up_right",
            "circle", "rhombus", "half_vertical", "half_horizontal", "half_vertical_right", "half_horizontal_bottom",
            "border", "curly_border", "gradient", "gradient_up", "bricks", "globe", "creeper", "skull",
            "flower", "mojang", "piglin", "flow", "guster"
    );

    private final ArenaRegen plugin;
    public final Map<String, Long2ObjectMap<BlockData>> sectionedBlockData = new ConcurrentHashMap<>();
    private final Long2ObjectMap<BlockData> pristineBlockMap = new Long2ObjectOpenHashMap<>();
    private final Map<Location, Map<String, Object>> entityDataMap = new ConcurrentHashMap<>();
    private final Map<Location, BlockData> modifiedBlocks = new ConcurrentHashMap<>();
    private final Map<Location, Map<String, Object>> bannerStates = new ConcurrentHashMap<>();
    private final Map<Location, Map<String, Object>> signStates = new ConcurrentHashMap<>();
    private final Map<Location, ItemStack[]> containerStates = new ConcurrentHashMap<>();
    private final DeltaLedger deltaLedger = new DeltaLedger();

    private String creator;
    private long creationDate;
    public String worldName;
    private String minecraftVersion;
    private String fileFormatVersion = FILE_FORMAT_VERSION;
    private int minX, minY, minZ;
    private int width, height, depth;
    private Location spawnLocation;
    private boolean locked = false;
    private boolean isBlockDataLoaded = false;
    private File datcFile;
    private boolean loadFailed = false;
    private boolean isLoading = false;
    private CompletableFuture<Void> blockDataLoadFuture = null;

    public RegionData(ArenaRegen plugin) {
        this.plugin = plugin;
    }

    public void setDatcFile(File datcFile) {
        this.datcFile = datcFile;
    }

    public void setSpawnLocation(Location location) {
        this.spawnLocation = location != null ? location.clone() : null;
    }

    public void setLocked(boolean locked) {
        this.locked = locked;
        if (datcFile != null) {
            String regionName = datcFile.getName().replace(".datc", "");
            plugin.markRegionDirty(regionName);
        }
    }

    public Location getSpawnLocation() {
        return spawnLocation != null ? spawnLocation.clone() : null;
    }

    public void addBlockToSection(String section, Location location, BlockData blockData) {
        addBlockToSection(section, location != null ? location.getWorld() : null, location != null ? location.getBlockX() : 0, location != null ? location.getBlockY() : 0, location != null ? location.getBlockZ() : 0, blockData);
    }

    public void addBlockToSection(String section, World world, int x, int y, int z, BlockData blockData) {
        int initialCap = (width > 0 && height > 0 && depth > 0) ? Math.min(1048576, (width * height * depth)) : 65536;
        long key = BlockPos.pack(x, y, z);
        sectionedBlockData.computeIfAbsent(section, k -> new Long2ObjectOpenHashMap<>(initialCap)).put(key, blockData);
        synchronized (pristineBlockMap) {
            pristineBlockMap.put(key, blockData);
        }

        Material mat = blockData.getMaterial();
        if (isTileEntityMaterial(mat) && world != null) {
            Location location = new Location(world, x, y, z);
            BlockState state = world.getBlockAt(location).getState();
            if (state instanceof Banner) {
                Banner banner = (Banner) state;
                Map<String, Object> bannerData = new HashMap<>();
                DyeColor baseColor = banner.getBaseColor();
                bannerData.put("baseColor", baseColor != null ? baseColor.name() : "NONE");
                List<Map<String, String>> patternDataList = new ArrayList<>();
                for (Pattern pattern : banner.getPatterns()) {
                    Map<String, String> patternData = new HashMap<>();
                    DyeColor color = pattern.getColor();
                    patternData.put("color", color.name());
                    String patternIdentifier = resolvePatternIdentifier(pattern);
                    patternData.put("type", patternIdentifier);
                    patternDataList.add(patternData);
                }
                bannerData.put("patterns", patternDataList);
                PersistentDataContainer pdc = banner.getPersistentDataContainer();
                if (!pdc.isEmpty()) {
                    Map<String, Object> pdcData = serializePdc(pdc);
                    bannerData.put("persistentData", pdcData);
                }
                bannerStates.put(location.clone(), bannerData);
            } else if (state instanceof Sign) {
                Sign sign = (Sign) state;
                Map<String, Object> signData = new HashMap<>();
                List<String> lines = new ArrayList<>();
                for (int i = 0; i < 4; i++) {
                    lines.add(sign.getLine(i));
                }
                signData.put("lines", lines);
                DyeColor color = sign.getColor();
                signData.put("color", color != null ? color.name() : "BLACK");
                signData.put("glowing", sign.isGlowingText());
                PersistentDataContainer pdc = sign.getPersistentDataContainer();
                if (!pdc.isEmpty()) {
                    Map<String, Object> pdcData = serializePdc(pdc);
                    signData.put("persistentData", pdcData);
                }
                signStates.put(location.clone(), signData);
            } else if (state instanceof Chest chest) {
                Inventory inv = chest.getBlockInventory();
                ItemStack[] contents = inv.getContents();
                if (contents != null) {
                    ItemStack[] copy = new ItemStack[contents.length];
                    for (int i = 0; i < contents.length; i++) {
                        copy[i] = contents[i] != null ? contents[i].clone() : null;
                    }
                    containerStates.put(location.clone(), copy);
                }
            } else if (state instanceof BlockInventoryHolder holder) {
                Inventory inv = holder.getInventory();
                ItemStack[] contents = inv.getContents();
                if (contents != null) {
                    ItemStack[] copy = new ItemStack[contents.length];
                    for (int i = 0; i < contents.length; i++) {
                        copy[i] = contents[i] != null ? contents[i].clone() : null;
                    }
                    containerStates.put(location.clone(), copy);
                }
            } else if (state instanceof ChiseledBookshelf shelf) {
                Inventory inv = shelf.getInventory();
                ItemStack[] contents = inv.getContents();
                if (contents != null) {
                    ItemStack[] copy = new ItemStack[contents.length];
                    for (int i = 0; i < contents.length; i++) {
                        copy[i] = contents[i] != null ? contents[i].clone() : null;
                    }
                    containerStates.put(location.clone(), copy);
                }
            }
        }
    }

    private boolean isTileEntityMaterial(Material mat) {
        if (mat == null || mat.isAir()) return false;
        String name = mat.name();
        return name.endsWith("_BANNER") || name.endsWith("_WALL_BANNER") ||
               name.endsWith("_SIGN") || name.endsWith("_WALL_SIGN") || name.endsWith("_HANGING_SIGN") || name.endsWith("_WALL_HANGING_SIGN") ||
               name.endsWith("_CHEST") || name.endsWith("_SHULKER_BOX") || name.endsWith("_BARREL") ||
               name.equals("CHISELED_BOOKSHELF") || name.equals("JUKEBOX") || name.equals("HOPPER") || name.equals("DISPENSER") || name.equals("DROPPER") || name.equals("FURNACE") || name.equals("BLAST_FURNACE") || name.equals("SMOKER") || name.equals("BREWING_STAND") || name.equals("LECTERN");
    }

    private boolean isBannerMaterial(Material mat) {
        String name = mat.name();
        return name.endsWith("_BANNER") || name.endsWith("_WALL_BANNER");
    }

    private boolean isSignMaterial(Material mat) {
        String name = mat.name();
        return name.endsWith("_SIGN") || name.endsWith("_WALL_SIGN") || name.endsWith("_HANGING_SIGN") || name.endsWith("_WALL_HANGING_SIGN");
    }

    public void addSection(String sectionName, Map<Location, BlockData> blocks) {
        Long2ObjectMap<BlockData> sectionBlocks = new Long2ObjectOpenHashMap<>(blocks.size());
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            LOGGER.warning("[ArenaRegen] World '" + worldName + "' not found, cannot check for banners or signs in section " + sectionName);
        }
        for (Map.Entry<Location, BlockData> entry : blocks.entrySet()) {
            Location location = entry.getKey();
            BlockData blockData = entry.getValue();
            if (blockData == null) {
                LOGGER.warning("[ArenaRegen] Block data in section " + sectionName + " at " + location + " is null, replacing with air.");
                blockData = getCachedBlockData("minecraft:air");
            }
            long key = BlockPos.pack(location.getBlockX(), location.getBlockY(), location.getBlockZ());
            sectionBlocks.put(key, blockData);
            if (world != null) {
                Material mat = blockData.getMaterial();
                if (isBannerMaterial(mat) || isSignMaterial(mat)) {
                    BlockState state = world.getBlockAt(location).getState();
                if (state instanceof Banner banner) {
                    Map<String, Object> bannerData = new HashMap<>();
                    DyeColor baseColor = banner.getBaseColor();
                    bannerData.put("baseColor", baseColor != null ? baseColor.name() : "NONE");
                    List<Map<String, String>> patternDataList = new ArrayList<>();
                    for (Pattern pattern : banner.getPatterns()) {
                        Map<String, String> patternData = new HashMap<>();
                        DyeColor color = pattern.getColor();
                        patternData.put("color", color.name());
                        String patternIdentifier = resolvePatternIdentifier(pattern);
                        patternData.put("type", patternIdentifier);
                        patternDataList.add(patternData);
                    }
                    bannerData.put("patterns", patternDataList);
                    PersistentDataContainer pdc = banner.getPersistentDataContainer();
                    if (!pdc.isEmpty()) {
                        Map<String, Object> pdcData = serializePdc(pdc);
                        bannerData.put("persistentData", pdcData);
                    }
                    bannerStates.put(location.clone(), bannerData);
                }
                if (state instanceof Sign sign) {
                    Map<String, Object> signData = new HashMap<>();
                    List<String> lines = new ArrayList<>();
                    for (int i = 0; i < 4; i++) {
                        lines.add(sign.getLine(i));
                    }
                    signData.put("lines", lines);
                    DyeColor color = sign.getColor();
                    signData.put("color", color != null ? color.name() : "BLACK");
                    signData.put("glowing", sign.isGlowingText());
                    PersistentDataContainer pdc = sign.getPersistentDataContainer();
                    if (!pdc.isEmpty()) {
                        Map<String, Object> pdcData = serializePdc(pdc);
                        signData.put("persistentData", pdcData);
                    }
                    signStates.put(location.clone(), signData);
                } else if (state instanceof BlockInventoryHolder holder) {
                    Inventory inv = holder.getInventory();
                    ItemStack[] contents = inv.getContents();
                    if (contents != null) {
                        ItemStack[] copy = new ItemStack[contents.length];
                        for (int i = 0; i < contents.length; i++) {
                            copy[i] = contents[i] != null ? contents[i].clone() : null;
                        }
                        containerStates.put(location.clone(), copy);
                    }
                }
            }
        }
        }
        sectionedBlockData.put(sectionName, sectionBlocks);
        synchronized (pristineBlockMap) {
            for (Long2ObjectMap.Entry<BlockData> entry : sectionBlocks.long2ObjectEntrySet()) {
                pristineBlockMap.put(entry.getLongKey(), entry.getValue());
            }
        }
    }

    private String resolvePatternIdentifier(Pattern pattern) {
        DyeColor color = pattern.getColor();
        Registry<PatternType> patternRegistry = Bukkit.getRegistry(PatternType.class);
        for (String identifier : KNOWN_PATTERN_IDENTIFIERS) {
            NamespacedKey key = new NamespacedKey("minecraft", identifier);
            PatternType patternType = patternRegistry.get(key);
            if (patternType != null) {
                Pattern testPattern = new Pattern(color, patternType);
                if (testPattern.toString().equals(pattern.toString())) {
                    return identifier;
                }
            }
        }
        LOGGER.warning("[ArenaRegen] Unknown pattern type for pattern: " + pattern.toString() + ", defaulting to 'base'.");
        return "base";
    }

    private Map<String, Object> serializePdc(PersistentDataContainer pdc) {
        Map<String, Object> pdcData = new HashMap<>();
        for (NamespacedKey key : pdc.getKeys()) {
            if (pdc.has(key, PersistentDataType.STRING)) {
                pdcData.put(key.toString(), pdc.get(key, PersistentDataType.STRING));
            } else if (pdc.has(key, PersistentDataType.INTEGER)) {
                pdcData.put(key.toString(), pdc.get(key, PersistentDataType.INTEGER));
            } else if (pdc.has(key, PersistentDataType.DOUBLE)) {
                pdcData.put(key.toString(), pdc.get(key, PersistentDataType.DOUBLE));
            } else if (pdc.has(key, PersistentDataType.BYTE)) {
                pdcData.put(key.toString(), pdc.get(key, PersistentDataType.BYTE));
            } else if (pdc.has(key, PersistentDataType.LONG)) {
                pdcData.put(key.toString(), pdc.get(key, PersistentDataType.LONG));
            } else {
                LOGGER.warning("[ArenaRegen] Unsupported PDC data type for key " + key + ", skipping.");
            }
        }
        return pdcData;
    }

    private void deserializePdc(PersistentDataContainer pdc, Map<String, Object> pdcData) {
        for (Map.Entry<String, Object> entry : pdcData.entrySet()) {
            try {
                NamespacedKey key = NamespacedKey.fromString(entry.getKey());
                if (key == null) {
                    LOGGER.warning("[ArenaRegen] Invalid NamespacedKey '" + entry.getKey() + "', skipping PDC entry.");
                    continue;
                }
                Object value = entry.getValue();
                if (value instanceof String) {
                    pdc.set(key, PersistentDataType.STRING, (String) value);
                } else if (value instanceof Integer) {
                    pdc.set(key, PersistentDataType.INTEGER, (Integer) value);
                } else if (value instanceof Double) {
                    pdc.set(key, PersistentDataType.DOUBLE, (Double) value);
                } else if (value instanceof Byte) {
                    pdc.set(key, PersistentDataType.BYTE, (Byte) value);
                } else if (value instanceof Long) {
                    pdc.set(key, PersistentDataType.LONG, (Long) value);
                } else {
                    LOGGER.warning("[ArenaRegen] Unsupported PDC value type for key " + key + ", skipping.");
                }
            } catch (Exception e) {
                LOGGER.warning("[ArenaRegen] Failed to deserialize PDC entry for key " + entry.getKey() + ": " + e.getMessage());
            }
        }
    }

    public void setMetadata(String creator, long creationDate, String world, String version, int minX, int minY, int minZ, int width, int height, int depth) {
        this.creator = creator;
        this.creationDate = creationDate;
        this.worldName = world;
        this.minecraftVersion = version;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.width = width;
        this.height = height;
        this.depth = depth;
        if (datcFile != null) {
            String regionName = datcFile.getName().replace(".datc", "");
            plugin.markRegionDirty(regionName);
        }
    }

    public void markBlockModified(Location location, BlockData newBlockData) {
        modifiedBlocks.put(location, newBlockData);
        if (datcFile != null) {
            String regionName = datcFile.getName().replace(".datc", "");
            plugin.markRegionDirty(regionName);
        }
    }

    public void clearModifiedBlocks() {
        modifiedBlocks.clear();
    }

    public Map<Location, BlockData> getModifiedBlocks() {
        return new HashMap<>(modifiedBlocks);
    }

    public void addEntity(Location location, Map<String, Object> serializedEntity) {
        entityDataMap.put(location, serializedEntity);
    }

    public Map<Location, Map<String, Object>> getEntityDataMap() {
        return new HashMap<>(entityDataMap);
    }

    public void clearEntities() {
        entityDataMap.clear();
    }

    public Map<Location, Map<String, Object>> getBannerStates() {
        return new HashMap<>(bannerStates);
    }

    public void clearBanners() {
        bannerStates.clear();
    }

    public Map<Location, Map<String, Object>> getSignStates() {
        return new HashMap<>(signStates);
    }

    public void clearSigns() {
        signStates.clear();
    }

    public Map<Location, ItemStack[]> getContainerStates() {
        return new HashMap<>(containerStates);
    }

    public void clearContainers() {
        containerStates.clear();
    }

    public void restoreContainerStates(World world) {
        if (containerStates.isEmpty() || world == null) return;
        for (Map.Entry<Location, ItemStack[]> entry : containerStates.entrySet()) {
            Location loc = entry.getKey();
            try {
                BlockState state = world.getBlockAt(loc).getState();
                Inventory inv = null;
                if (state instanceof Chest chest) {
                    inv = chest.getBlockInventory();
                } else if (state instanceof BlockInventoryHolder holder) {
                    inv = holder.getInventory();
                } else if (state instanceof ChiseledBookshelf shelf) {
                    inv = shelf.getInventory();
                }

                if (inv != null) {
                    ItemStack[] saved = entry.getValue();
                    inv.clear();
                    if (saved != null) {
                        int len = Math.min(inv.getSize(), saved.length);
                        for (int i = 0; i < len; i++) {
                            if (saved[i] != null && !saved[i].getType().isAir()) {
                                inv.setItem(i, saved[i].clone());
                            }
                        }
                    }
                }
            } catch (Exception e) {
                LOGGER.warning("[ArenaRegen] Failed to restore container at " + loc + ": " + e.getMessage());
            }
        }
    }

    public void clearRegion(String regionName) {
        sectionedBlockData.clear();
        entityDataMap.clear();
        modifiedBlocks.clear();
        bannerStates.clear();
        signStates.clear();
        containerStates.clear();
        deltaLedger.clear();
        plugin.getSpatialRegionIndex().unregisterRegion(this);
        plugin.getPendingDeletions().remove(regionName);
        plugin.getRegisteredRegions().remove(regionName);
        plugin.regeneratingArenas.remove(regionName);

        if (datcFile != null) {
            if (datcFile.exists()) {
                datcFile.delete();
            }
            File deltaFile = new File(datcFile.getParent(), datcFile.getName().replace(".datc", ".delta"));
            if (deltaFile.exists()) {
                deltaFile.delete();
            }
            File backupFile = new File(datcFile.getParent(), datcFile.getName() + ".bak");
            if (backupFile.exists()) {
                backupFile.delete();
            }
        }
    }

    private int calculateBufferSize() {
        long totalBlocks = sectionedBlockData.values().stream().mapToLong(Map::size).sum();
        long totalEntities = entityDataMap.size();
        long totalModifiedBlocks = modifiedBlocks.size();
        long totalBanners = bannerStates.size();
        long totalSigns = signStates.size();

        long estimatedSize = (totalBlocks * 42) + (totalEntities * 124) + (totalModifiedBlocks * 42) + (totalBanners * 128) + (totalSigns * 64) + 1024;

        
        if (estimatedSize < 500_000) {
            return 8192;   
        } else if (estimatedSize < 5_000_000) {
            return 65536;  
        } else {
            return 262144; 
        }
    }

    public CompletableFuture<Void> saveToDatc(File datcFile) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        long startTime = System.currentTimeMillis();

        File deltaFile = new File(datcFile.getParent(), datcFile.getName().replace(".datc", ".delta"));

        Map<String, Long2ObjectMap<BlockData>> sectionedBlockDataCopy = new ConcurrentHashMap<>(sectionedBlockData);
        Map<Location, Map<String, Object>> entityDataMapCopy = new ConcurrentHashMap<>(entityDataMap);
        Map<Location, Map<String, Object>> bannerStatesCopy = new ConcurrentHashMap<>(bannerStates);
        Map<Location, Map<String, Object>> signStatesCopy = new ConcurrentHashMap<>(signStates);
        Map<Location, ItemStack[]> containerStatesCopy = new ConcurrentHashMap<>(containerStates);
        Map<Location, BlockData> modifiedBlocksCopy = new ConcurrentHashMap<>(modifiedBlocks);

        int bufferSize = calculateBufferSize();
        int sectionCount = sectionedBlockDataCopy.size();
        long totalBlocks = sectionedBlockDataCopy.values().stream().mapToLong(Long2ObjectMap::size).sum();
        int entityCount = entityDataMapCopy.size();
        int bannerCount = bannerStatesCopy.size();
        int signCount = signStatesCopy.size();
        int modifiedCount = modifiedBlocksCopy.size();

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                deltaLedger.saveToFile(deltaFile);

                File backupFile = new File(datcFile.getParent(), datcFile.getName() + ".bak");
                if (datcFile.exists()) {
                    if (!datcFile.renameTo(backupFile)) {
                    }
                }

                try (FileOutputStream fos = new FileOutputStream(datcFile);
                     BufferedOutputStream bos = new BufferedOutputStream(fos, bufferSize);
                     GZIPOutputStream gzip = new GZIPOutputStream(bos) {{ def.setLevel(GZIP_COMPRESSION_LEVEL); }};
                     DataOutputStream dos = new DataOutputStream(gzip)) {

                    String header = fileFormatVersion + "," + creator + "," + creationDate + "," + worldName + "," +
                            minecraftVersion + "," + minX + "," + minY + "," + minZ + "," +
                            width + "," + height + "," + depth;
                    if (spawnLocation != null) {
                        header += "," + spawnLocation.getX() + "," + spawnLocation.getY() + "," + spawnLocation.getZ() +
                                "," + spawnLocation.getYaw() + "," + spawnLocation.getPitch();
                    } else {
                        header += ",0,0,0,0,0";
                    }
                    header += "," + locked;
                    dos.writeBytes(header);
                    dos.writeByte('\n');

                    writeSections(dos, sectionedBlockDataCopy);
                    writeEntities(dos, entityDataMapCopy);
                    writeBanners(dos, bannerStatesCopy);
                    writeSigns(dos, signStatesCopy);
                    writeContainers(dos, containerStatesCopy);
                    writeModifiedBlocks(dos, modifiedBlocksCopy);
                    dos.flush();

                } catch (IOException e) {
                    if (backupFile.exists()) {
                        if (datcFile.exists()) datcFile.delete();
                        backupFile.renameTo(datcFile);
                    }
                    future.completeExceptionally(e);
                    return;
                }

                long timeTaken = System.currentTimeMillis() - startTime;
                long fileSize = datcFile.length();
                LOGGER.info("[ArenaRegen] Saved RegionData to " + datcFile.getName() + " (" + (fileSize / 1024) + " KB, " + timeTaken + "ms). Delta size: " + deltaLedger.size() + " blocks.");

                future.complete(null);
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    private void writeSections(DataOutputStream dos, Map<String, Long2ObjectMap<BlockData>> sectionedBlockDataCopy) throws IOException {
        dos.writeInt(sectionedBlockDataCopy.size());
        for (Map.Entry<String, Long2ObjectMap<BlockData>> entry : sectionedBlockDataCopy.entrySet()) {
            String sectionName = entry.getKey();
            Long2ObjectMap<BlockData> blocks = entry.getValue();

            dos.writeUTF(sectionName);
            dos.writeInt(blocks.size());
            int batchSize = 1000;
            int count = 0;
            for (Long2ObjectMap.Entry<BlockData> blockEntry : blocks.long2ObjectEntrySet()) {
                long key = blockEntry.getLongKey();
                BlockData blockData = blockEntry.getValue();
                String blockDataStr = blockData != null ? blockData.getAsString() : "minecraft:air";
                dos.writeInt(BlockPos.unpackX(key));
                dos.writeInt(BlockPos.unpackY(key));
                dos.writeInt(BlockPos.unpackZ(key));
                dos.writeUTF(blockDataStr);
                count++;
                if (count % batchSize == 0) {
                    dos.flush();
                }
            }
            if (count % batchSize != 0) {
                dos.flush();
            }
        }
    }

    private static Object makeSerializable(Object obj) {
        if (obj instanceof org.bukkit.util.Vector vector) {
            return vector.serialize();
        } else if (obj instanceof Map<?, ?> map) {
            Map<Object, Object> newMap = new HashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                newMap.put(entry.getKey(), makeSerializable(entry.getValue()));
            }
            return newMap;
        } else if (obj instanceof List<?> list) {
            List<Object> newList = new ArrayList<>();
            for (Object item : list) {
                newList.add(makeSerializable(item));
            }
            return newList;
        } else if (obj == null || obj instanceof String || obj instanceof Number || obj instanceof Boolean) {
            return obj;
        } else if (obj instanceof java.io.Serializable) {
            return obj;
        } else {
            return null;
        }
    }

    private void writeEntities(DataOutputStream dos, Map<Location, Map<String, Object>> entityDataMapCopy) throws IOException {
        dos.writeInt(entityDataMapCopy.size());
        int batchSize = 100;
        int count = 0;
        for (Map.Entry<Location, Map<String, Object>> entry : entityDataMapCopy.entrySet()) {
            Location loc = entry.getKey();
            Map<String, Object> serializedEntity = entry.getValue();

            dos.writeDouble(loc.getX());
            dos.writeDouble(loc.getY());
            dos.writeDouble(loc.getZ());

            ByteArrayOutputStream entityStream = new ByteArrayOutputStream();
            byte[] entityDataBytes;
            Object safeEntity = makeSerializable(serializedEntity);
            try (ObjectOutputStream oos = new ObjectOutputStream(entityStream)) {
                if (safeEntity instanceof java.io.Serializable) {
                    oos.writeObject(safeEntity);
                } else {
                    oos.writeObject(new HashMap<>());
                }
                entityDataBytes = entityStream.toByteArray();
            } catch (IOException e) {
                LOGGER.warning("[ArenaRegen] Skipping non-serializable entity at " + loc + ": " + e.getMessage());
                entityStream = new ByteArrayOutputStream();
                try (ObjectOutputStream oos2 = new ObjectOutputStream(entityStream)) {
                    oos2.writeObject(new HashMap<>());
                    entityDataBytes = entityStream.toByteArray();
                } catch (IOException ex) {
                    entityDataBytes = new byte[0];
                }
            }
            dos.writeInt(entityDataBytes.length);
            dos.write(entityDataBytes);

            count++;
            if (count % batchSize == 0) {
                dos.flush();
            }
        }
        if (count % batchSize != 0) {
            dos.flush();
        }
    }

    private void writeBanners(DataOutputStream dos, Map<Location, Map<String, Object>> bannerStatesCopy) throws IOException {
        dos.writeInt(bannerStatesCopy.size());
        int batchSize = 100;
        int count = 0;
        for (Map.Entry<Location, Map<String, Object>> entry : bannerStatesCopy.entrySet()) {
            Location loc = entry.getKey();
            Map<String, Object> bannerData = entry.getValue();

            dos.writeDouble(loc.getX());
            dos.writeDouble(loc.getY());
            dos.writeDouble(loc.getZ());

            String baseColorStr = (String) bannerData.get("baseColor");
            DyeColor baseColor = baseColorStr.equals("NONE") ? null : DyeColor.valueOf(baseColorStr);
            dos.writeByte(baseColor != null ? (byte) baseColor.ordinal() : (byte) -1);

            List<Map<String, String>> patternDataList = (List<Map<String, String>>) bannerData.get("patterns");
            if (patternDataList != null && !patternDataList.isEmpty()) {
                dos.writeByte((byte) patternDataList.size());
                for (Map<String, String> patternData : patternDataList) {
                    String colorStr = patternData.get("color");
                    String typeStr = patternData.get("type");
                    try {
                        DyeColor color = DyeColor.valueOf(colorStr);
                        dos.writeByte((byte) color.ordinal());
                        dos.writeUTF(typeStr);
                    } catch (IllegalArgumentException e) {
                        LOGGER.warning("[ArenaRegen] Invalid pattern color " + colorStr + " at " + loc + ", skipping.");
                        dos.writeByte((byte) -1);
                        dos.writeUTF("");
                    }
                }
            } else {
                dos.writeByte((byte) 0);
            }

            Map<String, Object> pdcData = (Map<String, Object>) bannerData.get("persistentData");
            if (pdcData != null && !pdcData.isEmpty()) {
                dos.writeInt(pdcData.size());
                for (Map.Entry<String, Object> pdcEntry : pdcData.entrySet()) {
                    dos.writeUTF(pdcEntry.getKey());
                    Object value = pdcEntry.getValue();
                    if (value instanceof String) {
                        dos.writeByte(1);
                        dos.writeUTF((String) value);
                    } else if (value instanceof Integer) {
                        dos.writeByte(2);
                        dos.writeInt((Integer) value);
                    } else if (value instanceof Double) {
                        dos.writeByte(3);
                        dos.writeDouble((Double) value);
                    } else if (value instanceof Byte) {
                        dos.writeByte(4);
                        dos.writeByte((Byte) value);
                    } else if (value instanceof Long) {
                        dos.writeByte(5);
                        dos.writeLong((Long) value);
                    } else {
                        dos.writeByte(0);
                    }
                }
            } else {
                dos.writeInt(0);
            }

            count++;
            if (count % batchSize == 0) {
                dos.flush();
            }
        }
        if (count % batchSize != 0) {
            dos.flush();
        }
    }

    private void writeSigns(DataOutputStream dos, Map<Location, Map<String, Object>> signStatesCopy) throws IOException {
        dos.writeInt(signStatesCopy.size());
        int batchSize = 100;
        int count = 0;
        for (Map.Entry<Location, Map<String, Object>> entry : signStatesCopy.entrySet()) {
            Location loc = entry.getKey();
            Map<String, Object> signData = entry.getValue();

            dos.writeDouble(loc.getX());
            dos.writeDouble(loc.getY());
            dos.writeDouble(loc.getZ());

            List<String> lines = (List<String>) signData.get("lines");
            if (lines != null && !lines.isEmpty()) {
                dos.writeByte((byte) lines.size());
            for (String line : lines) {
                dos.writeUTF(line != null ? line : "");
            }
            } else {
                dos.writeByte((byte) 0);
            }

            String colorStr = (String) signData.get("color");
            DyeColor color = colorStr != null ? DyeColor.valueOf(colorStr) : DyeColor.BLACK;
            dos.writeByte((byte) color.ordinal());

            boolean glowing = (boolean) signData.getOrDefault("glowing", false);
            dos.writeBoolean(glowing);

            Map<String, Object> pdcData = (Map<String, Object>) signData.get("persistentData");
            if (pdcData != null && !pdcData.isEmpty()) {
                dos.writeInt(pdcData.size());
                for (Map.Entry<String, Object> pdcEntry : pdcData.entrySet()) {
                    dos.writeUTF(pdcEntry.getKey());
                    Object value = pdcEntry.getValue();
                    if (value instanceof String) {
                        dos.writeByte(1);
                        dos.writeUTF((String) value);
                    } else if (value instanceof Integer) {
                        dos.writeByte(2);
                        dos.writeInt((Integer) value);
                    } else if (value instanceof Double) {
                        dos.writeByte(3);
                        dos.writeDouble((Double) value);
                    } else if (value instanceof Byte) {
                        dos.writeByte(4);
                        dos.writeByte((Byte) value);
                    } else if (value instanceof Long) {
                        dos.writeByte(5);
                        dos.writeLong((Long) value);
                    } else {
                        dos.writeByte(0);
                    }
                }
            } else {
                dos.writeInt(0);
            }

            count++;
            if (count % batchSize == 0) {
                dos.flush();
            }
        }
        if (count % batchSize != 0) {
            dos.flush();
        }
    }

    private void writeModifiedBlocks(DataOutputStream dos, Map<Location, BlockData> modifiedBlocksCopy) throws IOException {
        dos.writeInt(modifiedBlocksCopy.size());
        int batchSize = 1000;
        int count = 0;
        for (Map.Entry<Location, BlockData> entry : modifiedBlocksCopy.entrySet()) {
            Location loc = entry.getKey();
            BlockData blockData = entry.getValue();
            String blockDataStr = blockData != null ? blockData.getAsString() : "minecraft:air";

            dos.writeInt(loc.getBlockX());
            dos.writeInt(loc.getBlockY());
            dos.writeInt(loc.getBlockZ());
            dos.writeUTF(blockDataStr);

            count++;
            if (count % batchSize == 0) {
                dos.flush();
            }
        }
        if (count % batchSize != 0) {
            dos.flush();
        }
    }

    private void writeContainers(DataOutputStream dos, Map<Location, ItemStack[]> containerStatesCopy) throws IOException {
        dos.writeInt(containerStatesCopy.size());
        int batchSize = 100;
        int count = 0;
        for (Map.Entry<Location, ItemStack[]> entry : containerStatesCopy.entrySet()) {
            Location loc = entry.getKey();
            ItemStack[] contents = entry.getValue();

            dos.writeDouble(loc.getX());
            dos.writeDouble(loc.getY());
            dos.writeDouble(loc.getZ());

            if (contents != null && contents.length > 0) {
                dos.writeInt(contents.length);
                for (ItemStack item : contents) {
                    if (item != null && !item.getType().isAir()) {
                        byte[] bytes = item.serializeAsBytes();
                        dos.writeInt(bytes.length);
                        dos.write(bytes);
                    } else {
                        dos.writeInt(0);
                    }
                }
            } else {
                dos.writeInt(0);
            }

            count++;
            if (count % batchSize == 0) {
                dos.flush();
            }
        }
        if (count % batchSize != 0) {
            dos.flush();
        }
    }

    public CompletableFuture<Void> loadFromDatc(File datcFile) {
        this.datcFile = datcFile;
        CompletableFuture<Void> future = new CompletableFuture<>();
        long startTime = System.currentTimeMillis();
        
        int bufferSize = datcFile.length() < 500_000 ? 8192 : (datcFile.length() < 5_000_000 ? 65536 : 262144);

        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                try (FileInputStream fis = new FileInputStream(datcFile);
                     BufferedInputStream bis = new BufferedInputStream(fis, bufferSize);
                     GZIPInputStream gzip = new GZIPInputStream(bis);
                     DataInputStream dis = new DataInputStream(gzip)) {

                    String header = readHeader(dis);
                    String[] headerParts = header.split(",");
                    if (headerParts.length < 11) {
                        throw new IOException("Invalid .datc file: Incomplete header");
                    }

                    String fileVersion = headerParts[0];
                    this.fileFormatVersion = fileVersion;
                    if (!fileVersion.equals(FILE_FORMAT_VERSION)) {
                        headerParts = migrateHeader(fileVersion, headerParts);
                    }

                    creator = headerParts[1];
                    creationDate = Long.parseLong(headerParts[2]);
                    worldName = headerParts[3];
                    minecraftVersion = headerParts[4];
                    minX = Integer.parseInt(headerParts[5]);
                    minY = Integer.parseInt(headerParts[6]);
                    minZ = Integer.parseInt(headerParts[7]);
                    width = Integer.parseInt(headerParts[8]);
                    height = Integer.parseInt(headerParts[9]);
                    depth = Integer.parseInt(headerParts[10]);

                    World world = Bukkit.getWorld(worldName);
                    if (world == null) {
                        LOGGER.warning("[ArenaRegen] World '" + worldName + "' not found for region in " + datcFile.getName() + ". Deferring block data loading.");
                        isBlockDataLoaded = false;
                        spawnLocation = null;
                        locked = false;
                        future.complete(null);
                        return;
                    }

                    if (headerParts.length >= 16) {
                        double spawnX = Double.parseDouble(headerParts[11]);
                        double spawnY = Double.parseDouble(headerParts[12]);
                        double spawnZ = Double.parseDouble(headerParts[13]);
                        float spawnYaw = Float.parseFloat(headerParts[14]);
                        float spawnPitch = Float.parseFloat(headerParts[15]);
                        if (spawnX == 0 && spawnY == 0 && spawnZ == 0 && spawnYaw == 0 && spawnPitch == 0) {
                            spawnLocation = null;
                        } else {
                            spawnLocation = new Location(world, spawnX, spawnY, spawnZ, spawnYaw, spawnPitch);
                        }
                        locked = Boolean.parseBoolean(headerParts[16]);
                    } else if (headerParts.length >= 11) {
                        double spawnX = headerParts.length > 11 ? Double.parseDouble(headerParts[11]) : 0;
                        double spawnY = headerParts.length > 12 ? Double.parseDouble(headerParts[12]) : 0;
                        double spawnZ = headerParts.length > 13 ? Double.parseDouble(headerParts[13]) : 0;
                        float spawnYaw = headerParts.length > 14 ? Float.parseFloat(headerParts[14]) : 0;
                        float spawnPitch = headerParts.length > 15 ? Float.parseFloat(headerParts[15]) : 0;
                        if (spawnX == 0 && spawnY == 0 && spawnZ == 0 && spawnYaw == 0 && spawnPitch == 0) {
                            spawnLocation = null;
                        } else {
                            spawnLocation = new Location(world, spawnX, spawnY, spawnZ, spawnYaw, spawnPitch);
                        }
                        locked = false;
                    } else {
                        spawnLocation = null;
                        locked = false;
                    }

                    File deltaFile = new File(datcFile.getParent(), datcFile.getName().replace(".datc", ".delta"));
                    deltaLedger.loadFromFile(deltaFile);

                    readSections(dis, world);
                    readEntities(dis, world);
                    readBanners(dis, world, fileVersion);
                    readSigns(dis, world, fileVersion);
                    readContainers(dis, world, fileVersion);
                    readModifiedBlocks(dis, world);
                    isBlockDataLoaded = true;

                    long timeTaken = System.currentTimeMillis() - startTime;
                    long fileSize = datcFile.length();
                    LOGGER.info("[ArenaRegen] Loaded metadata, sections & delta for " + datcFile.getName() + " (" + (fileSize / 1024) + " KB, " + timeTaken + "ms). Delta size: " + deltaLedger.size() + " blocks.");

                    future.complete(null);
                }
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });

        return future;
    }

    private String readHeader(DataInputStream dis) throws IOException {
        StringBuilder headerBuilder = new StringBuilder();
        int b;
        while ((b = dis.read()) != -1 && b != '\n') {
            headerBuilder.append((char) b);
        }
        if (b == -1) throw new IOException("Invalid .datc file: No header section found");
        return headerBuilder.toString();
    }

    private String[] migrateHeader(String fileVersion, String[] headerParts) throws IOException {
        if (fileVersion.equals("1") || fileVersion.equals("2") || fileVersion.equals("3")) {
            LOGGER.info("[ArenaRegen] Migrating file format version '" + fileVersion + "' to version " + FILE_FORMAT_VERSION + ".");
            String[] newHeader = new String[17];
            System.arraycopy(headerParts, 0, newHeader, 0, Math.min(headerParts.length, 11));
            for (int i = headerParts.length; i < 11; i++) {
                newHeader[i] = "0";
            }
            if (headerParts.length < 16) {
                newHeader[11] = "0";
                newHeader[12] = "0";
                newHeader[13] = "0";
                newHeader[14] = "0";
                newHeader[15] = "0";
            } else {
                System.arraycopy(headerParts, 11, newHeader, 11, 5);
            }
            newHeader[16] = "false";
            newHeader[0] = FILE_FORMAT_VERSION;
            return newHeader;
        }
        throw new IOException("Unsupported .datc file version: " + fileVersion);
    }

    private void readSections(DataInputStream dis, World world) throws IOException {
        int sectionCount = dis.readInt();
        sectionedBlockData.clear();
        synchronized (pristineBlockMap) {
            pristineBlockMap.clear();
            for (int i = 0; i < sectionCount; i++) {
                String sectionName = dis.readUTF();
                int blockCount = dis.readInt();
                Long2ObjectMap<BlockData> blocks = new Long2ObjectOpenHashMap<>(blockCount);

                for (int j = 0; j < blockCount; j++) {
                    int x = dis.readInt();
                    int y = dis.readInt();
                    int z = dis.readInt();
                    String blockDataStr = dis.readUTF();
                    long key = BlockPos.pack(x, y, z);

                    try {
                        BlockData blockData = getCachedBlockData(blockDataStr);
                        blocks.put(key, blockData);
                        pristineBlockMap.put(key, blockData);
                    } catch (IllegalArgumentException e) {
                        LOGGER.warning("[ArenaRegen] Invalid block data '" + blockDataStr + "' at (" + x + "," + y + "," + z + ") in section " + sectionName + ": " + e.getMessage() + ", replacing with air.");
                        BlockData air = getCachedBlockData("minecraft:air");
                        blocks.put(key, air);
                        pristineBlockMap.put(key, air);
                    }
                }
                sectionedBlockData.put(sectionName, blocks);
            }
        }
    }

    public synchronized BlockData getPristineBlockData(int x, int y, int z) {
        if (pristineBlockMap.isEmpty()) {
            return null;
        }
        return pristineBlockMap.get(BlockPos.pack(x, y, z));
    }

    private void readEntities(DataInputStream dis, World world) throws IOException {
        int entityCount = dis.readInt();
        entityDataMap.clear();
        for (int i = 0; i < entityCount; i++) {
            double x = dis.readDouble();
            double y = dis.readDouble();
            double z = dis.readDouble();
            int entityDataLength = dis.readInt();
            byte[] entityDataBytes = new byte[entityDataLength];
            dis.readFully(entityDataBytes);

            Location loc = new Location(world, x, y, z);
            try {
                ByteArrayInputStream entityStream = new ByteArrayInputStream(entityDataBytes);
                try (ObjectInputStream ois = new ObjectInputStream(entityStream)) {
                    Map<String, Object> serializedEntity = (Map<String, Object>) ois.readObject();
                    entityDataMap.put(loc, serializedEntity);
                }
            } catch (Exception e) {
                LOGGER.warning("[ArenaRegen] Failed to deserialize entity data at " + loc + ": " + e.getMessage() + ", skipping.");
            }
        }
    }

    private void readBanners(DataInputStream dis, World world, String fileVersion) throws IOException {
        bannerStates.clear();
        int bannerCount = dis.readInt();
        for (int i = 0; i < bannerCount; i++) {
            double x = dis.readDouble();
            double y = dis.readDouble();
            double z = dis.readDouble();
            Location loc = new Location(world, x, y, z);
            Map<String, Object> bannerData = new HashMap<>();

            if (!fileVersion.equals(FILE_FORMAT_VERSION)) {
                if (bannerCount > 0) {
                    dis.readByte();
                    byte patternCount = dis.readByte();
                    for (int j = 0; j < patternCount; j++) {
                        dis.readByte();
                        dis.readUTF();
                    }
                    int pdcSize = dis.readInt();
                    for (int j = 0; j < pdcSize; j++) {
                        dis.readUTF();
                        byte type = dis.readByte();
                        if (type == 1) dis.readUTF();
                        else if (type == 2) dis.readInt();
                        else if (type == 3) dis.readDouble();
                        else if (type == 4) dis.readByte();
                        else if (type == 5) dis.readLong();
                    }
                }
                continue;
            }

            byte baseColorOrdinal = dis.readByte();
            DyeColor baseColor = null;
            try {
                baseColor = baseColorOrdinal != -1 ? DyeColor.values()[baseColorOrdinal] : null;
            } catch (ArrayIndexOutOfBoundsException e) {
                LOGGER.warning("[ArenaRegen] Invalid base color ordinal " + baseColorOrdinal + " at " + loc + ", defaulting to null.");
            }
            bannerData.put("baseColor", baseColor != null ? baseColor.name() : "NONE");

            byte patternCount = dis.readByte();
            List<Map<String, String>> patternDataList = new ArrayList<>();
            for (int j = 0; j < patternCount; j++) {
                byte colorOrdinal = dis.readByte();
                String typeStr = dis.readUTF();
                try {
                    DyeColor color = DyeColor.values()[colorOrdinal];
                    String validTypeStr = KNOWN_PATTERN_IDENTIFIERS.contains(typeStr) ? typeStr : "base";
                    patternDataList.add(Map.of("color", color.name(), "type", validTypeStr));
                } catch (ArrayIndexOutOfBoundsException e) {
                    LOGGER.warning("[ArenaRegen] Invalid color ordinal " + colorOrdinal + " for pattern at " + loc + ", skipping.");
                }
            }
            bannerData.put("patterns", patternDataList);

            int pdcSize = dis.readInt();
            if (pdcSize > 0) {
                Map<String, Object> pdcData = new HashMap<>();
                for (int j = 0; j < pdcSize; j++) {
                    String key = dis.readUTF();
                    byte type = dis.readByte();
                    try {
                        switch (type) {
                            case 1:
                                pdcData.put(key, dis.readUTF());
                                break;
                            case 2:
                                pdcData.put(key, dis.readInt());
                                break;
                            case 3:
                                pdcData.put(key, dis.readDouble());
                                break;
                            case 4:
                                pdcData.put(key, dis.readByte());
                                break;
                            case 5:
                                pdcData.put(key, dis.readLong());
                                break;
                            default:
                                LOGGER.warning("[ArenaRegen] Unknown PDC type " + type + " for key " + key + " at " + loc + ", skipping.");
                        }
                    } catch (Exception e) {
                        LOGGER.warning("[ArenaRegen] Failed to read PDC entry for key " + key + " at " + loc + ": " + e.getMessage());
                    }
                }
                bannerData.put("persistentData", pdcData);
                try {
                    BlockState state = world.getBlockAt(loc).getState();
                    if (state instanceof Banner) {
                        Banner banner = (Banner) state;
                        deserializePdc(banner.getPersistentDataContainer(), pdcData);
                    } else {
                        LOGGER.warning("[ArenaRegen] Block at " + loc + " is not a banner, cannot apply PDC.");
                    }
                } catch (Exception e) {
                    LOGGER.warning("[ArenaRegen] Failed to apply PDC for banner at " + loc + ": " + e.getMessage());
                }
            }

            bannerStates.put(loc, bannerData);
        }
    }

    private void readSigns(DataInputStream dis, World world, String fileVersion) throws IOException {
        signStates.clear();
        int signCount = dis.readInt();
        for (int i = 0; i < signCount; i++) {
            double x = dis.readDouble();
            double y = dis.readDouble();
            double z = dis.readDouble();
            Location loc = new Location(world, x, y, z);
            Map<String, Object> signData = new HashMap<>();

            if (!fileVersion.equals(FILE_FORMAT_VERSION)) {
                if (signCount > 0) {
                    byte lineCount = dis.readByte();
                    for (int j = 0; j < lineCount; j++) {
                        dis.readUTF();
                    }
                    dis.readByte();
                    dis.readBoolean();
                    int pdcSize = dis.readInt();
                    for (int j = 0; j < pdcSize; j++) {
                        dis.readUTF();
                        byte type = dis.readByte();
                        if (type == 1) dis.readUTF();
                        else if (type == 2) dis.readInt();
                        else if (type == 3) dis.readDouble();
                        else if (type == 4) dis.readByte();
                        else if (type == 5) dis.readLong();
                    }
                }
                continue;
            }

            byte lineCount = dis.readByte();
            List<String> lines = new ArrayList<>();
            for (int j = 0; j < lineCount && j < 4; j++) {
                lines.add(dis.readUTF());
            }
            while (lines.size() < 4) {
                lines.add("");
            }
            signData.put("lines", lines);

            byte colorOrdinal = dis.readByte();
            DyeColor color = null;
            try {
                color = DyeColor.values()[colorOrdinal];
            } catch (ArrayIndexOutOfBoundsException e) {
                LOGGER.warning("[ArenaRegen] Invalid color ordinal " + colorOrdinal + " at " + loc + ", defaulting to BLACK.");
                color = DyeColor.BLACK;
            }
            signData.put("color", color.name());

            boolean glowing = dis.readBoolean();
            signData.put("glowing", glowing);

            int pdcSize = dis.readInt();
            if (pdcSize > 0) {
                Map<String, Object> pdcData = new HashMap<>();
                for (int j = 0; j < pdcSize; j++) {
                    String key = dis.readUTF();
                    byte type = dis.readByte();
                    try {
                        switch (type) {
                            case 1:
                                pdcData.put(key, dis.readUTF());
                                break;
                            case 2:
                                pdcData.put(key, dis.readInt());
                                break;
                            case 3:
                                pdcData.put(key, dis.readDouble());
                                break;
                            case 4:
                                pdcData.put(key, dis.readByte());
                                break;
                            case 5:
                                pdcData.put(key, dis.readLong());
                                break;
                            default:
                                LOGGER.warning("[ArenaRegen] Unknown PDC type " + type + " for key " + key + " at " + loc + ", skipping.");
                        }
                    } catch (Exception e) {
                        LOGGER.warning("[ArenaRegen] Failed to read PDC entry for key " + key + " at " + loc + ": " + e.getMessage());
                    }
                }
                signData.put("persistentData", pdcData);
                try {
                    BlockState state = world.getBlockAt(loc).getState();
                    if (state instanceof Sign) {
                        Sign sign = (Sign) state;
                        deserializePdc(sign.getPersistentDataContainer(), pdcData);
                    } else {
                        LOGGER.warning("[ArenaRegen] Block at " + loc + " is not a sign, cannot apply PDC.");
                    }
                } catch (Exception e) {
                    LOGGER.warning("[ArenaRegen] Failed to apply PDC for sign at " + loc + ": " + e.getMessage());
                }
            }
            signStates.put(loc, signData);
        }
    }

    private void readContainers(DataInputStream dis, World world, String fileVersion) throws IOException {
        containerStates.clear();
        if (dis.available() <= 0) return;
        int containerCount = dis.readInt();
        for (int i = 0; i < containerCount; i++) {
            double x = dis.readDouble();
            double y = dis.readDouble();
            double z = dis.readDouble();
            Location loc = new Location(world, x, y, z);
            int slotCount = dis.readInt();
            ItemStack[] contents = new ItemStack[slotCount];
            for (int j = 0; j < slotCount; j++) {
                int byteLen = dis.readInt();
                if (byteLen > 0) {
                    byte[] bytes = new byte[byteLen];
                    dis.readFully(bytes);
                    try {
                        contents[j] = ItemStack.deserializeBytes(bytes);
                    } catch (Exception e) {
                        LOGGER.warning("[ArenaRegen] Failed to deserialize item at slot " + j + " for container at " + loc + ": " + e.getMessage());
                        contents[j] = null;
                    }
                } else {
                    contents[j] = null;
                }
            }
            containerStates.put(loc, contents);
        }
    }

    private void readModifiedBlocks(DataInputStream dis, World world) throws IOException {
        int modifiedCount = dis.readInt();
        modifiedBlocks.clear();
        for (int i = 0; i < modifiedCount; i++) {
            int x = dis.readInt();
            int y = dis.readInt();
            int z = dis.readInt();
            String blockDataStr = dis.readUTF();

            Location loc = new Location(world, x, y, z);
            try {
                
                BlockData blockData = getCachedBlockData(blockDataStr);
                modifiedBlocks.put(loc, blockData);
            } catch (IllegalArgumentException e) {
                LOGGER.warning("[ArenaRegen] Invalid block data '" + blockDataStr + "' for modified block at " + loc + ": " + e.getMessage() + ", skipping.");
            }
        }
    }

    public synchronized CompletableFuture<Void> ensureBlockDataLoaded() {
        if (isBlockDataLoaded && !sectionedBlockData.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        if (blockDataLoadFuture != null && !blockDataLoadFuture.isDone()) {
            return blockDataLoadFuture;
        }

        blockDataLoadFuture = CompletableFuture.runAsync(() -> {
            if (datcFile == null || !datcFile.exists()) {
                throw new RuntimeException("Datc file does not exist: " + (datcFile != null ? datcFile.getAbsolutePath() : "null"));
            }
            World world = Bukkit.getWorld(worldName);
            if (world == null) {
                throw new RuntimeException("World '" + worldName + "' not found for region in " + datcFile.getName());
            }

            try (FileInputStream fis = new FileInputStream(datcFile);
                 BufferedInputStream bis = new BufferedInputStream(fis, 65536);
                 GZIPInputStream gzip = new GZIPInputStream(bis);
                 DataInputStream dis = new DataInputStream(gzip)) {

                String header = readHeader(dis);
                String[] headerParts = header.split(",");
                String fileVersion = headerParts.length > 0 ? headerParts[0] : FILE_FORMAT_VERSION;

                readSections(dis, world);
                readEntities(dis, world);
                readBanners(dis, world, fileVersion);
                readSigns(dis, world, fileVersion);
                readContainers(dis, world, fileVersion);
                readModifiedBlocks(dis, world);

                isBlockDataLoaded = true;
            } catch (Exception e) {
                LOGGER.severe("[ArenaRegen] Failed to load full section block data for " + datcFile.getName() + ": " + e.getMessage());
                throw new RuntimeException("Failed to read block section data from " + datcFile.getName(), e);
            }
        }).whenComplete((v, ex) -> {
            if (ex != null) {
                blockDataLoadFuture = null;
            }
        });

        return blockDataLoadFuture;
    }

    public void clearBlockData() {
        sectionedBlockData.clear();
        synchronized (pristineBlockMap) {
            pristineBlockMap.clear();
        }
        entityDataMap.clear();
        bannerStates.clear();
        signStates.clear();
        modifiedBlocks.clear();
        isBlockDataLoaded = false;
    }

    public CompletableFuture<Map<String, Long2ObjectMap<BlockData>>> getSectionedBlockData() {
        return ensureBlockDataLoaded().thenApply(v -> sectionedBlockData);
    }

    public CompletableFuture<Map<Location, BlockData>> getAllBlocks() {
        return ensureBlockDataLoaded().thenApply(v -> {
            Map<Location, BlockData> allBlocks = new ConcurrentHashMap<>();
            World world = Bukkit.getWorld(worldName);
            for (Map.Entry<String, Long2ObjectMap<BlockData>> entry : sectionedBlockData.entrySet()) {
                for (Long2ObjectMap.Entry<BlockData> blockEntry : entry.getValue().long2ObjectEntrySet()) {
                    long key = blockEntry.getLongKey();
                    int x = BlockPos.unpackX(key);
                    int y = BlockPos.unpackY(key);
                    int z = BlockPos.unpackZ(key);
                    if (world != null) {
                        allBlocks.put(new Location(world, x, y, z), blockEntry.getValue());
                    }
                }
            }
            return allBlocks;
        });
    }

    public boolean isLocked() {
        return locked;
    }

    public String getCreator() { return creator; }
    public long getCreationDate() { return creationDate; }
    public String getWorldName() { return worldName; }
    public String getMinecraftVersion() { return minecraftVersion; }
    public String getFileFormatVersion() { return fileFormatVersion; }
    public int getMinX() { return minX; }
    public int getMinY() { return minY; }
    public int getMinZ() { return minZ; }
    public int getMaxX() { return minX + width - 1; }
    public int getMaxY() { return minY + height - 1; }
    public int getMaxZ() { return minZ + depth - 1; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int getDepth() { return depth; }
    public File getDatcFile() { return datcFile; }
    public long getArea() {
        return (long) width * height * depth;
    }

    public boolean isBlockDataLoaded() {
        return isBlockDataLoaded;
    }
    
    public void setBlockDataLoaded(boolean loaded) {
        this.isBlockDataLoaded = loaded;
    }

    public DeltaLedger getDeltaLedger() {
        return deltaLedger;
    }

    public boolean containsLocation(World world, int x, int y, int z) {
        if (worldName == null || world == null || !worldName.equals(world.getName())) {
            return false;
        }
        return x >= minX && x <= getMaxX()
            && y >= minY && y <= getMaxY()
            && z >= minZ && z <= getMaxZ();
    }
}