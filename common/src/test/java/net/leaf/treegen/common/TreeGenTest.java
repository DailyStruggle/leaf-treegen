package net.leaf.treegen.common;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.logging.Logger;

public class TreeGenTest {

    private static final Gson GSON = new Gson();
    private MockPlatform platform;
    private TreeRegistry registry;

    @BeforeEach
    public void setup() {
        platform = new MockPlatform();
        registry = new TreeRegistry(platform);
    }

    @Test
    public void testJsonIngestion() {
        // Demonstrate ingestion of a "flat" JSON schema (Python tool output)
        String jsonStr = "{\n" +
                "  \"name\": \"Spiral Oak\",\n" +
                "  \"trunk\": \"minecraft:dark_oak_log\",\n" +
                "  \"leaves\": \"minecraft:dark_oak_leaves\",\n" +
                "  \"heightMin\": 15,\n" +
                "  \"heightMax\": 25,\n" +
                "  \"trunkWidth\": 2.0,\n" +
                "  \"azimuth-fn\": \"SPIRAL\",\n" +
                "  \"azimuth-params\": {\"step\": 0.1},\n" +
                "  \"canopy\": {\n" +
                "    \"leafDensity\": 0.8,\n" +
                "    \"layers\": [\n" +
                "      {\"y_offset\": 0, \"radius\": 4.0},\n" +
                "      {\"y_offset\": 2, \"radius\": 3.0}\n" +
                "    ]\n" +
                "  }\n" +
                "}";

        JsonObject json = GSON.fromJson(jsonStr, JsonObject.class);
        
        // TreeRegistry.parseSpecies is private, but loadSpecies calls it.
        // For testing purposes, we can test that it handles the flat fields correctly.
        // Since loadSpecies reads from files, we'll create a temporary file.
        
        Path speciesDir = platform.getRootFolder().resolve("species");
        speciesDir.toFile().mkdirs();
        
        java.io.File speciesFile = speciesDir.resolve("spiral_oak.json").toFile();
        try (java.io.FileWriter writer = new java.io.FileWriter(speciesFile)) {
            writer.write(jsonStr);
        } catch (Exception e) {
            Assertions.fail(e);
        }

        registry.loadSpecies();
        
        TreeSpecies species = registry.getSpeciesMap().get("spiral_oak");
        Assertions.assertNotNull(species, "Species should be loaded and keyed by lowercase name. Keys found: " + registry.getSpeciesMap().keySet());
        Assertions.assertEquals("Spiral Oak", species.displayName());
        
        TreeSpecies.ProceduralParams proc = species.procedural();
        Assertions.assertNotNull(proc, "Procedural params should be parsed from flat fields");
        Assertions.assertEquals("minecraft:dark_oak_log", proc.trunkBlock());
        Assertions.assertEquals("minecraft:dark_oak_leaves", proc.leafBlock());
        Assertions.assertEquals(15, proc.minHeight());
        Assertions.assertEquals(25, proc.maxHeight());
        Assertions.assertEquals(2.0, proc.trunkWidth());
        Assertions.assertEquals("SPIRAL", proc.azimuthFn());
        Assertions.assertEquals(0.1, proc.azimuthParams().get("step"));
        
        Assertions.assertNotNull(proc.canopy(), "Canopy should be parsed");
        Assertions.assertEquals(0.8, proc.canopy().density());
        Assertions.assertEquals(2, proc.canopy().layers().size());
        Assertions.assertEquals(4.0, proc.canopy().layers().get(0).radius());
    }

    @Test
    public void testProceduralGeneration() {
        // Test that procedural generation actually places blocks
        TreeSpecies species = new TreeSpecies(
                "test_tree", "Test Tree", "leaf", "test",
                new HashSet<>(), "OAK_SAPLING", true, false, 3, 2, -1, "linear", "surface_structures",
                new ArrayList<>(),
                new TreeSpecies.ProceduralParams(
                        "minecraft:oak_log", "minecraft:oak_leaves", 5, 5, "OAK",
                        1.0, "CONSTANT", new HashMap<>(), false,
                        0.0, 0.0, "CONSTANT", new HashMap<>(),
                        "LINEAR", new HashMap<>(),
                        null, 0.5, 1.0,
                        new TreeSpecies.CanopyParams("DENSITY", 1.0, 
                                List.of(new TreeSpecies.CanopyParams.Layer(0, 2.0)),
                                null, null, 0.0),
                        1
                ),
                new ArrayList<>(),
                new HashMap<>(),
                1
        );

        ProceduralGenerator generator = new ProceduralGenerator(platform, registry);
        generator.generate("world", 0, 64, 0, species, new Random(123));

        Assertions.assertFalse(platform.placedBlocks.isEmpty(), "Generator should have placed some blocks");
        
        // Check for trunk
        boolean hasLog = platform.placedBlocks.values().stream().anyMatch(b -> b.contains("log"));
        Assertions.assertTrue(hasLog, "Should have placed at least one log block");

        // Check for leaves
        boolean hasLeaves = platform.placedBlocks.values().stream().anyMatch(b -> b.contains("leaves"));
        Assertions.assertTrue(hasLeaves, "Should have placed at least one leaf block");
    }

    @Test
    void testCacheEvictionOnMReuses() {
        MockPlatform platform = new MockPlatform();
        TreeRegistry registry = new TreeRegistry(platform);
        TreeSpecies species = new TreeSpecies("eviction_test", "Eviction", "ns", "gp", Set.of(), "OAK", true, false, 3, 2, -1, "linear", "step", List.of(), null, null, null, 1);
        
        // N=100, M=2
        TreeCache cache = new TreeCache(100, 2);
        
        long seed = 12345L;
        int[] genCount = {0};
        TreeCache.GeneratorFunction gen = s -> {
            genCount[0]++;
            return new TreeModel("eviction_test");
        };

        // 1st call: Generate
        cache.getOrGenerate("eviction_test", seed, gen);
        Assertions.assertEquals(1, genCount[0]);

        // 2nd call: Reuse (this is the 1st reuse, usage becomes 1)
        cache.getOrGenerate("eviction_test", seed, gen);
        Assertions.assertEquals(1, genCount[0]);

        // 3rd call: Reuse (this is the 2nd reuse, usage becomes 2, it should be removed AFTER returning)
        // Wait, my implementation: cached.uses++ -> if (uses >= maxReuses) remove.
        // So on 2nd call (1st reuse): uses becomes 1.
        // On 3rd call (2nd reuse): uses becomes 2, it is removed.
        cache.getOrGenerate("eviction_test", seed, gen);
        Assertions.assertEquals(1, genCount[0]);

        // 4th call: Should generate again
        cache.getOrGenerate("eviction_test", seed, gen);
        Assertions.assertEquals(2, genCount[0]);
    }

    @Test
    void testCacheEfficiency() {
        MockPlatform platform = new MockPlatform();
        TreeRegistry registry = new TreeRegistry(platform);
        TreeSpecies species = new TreeSpecies("cache_test", "Cache", "ns", "gp", Set.of(), "OAK", true, false, 3, 2, -1, "linear", "step", List.of(), null, null, null, 1);
        ProceduralGenerator generator = new ProceduralGenerator(platform, registry);

        Random r1 = new Random(100);
        generator.generate("world", 0, 64, 0, species, r1);
        int blockCount1 = platform.placedBlocks.size();

        platform.placedBlocks.clear();
        Random r2 = new Random(100); // Same seed
        generator.generate("world", 10, 64, 10, species, r2);
        int blockCount2 = platform.placedBlocks.size();

        Assertions.assertEquals(blockCount1, blockCount2, "Cache should produce same number of blocks for same seed");
    }

    @Test
    public void testTrunkLogicParity() {
        // Mock a 2.0 width trunk, which should be 2x2 blocks per layer
        TreeSpecies.ProceduralParams params = new TreeSpecies.ProceduralParams(
                "minecraft:oak_log", "minecraft:oak_leaves", 3, 3, "OAK",
                2.0, "CONSTANT", new HashMap<>(), false,
                0.0, 0.0, "CONSTANT", new HashMap<>(),
                "LINEAR", new HashMap<>(),
                null, 0.5, 1.0,
                null, 1
        );
        TreeSpecies species = new TreeSpecies("parity", "Parity", "ns", "gp", Set.of(), "OAK", true, false, 3, 2, -1, "linear", "step", List.of(), params, null, null, 1);

        ProceduralGenerator generator = new ProceduralGenerator(platform, registry);
        
        // Test buildModel
        TreeModel model = generator.buildModel(species, new Random(42));
        Assertions.assertNotNull(model);
        Assertions.assertFalse(model.getBlocks().isEmpty());

        // Test SegmentedTreeModel
        SegmentedTreeModel segmented = new SegmentedTreeModel(model);
        segmented.place(platform, "world", 0, 64, 0);

        // Layer 0 should be at 0,64,0. 
        // Python _square_positions for width=2.0 (even) and cx=0, cz=0:
        // ox = int(floor(0)) - 2//2 + 1 = 0 - 1 + 1 = 0
        // oz = int(floor(0)) - 2//2 + 1 = 0 - 1 + 1 = 0
        // yields (0,0), (0,1), (1,0), (1,1)
        
        // Let's check what Java currently does
        platform.placedBlocks.keySet().forEach(k -> {
            if (k.contains(",64,")) {
                System.out.println("[DEBUG_LOG] Block at y=64: " + k + " = " + platform.placedBlocks.get(k));
            }
        });

        Assertions.assertTrue(platform.placedBlocks.containsKey("0,64,0"));
        Assertions.assertTrue(platform.placedBlocks.containsKey("0,64,1"));
        Assertions.assertTrue(platform.placedBlocks.containsKey("1,64,0"));
        Assertions.assertTrue(platform.placedBlocks.containsKey("1,64,1"));
        Assertions.assertEquals(4, platform.placedBlocks.keySet().stream().filter(k -> k.contains(",64,")).count(), "Layer 64 should have 4 blocks");

        // Verify wood variant: Layer 64 bottom (y=64) should be wood because y-1 is air
        // Actually trunk starts at y=64. So y=64 is the bottom.
        // It should be minecraft:oak_wood
        Assertions.assertEquals("minecraft:oak_wood", platform.placedBlocks.get("0,64,0"));
    }

    @Test
    public void testDarkForestGiantsIngestion() throws Exception {
        // Demonstrate processing of dark-forest-giants.json
        Path giantsJsonPath = Paths.get("src", "main", "resources", "species", "dark-forest-megas.json");
        java.io.File giantsFile = giantsJsonPath.toFile();
        
        if (!giantsFile.exists()) {
            // Fallback for different working directories
            giantsJsonPath = Paths.get("plugins", "leaf-treegen", "common", "src", "main", "resources", "species", "dark-forest-megas.json");
            giantsFile = giantsJsonPath.toFile();
        }

        Assertions.assertTrue(giantsFile.exists(), "dark-forest-megas.json should exist at " + giantsFile.getAbsolutePath());

        // Copy to species dir for registry to find
        Path speciesDir = platform.getRootFolder().resolve("species");
        speciesDir.toFile().mkdirs();
        java.nio.file.Files.copy(giantsJsonPath, speciesDir.resolve("dark-forest-megas.json"), java.nio.file.StandardCopyOption.REPLACE_EXISTING);

        registry.loadSpecies();

        Map<String, TreeSpecies> map = registry.getSpeciesMap();
        Assertions.assertTrue(map.containsKey("dark-forest-megas"), "Should contain dark-forest-megas. Keys found: " + map.keySet());
        TreeSpecies megas = map.get("dark-forest-megas");
        Assertions.assertTrue(megas.treeDefinitions().containsKey("titan"), "Should contain titan variant");
        Assertions.assertTrue(megas.treeDefinitions().containsKey("spiral"), "Should contain spiral variant");

        TreeSpecies.ProceduralParams titan0 = megas.treeDefinitions().get("titan");
        Assertions.assertEquals("minecraft:dark_oak_log", titan0.trunkBlock());
        // titan has height_min: 55, height_max: 75
        Assertions.assertEquals(55, titan0.minHeight());
        Assertions.assertEquals(75, titan0.maxHeight());

        TreeSpecies.ProceduralParams titan1 = megas.treeDefinitions().get("titan_1");
        Assertions.assertEquals("parabolic", titan1.trunkShape().toLowerCase(java.util.Locale.ROOT));
        Assertions.assertEquals(0.35, titan1.trunkShapeParams().get("peak_offset"));

        TreeSpecies.ProceduralParams spiral3 = megas.treeDefinitions().get("spiral_3");
        Assertions.assertEquals("spiral", spiral3.azimuthFn().toLowerCase(java.util.Locale.ROOT));
        Assertions.assertEquals("sigmoid", spiral3.curveFn().toLowerCase(java.util.Locale.ROOT));
    }

    @Test
    public void testAllConfigParsing() throws Exception {
        Path speciesResourcesPath = Paths.get("src", "main", "resources", "species");
        if (!speciesResourcesPath.toFile().exists()) {
            speciesResourcesPath = Paths.get("plugins", "leaf-treegen", "common", "src", "main", "resources", "species");
        }
        
        Assertions.assertTrue(speciesResourcesPath.toFile().exists(), "Species resources directory should exist");
        
        java.io.File[] files = speciesResourcesPath.toFile().listFiles((dir, name) -> name.endsWith(".json"));
        Assertions.assertNotNull(files);
        Assertions.assertTrue(files.length > 0, "Should have at least some config files");

        Path speciesDir = platform.getRootFolder().resolve("species");
        speciesDir.toFile().mkdirs();

        for (java.io.File file : files) {
            java.nio.file.Files.copy(file.toPath(), speciesDir.resolve(file.getName()), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }

        registry.loadSpecies();
        Map<String, TreeSpecies> map = registry.getSpeciesMap();
        
        Assertions.assertFalse(map.isEmpty(), "Species map should not be empty after loading all configs");
        
        // Detailed check for a few known ones
        Assertions.assertTrue(map.keySet().stream().anyMatch(k -> k.contains("glowcap")), "Should contain glowcap");
        Assertions.assertTrue(map.keySet().stream().anyMatch(k -> k.contains("ashwood")), "Should contain ashwood");
        Assertions.assertTrue(map.keySet().stream().anyMatch(k -> k.contains("forest")), "Should contain forest (from forest.json). Keys: " + map.keySet());
        Assertions.assertTrue(map.keySet().stream().anyMatch(k -> k.contains("dark-forest-megas")), "Should contain dark-forest-megas");
    }

    @Test
    public void testWeightedSelection() {
        MockPlatform platform = new MockPlatform();
        TreeRegistry registry = new TreeRegistry(platform);
        
        // Define two procedural variants with heavy weight on the second one
        Map<String, TreeSpecies.ProceduralParams> defs = new HashMap<>();
        defs.put("small", new TreeSpecies.ProceduralParams(
            "minecraft:oak_log", "minecraft:oak_leaves", 5, 5, "OAK", 1.0, "CONSTANT", new HashMap<>(), 
            false, 0, 0, "CONSTANT", new HashMap<>(), "LINEAR", new HashMap<>(), null, 0.5, 1.0, null, 1
        ));
        defs.put("massive", new TreeSpecies.ProceduralParams(
            "minecraft:oak_log", "minecraft:oak_leaves", 50, 50, "OAK", 4.0, "CONSTANT", new HashMap<>(), 
            false, 0, 0, "CONSTANT", new HashMap<>(), "LINEAR", new HashMap<>(), null, 0.5, 1.0, null, 99
        ));
        
        TreeSpecies species = new TreeSpecies("weighted", "Weighted", "ns", "gp", Set.of(), "OAK", true, false, 3, 2, -1, "linear", "step", List.of(), null, null, defs, 1);
        ProceduralGenerator generator = new ProceduralGenerator(platform, registry);
        
        int massiveCount = 0;
        int trials = 1000;
        for (int i = 0; i < trials; i++) {
            TreeModel model = generator.buildModel(species, new Random(i));
            if (model.getSpeciesId().contains("massive")) {
                massiveCount++;
            }
        }
        
        System.out.println("[DEBUG_LOG] Massive count: " + massiveCount + "/" + trials);
        // With 99:1 weight, massive should be selected ~99% of the time.
        // Even with bad luck, it should be > 90% for 1000 trials.
        Assertions.assertTrue(massiveCount > 900, "Massive trees should be heavily weighted. Got: " + massiveCount);
    }

    @Test
    public void testDefinitionOverride() {
        MockPlatform platform = new MockPlatform();
        TreeRegistry registry = new TreeRegistry(platform);
        
        // Base species with 1:1 weights
        Map<String, TreeSpecies.ProceduralParams> defs = new HashMap<>();
        defs.put("small", new TreeSpecies.ProceduralParams(
            "minecraft:oak_log", "minecraft:oak_leaves", 5, 5, "OAK", 1.0, "CONSTANT", new HashMap<>(), 
            false, 0, 0, "CONSTANT", new HashMap<>(), "LINEAR", new HashMap<>(), null, 0.5, 1.0, null, 1
        ));
        defs.put("massive", new TreeSpecies.ProceduralParams(
            "minecraft:oak_log", "minecraft:oak_leaves", 50, 50, "OAK", 4.0, "CONSTANT", new HashMap<>(), 
            false, 0, 0, "CONSTANT", new HashMap<>(), "LINEAR", new HashMap<>(), null, 0.5, 1.0, null, 1
        ));
        
        TreeSpecies base = new TreeSpecies("dark_oak", "Dark Oak", "ns", "gp", Set.of("forest"), "OAK", true, false, 3, 2, -1, "linear", "step", List.of(), null, null, defs, 1);
        
        // Mock YAML section for override
        // In a real test we'd use the actual RtpYamlSection, but we can test the result of the logic.
        // Let's verify that after the logic in TreeGenConfig, the weights are updated.
        
        // We simulate the update
        Map<String, TreeSpecies.ProceduralParams> treeDefs = new HashMap<>(base.treeDefinitions());
        TreeSpecies.ProceduralParams p = treeDefs.get("massive");
        treeDefs.put("massive", new TreeSpecies.ProceduralParams(
            p.trunkBlock(), p.leafBlock(), p.minHeight(), p.maxHeight(), p.profile(),
            p.trunkWidth(), p.trunkShape(), p.trunkShapeParams(), p.roundTrunk(),
            p.leanAngle(), p.leanAzimuth(), p.azimuthFn(), p.azimuthParams(),
            p.curveFn(), p.curveParams(), p.secondaryTrunk(), p.secondaryTrunkStart(),
            p.secondaryTrunkEnd(), p.canopy(), 100 // Updated weight
        ));
        
        TreeSpecies overridden = new TreeSpecies(
            base.id(), base.displayName(), base.namespace(), base.group(),
            base.biomes(), base.saplingItem(), base.worldgen(), base.replaceVanilla(), base.spacing(), base.separation(), base.salt(), base.spreadType(), base.step(),
            base.variants(), base.procedural(), base.trees(), treeDefs, base.weight()
        );
        
        ProceduralGenerator generator = new ProceduralGenerator(platform, registry);
        int massiveCount = 0;
        int trials = 1000;
        for (int i = 0; i < trials; i++) {
            TreeModel model = generator.buildModel(overridden, new Random(i));
            if (model.getSpeciesId().contains("massive")) {
                massiveCount++;
            }
        }
        
        // With 100:1 weight, massive should be dominant
        Assertions.assertTrue(massiveCount > 950, "Massive trees should be overridden to heavy weight. Got: " + massiveCount);
    }

    @Test
    public void testWeightedGroupData() {
        TreeSpecies s = new TreeSpecies("test", "test", "ns", "gp", Set.of(), "OAK", true, false, 3, 2, -1, "linear", "step", List.of(), null, 
            List.of(new TreeSpecies.WeightedSpecies("forest:oak", 80), new TreeSpecies.WeightedSpecies("birch", 20)), null, 1);
        
        Assertions.assertEquals(2, s.trees().size());
        Assertions.assertEquals("forest:oak", s.trees().get(0).id());
        Assertions.assertEquals(80, s.trees().get(0).weight());
    }

    private static class MockPlatform implements Platform {
        private final Path root = Paths.get("build/test-data-" + System.currentTimeMillis());
        public final Map<String, String> placedBlocks = new HashMap<>();

        @Override public Logger logger() { 
            Logger logger = Logger.getLogger("Test-" + System.currentTimeMillis());
            logger.setUseParentHandlers(true);
            return logger;
        }
        @Override public Path getRootFolder() { return root; }
        @Override public Path getWorldFolder(String worldName) { return root.resolve(worldName); }
        @Override public List<String> getWorldNames() { return List.of("world"); }
        @Override public void scheduleRepeatingTask(Runnable task, long delayTicks, long periodTicks) {}
        @Override public String getBiomeKey(String worldName, int x, int y, int z) { return "minecraft:plains"; }
        @Override public boolean placeStructure(String worldName, int x, int y, int z, String structureKey) { return true; }
        @Override public void giveSapling(UUID playerId, String speciesId, int amount) {}
        @Override public void setBlock(String worldName, int x, int y, int z, String blockData) {
            placedBlocks.put(x + "," + y + "," + z, blockData);
        }
        @Override public void sendMessage(UUID playerId, String message, boolean error) {}
    }
}
