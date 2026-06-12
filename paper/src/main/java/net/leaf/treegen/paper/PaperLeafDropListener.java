package net.leaf.treegen.paper;

import net.leaf.treegen.common.SaplingDropResolver;
import net.leaf.treegen.common.TreeSpecies;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.LeavesDecayEvent;

import java.util.Random;

/**
 * Makes the leaves of custom trees drop species-tagged saplings when they decay
 * or are broken, so players can replant (and farm) the same tree.
 *
 * <p>Resolution is hybrid: it first consults the precise, platform-agnostic
 * {@link net.leaf.treegen.common.TreeFarmStore} (where trees actually grew), then
 * falls back to biome-based matching via {@link SaplingDropResolver}.
 */
public final class PaperLeafDropListener implements Listener {

    private final PaperLeafTreeGenPlugin plugin;
    private final Random random = new Random();

    public PaperLeafDropListener(PaperLeafTreeGenPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onLeavesDecay(LeavesDecayEvent event) {
        maybeDropSapling(event.getBlock());
    }

    @EventHandler(ignoreCancelled = true, priority = EventPriority.MONITOR)
    public void onBlockBreak(BlockBreakEvent event) {
        if (!event.getBlock().getType().toString().endsWith("LEAVES")) return;
        maybeDropSapling(event.getBlock());
    }

    private void maybeDropSapling(Block block) {
        if (plugin.config() == null) return;
        double chance = plugin.config().saplingDropChance();
        if (chance <= 0.0) return;

        World world = block.getWorld();
        int x = block.getX();
        int y = block.getY();
        int z = block.getZ();
        String blockId = block.getType().getKey().toString();

        TreeSpecies species = resolveSpecies(world, x, y, z, blockId);
        if (species == null) return;

        if (random.nextDouble() >= chance) return;

        Location dropAt = block.getLocation().add(0.5, 0.5, 0.5);
        world.dropItemNaturally(dropAt, PaperSaplingItem.create(species, 1));
    }

    private TreeSpecies resolveSpecies(World world, int x, int y, int z, String blockId) {
        // Precise: a tree we recorded as actually grown here.
        String trackedId = plugin.farmStore().resolve(world.getName(), x, y, z);
        if (trackedId != null) {
            TreeSpecies tracked = plugin.config().get(trackedId);
            if (tracked != null) return tracked;
        }
        // Fallback: biome-based resolution using the leaf block type.
        String biome = world.getBiome(x, y, z).getKey().toString();
        return SaplingDropResolver.resolveByBiome(plugin.config().species().values(), biome, blockId);
    }
}
