package net.leaf.treegen.paper;

import net.leaf.treegen.common.TreeSpecies;
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

        Block target = event.getClickedBlock().getRelative(BlockFace.UP);
        Location base = target.getLocation();

        String biome = plugin.platform().getBiomeKey(base.getWorld().getName(), base.getBlockX(), base.getBlockY(), base.getBlockZ());
        if (!species.allowsBiome(biome)) {
            player.sendMessage(Component.text(species.displayName() + " won't take root here.", NamedTextColor.YELLOW));
            return;
        }

        List<String> variants = plugin.registry().variantsFor(base.getWorld().getName(), species);
        if (variants.isEmpty()) {
            player.sendMessage(Component.text("No tree templates available.", NamedTextColor.RED));
            return;
        }

        String variant = variants.get(random.nextInt(variants.size()));
        boolean placed = plugin.platform().placeStructure(base.getWorld().getName(), base.getBlockX(), base.getBlockY(), base.getBlockZ(), variant);
        
        if (placed) {
            if (player.getGameMode() != GameMode.CREATIVE) inHand.setAmount(inHand.getAmount() - 1);
            player.sendMessage(Component.text("A " + species.displayName() + " springs up!", NamedTextColor.GREEN));
        } else {
            player.sendMessage(Component.text("The sapling failed to grow.", NamedTextColor.RED));
        }
    }
}
