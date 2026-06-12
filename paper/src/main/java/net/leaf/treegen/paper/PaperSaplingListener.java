package net.leaf.treegen.paper;

import net.leaf.treegen.common.TreeSpecies;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

import java.util.List;
import java.util.Random;

public final class PaperSaplingListener implements Listener {

    private final PaperLeafTreeGenPlugin plugin;
    private final Random random = new Random();

    public PaperSaplingListener(PaperLeafTreeGenPlugin plugin) {
        this.plugin = plugin;
    }

    @EventHandler(ignoreCancelled = true)
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_BLOCK
                || event.getHand() != EquipmentSlot.HAND
                || event.getClickedBlock() == null) {
            return;
        }

        ItemStack inHand = event.getItem();
        String speciesId = PaperSaplingItem.speciesId(inHand);
        if (speciesId == null) return;

        event.setCancelled(true);

        Player player = event.getPlayer();
        if (!player.hasPermission("leaftreegen.sapling")) {
            player.sendMessage(Component.text("You can't plant magical saplings.", NamedTextColor.RED));
            return;
        }

        TreeSpecies species = plugin.config().get(speciesId);
        if (species == null) {
            player.sendMessage(Component.text("Unknown tree species: " + speciesId, NamedTextColor.RED));
            return;
        }

        Block clicked = event.getClickedBlock();
        Block target = clicked.getRelative(BlockFace.UP);

        // Most trees can't take root on or in water (or other liquids). Species listed under
        // 'water-growth-trees' in config.yml (e.g. swamp trees) are exempt and may grow on water.
        if (!plugin.config().allowsWaterGrowth(species.id()) && (isLiquid(clicked) || isLiquid(target))) {
            player.sendMessage(Component.text(species.displayName() + " won't take root on water.", NamedTextColor.YELLOW));
            return;
        }

        Location base = target.getLocation();

        // Biome restrictions only gate natural worldgen; a magical sapling is hand-planted by a
        // player, so it may take root on any grass/ground regardless of the current biome.

        // If the sapling is bound to a specific variant, grow exactly that tree; otherwise the
        // variant is left unspecified until placement and chosen at random for the species.
        String boundVariant = PaperSaplingItem.variant(inHand);
        String variant;
        if (boundVariant != null && !boundVariant.isBlank()) {
            variant = boundVariant;
        } else {
            List<String> variants = plugin.registry().variantsFor(base.getWorld().getName(), species);
            if (variants.isEmpty()) {
                player.sendMessage(Component.text("No tree templates available.", NamedTextColor.RED));
                return;
            }
            variant = variants.get(random.nextInt(variants.size()));
        }
        
        final String finalWorldName = base.getWorld().getName();
        final int finalX = base.getBlockX();
        final int finalY = base.getBlockY();
        final int finalZ = base.getBlockZ();
        final String finalVariant = variant;
        final String finalDisplayName = species.displayName();
        final ItemStack finalInHand = inHand;
        final boolean isCreative = player.getGameMode() == GameMode.CREATIVE;

        Bukkit.getRegionScheduler().run(plugin, base, task -> {
            boolean placed = plugin.platform().placeStructure(finalWorldName, finalX, finalY, finalZ, finalVariant);

            // Send feedback on the same thread/next tick safely
            Bukkit.getRegionScheduler().run(plugin, base, t -> {
                if (placed) {
                    int radius = net.leaf.treegen.common.SaplingDropResolver.estimateCanopyRadius(species);
                    int height = net.leaf.treegen.common.SaplingDropResolver.estimateHeight(species);
                    plugin.farmStore().recordTree(finalWorldName, finalX, finalY, finalZ, radius, height, species.id());
                    if (!isCreative) finalInHand.setAmount(finalInHand.getAmount() - 1);
                    player.sendMessage(Component.text("A " + finalDisplayName + " springs up!", NamedTextColor.GREEN));
                } else {
                    player.sendMessage(Component.text("The sapling failed to grow.", NamedTextColor.RED));
                }
            });
        });
    }

    /**
     * Returns {@code true} if the block is a liquid (water or lava) or is waterlogged, meaning a
     * tree should not be planted on or in it.
     */
    private static boolean isLiquid(Block block) {
        if (block.isLiquid()) {
            return true;
        }
        org.bukkit.block.data.BlockData data = block.getBlockData();
        return data instanceof org.bukkit.block.data.Waterlogged waterlogged && waterlogged.isWaterlogged();
    }
}
