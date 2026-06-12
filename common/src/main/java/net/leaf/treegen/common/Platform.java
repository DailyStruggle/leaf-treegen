package net.leaf.treegen.common;

import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

/**
 * Platform abstraction layer for LeafTreeGen.
 */
public interface Platform {
    Logger logger();
    Path getRootFolder();
    
    /** Gets the root folder for a world (e.g. for datapack generation). */
    Path getWorldFolder(String worldName);
    
    /** List of currently loaded world names. */
    List<String> getWorldNames();

    /** Schedules a repeating task. */
    void scheduleRepeatingTask(Runnable task, long delayTicks, long periodTicks);

    /** Resolves a biome key at the given location in the specified world. */
    String getBiomeKey(String worldName, int x, int y, int z);

    /** Places a structure at the given location. */
    boolean placeStructure(String worldName, int x, int y, int z, String structureKey);

    /** Hands a tagged sapling to a player. */
    void giveSapling(UUID playerId, String speciesId, int amount);
    
    /** Places a block at the given location. Returns true if successful. */
    boolean setBlock(String worldName, int x, int y, int z, String blockData);

    /** Places a batch of blocks in a specific chunk. Returns true if all were successful. */
    boolean setBlocks(String worldName, int chunkX, int chunkZ, java.util.Map<TreeModel.BlockPos, String> blocks);

    /** Message a player or console. */
    void sendMessage(UUID playerId, String message, boolean error);

    /**
     * Reads the plugin's persistent per-chunk data blob for the given chunk, or
     * {@code null} when none is stored. Used by {@link TreeFarmStore} to track
     * where custom trees have grown. Platforms that cannot persist chunk data may
     * leave the default no-op implementation (the feature then relies solely on
     * biome-based resolution).
     */
    default String getChunkData(String worldName, int chunkX, int chunkZ) {
        return null;
    }

    /**
     * Writes (or clears, when {@code data} is {@code null}/empty) the plugin's
     * persistent per-chunk data blob for the given chunk. Counterpart to
     * {@link #getChunkData(String, int, int)}.
     */
    default void setChunkData(String worldName, int chunkX, int chunkZ, String data) {
    }

    /**
     * Minecraft DataVersion stamped into generated structure {@code .nbt} templates.
     * Platforms should override this with the running server's data version so the
     * baked structures match the world; the default targets 1.20.1 and relies on the
     * client/server DataFixerUpper to upgrade if older than the running version.
     */
    default int getDataVersion() {
        return 3578;
    }
}
