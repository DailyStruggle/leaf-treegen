package net.leaf.treegen.fabric;

import net.leaf.treegen.common.TreeSpecies;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.component.ItemLore;

import java.util.List;
import java.util.Locale;

/**
 * Builds and reads the "magical" sapling item handed out by {@code /leaftree give} on Fabric.
 *
 * <p>The plugin has no custom registered item, so it reuses a vanilla sapling (the species'
 * configured {@code sapling-item}, e.g. {@code OAK_SAPLING}) but stamps it with a custom-data
 * component carrying the species id. That tag is what makes the stack <em>accurate to its tree</em>
 * and lets {@link FabricSaplingListener} recognise it when planted and grow the right species,
 * mirroring the NeoForge / Paper platforms.
 */
final class FabricSaplingItem {

    /** Custom-data key under which the species id is stored on the stack. */
    static final String TREE_SPECIES_KEY = "leaf_treegen_tree_species";

    private FabricSaplingItem() {}

    /** Builds {@code amount} of the species' tagged sapling, falling back to an oak sapling. */
    static ItemStack create(TreeSpecies species, int amount) {
        Item item = resolveItem(species.saplingItem());
        ItemStack stack = new ItemStack(item, Math.max(1, amount));

        CompoundTag tag = new CompoundTag();
        tag.putString(TREE_SPECIES_KEY, species.id());
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag));

        stack.set(DataComponents.CUSTOM_NAME, Component.literal(species.displayName() + " Sapling"));
        stack.set(DataComponents.LORE, new ItemLore(List.of(
            Component.literal("A magical sapling."),
            Component.literal("Plant it to grow a " + species.displayName() + "."))));
        return stack;
    }

    /** Returns the species id tagged on the stack, or {@code null} if it is not a magical sapling. */
    static String speciesId(ItemStack stack) {
        if (stack == null || stack.isEmpty()) return null;
        CustomData data = stack.get(DataComponents.CUSTOM_DATA);
        if (data == null) return null;
        CompoundTag tag = data.copyTag();
        String id = tag.getStringOr(TREE_SPECIES_KEY, "");
        return id.isEmpty() ? null : id;
    }

    /** Resolves a Bukkit-style material name or {@code namespace:path} id to an item, defaulting to oak. */
    static Item resolveItem(String materialName) {
        if (materialName == null || materialName.isBlank()) return Items.OAK_SAPLING;
        String idStr = materialName.contains(":")
            ? materialName.toLowerCase(Locale.ROOT)
            : "minecraft:" + materialName.toLowerCase(Locale.ROOT);
        try {
            Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(idStr));
            return (item == null || item == Items.AIR) ? Items.OAK_SAPLING : item;
        } catch (Exception ex) {
            return Items.OAK_SAPLING;
        }
    }
}
