package net.leaf.treegen.paper;

import io.papermc.paper.plugin.bootstrap.BootstrapContext;
import io.papermc.paper.plugin.bootstrap.PluginBootstrap;
import net.leaf.treegen.common.DatapackGenerator;
import net.leaf.treegen.common.TreeGenConfig;
import net.leaf.treegen.common.TreeRegistry;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;
import java.util.logging.Logger;

/**
 * Paper bootstrap entry point.
 *
 * <p>The bootstrap phase runs <b>before</b> the server reads worlds and their
 * datapacks. Generating the worldgen datapacks here (instead of in the legacy
 * {@code JavaPlugin#onLoad()}) means they are written to disk before datapack
 * discovery, so the trees are picked up on the same boot rather than only after
 * an extra restart.</p>
 */
public final class PaperBootstrap implements PluginBootstrap {

    @Override
    public void bootstrap(@NotNull BootstrapContext context) {
        Logger logger = Logger.getLogger("LeafTreeGen");
        Path dataDir = context.getDataDirectory();
        try {
            Files.createDirectories(dataDir);
        } catch (IOException ex) {
            logger.severe("Could not create data directory " + dataDir + ": " + ex.getMessage());
            return;
        }

        BootstrapPlatform platform = new BootstrapPlatform(logger, dataDir);
        TreeRegistry registry = new TreeRegistry(platform);
        DatapackGenerator datapackGenerator = new DatapackGenerator(platform);

        // Extracts the bundled species/*.json and default config.yml into the
        // data directory (if missing) and loads them.
        registry.loadSpecies();

        File configFile = dataDir.resolve("config.yml").toFile();
        io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig yaml =
                new io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig(configFile);
        TreeGenConfig config = TreeGenConfig.fromYaml(yaml, registry, platform);

        // A datapack is written either for the full DATAPACK/BOTH baking path or,
        // in PROCEDURAL mode, for a suppression-only pack that removes the vanilla
        // trees the plugin replaces at runtime. Writing it here (bootstrap, before
        // datapack discovery) means the suppression takes effect on the same boot.
        if (!config.needsDatapack()) {
            return;
        }

        String levelName = readLevelName(logger);

        // World layout differs between server flavours:
        //  - Folia 26.1 (and vanilla) keep every dimension inside the single
        //    level folder (e.g. world/dimensions/...), so a datapack placed in
        //    "world/datapacks" already governs the nether and the end too.
        //  - Legacy Spigot/Paper split the dimensions into sibling folders
        //    (world_nether, world_the_end), each with its own datapacks dir.
        // Always generate for the main level folder, and only generate for the
        // legacy split folders when they actually exist (so we don't create
        // stray world_nether/world_the_end folders on Folia 26.1).
        generateFor(datapackGenerator, config, registry, levelName, logger, true);
        generateFor(datapackGenerator, config, registry, levelName + "_nether", logger, false);
        generateFor(datapackGenerator, config, registry, levelName + "_the_end", logger, false);
    }

    private void generateFor(DatapackGenerator datapackGenerator, TreeGenConfig config,
                             TreeRegistry registry, String world, Logger logger, boolean always) {
        if (!always && !new File(world).isDirectory()) {
            // Legacy split-dimension folder absent (Folia 26.1 single-folder layout); skip.
            return;
        }
        int written = datapackGenerator.generate(world, config, registry);
        if (written > 0) {
            logger.info("Pre-generated worldgen datapack '" + config.packName()
                    + "' for world '" + world + "' (bootstrap, before datapacks load)");
        }
    }

    /** Reads the {@code level-name} from server.properties (defaults to "world"). */
    private String readLevelName(Logger logger) {
        File props = new File("server.properties");
        if (props.exists()) {
            Properties p = new Properties();
            try (FileInputStream in = new FileInputStream(props)) {
                p.load(in);
                String name = p.getProperty("level-name");
                if (name != null && !name.isBlank()) {
                    return name.trim();
                }
            } catch (IOException ex) {
                logger.warning("Could not read level-name from server.properties: " + ex.getMessage());
            }
        }
        return "world";
    }
}
