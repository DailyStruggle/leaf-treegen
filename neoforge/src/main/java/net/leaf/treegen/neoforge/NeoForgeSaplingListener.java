package net.leaf.treegen.neoforge;

import net.leaf.treegen.common.TreeGenConfig;
import net.leaf.treegen.common.TreeRegistry;
import net.leaf.treegen.common.TreeSpecies;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

import java.util.List;
import java.util.Random;
import java.util.function.Supplier;

/**
 * Grows the correct custom tree when a player right-clicks with a magical sapling handed out by
 * {@code /leaftree give}. This mirrors the Paper platform's {@code PaperSaplingListener}: the stack
 * carries a species id in its custom-data component (see {@link NeoForgeSaplingItem}), which we read
 * to resolve the species, validate the location, pick a template variant and place the structure.
 */
final class NeoForgeSaplingListener {

    private final Supplier<TreeGenConfig> configRef;
    private final Supplier<TreeRegistry> registryRef;
    private final NeoForgePlatform platform;
    private final Random random = new Random();

    NeoForgeSaplingListener(Supplier<TreeGenConfig> configRef,
                            Supplier<TreeRegistry> registryRef,
                            NeoForgePlatform platform) {
        this.configRef = configRef;
        this.registryRef = registryRef;
        this.platform = platform;
    }

    void onRightClickBlock(PlayerInteractEvent.RightClickBlock event) {
        Level level = event.getLevel();
        if (level.isClientSide()) return;
        if (event.getHand() != InteractionHand.MAIN_HAND) return;

        ItemStack inHand = event.getItemStack();
        String speciesId = NeoForgeSaplingItem.speciesId(inHand);
        if (speciesId == null) return;

        // It is one of our saplings: take over vanilla placement regardless of outcome.
        event.setCanceled(true);

        Player player = event.getEntity();
        TreeGenConfig cfg = configRef.get();
        TreeSpecies species = cfg == null ? null : cfg.get(speciesId);
        if (species == null) {
            player.sendSystemMessage(Component.literal("Unknown tree species: " + speciesId));
            return;
        }

        BlockPos clicked = event.getPos();
        BlockPos target = clicked.above();

        // Most trees can't take root on or in liquids; water-growth species are exempt.
        if (!cfg.allowsWaterGrowth(species.id())
            && (!level.getFluidState(clicked).isEmpty() || !level.getFluidState(target).isEmpty())) {
            player.sendSystemMessage(Component.literal(species.displayName() + " won't take root on water."));
            return;
        }

        String worldName = level.dimension().identifier().getPath();
        String biome = platform.getBiomeKey(worldName, target.getX(), target.getY(), target.getZ());
        if (!species.allowsBiome(biome)) {
            player.sendSystemMessage(Component.literal(species.displayName() + " won't take root here."));
            return;
        }

        TreeRegistry registry = registryRef.get();
        // Templates are generated under the save's level-name folder (see getWorldNames /
        // DatapackGenerator), not under the dimension path (e.g. "overworld"), so resolve
        // variants against the level-name to find the generated .nbt structures.
        String templateWorld = templateWorldName();
        List<String> variants = registry == null ? List.of() : registry.variantsFor(templateWorld, species);
        if (variants.isEmpty()) {
            player.sendSystemMessage(Component.literal("No tree templates available."));
            return;
        }

        String variant = variants.get(random.nextInt(variants.size()));
        boolean placed = platform.placeStructure(worldName, target.getX(), target.getY(), target.getZ(), variant);
        if (placed) {
            if (!player.isCreative()) inHand.shrink(1);
            player.sendSystemMessage(Component.literal("A " + species.displayName() + " springs up!"));
        } else {
            player.sendSystemMessage(Component.literal("The sapling failed to grow."));
        }
    }

    /**
     * The world name under which generated tree templates live. The datapack generator writes
     * structures per {@link NeoForgePlatform#getWorldNames()} (the save's level-name, e.g.
     * {@code "world"}), which is what {@link TreeRegistry#variantsFor} scans. Falls back to
     * {@code "world"} if no world name is available.
     */
    private String templateWorldName() {
        List<String> names = platform.getWorldNames();
        return (names == null || names.isEmpty()) ? "world" : names.get(0);
    }
}
