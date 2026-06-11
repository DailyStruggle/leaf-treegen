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
import java.util.UUID;
import java.util.logging.Logger;

public class PaperPlatform implements Platform {
    private final JavaPlugin plugin;
    private final StructureRotation[] rotations = StructureRotation.values();

    public PaperPlatform(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    @Override public Logger logger() { return plugin.getLogger(); }
    @Override public Path getRootFolder() { return plugin.getDataFolder().toPath(); }
    @Override public Path getWorldFolder(String worldName) { 
        World world = Bukkit.getWorld(worldName);
        if (world != null) return world.getWorldFolder().toPath();
        
        // Fallback for onLoad when worlds aren't registered yet
        File folder = new File(worldName);
        if (folder.exists() && folder.isDirectory()) {
            return folder.toPath();
        }
        return null;
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
        // Centre logic (simplified for multi-platform, but can be kept same as before)
        int offsetX = s.getSize().getBlockX() / 2;
        int offsetZ = s.getSize().getBlockZ() / 2;
        Location origin = loc.clone().subtract(offsetX, 0, offsetZ);

        try {
            s.place(origin, true, rotations[new java.util.Random().nextInt(rotations.length)], Mirror.NONE, 0, 1.0f, new java.util.Random());
            return true;
        } catch (Exception ex) {
            logger().warning("Failed to place " + structureKey + ": " + ex.getMessage());
            return false;
        }
    }

    @Override public void giveSapling(UUID playerId, String speciesId, int amount) {
        Player player = Bukkit.getPlayer(playerId);
        if (player == null) return;
        // This will need access to TreeSpecies and config, or just do it here if we pass the material.
        // For now, let's assume we use PaperSaplingItem.
    }

    @Override public void setBlock(String worldName, int x, int y, int z, String blockData) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) return;
        try {
            world.setBlockData(x, y, z, Bukkit.createBlockData(blockData));
        } catch (Exception ex) {
            logger().warning("Failed to set block " + blockData + " at " + x + "," + y + "," + z + ": " + ex.getMessage());
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
}
