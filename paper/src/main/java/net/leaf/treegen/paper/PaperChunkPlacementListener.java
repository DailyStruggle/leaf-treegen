package net.leaf.treegen.paper;

import net.leaf.treegen.common.TreeSpecies;
import org.bukkit.Bukkit;
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

    /**
     * Delay (in ticks) between a chunk loading and the procedural tree being
     * placed. Deferring placement lets neighbouring chunks finish generating so
     * trees that span a chunk border are no longer clipped into square shapes.
     */
    private static final long PLACEMENT_DELAY_TICKS = 40L;

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
        // plugin.getLogger().info("[DEBUG_LOG] Processing new chunk " + chunk.getX() + "," + chunk.getZ());

        for (TreeSpecies species : plugin.config().species().values()) {
            if (!species.worldgen()) continue;
            
            // Spacing-based placement for procedural mode
            // spacing: 1 means every chunk, spacing: 2 means 1/4 chunks, etc.
            int spacing = Math.max(1, species.spacing());
            int chunkX = chunk.getX();
            int chunkZ = chunk.getZ();

            // Grid-based placement using spacing and salt
            int salt = species.effectiveSalt();
            int gridX = Math.floorDiv(chunkX, spacing);
            int gridZ = Math.floorDiv(chunkZ, spacing);

            // Determine which chunk in the spacing x spacing grid this species belongs to
            Random gridRandom = new Random((long) gridX * 34133213L ^ (long) gridZ * 82889429L ^ (long) salt);
            int targetChunkOffset = gridRandom.nextInt(spacing * spacing);
            int targetChunkX = gridX * spacing + (targetChunkOffset % spacing);
            int targetChunkZ = gridZ * spacing + (targetChunkOffset / spacing);

            if (chunkX != targetChunkX || chunkZ != targetChunkZ) {
                continue;
            }

            // Check if this species should spawn in this chunk's biomes
            int centerX = (chunk.getX() << 4) + 8;
            int centerZ = (chunk.getZ() << 4) + 8;
            int topY = world.getHighestBlockYAt(centerX, centerZ);
            
            String biome = world.getBiome(centerX, topY, centerZ).getKey().toString();
            if (!species.allowsBiome(biome)) {
                continue;
            }

            int x = (chunk.getX() << 4) + gridRandom.nextInt(16);
            int z = (chunk.getZ() << 4) + gridRandom.nextInt(16);
            
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
                plugin.getLogger().fine("Ground scanning failed for " + species.id() + " at " + x + "," + z + " (Highest Y: " + world.getHighestBlockYAt(x, z) + ")");
                continue;
            }
            
            // Final biome check at exact location
            String exactBiome = world.getBiome(x, y, z).getKey().toString();
            if (!species.allowsBiome(exactBiome)) continue;

            org.bukkit.Material ground = world.getBlockAt(x, y, z).getType();
            // plugin.getLogger().info("[DEBUG_LOG] Spawning procedural tree " + species.id() + " at " + x + "," + (y+1) + "," + z + " in biome " + exactBiome + " (Ground: " + ground + ")");
            
            if (species.replaceVanilla()) {
                // Clearing area must also be done on the correct thread.
                // We'll move all block modifications into the scheduled task.
            }

            final int finalX = x;
            final int finalY = y;
            final int finalZ = z;
            final TreeSpecies finalSpecies = species;
            final Random finalRandom = new Random(gridRandom.nextLong());
            final boolean shouldReplaceVanilla = species.replaceVanilla();

            // Space out placement: defer to a delayed regional task so the surrounding
            // chunks have a chance to load/generate first. Placing immediately on
            // ChunkLoadEvent meant any part of the tree that crossed into a
            // not-yet-generated neighbour chunk was later overwritten by terrain
            // generation, which read in-world as the canopy being cut off in square
            // shapes along the chunk borders.
            Bukkit.getRegionScheduler().runDelayed(plugin, world, finalX, finalZ, task -> {
                // Re-verify ground and biome on the regional thread to be absolutely sure
                // although we already checked, chunks might have changed or we might be at a boundary.
                if (shouldReplaceVanilla) {
                    // Clear a small area around the trunk to ensure it's visible
                    // Group by chunk to ensure thread safety on Folia
                    java.util.Map<Long, java.util.Map<net.leaf.treegen.common.TreeModel.BlockPos, String>> clearingChunks = new java.util.HashMap<>();
                    int radius = 2;
                    for (int dx = -radius; dx <= radius; dx++) {
                        for (int dz = -radius; dz <= radius; dz++) {
                            for (int dy = 1; dy <= 40; dy++) {
                                int bx = finalX + dx;
                                int by = finalY + dy;
                                int bz = finalZ + dz;
                                
                                int bcx = bx >> 4;
                                int bcz = bz >> 4;
                                long key = ((long) bcx << 32) | (bcz & 0xFFFFFFFFL);
                                
                                clearingChunks.computeIfAbsent(key, k -> new java.util.HashMap<>())
                                              .put(new net.leaf.treegen.common.TreeModel.BlockPos(bx, by, bz), "minecraft:void_air");
                            }
                        }
                    }
                    
                    clearingChunks.forEach((key, blocks) -> {
                        int bcx = (int) (key >> 32);
                        int bcz = (int) key.longValue();
                        plugin.platform().setBlocks(world.getName(), bcx, bcz, blocks);
                    });
                }
                plugin.proceduralGenerator().generate(world.getName(), finalX, finalY + 1, finalZ, finalSpecies, finalRandom);

                int radius = net.leaf.treegen.common.SaplingDropResolver.estimateCanopyRadius(finalSpecies);
                int height = net.leaf.treegen.common.SaplingDropResolver.estimateHeight(finalSpecies);
                plugin.farmStore().recordTree(world.getName(), finalX, finalY + 1, finalZ, radius, height, finalSpecies.id());
            }, PLACEMENT_DELAY_TICKS);
        }
    }
}
