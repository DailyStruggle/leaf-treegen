package net.leaf.treegen.common;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Platform-agnostic helper for the "leaves drop tagged saplings" feature.
 *
 * <p>It knows which block ids a species uses as leaves (from its procedural
 * params) and can resolve, from a broken/decayed leaf block plus the biome it
 * sits in, which custom species the leaf most likely belongs to. This is the
 * biome-based half of the hybrid resolution; the precise half is handled by the
 * platform-side farm registry that records where trees actually grew.
 */
public final class SaplingDropResolver {

    private SaplingDropResolver() {
    }

    /**
     * Normalise a block-state string (e.g. {@code "minecraft:oak_leaves[distance=1]"})
     * to its base, namespaced block id ({@code "minecraft:oak_leaves"}).
     */
    public static String baseBlockId(String state) {
        if (state == null) return null;
        String s = state.split("\\[")[0].trim().toLowerCase(Locale.ROOT);
        if (s.isEmpty()) return null;
        if (!s.contains(":")) s = "minecraft:" + s;
        return s;
    }

    /**
     * The set of leaf block ids a species places: its primary leaf block plus any
     * secondary and underside leaves. Empty when the species has no procedural
     * definition (e.g. purely baked-NBT variants).
     */
    public static Set<String> leafBlocksOf(TreeSpecies species) {
        Set<String> out = new LinkedHashSet<>();
        if (species == null || species.procedural() == null) return out;
        TreeSpecies.ProceduralParams p = species.procedural();
        addLeaf(out, p.leafBlock());
        if (p.canopy() != null) {
            addLeaf(out, p.canopy().secondaryLeaves());
            addLeaf(out, p.canopy().undersideLeaves());
        }
        return out;
    }

    private static void addLeaf(Set<String> out, String block) {
        String id = baseBlockId(block);
        if (id != null) out.add(id);
    }

    /**
     * Rough horizontal radius (in blocks) of a species' canopy, used to size the
     * tracked farm region around a planted/placed tree. Always at least 4.
     */
    public static int estimateCanopyRadius(TreeSpecies species) {
        int r = 4;
        if (species != null && species.procedural() != null && species.procedural().canopy() != null) {
            TreeSpecies.CanopyParams c = species.procedural().canopy();
            if (c.layers() != null) {
                for (TreeSpecies.CanopyParams.Layer l : c.layers()) {
                    r = Math.max(r, (int) Math.ceil(l.radius()) + 1);
                }
            }
            if (c.branches() != null) {
                r = Math.max(r, (int) Math.ceil(c.branches().maxLength()) + 2);
            }
        }
        return r;
    }

    /**
     * Rough total height (in blocks) of a species, used to size the tracked farm
     * region above a planted/placed tree. Always at least 12.
     */
    public static int estimateHeight(TreeSpecies species) {
        int h = 12;
        if (species != null && species.procedural() != null) {
            h = Math.max(h, species.procedural().maxHeight() + estimateCanopyRadius(species) + 2);
        }
        return h;
    }

    /**
     * Resolve which species a broken leaf block belongs to, using only the biome.
     * A species matches when it uses {@code brokenBlock} as a leaf and is allowed
     * in {@code biomeKey}. Returns {@code null} when nothing matches.
     */
    public static TreeSpecies resolveByBiome(Collection<TreeSpecies> species, String biomeKey, String brokenBlock) {
        String target = baseBlockId(brokenBlock);
        if (target == null || species == null) return null;
        for (TreeSpecies s : species) {
            if (s.allowsBiome(biomeKey) && leafBlocksOf(s).contains(target)) {
                return s;
            }
        }
        return null;
    }
}
