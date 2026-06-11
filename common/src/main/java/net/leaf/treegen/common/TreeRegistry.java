package net.leaf.treegen.common;
 
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.File;
import java.io.FileReader;
import java.nio.file.Path;
import java.util.*;

/**
 * Platform-agnostic tree variant and species registry.
 */
public final class TreeRegistry {

    private final Platform platform;
    private final Map<String, TreeSpecies> speciesMap = new HashMap<>();
    private static final Gson GSON = new Gson();

    public TreeRegistry(Platform platform) {
        this.platform = platform;
    }

    public void loadSpecies() {
        extractDefaultSpecies();
        speciesMap.clear();
        File speciesDir = platform.getRootFolder().resolve("species").toFile();
        if (!speciesDir.exists()) {
            speciesDir.mkdirs();
        }

        File[] files = speciesDir.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".json"));
        if (files == null) return;
        
        platform.logger().info("Loading species from " + speciesDir.getAbsolutePath() + " (found " + files.length + " files)");

        for (File file : files) {
            try (FileReader reader = new FileReader(file)) {
                Object jsonElement = GSON.fromJson(reader, Object.class);
                String baseId = file.getName().replace(".json", "");
                if (jsonElement instanceof List<?> list) {
                    Map<String, TreeSpecies.ProceduralParams> definitions = new HashMap<>();
                    for (int i = 0; i < list.size(); i++) {
                        Object entry = list.get(i);
                        if (entry instanceof Map<?, ?> map) {
                            JsonObject obj = GSON.toJsonTree(map).getAsJsonObject();
                            String treeName = obj.has("name") ? obj.get("name").getAsString() : "tree_" + i;
                            // Handle duplicate names in the same file
                            if (definitions.containsKey(treeName)) {
                                treeName = treeName + "_" + i;
                            }
                            definitions.put(treeName, parseProcedural(obj));
                        }
                    }
                    
                    // The species itself can be a container. We use the first entry for default props if needed,
                    // but mainly we want the treeDefinitions populated.
                    if (!list.isEmpty()) {
                        JsonObject first = GSON.toJsonTree(list.get(0)).getAsJsonObject();
                        TreeSpecies species = parseSpecies(first, baseId, definitions);
                        String speciesId = species.id().toLowerCase(Locale.ROOT);
                        speciesMap.put(speciesId, species);
                        platform.logger().info("Registered species container: " + speciesId + " (" + definitions.size() + " tree variants)");
                    }
                } else if (jsonElement instanceof Map<?, ?> map) {
                    TreeSpecies species = parseSpecies(GSON.toJsonTree(map).getAsJsonObject(), baseId, null);
                    String speciesId = species.id().toLowerCase(Locale.ROOT);
                    speciesMap.put(speciesId, species);
                    platform.logger().info("Registered species: " + speciesId);
                }
            } catch (Exception e) {
                platform.logger().severe("Failed to load species from " + file.getName() + ": " + e.getMessage());
            }
        }
    }

    private void extractDefaultSpecies() {
        File speciesDir = platform.getRootFolder().resolve("species").toFile();
        if (!speciesDir.exists()) {
            speciesDir.mkdirs();
        }

        // Extract default config.yml if missing
        File configFile = platform.getRootFolder().resolve("config.yml").toFile();
        if (!configFile.exists()) {
            try {
                java.nio.file.Files.createDirectories(platform.getRootFolder());
                try (java.io.InputStream is = TreeRegistry.class.getClassLoader().getResourceAsStream("config.yml")) {
                    if (is != null) {
                        java.nio.file.Files.copy(is, configFile.toPath());
                        platform.logger().info("Extracted default config.yml (Size: " + configFile.length() + ")");
                    } else {
                        try (java.io.InputStream is2 = TreeRegistry.class.getResourceAsStream("/config.yml")) {
                            if (is2 != null) {
                                java.nio.file.Files.copy(is2, configFile.toPath());
                                platform.logger().info("Extracted default config.yml via getResourceAsStream('/') (Size: " + configFile.length() + ")");
                            }
                        }
                    }
                }
            } catch (java.io.IOException e) {
                platform.logger().severe("Failed to extract default config.yml: " + e.getMessage());
            }
        }

        // List of default species to extract from resources
        String[] defaults = {
            "ashwood.json", "birch-forest.json", "birch.json", "cherry.json", "cloudcap.json",
            "dark-forest-megas.json", "dark-forest.json", "forest.json", "glacierpine.json",
            "glowcap.json", "jungle.json", "lavatree.json", "mangrove.json", "oak.json",
            "rainbow-grove.json", "rainbow-oak.json", "savanna.json", "spruce.json",
            "swamp.json", "taiga.json"
        };

        for (String name : defaults) {
            File outFile = new File(speciesDir, name);
            if (!outFile.exists()) {
                java.io.InputStream is = TreeRegistry.class.getResourceAsStream("/species/" + name);
                if (is == null) {
                    is = TreeRegistry.class.getClassLoader().getResourceAsStream("species/" + name);
                }
                
                if (is != null) {
                    try (java.io.InputStream finalIs = is) {
                        java.nio.file.Files.copy(finalIs, outFile.toPath());
                        platform.logger().info("Extracted default species: " + name);
                    } catch (java.io.IOException e) {
                        platform.logger().severe("Failed to extract default species " + name + ": " + e.getMessage());
                    }
                }
            }
        }
    }

    private TreeSpecies parseSpecies(JsonObject json, String defaultId) {
        return parseSpecies(json, defaultId, null);
    }

    private TreeSpecies.ProceduralParams parseProcedural(JsonObject p) {
        TreeSpecies.CanopyParams canopy = null;
        if (p.has("canopy")) {
            JsonObject c = p.getAsJsonObject("canopy");
            List<TreeSpecies.CanopyParams.Layer> layers = new ArrayList<>();
            if (c.has("layers")) {
                c.getAsJsonArray("layers").forEach(l -> {
                    JsonObject lo = l.getAsJsonObject();
                    layers.add(new TreeSpecies.CanopyParams.Layer(
                        lo.has("yOffset") ? lo.get("yOffset").getAsInt() : (lo.has("y_offset") ? lo.get("y_offset").getAsInt() : 0),
                        lo.get("radius").getAsDouble()
                    ));
                });
            }
            
            TreeSpecies.BranchParams branches = null;
            if (c.has("branches")) {
                JsonObject b = c.getAsJsonObject("branches");
                branches = new TreeSpecies.BranchParams(
                    b.has("count") ? b.get("count").getAsInt() : 0,
                    b.has("min-length") ? b.get("min-length").getAsDouble() : (b.has("lengthBase") ? b.get("lengthBase").getAsDouble() : 2.0),
                    b.has("max-length") ? b.get("max-length").getAsDouble() : (b.has("lengthCrown") ? b.get("lengthCrown").getAsDouble() : 5.0),
                    b.has("length-fn") ? b.get("length-fn").getAsString() : "CONSTANT",
                    parseMap(b.getAsJsonObject("length-params")),
                    b.has("min-elevation") ? b.get("min-elevation").getAsDouble() : (b.has("elevation") ? b.get("elevation").getAsDouble() : -20.0),
                    b.has("max-elevation") ? b.get("max-elevation").getAsDouble() : (b.has("elevation") ? b.get("elevation").getAsDouble() : 45.0),
                    b.has("spacing") ? b.get("spacing").getAsDouble() : 1.0,
                    b.has("start-height") ? b.get("start-height").getAsDouble() : 0.6
                );
            }

            canopy = new TreeSpecies.CanopyParams(
                c.has("mode") ? c.get("mode").getAsString() : "DENSITY",
                c.has("density") ? c.get("density").getAsDouble() : (c.has("leafDensity") ? c.get("leafDensity").getAsDouble() : 1.0),
                layers,
                branches,
                c.has("secondary-leaves") ? c.get("secondary-leaves").getAsString() : null,
                c.has("secondary-fraction") ? c.get("secondary-fraction").getAsDouble() : 0.0
            );
        }

        return new TreeSpecies.ProceduralParams(
            p.has("trunk-block") ? p.get("trunk-block").getAsString() : (p.has("trunk") ? p.get("trunk").getAsString() : "minecraft:oak_log"),
            p.has("leaf-block") ? p.get("leaf-block").getAsString() : (p.has("leaves") ? p.get("leaves").getAsString() : "minecraft:oak_leaves"),
            p.has("height-min") ? p.get("height-min").getAsInt() : (p.has("height_min") ? p.get("height_min").getAsInt() : (p.has("heightMin") ? p.get("heightMin").getAsInt() : 5)),
            p.has("height-max") ? p.get("height-max").getAsInt() : (p.has("height_max") ? p.get("height_max").getAsInt() : (p.has("heightMax") ? p.get("heightMax").getAsInt() : 10)),
            p.has("profile") ? p.get("profile").getAsString() : "OAK",
            p.has("trunk-width") ? p.get("trunk-width").getAsDouble() : (p.has("trunk_width") ? p.get("trunk_width").getAsDouble() : (p.has("trunkWidth") ? p.get("trunkWidth").getAsDouble() : 1.0)),
            p.has("trunk-shape") ? p.get("trunk-shape").getAsString() : (p.has("trunk_shape") ? p.get("trunk_shape").getAsString() : (p.has("trunkShape") ? p.get("trunkShape").getAsString() : "CONSTANT")),
            parseMap(p.has("trunk-shape-params") ? p.getAsJsonObject("trunk-shape-params") : p.getAsJsonObject("trunk_shape_params")),
            p.has("round-trunk") ? p.get("round-trunk").getAsBoolean() : (p.has("round_trunk") ? p.get("round_trunk").getAsBoolean() : false),
            p.has("lean-angle") ? p.get("lean-angle").getAsDouble() : (p.has("lean_angle") ? p.get("lean_angle").getAsDouble() : (p.has("leanAngle") ? p.get("leanAngle").getAsDouble() : 0.0)),
            p.has("lean-azimuth") ? p.get("lean-azimuth").getAsDouble() : (p.has("lean_azimuth") ? p.get("lean_azimuth").getAsDouble() : (p.has("leanAzimuth") ? p.get("leanAzimuth").getAsDouble() : 0.0)),
            p.has("azimuth-fn") ? p.get("azimuth-fn").getAsString() : (p.has("lean_azimuth_fn") ? p.get("lean_azimuth_fn").getAsString() : "CONSTANT"),
            parseMap(p.has("azimuth-params") ? p.getAsJsonObject("azimuth-params") : p.getAsJsonObject("lean_azimuth_params")),
            p.has("curve-fn") ? p.get("curve-fn").getAsString() : (p.has("trunk_curve_fn") ? p.get("trunk_curve_fn").getAsString() : "LINEAR"),
            parseMap(p.has("curve-params") ? p.getAsJsonObject("curve-params") : p.getAsJsonObject("trunk_curve_params")),
            p.has("secondary-trunk") ? p.get("secondary-trunk").getAsString() : (p.has("secondary_trunk") ? p.get("secondary_trunk").getAsString() : null),
            p.has("secondary-trunk-start") ? p.get("secondary-trunk-start").getAsDouble() : (p.has("secondary_trunk_start") ? p.get("secondary_trunk_start").getAsDouble() : 0.5),
            p.has("secondary-trunk-end") ? p.get("secondary-trunk-end").getAsDouble() : (p.has("secondary_trunk_end") ? p.get("secondary_trunk_end").getAsDouble() : 1.0),
            canopy,
            p.has("weight") ? p.get("weight").getAsInt() : (p.has("count") ? p.get("count").getAsInt() : 1)
        );
    }

    private TreeSpecies parseSpecies(JsonObject json, String defaultId, Map<String, TreeSpecies.ProceduralParams> treeDefinitions) {
        String id = json.has("id") ? json.get("id").getAsString() : defaultId;
        String displayName = json.has("display-name") ? json.get("display-name").getAsString() : (json.has("name") ? json.get("name").getAsString() : id);
        String namespace = json.has("namespace") ? json.get("namespace").getAsString() : "minecraft";
        String group = json.has("group") ? json.get("group").getAsString() : id;
        
        Set<String> biomes = new HashSet<>();
        if (json.has("biomes")) {
            json.getAsJsonArray("biomes").forEach(b -> biomes.add(b.getAsString()));
        }

        String saplingItem = json.has("sapling-item") ? json.get("sapling-item").getAsString() : "OAK_SAPLING";
        boolean worldgen = json.has("worldgen") ? json.get("worldgen").getAsBoolean() : (json.has("world-gen") ? json.get("world-gen").getAsBoolean() : (json.has("world_gen") ? json.get("world_gen").getAsBoolean() : !biomes.isEmpty()));
        boolean replaceVanilla = json.has("replace-vanilla") ? json.get("replace-vanilla").getAsBoolean() : (json.has("replace_vanilla") ? json.get("replace_vanilla").getAsBoolean() : false);
        int spacing = json.has("spacing") ? json.get("spacing").getAsInt() : 3;
        int separation = json.has("separation") ? json.get("separation").getAsInt() : 2;
        int salt = json.has("salt") ? json.get("salt").getAsInt() : -1;
        String spreadType = json.has("spread-type") ? json.get("spread-type").getAsString() : "linear";
        String step = json.has("step") ? json.get("step").getAsString() : "surface_structures";

        List<TreeSpecies.TreeVariant> variants = new ArrayList<>();
        if (json.has("variants") && json.get("variants").isJsonObject()) {
            JsonObject vObj = json.getAsJsonObject("variants");
            for (String vKey : vObj.keySet()) {
                if (vObj.get(vKey).isJsonObject()) {
                    JsonObject vv = vObj.getAsJsonObject(vKey);
                    variants.add(new TreeSpecies.TreeVariant(vv.get("location").getAsString(), vv.has("weight") ? vv.get("weight").getAsInt() : 1));
                } else {
                    variants.add(new TreeSpecies.TreeVariant(vKey, vObj.get(vKey).getAsInt()));
                }
            }
        }

        TreeSpecies.ProceduralParams procedural = null;
        if (json.has("procedural") || json.has("trunk") || json.has("leaves")) {
            JsonObject p = json.has("procedural") ? json.getAsJsonObject("procedural") : json;
            procedural = parseProcedural(p);
        }

        int placementWeight = json.has("weight") ? json.get("weight").getAsInt() : (json.has("count") ? json.get("count").getAsInt() : 1);

        return new TreeSpecies(id, displayName, namespace, group, biomes, saplingItem, worldgen, replaceVanilla, spacing, separation, salt, spreadType, step, variants, procedural, null, treeDefinitions, placementWeight);
    }

    private Map<String, Double> parseMap(JsonObject obj) {
        Map<String, Double> out = new HashMap<>();
        if (obj != null) {
            for (String key : obj.keySet()) {
                if (obj.get(key).isJsonPrimitive() && obj.get(key).getAsJsonPrimitive().isNumber()) {
                    out.put(key, obj.get(key).getAsDouble());
                }
            }
        }
        return out;
    }

    public Map<String, TreeSpecies> getSpeciesMap() {
        return new HashMap<>(speciesMap);
    }

    public List<String> variantsFor(String worldName, TreeSpecies species) {
        if (species.variants() != null && !species.variants().isEmpty()) {
            return species.variantLocations();
        }
        List<String> out = new ArrayList<>();
        Path worldFolder = platform.getWorldFolder(worldName);
        if (worldFolder == null) return out;
        File datapacks = worldFolder.resolve("datapacks").toFile();
        File[] packs = datapacks.listFiles(File::isDirectory);
        if (packs == null) return out;

        for (File pack : packs) {
            File groupDir = new File(pack, "data/" + species.namespace() + "/structure/" + species.group());
            File[] nbts = groupDir.listFiles((dir, name) -> name.toLowerCase(Locale.ROOT).endsWith(".nbt"));
            if (nbts == null) continue;

            for (File nbt : nbts) {
                String base = nbt.getName().substring(0, nbt.getName().length() - ".nbt".length());
                String key = species.namespace() + ":" + species.group() + "/" + base;
                if (!out.contains(key)) out.add(key);
            }
        }
        out.sort(String::compareTo);
        return out;
    }
}
