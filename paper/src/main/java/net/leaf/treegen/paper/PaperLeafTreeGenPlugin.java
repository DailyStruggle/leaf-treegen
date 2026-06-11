package net.leaf.treegen.paper;

import net.leaf.treegen.common.DatapackGenerator;
import net.leaf.treegen.common.ProceduralGenerator;
import net.leaf.treegen.common.TreeGenConfig;
import net.leaf.treegen.common.TreeRegistry;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.util.List;
import java.util.Map;

public final class PaperLeafTreeGenPlugin extends JavaPlugin {

    private PaperPlatform platform;
    private TreeGenConfig config;
    private TreeRegistry registry;
    private DatapackGenerator datapackGenerator;
    private ProceduralGenerator proceduralGenerator;

    @Override
    public void onLoad() {
        this.platform = new PaperPlatform(this);
        this.registry = new TreeRegistry(platform);
        this.datapackGenerator = new DatapackGenerator(platform);
        this.proceduralGenerator = new ProceduralGenerator(platform, registry);

        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            saveResource("config.yml", false);
        }

        io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig yaml =
                new io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig(configFile);

        this.registry.loadSpecies();
        this.config = TreeGenConfig.fromYaml(yaml, registry, platform);

        // Pre-generate datapacks for every world listed in Bukkit (if any exist yet)
        // or try to infer world names from the server configuration.
        // On Folia, worlds might not be loaded yet in onLoad.
        generateDatapacksStatic();
    }

    @Override
    public void onEnable() {
        // ... previous initialization already done in onLoad ...
        
        // Re-run registration just in case
        getServer().getPluginManager().registerEvents(new PaperSaplingListener(this), this);
        getServer().getPluginManager().registerEvents(new PaperChunkPlacementListener(this), this);
        getServer().getPluginManager().registerEvents(new PaperTreeSuppressionListener(this), this);

        new PaperTreeGenCommand(this);

        platform.scheduleRepeatingTask(() -> {
            io.github.dailystruggle.commandsapi.common.CommandsAPI.execute();
        }, 1, 1);

        getLogger().info("LeafTreeGen (Paper) enabled.");
    }

    public void reload() {
        File configFile = new File(getDataFolder(), "config.yml");
        io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig yaml =
            new io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig(configFile);

        this.registry.loadSpecies();
        this.config = TreeGenConfig.fromYaml(yaml, registry, platform);
        generateDatapacks();
    }

    private void generateDatapacksStatic() {
        if (config == null || !config.generateDatapack()) return;
        // In onLoad, we might need to look at server.properties for the world name
        String mainWorld = "world"; // Default
        
        // Try to generate for 'world', 'world_nether', 'world_the_end'
        for (String w : List.of("world", "world_nether", "world_the_end")) {
            File worldFolder = new File(w);
            if (worldFolder.exists()) {
                int written = datapackGenerator.generate(w, config, registry);
                if (written > 0) {
                    getLogger().info("Pre-generated worldgen datapack '" + config.packName() + "' for world '" + w + "' (onLoad)");
                }
            } else {
                // If it doesn't exist, we might be in a dimension subfolder in a different setup
                // but for standard Paper, world, world_nether etc are at root.
                // We'll also try the dimensions/minecraft/... path that DatapackGenerator used to suggest
                File overworldDim = new File(w + "/dimensions/minecraft/overworld");
                if (overworldDim.exists()) {
                     datapackGenerator.generate(w, config, registry);
                }
            }
        }
    }

    private void generateDatapacks() {
        if (config == null || !config.generateDatapack()) return;
        for (World world : getServer().getWorlds()) {
            int written = datapackGenerator.generate(world.getName(), config, registry);
            getLogger().info("Generated worldgen datapack '" + config.packName() + "' for world '" + world.getName() + "' with " + written + " species.");
        }
    }

    public TreeGenConfig config() { return config; }
    public TreeRegistry registry() { return registry; }
    public ProceduralGenerator proceduralGenerator() { return proceduralGenerator; }
    public PaperPlatform platform() { return platform; }
}
