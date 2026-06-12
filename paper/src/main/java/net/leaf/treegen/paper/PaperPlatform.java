package net.leaf.treegen.paper;

import net.leaf.treegen.common.Platform;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.structure.Mirror;
import org.bukkit.block.structure.StructureRotation;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.structure.Structure;
import java.io.File;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class PaperPlatform implements Platform {
    private final JavaPlugin plugin;

    private final Map<String, org.bukkit.block.data.BlockData> blockDataCache = new ConcurrentHashMap<>();

    private long totalBlocksPlaced = 0;
    private long totalTimeSpentNanos = 0;
    private long totalBatches = 0;
    private long budgetExceededCount = 0;

    public PaperPlatform(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    private org.bukkit.block.data.BlockData getCachedBlockData(String data) {
        return blockDataCache.computeIfAbsent(data, k -> {
            org.bukkit.block.data.BlockData bd = Bukkit.createBlockData(k);
            if (bd instanceof org.bukkit.block.data.type.Leaves leaves) {
                if (!k.contains("persistent")) {
                    leaves.setPersistent(false);
                }
                if (!k.contains("distance")) {
                    leaves.setDistance(1);
                }
            }
            return bd;
        });
    }

    @Override public Logger logger() { return plugin.getLogger(); }
    @Override public Path getRootFolder() { return plugin.getDataFolder().toPath(); }
    @Override public Path getWorldFolder(String worldName) { 
        World world = Bukkit.getWorld(worldName);
        if (world != null) return resolveLevelFolder(world.getWorldFolder().toPath());
        
        // Fallback for onLoad when worlds aren't registered yet
        File folder = new File(worldName);
        if (folder.exists() && folder.isDirectory()) {
            return folder.toPath();
        }
        return null;
    }

    /**
     * Resolves the level (base) folder that owns the {@code datapacks} directory.
     *
     * <p>On regular Paper the overworld's {@link World#getWorldFolder()} already
     * points at the level folder (e.g. {@code ./world}), which is where the
     * worldgen datapacks are written. On Folia the world is regionised and
     * {@code getWorldFolder()} returns a dimension subfolder nested under the
     * level folder (e.g. {@code world/dimensions/minecraft/overworld}), so the
     * {@code datapacks} directory lives in a parent directory. Walk up a few
     * levels to find the folder that actually contains {@code datapacks};
     * fall back to the original folder when none is found.</p>
     */
    private static Path resolveLevelFolder(Path worldFolder) {
        Path current = worldFolder;
        for (int depth = 0; depth < 8 && current != null; depth++) {
            if (new File(current.toFile(), "datapacks").isDirectory()) {
                return current;
            }
            current = current.getParent();
        }
        return worldFolder;
    }
    @Override public List<String> getWorldNames() { 
        return Bukkit.getWorlds().stream().map(World::getName).toList(); 
    }
    @Override public void scheduleRepeatingTask(Runnable task, long delayTicks, long periodTicks) {
        Bukkit.getGlobalRegionScheduler().runAtFixedRate(plugin, t -> task.run(), delayTicks, periodTicks);
    }
    @Override public String getBiomeKey(String worldName, int x, int y, int z) {
        World world = Bukkit.getWorld(worldName);
        return world != null ? world.getBiome(x, y, z).getKey().toString() : "";
    }

    /** Chunk PDC key holding the encoded tree-region list for that chunk. */
    private static final NamespacedKey TREE_REGIONS = new NamespacedKey("leaf-treegen", "tree_regions");

    @Override public String getChunkData(String worldName, int chunkX, int chunkZ) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) return null;
        return world.getChunkAt(chunkX, chunkZ).getPersistentDataContainer()
                .get(TREE_REGIONS, org.bukkit.persistence.PersistentDataType.STRING);
    }

    @Override public void setChunkData(String worldName, int chunkX, int chunkZ, String data) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) return;
        org.bukkit.persistence.PersistentDataContainer pdc =
                world.getChunkAt(chunkX, chunkZ).getPersistentDataContainer();
        if (data == null || data.isEmpty()) {
            pdc.remove(TREE_REGIONS);
        } else {
            pdc.set(TREE_REGIONS, org.bukkit.persistence.PersistentDataType.STRING, data);
        }
    }
    @Override public boolean placeStructure(String worldName, int x, int y, int z, String structureKey) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) return false;
        NamespacedKey key = NamespacedKey.fromString(structureKey);
        if (key == null) return false;
        
        Structure s = Bukkit.getStructureManager().getStructure(key);
        if (s == null) {
            try { s = Bukkit.getStructureManager().loadStructure(key); }
            catch (Exception ignored) {}
        }
        if (s == null) return false;

        Location loc = new Location(world, x, y, z);
        // Centre the structure on the clicked block. The structure grows in +X/+Z from its
        // origin corner, so subtracting half the (un-rotated) footprint lines the trunk up with
        // the target column. We place with StructureRotation.NONE: a random rotation would swap
        // the X/Z footprint and pivot the structure around the origin, landing the tree off-centre
        // (the Fabric/NeoForge platforms likewise place un-rotated). Variety still comes from the
        // multiple baked template variants selected per placement.
        int offsetX = s.getSize().getBlockX() / 2;
        int offsetZ = s.getSize().getBlockZ() / 2;
        Location origin = loc.clone().subtract(offsetX, 0, offsetZ);

        try {
            s.place(origin, true, StructureRotation.NONE, Mirror.NONE, 0, 1.0f, new java.util.Random());
            
            // Post-process leaves if needed to set persistent=false
            // Bukkit's Structure API doesn't easily allow intercepting block data during placement 
            // without NMS or complex listeners. 
            // However, most NBT structures for trees ALREADY have persistent=false if they were saved from natural trees.
            // If they were saved from manual placement, they might have persistent=true.
            
            return true;
        } catch (Exception ex) {
            logger().warning("Failed to place " + structureKey + ": " + ex.getMessage());
            return false;
        }
    }

    @Override public void giveSapling(UUID playerId, String speciesId, int amount) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) return;
        
        net.leaf.treegen.common.TreeSpecies species = ((PaperLeafTreeGenPlugin)plugin).config().get(speciesId);
        if (species == null) return;

        player.getInventory().addItem(PaperSaplingItem.create(species, amount));
    }

    @Override public boolean setBlock(String worldName, int x, int y, int z, String blockData) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) return false;
        try {
            world.getBlockAt(x, y, z).setBlockData(getCachedBlockData(blockData), false);
            return true;
        } catch (Exception ex) {
            logger().warning("Failed to set block " + blockData + " at " + x + "," + y + "," + z + ": " + ex.getMessage());
            return false;
        }
    }

    @Override public boolean setBlocks(String worldName, int chunkX, int chunkZ, java.util.Map<net.leaf.treegen.common.TreeModel.BlockPos, String> blocks) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) return false;
        
        // On Folia, we must ensure we are on the correct thread for the chunk.
        if (Bukkit.isOwnedByCurrentRegion(world, chunkX, chunkZ)) {
            placeBlocksThrottled(world, chunkX, chunkZ, blocks);
            return true;
        } else {
            // Conditionally schedule based on chunk coordinate on the folia scheduler
            Bukkit.getRegionScheduler().run(plugin, world, chunkX, chunkZ, task -> {
                placeBlocksThrottled(world, chunkX, chunkZ, blocks);
            });
            return true;
        }
    }

    private void placeBlocksThrottled(World world, int chunkX, int chunkZ, java.util.Map<net.leaf.treegen.common.TreeModel.BlockPos, String> blocks) {
        if (blocks.isEmpty()) return;

        double allocationMs = ((PaperLeafTreeGenPlugin) plugin).config().timeAllocationMs();
        int maxBlocksPerTick = ((PaperLeafTreeGenPlugin) plugin).config().maxBlocksPerTick();
        List<java.util.Map.Entry<net.leaf.treegen.common.TreeModel.BlockPos, String>> entries = new java.util.ArrayList<>(blocks.entrySet());
        placeNextBatch(world, chunkX, chunkZ, entries, 0, allocationMs, maxBlocksPerTick);
    }

    private void placeNextBatch(World world, int chunkX, int chunkZ, List<java.util.Map.Entry<net.leaf.treegen.common.TreeModel.BlockPos, String>> entries, int start, double allocationMs, int maxBlocksPerTick) {
        long startNanos = System.nanoTime();
        long budgetNanos = (long) (allocationMs * 1_000_000.0);
        int i = start;
        int placedThisTick = 0;
        
        org.bukkit.Chunk chunk = world.getChunkAt(chunkX, chunkZ);
        
        // Use a block state list to minimize individual update calls if possible.
        // Paper/Folia: Chunk interface might have specialized bulk update methods
        // but often the most stable multi-block way without NMS is BlockState batching.
        java.util.List<org.bukkit.block.BlockState> statesToUpdate = new java.util.ArrayList<>();
        
        try {
            while (i < entries.size()) {
                java.util.Map.Entry<net.leaf.treegen.common.TreeModel.BlockPos, String> entry = entries.get(i);
                net.leaf.treegen.common.TreeModel.BlockPos pos = entry.getKey();
                String data = entry.getValue();

                // Never write plain air into the world. Placing air (or cave_air)
                // carved square holes around trees and let stray model gaps stamp
                // out existing terrain. Only void_air is allowed through, since it
                // is used deliberately to clear space (e.g. lava-tree cones).
                String baseBlock = data.split("\\[")[0].toLowerCase(java.util.Locale.ROOT);
                if (baseBlock.equals("minecraft:air") || baseBlock.equals("minecraft:cave_air")) {
                    i++;
                    continue;
                }

                org.bukkit.block.data.BlockData bd = getCachedBlockData(data);
                
                // Fetch BlockState once, update data, add to list
                org.bukkit.block.BlockState state = chunk.getBlock(pos.x() & 0xF, pos.y(), pos.z() & 0xF).getState();
                state.setBlockData(bd);
                statesToUpdate.add(state);
                
                i++;
                placedThisTick++;
                
                if (placedThisTick >= maxBlocksPerTick) {
                    break;
                }

                if (i % 10 == 0 && (System.nanoTime() - startNanos) >= budgetNanos) {
                    budgetExceededCount++;
                    break;
                }
            }

            // Perform a multi-block update if the API supports a better way, 
            // otherwise iterate and update.
            // Note: world.setBlockData is usually faster for bulk if we can avoid the Block object.
            for (org.bukkit.block.BlockState state : statesToUpdate) {
                state.update(true, false);
            }

            long endNanos = System.nanoTime();
            long tickTime = endNanos - startNanos;
            totalTimeSpentNanos += tickTime;
            totalBlocksPlaced += placedThisTick;
            totalBatches++;

            if (tickTime > 1_000_000) {
                logger().info(String.format("[LeafTreeGen Profiling] Chunk %d,%d batch: %.2fms, Blocks: %d, Progress: %d/%d",
                    chunkX, chunkZ, tickTime / 1_000_000.0, placedThisTick, i, entries.size()));
            }
        } catch (Exception ex) {
            logger().warning("Failed to set batch of blocks in chunk " + chunkX + "," + chunkZ + " at index " + i + ": " + ex.getMessage());
            return;
        }

        if (i < entries.size()) {
            final int nextStart = i;
            Bukkit.getRegionScheduler().runDelayed(plugin, world, chunkX, chunkZ, task -> {
                placeNextBatch(world, chunkX, chunkZ, entries, nextStart, allocationMs, maxBlocksPerTick);
            }, 1L);
        }
    }

    private boolean placeBlocksInternal(World world, int chunkX, int chunkZ, java.util.Map<net.leaf.treegen.common.TreeModel.BlockPos, String> blocks) {
        try {
            blocks.forEach((pos, data) -> {
                world.getBlockAt(pos.x(), pos.y(), pos.z()).setBlockData(Bukkit.createBlockData(data), false);
            });
            return true;
        } catch (Exception ex) {
            logger().warning("Failed to set batch of " + blocks.size() + " blocks in chunk " + chunkX + "," + chunkZ + ": " + ex.getMessage());
            return false;
        }
    }

    @Override public void sendMessage(UUID playerId, String message, boolean error) {
        if (playerId.equals(new UUID(0, 0))) {
            Bukkit.getConsoleSender().sendMessage(message);
        } else {
            Player p = Bukkit.getPlayer(playerId);
            if (p != null) p.sendMessage(message);
        }
    }

    @Override public int getDataVersion() {
        try {
            return Bukkit.getUnsafe().getDataVersion();
        } catch (Throwable t) {
            return Platform.super.getDataVersion();
        }
    }

    public long getTotalBlocksPlaced() { return totalBlocksPlaced; }
    public long getTotalTimeSpentNanos() { return totalTimeSpentNanos; }
    public long getTotalBatches() { return totalBatches; }
    public long getBudgetExceededCount() { return budgetExceededCount; }

    public void resetStats() {
        totalBlocksPlaced = 0;
        totalTimeSpentNanos = 0;
        totalBatches = 0;
        budgetExceededCount = 0;
    }
}
