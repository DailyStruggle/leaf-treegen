package net.leaf.treegen.paper;

import net.leaf.treegen.common.TreeSpecies;
import org.bukkit.Location;
import org.bukkit.TreeType;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.StructureGrowEvent;

import java.util.List;
import java.util.Random;

/**
 * Handles suppression and replacement of vanilla trees.
 */
public final class PaperTreeSuppressionListener implements Listener {

    private final PaperLeafTreeGenPlugin plugin;
    private final Random random = new Random();

    public PaperTreeSuppressionListener(PaperLeafTreeGenPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = true)
    public void onStructureGrow(StructureGrowEvent event) {
        Location loc = event.getLocation();
        String biome = plugin.platform().getBiomeKey(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
        
        // Find if any species active in this biome has replaceVanilla enabled
        for (TreeSpecies species : plugin.config().species().values()) {
            if (species.replaceVanilla() && species.allowsBiome(biome)) {
                // Check if the growth is a vanilla tree type
                if (isVanillaTree(event.getSpecies())) {
                    event.setCancelled(true);
                    
                    // Optionally replace with a procedural tree
                    if (species.worldgen()) {
                        List<String> variants = plugin.registry().variantsFor(loc.getWorld().getName(), species);
                        if (!variants.isEmpty()) {
                            String variant = variants.get(random.nextInt(variants.size()));
                            plugin.platform().placeStructure(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), variant);
                        } else if (species.procedural() != null) {
                            plugin.proceduralGenerator().generate(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ(), species, random);
                        }
                    }
                    return;
                }
            }
        }
    }

    private boolean isVanillaTree(TreeType type) {
        String name = type.name();
        return name.contains("OAK") || name.contains("BIRCH") || name.contains("SPRUCE") || 
               name.contains("JUNGLE") || name.contains("ACACIA") || name.contains("PINE") || 
               name.contains("REDWOOD") || name.contains("CHERRY") || name.contains("MANGROVE") ||
               name.contains("AZALEA") || name.contains("TREE");
    }
}
