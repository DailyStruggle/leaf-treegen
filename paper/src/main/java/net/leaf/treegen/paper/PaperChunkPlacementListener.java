package net.leaf.treegen.paper;

import net.leaf.treegen.common.TreeSpecies;
import org.bukkit.Chunk;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;

import java.util.List;
import java.util.Random;

/**
 * Procedural tree placement on chunk load (new chunks).
 */
public final class PaperChunkPlacementListener implements Listener {

    private final PaperLeafTreeGenPlugin plugin;

    public PaperChunkPlacementListener(PaperLeafTreeGenPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler
    public void onChunkLoad(ChunkLoadEvent event) {
        if (!event.isNewChunk()) return;
        if (!plugin.config().useProcedural()) return;

        Chunk chunk = event.getChunk();
        World world = chunk.getWorld();
        
        // Use a stable random based on world seed and chunk coordinates
        Random random = new Random((long) world.getSeed() ^ ((long) chunk.getX() << 32 | (chunk.getZ() & 0xFFFFFFFFL)));

        plugin.getLogger().fine("Checking procedural placement for chunk " + chunk.getX() + "," + chunk.getZ() + " in world " + world.getName());
        plugin.getLogger().info("[DEBUG_LOG] Processing new chunk " + chunk.getX() + "," + chunk.getZ());

        for (TreeSpecies species : plugin.config().species().values()) {
            if (!species.worldgen()) continue;
            
            // Check if this species should spawn in this chunk's biomes
            int centerX = (chunk.getX() << 4) + 8;
            int centerZ = (chunk.getZ() << 4) + 8;
            int topY = world.getHighestBlockYAt(centerX, centerZ);
            
            String biome = world.getBiome(centerX, topY, centerZ).getKey().toString();
            if (!species.allowsBiome(biome)) {
                continue;
            }

            // Spacing-based placement for procedural mode
            // spacing: 1 means every chunk, spacing: 2 means 1/4 chunks, etc.
            double chance = 1.0 / (Math.max(1, species.spacing()) * Math.max(1, species.spacing()));
            
            if (random.nextDouble() < chance) {
                int x = (chunk.getX() << 4) + random.nextInt(16);
                int z = (chunk.getZ() << 4) + random.nextInt(16);
                
                // Find ground level by scanning down from the top (avoid spawning on canopy)
                int y = world.getHighestBlockYAt(x, z);
                boolean foundGround = false;
                while (y > world.getMinHeight()) {
                    org.bukkit.block.Block b = world.getBlockAt(x, y, z);
                    org.bukkit.Material type = b.getType();
                    // Solid ground check - broaden to anything solid that isn't leaves or logs
                    if (type.isSolid() && !type.toString().contains("LEAVES") && !type.toString().contains("LOG")) {
                        foundGround = true;
                        break;
                    }
                    y--;
                }
                
                if (!foundGround) {
                    plugin.getLogger().warning("[DEBUG_LOG] Ground scanning failed for " + species.id() + " at " + x + "," + z + " (Highest Y: " + world.getHighestBlockYAt(x, z) + ")");
                    continue;
                }
                
                // Final biome check at exact location
                String exactBiome = world.getBiome(x, y, z).getKey().toString();
                if (!species.allowsBiome(exactBiome)) continue;

                org.bukkit.Material ground = world.getBlockAt(x, y, z).getType();
                plugin.getLogger().info("[DEBUG_LOG] Spawning procedural tree " + species.id() + " at " + x + "," + (y+1) + "," + z + " in biome " + exactBiome + " (Ground: " + ground + ")");
                
                if (species.replaceVanilla()) {
                    // Clear a small area around the trunk to ensure it's visible
                    int radius = 2;
                    for (int dx = -radius; dx <= radius; dx++) {
                        for (int dz = -radius; dz <= radius; dz++) {
                            for (int dy = 1; dy <= 40; dy++) {
                                world.setType(x + dx, y + dy, z + dz, org.bukkit.Material.AIR);
                            }
                        }
                    }
                }

                plugin.proceduralGenerator().generate(world.getName(), x, y + 1, z, species, random);
            } else {
                plugin.getLogger().finest("Spacing check failed for " + species.id() + " in chunk " + chunk.getX() + "," + chunk.getZ());
            }
        }
    }
}
