package net.leaf.treegen.common;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Platform-agnostic configuration parser.
 */
public final class TreeGenConfig {

    public enum GenerationMode {
        DATAPACK,
        PROCEDURAL,
        BOTH,
        NONE
    }

    private final Map<String, TreeSpecies> species;
    private final String packName;
    private final String packDescription;
    private final GenerationMode mode;

    public TreeGenConfig(Map<String, TreeSpecies> species, String packName, String packDescription, GenerationMode mode) {
        this.species = species;
        this.packName = packName;
        this.packDescription = packDescription;
        this.mode = mode;
    }

    public Map<String, TreeSpecies> species() { return species; }
    public String packName() { return packName; }
    public String packDescription() { return packDescription; }
    public GenerationMode mode() { return mode; }

    public boolean generateDatapack() {
        return mode == GenerationMode.DATAPACK || mode == GenerationMode.BOTH;
    }

    public boolean useProcedural() {
        return mode == GenerationMode.PROCEDURAL || mode == GenerationMode.BOTH;
    }

    public TreeSpecies get(String id) {
        return species.get(id.toLowerCase(Locale.ROOT));
    }

    public List<String> ids() {
        return List.copyOf(species.keySet());
    }

    /**
     * Helper to load from RtpYamlConfig.
     */
    public static TreeGenConfig fromYaml(io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig yaml, TreeRegistry registry, Platform platform) {
        Map<String, TreeSpecies> registered = registry.getSpeciesMap();

        try {
            yaml.loadWithComments();
        } catch (Exception e) {
             platform.logger().severe("CRITICAL: Failed to load config.yml: " + e.getMessage());
        }

        String packName = yaml.getString("datapack.name", "leaf-treegen-generated");
        String packDescription = yaml.getString("datapack.description", "LeafTreeGen generated worldgen.");

        GenerationMode mode = GenerationMode.DATAPACK;
        String modeStr = yaml.getString("generation-mode", "DATAPACK");
        try {
            mode = GenerationMode.valueOf(modeStr.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {}

        Map<String, TreeSpecies> speciesMap = new LinkedHashMap<>();

        // Populate with registered species first
        platform.logger().info("Registered species available: " + String.join(", ", registered.keySet()));
        speciesMap.putAll(registered);

        // Placement overrides from config.yml
        io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection placementRoot = null;
        Object placementObj = yaml.get("placement");
        if (placementObj instanceof io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection) {
            placementRoot = (io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection) placementObj;
        }

        if (placementRoot != null) {
            for (String id : placementRoot.getKeys(false)) {
                Object val = placementRoot.get(id);
                if (!(val instanceof io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection sec)) {
                    continue;
                }
                
                String key = id.toLowerCase(Locale.ROOT).replace("-", "_");
                TreeSpecies existing = registered.get(key);
                if (existing == null) {
                    // Try hyphenated version
                    String hyphenated = id.toLowerCase(Locale.ROOT).replace("_", "-");
                    existing = registered.get(hyphenated);
                    if (existing != null) {
                        key = hyphenated;
                    }
                }
                
                if (existing == null) {
                    // Try with original key if normalization failed
                    key = id.toLowerCase(Locale.ROOT);
                    existing = registered.get(key);
                }
                
                if (existing == null && (sec.contains("trees") || sec.contains("weighted-trees") || sec.contains("weighted_trees"))) {
                    // Create a virtual species if it doesn't exist but has a tree group
                    existing = new TreeSpecies(
                        id.toLowerCase(Locale.ROOT), id, "minecraft", id.toLowerCase(Locale.ROOT),
                        new HashSet<>(), "OAK_SAPLING", true, false, 3, 2, -1, "linear", "surface_structures",
                        new ArrayList<>(), null, new ArrayList<>(), new HashMap<>(), 1
                    );
                    platform.logger().info("Created virtual species for '" + id + "'");
                }

                if (existing == null) {
                    platform.logger().warning("Placement override for unknown species: " + id + ". Key tried: '" + key + "'. Available: " + String.join(", ", registered.keySet()));
                    continue;
                }

                // If it was already in speciesMap (from registered), we are about to override it.
                // We should ensure we use the canonical key from the registry.
                key = existing.id().toLowerCase(Locale.ROOT);
                platform.logger().info("Applying config.yml override for species '" + key + "' (from key '" + id + "')");

                Set<String> biomes = new HashSet<>(existing.biomes());
                List<?> bList = sec.getList("biomes");
                if (bList != null) {
                    biomes = new HashSet<>();
                    for (Object b : bList) biomes.add(normaliseBiome(b.toString()));
                }
                platform.logger().info("  biomes: " + biomes.size());

                boolean worldgen = sec.contains("worldgen") ? sec.getBoolean("worldgen", !biomes.isEmpty()) : (sec.contains("world-gen") ? sec.getBoolean("world-gen", !biomes.isEmpty()) : (sec.contains("world_gen") ? sec.getBoolean("world_gen", !biomes.isEmpty()) : !biomes.isEmpty()));
                if (sec.contains("biomes") && !sec.contains("worldgen") && !sec.contains("world-gen") && !sec.contains("world_gen")) {
                    worldgen = true; // Auto-enable worldgen if biomes are explicitly provided in config.yml
                }
                platform.logger().info("  worldgen: " + worldgen);
                boolean replaceVanilla = sec.contains("replace-vanilla") ? sec.getBoolean("replace-vanilla", existing.replaceVanilla()) : (sec.contains("replace_vanilla") ? sec.getBoolean("replace_vanilla", existing.replaceVanilla()) : existing.replaceVanilla());
                int spacing = sec.getInt("spacing", existing.spacing());
                int separation = sec.getInt("separation", existing.separation());
                int salt = sec.getInt("salt", existing.salt());
                String spreadType = sec.getString("spread-type", existing.spreadType());
                String step = sec.getString("step", existing.step());
                int weight = sec.getInt("weight", existing.weight());

                Map<String, TreeSpecies.ProceduralParams> treeDefs = new HashMap<>(existing.treeDefinitions() != null ? existing.treeDefinitions() : Map.of());
                Object defsObj = sec.get("definitions");
                if (defsObj instanceof io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection dSec) {
                    for (String defName : dSec.getKeys(false)) {
                        TreeSpecies.ProceduralParams p = treeDefs.get(defName);
                        if (p != null) {
                            treeDefs.put(defName, new TreeSpecies.ProceduralParams(
                                p.trunkBlock(), p.leafBlock(), p.minHeight(), p.maxHeight(), p.profile(),
                                p.trunkWidth(), p.trunkShape(), p.trunkShapeParams(), p.roundTrunk(),
                                p.leanAngle(), p.leanAzimuth(), p.azimuthFn(), p.azimuthParams(),
                                p.curveFn(), p.curveParams(), p.secondaryTrunk(), p.secondaryTrunkStart(),
                                p.secondaryTrunkEnd(), p.canopy(), dSec.getInt(defName, p.weight())
                            ));
                        }
                    }
                }
                
                List<TreeSpecies.WeightedSpecies> trees = existing.trees();
                Object treesObj = sec.get("trees");
                if (treesObj == null) treesObj = sec.get("weighted-trees");
                if (treesObj == null) treesObj = sec.get("weighted_trees");
                
                if (treesObj instanceof io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection tSec) {
                    trees = new ArrayList<>();
                    for (String tId : tSec.getKeys(false)) {
                        trees.add(new TreeSpecies.WeightedSpecies(tId, tSec.getInt(tId, 1)));
                    }
                }

                speciesMap.put(key, new TreeSpecies(
                    existing.id(), existing.displayName(), existing.namespace(), existing.group(),
                    biomes, existing.saplingItem(), worldgen, replaceVanilla, spacing, separation, salt, spreadType, step,
                    existing.variants(), existing.procedural(), trees, treeDefs, weight
                ));
                platform.logger().info("Configured species '" + key + "': worldgen=" + worldgen + ", biomes=" + biomes.size());
            }
        }

        // Only include species that have worldgen enabled AND (at least one biome OR a weighted tree list)
        int sizeBefore = speciesMap.size();
        speciesMap.values().removeIf(s -> {
            boolean hasBiomes = s.biomes() != null && !s.biomes().isEmpty();
            boolean hasTrees = s.trees() != null && !s.trees().isEmpty();
            boolean keep = s.worldgen() && (hasBiomes || hasTrees);
            if (!keep) {
                platform.logger().info("Species '" + s.id() + "' removed: worldgen=" + s.worldgen() + ", hasBiomes=" + hasBiomes + " (" + (s.biomes() != null ? s.biomes().size() : 0) + "), hasTrees=" + hasTrees);
            }
            return !keep;
        });
        int sizeAfter = speciesMap.size();
        
        platform.logger().info("Configured " + sizeAfter + " active species for generation (removed " + (sizeBefore - sizeAfter) + " inactive).");

        // Second pass: if any species are defined as 'trees' in active species, we MUST keep them too
        // even if they don't have their own biome mapping, so they can be referenced.
        Map<String, TreeSpecies> finalMap = new LinkedHashMap<>(speciesMap);
        boolean added;
        do {
            added = false;
            List<TreeSpecies> current = new ArrayList<>(finalMap.values());
            for (TreeSpecies s : current) {
                if (s.trees() == null) continue;
                for (TreeSpecies.WeightedSpecies ws : s.trees()) {
                    String refId = ws.id().split(":")[0].toLowerCase(Locale.ROOT);
                    if (!finalMap.containsKey(refId) && registered.containsKey(refId)) {
                        platform.logger().info("Re-adding referenced species (deep): " + refId);
                        finalMap.put(refId, registered.get(refId));
                        added = true;
                    }
                }
            }
        } while (added);

        return new TreeGenConfig(finalMap, packName, packDescription, mode);
    }

    /**
     * Helper to load from a generic map (e.g. from snakeyaml or other parsers).
     * @deprecated Use fromYaml for better multi-platform support.
     */
    @Deprecated
    @SuppressWarnings("unchecked")
    public static TreeGenConfig fromMap(Map<String, Object> map, Platform platform) {
        Map<String, TreeSpecies> out = new LinkedHashMap<>();

        Map<String, Object> dp = (Map<String, Object>) map.get("datapack");
        String packName = dp != null ? (String) dp.getOrDefault("name", "leaf-treegen-generated") : "leaf-treegen-generated";
        String packDescription = dp != null ? (String) dp.getOrDefault("description", "LeafTreeGen generated worldgen.") : "LeafTreeGen generated worldgen.";

        GenerationMode mode = GenerationMode.DATAPACK;
        String modeStr = (String) map.get("generation-mode");
        if (modeStr != null) {
            try {
                mode = GenerationMode.valueOf(modeStr.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ignored) {}
        }

        Map<String, Object> speciesRoot = (Map<String, Object>) map.get("species");
        if (speciesRoot == null) {
            platform.logger().warning("No 'species' section in config.");
            return new TreeGenConfig(out, packName, packDescription, mode);
        }

        for (Map.Entry<String, Object> entry : speciesRoot.entrySet()) {
            String id = entry.getKey();
            Map<String, Object> sec = (Map<String, Object>) entry.getValue();
            String key = id.toLowerCase(Locale.ROOT);
            String displayName = (String) sec.getOrDefault("display-name", id);
            String namespace = (String) sec.getOrDefault("namespace", "minecraft");
            String group = (String) sec.getOrDefault("group", key);

            Set<String> biomes = new HashSet<>();
            List<String> bList = (List<String>) sec.get("biomes");
            if (bList != null) {
                for (String b : bList) biomes.add(normaliseBiome(b));
            }

            String sapling = (String) sec.getOrDefault("sapling-item", "OAK_SAPLING");
            boolean worldgen = sec.containsKey("worldgen") ? (boolean) sec.get("worldgen") : !biomes.isEmpty();
            boolean replaceVanilla = sec.containsKey("replace-vanilla") ? (boolean) sec.get("replace-vanilla") : (sec.containsKey("replace_vanilla") ? (boolean) sec.get("replace_vanilla") : false);
            int spacing = (int) sec.getOrDefault("spacing", 3);
            int separation = (int) sec.getOrDefault("separation", 2);
            int salt = (int) sec.getOrDefault("salt", -1);
            String spreadType = ((String) sec.getOrDefault("spread-type", "linear")).toLowerCase(Locale.ROOT);
            String step = (String) sec.getOrDefault("step", "surface_structures");
            int weight = (int) sec.getOrDefault("weight", 1);

            List<TreeSpecies.TreeVariant> variants = new ArrayList<>();
            Map<String, Object> variantsMap = (Map<String, Object>) sec.get("variants");
            if (variantsMap != null) {
                for (Map.Entry<String, Object> vEntry : variantsMap.entrySet()) {
                    String vName = vEntry.getKey();
                    Object vVal = vEntry.getValue();
                    String location;
                    int vWeight = 1;
                    if (vVal instanceof Map) {
                        Map<String, Object> vv = (Map<String, Object>) vVal;
                        location = (String) vv.getOrDefault("location", vName);
                        vWeight = (int) vv.getOrDefault("weight", 1);
                    } else if (vVal instanceof Number n) {
                        location = vName;
                        vWeight = n.intValue();
                    } else {
                        location = vVal != null ? vVal.toString() : vName;
                    }
                    if (!location.contains(":")) location = namespace + ":" + group + "/" + location;
                    variants.add(new TreeSpecies.TreeVariant(location, vWeight));
                }
            }

            out.put(key, new TreeSpecies(key, displayName, namespace, group, biomes, sapling,
                    worldgen, replaceVanilla, spacing, separation, salt, spreadType, step, variants, null, null, null, weight));
        }

        return new TreeGenConfig(out, packName, packDescription, mode);
    }

    private static String normaliseBiome(String raw) {
        String b = raw.trim().toLowerCase(Locale.ROOT);
        if (b.isEmpty()) return b;
        return b.contains(":") ? b : "minecraft:" + b;
    }
}
