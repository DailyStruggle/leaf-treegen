package net.leaf.treegen.paper;

import net.leaf.treegen.common.DatapackGenerator;
import net.leaf.treegen.common.ProceduralGenerator;
import net.leaf.treegen.common.TreeFarmStore;
import net.leaf.treegen.common.TreeGenConfig;
import net.leaf.treegen.common.TreeRegistry;
import org.bukkit.World;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;

public final class PaperLeafTreeGenPlugin extends JavaPlugin {

    private PaperPlatform platform;
    private TreeGenConfig config;
    private TreeRegistry registry;
    private DatapackGenerator datapackGenerator;
    private ProceduralGenerator proceduralGenerator;
    private TreeFarmStore farmStore;

    @Override
    public void onLoad() {
        this.platform = new PaperPlatform(this);
        this.registry = new TreeRegistry(platform);
        this.datapackGenerator = new DatapackGenerator(platform);
        this.proceduralGenerator = new ProceduralGenerator(platform, registry);
        this.farmStore = new TreeFarmStore(platform);

        if (!getDataFolder().exists()) getDataFolder().mkdirs();
        File configFile = new File(getDataFolder(), "config.yml");
        if (!configFile.exists()) {
            saveResource("config.yml", false);
        }

        io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig yaml =
                new io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig(configFile);

        this.registry.loadSpecies();
        this.config = TreeGenConfig.fromYaml(yaml, registry, platform);
        this.proceduralGenerator.setConfigSpecies(this.config.species());

        // Worldgen datapacks are generated earlier, in PaperBootstrap#bootstrap,
        // which runs before worlds and their datapacks are loaded. onLoad only
        // prepares the runtime state (registry/config/procedural generator).
    }

    @Override
    public void onEnable() {
        // ... previous initialization already done in onLoad ...
        
        // Re-run registration just in case
        getServer().getPluginManager().registerEvents(new PaperSaplingListener(this), this);
        getServer().getPluginManager().registerEvents(new PaperChunkPlacementListener(this), this);
        getServer().getPluginManager().registerEvents(new PaperTreeSuppressionListener(this), this);
        getServer().getPluginManager().registerEvents(new PaperLeafDropListener(this), this);

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
        this.proceduralGenerator.setConfigSpecies(this.config.species());
        generateDatapacks();
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
    public TreeFarmStore farmStore() { return farmStore; }
    public PaperPlatform platform() { return platform; }
}
