package net.leaf.treegen.paper;

import net.leaf.treegen.common.TreeSpecies;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

import java.util.List;

public final class PaperSaplingItem {
    public static final NamespacedKey TREE_SPECIES = new NamespacedKey("leaf-treegen", "tree_species");
    /**
     * Optional: a specific template variant (structure key) this sapling grows. When absent the
     * planting listener picks a random variant for the species at placement time.
     */
    public static final NamespacedKey TREE_VARIANT = new NamespacedKey("leaf-treegen", "tree_variant");

    public static ItemStack create(TreeSpecies species, int amount) {
        return create(species, amount, null);
    }

    public static ItemStack create(TreeSpecies species, int amount, String variant) {
        Material mat = Material.matchMaterial(species.saplingItem());
        if (mat == null) mat = Material.OAK_SAPLING;
        
        ItemStack item = new ItemStack(mat, Math.max(1, amount));
        ItemMeta meta = item.getItemMeta();

        boolean hasVariant = variant != null && !variant.isBlank();
        meta.displayName(Component.text(species.displayName() + " Sapling", NamedTextColor.GREEN)
                .decoration(TextDecoration.ITALIC, false));
        meta.lore(List.of(
                Component.text("A magical sapling.", NamedTextColor.GRAY)
                        .decoration(TextDecoration.ITALIC, false),
                Component.text(hasVariant
                                ? "Plant it to grow a " + species.displayName() + " (" + variant + ")."
                                : "Plant it to grow a " + species.displayName() + ".", NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.ITALIC, false)));

        meta.getPersistentDataContainer().set(TREE_SPECIES, PersistentDataType.STRING, species.id());
        if (hasVariant) {
            meta.getPersistentDataContainer().set(TREE_VARIANT, PersistentDataType.STRING, variant);
        }
        item.setItemMeta(meta);
        return item;
    }

    public static String speciesId(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(TREE_SPECIES, PersistentDataType.STRING);
    }

    /** The specific variant (structure key) bound to this sapling, or {@code null} when unbound. */
    public static String variant(ItemStack item) {
        if (item == null || !item.hasItemMeta()) return null;
        return item.getItemMeta().getPersistentDataContainer().get(TREE_VARIANT, PersistentDataType.STRING);
    }
}
