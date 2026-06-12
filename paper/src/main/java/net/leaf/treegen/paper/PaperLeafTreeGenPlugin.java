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

        registerCommand(new PaperTreeGenCommand(this));

        platform.scheduleRepeatingTask(() -> {
            io.github.dailystruggle.commandsapi.common.CommandsAPI.execute();
        }, 1, 1);

        getLogger().info("LeafTreeGen (Paper) enabled.");
    }

    /**
     * Ensures the {@code /leaftree} command is bound to its executor.
     *
     * <p>{@link io.github.dailystruggle.commandsapi.bukkit.localCommands.BukkitTreeCommand}
     * only binds itself through {@code Bukkit.getPluginCommand(name)} inside its
     * constructor, which returns {@code null} for Paper plugins (loaded from
     * {@code paper-plugin.yml}), so the executor is never attached and the
     * command appears unregistered (notably on Folia).</p>
     *
     * <p>Paper plugins also reject {@link JavaPlugin#getCommand(String)} during
     * startup (it throws {@link UnsupportedOperationException} because YAML-based
     * command declarations are unsupported). We therefore register the command
     * explicitly into the live server command map, which works the same on Paper
     * and Folia regardless of descriptor parsing.</p>
     */
    private void registerCommand(PaperTreeGenCommand command) {
        // Register a lightweight wrapper directly into the live command map.
        // Note: JavaPlugin#getCommand cannot be used on Paper plugins (it throws
        // UnsupportedOperationException during startup).
        org.bukkit.command.Command wrapper = new org.bukkit.command.Command(command.name()) {
            @Override
            public boolean execute(org.bukkit.command.CommandSender sender, String label, String[] args) {
                return command.onCommand(sender, this, label, args);
            }

            @Override
            public java.util.List<String> tabComplete(org.bukkit.command.CommandSender sender, String alias, String[] args) {
                java.util.List<String> result = command.onTabComplete(sender, this, alias, args);
                return result != null ? result : java.util.Collections.emptyList();
            }
        };
        wrapper.setDescription(command.description());
        wrapper.setPermission(command.permission());
        wrapper.setUsage("/" + command.name() + " <generate|give|list|reload|debug|stats> ...");
        getServer().getCommandMap().register(getName().toLowerCase(java.util.Locale.ROOT), wrapper);
        getLogger().info("Registered '/" + command.name() + "' via command map fallback.");
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
