package net.leaf.treegen.common;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Platform-agnostic tree species.
 */
public record TreeSpecies(
        String id,
        String displayName,
        String namespace,
        String group,
        Set<String> biomes,
        String saplingItem, // Material name (e.g. "OAK_SAPLING")
        boolean worldgen,
        boolean replaceVanilla,
        int spacing,
        int separation,
        int salt,
        String spreadType,
        String step,
        List<TreeVariant> variants,
        ProceduralParams procedural,
        List<WeightedSpecies> trees, // Optional: for group placement
        Map<String, ProceduralParams> treeDefinitions, // Named tree definitions within this species
        int weight // Species-level weight for placement
) {

        public record TreeVariant(String location, int weight) {}

        public record WeightedSpecies(String id, int weight) {}

        public record ProceduralParams(
                String trunkBlock,
                String leafBlock,
                int minHeight,
                int maxHeight,
                String profile,
                double trunkWidth,
                String trunkShape,
                Map<String, Double> trunkShapeParams,
                boolean roundTrunk,
                double leanAngle,
                double leanAzimuth,
                String azimuthFn,
                Map<String, Double> azimuthParams,
                String curveFn,
                Map<String, Double> curveParams,
                String secondaryTrunk,
                double secondaryTrunkStart,
                double secondaryTrunkEnd,
                CanopyParams canopy,
                int weight
        ) {}

        public record CanopyParams(
                String mode,
                double density,
                List<Layer> layers,
                BranchParams branches,
                String secondaryLeaves,
                double secondaryFraction
        ) {
                public record Layer(int yOffset, double radius) {}
        }

        public record BranchParams(
                int count,
                double minLength,
                double maxLength,
                String lengthFn,
                Map<String, Double> lengthParams,
                double minElevation,
                double maxElevation,
                double spacing,
                double startHeight
        ) {}

    public int effectiveSalt() {
        return salt >= 0 ? salt : (id.hashCode() & 0x7fffffff) % 1_000_000;
    }

    public int getPlacementWeight() {
        return weight;
    }

    public boolean allowsBiome(String biomeKey) {
        return biomes.isEmpty() || biomes.contains(biomeKey);
    }

    public List<String> variantLocations() {
        return variants.stream().map(TreeVariant::location).toList();
    }
}
