package net.leaf.treegen.common;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Builds a vanilla worldgen datapack from the configured {@link TreeSpecies}.
 * Platform-agnostic (uses java.nio.file.Path).
 */
public final class DatapackGenerator {

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Platform platform;

    public DatapackGenerator(Platform platform) {
        this.platform = platform;
    }

    public int generate(String worldName, TreeGenConfig config, TreeRegistry registry) {
        Path worldFolder = platform.getWorldFolder(worldName);
        if (worldFolder == null) return 0;

        Path packRoot = worldFolder.resolve("datapacks").resolve(config.packName());
        try {
            wipe(packRoot);
            Files.createDirectories(packRoot);
            writePackMeta(packRoot, config.packDescription());

            int written = 0;
            Map<String, TreeSpecies> activeSpecies = config.species();
            if (activeSpecies.isEmpty()) {
                platform.logger().warning("No species found in config for datapack generation.");
            }

            for (TreeSpecies species : activeSpecies.values()) {
                if (!species.worldgen()) {
                    platform.logger().info("Species '" + species.id() + "': worldgen disabled; skipping.");
                    continue;
                }
                if (species.biomes().isEmpty()) {
                    platform.logger().warning("Species '" + species.id() + "': no biomes set; skipping.");
                    continue;
                }

                String ns = species.namespace();
                String id = species.id();
                String group = species.group();
                String poolName = ns + ":" + group + "/" + id;

                // Write structure common parts
                writeStructureCommon(packRoot, species, ns, id, poolName);
                // No need to duplicate into minecraft namespace in the outer loop anymore
                // as writeStructureCommon handles it internally now.

                if (species.trees() != null && !species.trees().isEmpty()) {
                    writeGroupPool(packRoot, species, ns, id, group, poolName, config, registry);
                } else {
                    List<String> variants = registry.variantsFor(worldName, species);
                    if (variants.isEmpty() && species.procedural() == null && (species.treeDefinitions() == null || species.treeDefinitions().isEmpty())) {
                        platform.logger().warning("Species '" + species.id() + "': no variants, procedural config, or tree definitions found; skipping.");
                        continue;
                    }
                    writeSpeciesPool(packRoot, species, ns, id, group, poolName, variants);
                }

                if (species.replaceVanilla()) {
                    writeSuppression(packRoot, species);
                    platform.logger().info("Species '" + species.id() + "': generated vanilla suppression features.");
                }
                written++;
            }

            platform.logger().info("Generated worldgen datapack '" + config.packName() + "' with "
                    + written + " species at " + packRoot);
            return written;
        } catch (IOException ex) {
            throw new UncheckedIOException("Failed to generate datapack at " + packRoot, ex);
        }
    }

    private void writeStructureCommon(Path packRoot, TreeSpecies species, String ns, String id, String poolName) throws IOException {
        // structure_set
        Map<String, Object> structureEntry = new LinkedHashMap<>();
        structureEntry.put("structure", ns + ":" + id);
        structureEntry.put("weight", 1);
        Map<String, Object> placement = new LinkedHashMap<>();
        placement.put("type", "minecraft:random_spread");
        placement.put("salt", species.effectiveSalt());
        placement.put("spacing", species.spacing());
        placement.put("separation", species.separation());
        placement.put("spread_type", species.spreadType());
        Map<String, Object> structureSet = new LinkedHashMap<>();
        structureSet.put("structures", List.of(structureEntry));
        structureSet.put("placement", placement);
        writeJson(dataFile(packRoot, ns, "worldgen/structure_set/" + id + ".json"), structureSet);
        if (!ns.equals("minecraft")) {
            writeJson(dataFile(packRoot, "minecraft", "worldgen/structure_set/" + id + ".json"), structureSet);
        }

        // structure
        Map<String, Object> structure = new LinkedHashMap<>();
        structure.put("type", "minecraft:jigsaw");
        structure.put("biomes", "#" + ns + ":has_structure/" + id);
        structure.put("step", species.step());
        structure.put("spawn_overrides", new LinkedHashMap<>());
        structure.put("terrain_adaptation", "none");
        structure.put("start_pool", poolName);
        structure.put("size", 1);
        structure.put("start_height", Map.of("absolute", 0));
        structure.put("project_start_to_heightmap", "WORLD_SURFACE_WG");
        structure.put("max_distance_from_center", 80);
        structure.put("use_expansion_hack", false);
        writeJson(dataFile(packRoot, ns, "worldgen/structure/" + id + ".json"), structure);
        if (!ns.equals("minecraft")) {
            writeJson(dataFile(packRoot, "minecraft", "worldgen/structure/" + id + ".json"), structure);
        }

        // has_structure biome tag
        List<String> biomes = new ArrayList<>(species.biomes());
        biomes.sort(Comparator.naturalOrder());
        writeJson(dataFile(packRoot, ns, "tags/worldgen/biome/has_structure/" + id + ".json"),
                Map.of("values", biomes));
        if (!ns.equals("minecraft")) {
            writeJson(dataFile(packRoot, "minecraft", "tags/worldgen/biome/has_structure/" + id + ".json"),
                    Map.of("values", biomes));
        }
    }

    private void writeGroupPool(Path packRoot, TreeSpecies species, String ns, String id, String group, String poolName, TreeGenConfig config, TreeRegistry registry) throws IOException {
        // template_pool (Grouped)
        List<Map<String, Object>> elements = new ArrayList<>();
        for (TreeSpecies.WeightedSpecies ws : species.trees()) {
            String[] parts = ws.id().split(":", 2);
            String speciesId = parts[0];
            String treeName = parts.length > 1 ? parts[1] : null;

            TreeSpecies child = config.get(speciesId);
            if (child == null) {
                // Try hyphen/underscore normalization
                child = config.get(speciesId.replace("_", "-"));
                if (child == null) child = config.get(speciesId.replace("-", "_"));
            }
            
            if (child == null) {
                platform.logger().warning("Group '" + id + "' references unknown species '" + speciesId + "'");
                continue;
            }

            if (treeName != null) {
                // Specific named tree reference
                Map<String, Object> el = new LinkedHashMap<>();
                el.put("element_type", "minecraft:single_pool_element");
                el.put("location", child.namespace() + ":" + child.group() + "/" + treeName);
                el.put("processors", "minecraft:empty");
                el.put("projection", "rigid");

                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("weight", ws.weight());
                entry.put("element", el);
                elements.add(entry);
            } else {
                // Entire species reference - flatten its variants
                List<String> variants = registry.variantsFor("world", child);
                if (child.variants().isEmpty()) {
                    for (String v : variants) {
                        Map<String, Object> el = new LinkedHashMap<>();
                        el.put("element_type", "minecraft:single_pool_element");
                        el.put("location", v);
                        el.put("processors", "minecraft:empty");
                        el.put("projection", "rigid");

                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("weight", ws.weight());
                        entry.put("element", el);
                        elements.add(entry);
                    }
                } else {
                    for (TreeSpecies.TreeVariant v : child.variants()) {
                        Map<String, Object> el = new LinkedHashMap<>();
                        el.put("element_type", "minecraft:single_pool_element");
                        el.put("location", v.location());
                        el.put("processors", "minecraft:empty");
                        el.put("projection", "rigid");

                        Map<String, Object> entry = new LinkedHashMap<>();
                        entry.put("weight", ws.weight() * v.weight());
                        entry.put("element", el);
                        elements.add(entry);
                    }
                }
            }
        }
        
        Map<String, Object> pool = new LinkedHashMap<>();
        pool.put("name", poolName);
        pool.put("fallback", "minecraft:empty");
        pool.put("elements", elements);
        writeJson(dataFile(packRoot, ns, "worldgen/template_pool/" + group + "/" + id + ".json"), pool);
    }

    private void writeSpeciesPool(Path packRoot, TreeSpecies species, String ns, String id, String group, String poolName, List<String> variants) throws IOException {
        // template_pool
        List<Map<String, Object>> elements = new ArrayList<>();
        
        // Add named tree definitions if they exist
        if (species.treeDefinitions() != null && !species.treeDefinitions().isEmpty()) {
            for (String treeName : species.treeDefinitions().keySet()) {
                Map<String, Object> element = new LinkedHashMap<>();
                element.put("element_type", "minecraft:single_pool_element");
                element.put("location", ns + ":" + group + "/" + treeName);
                element.put("processors", "minecraft:empty");
                element.put("projection", "rigid");
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("weight", 1);
                entry.put("element", element);
                elements.add(entry);
            }
        }
        
        if (!species.variants().isEmpty()) {
            for (TreeSpecies.TreeVariant v : species.variants()) {
                Map<String, Object> element = new LinkedHashMap<>();
                element.put("element_type", "minecraft:single_pool_element");
                element.put("location", v.location());
                element.put("processors", "minecraft:empty");
                element.put("projection", "rigid");
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("weight", v.weight());
                entry.put("element", element);
                elements.add(entry);
            }
        } else {
            for (String variant : variants) {
                Map<String, Object> element = new LinkedHashMap<>();
                element.put("element_type", "minecraft:single_pool_element");
                element.put("location", variant);
                element.put("processors", "minecraft:empty");
                element.put("projection", "rigid");
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("weight", 1);
                entry.put("element", element);
                elements.add(entry);
            }
        }
        Map<String, Object> pool = new LinkedHashMap<>();
        pool.put("name", poolName);
        pool.put("fallback", "minecraft:empty");
        pool.put("elements", elements);
        writeJson(dataFile(packRoot, ns, "worldgen/template_pool/" + group + "/" + id + ".json"), pool);
    }

    private void writeSuppression(Path packRoot, TreeSpecies species) throws IOException {
        // To suppress vanilla trees, we need to override the PlacedFeatures in the minecraft namespace.
        // We use an impossible placement filter to ensure they never spawn.
        
        List<String> targetFeatures = List.of(
            "trees_oak", "trees_birch", "trees_spruce", "trees_jungle", "trees_savanna", "trees_dark_forest",
            "oak_checked", "oak_bees_0002", "birch_checked", "birch_bees_0002", "spruce_checked", "spruce_bees_0002",
            "jungle_tree_checked", "fancy_oak_checked", "dark_oak_checked", "cherry_checked", "mangrove_checked",
            "trees_water", "trees_birch_and_oak", "trees_swamp", "trees_sparse_jungle", "trees_old_growth_pine_taiga",
            "trees_old_growth_spruce_taiga", "trees_jungle_edge", "oak_mountain", "oak_mountain_bees"
        );
        
        for (String feature : targetFeatures) {
            Map<String, Object> empty = new LinkedHashMap<>();
            // We reference a vanilla feature but with impossible placement
            empty.put("feature", "minecraft:oak"); 
            empty.put("placement", List.of(
                Map.of("type", "minecraft:block_predicate_filter", "predicate", Map.of("type", "minecraft:matching_blocks", "blocks", List.of("minecraft:bedrock")))
            ));
            
            writeJson(dataFile(packRoot, "minecraft", "worldgen/placed_feature/" + feature + ".json"), empty);
        }
    }

    private void writePackMeta(Path packRoot, String description) throws IOException {
        Map<String, Object> pack = new LinkedHashMap<>();
        pack.put("description", description);
        pack.put("pack_format", 71);
        pack.put("supported_formats", List.of(61, 71));
        writeJson(packRoot.resolve("pack.mcmeta"), Map.of("pack", pack));
    }

    private static Path dataFile(Path packRoot, String namespace, String relative) {
        return packRoot.resolve("data").resolve(namespace).resolve(relative);
    }

    private static void writeJson(Path file, Object value) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, GSON.toJson(value), StandardCharsets.UTF_8);
    }

    private static void wipe(Path root) throws IOException {
        if (!Files.exists(root)) return;
        try (Stream<Path> walk = Files.walk(root)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.delete(p);
                } catch (IOException ex) {
                    throw new UncheckedIOException(ex);
                }
            });
        }
    }
}
