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
    
    /** Places a block at the given location. */
    void setBlock(String worldName, int x, int y, int z, String blockData);

    /** Message a player or console. */
    void sendMessage(UUID playerId, String message, boolean error);
}
