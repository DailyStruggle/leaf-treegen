package net.leaf.treegen.neoforge;

import net.neoforged.fml.common.Mod;
import net.leaf.treegen.common.DatapackGenerator;
import net.leaf.treegen.common.TreeGenConfig;
import net.leaf.treegen.common.TreeRegistry;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;

import java.io.File;
import java.util.logging.Logger;

@Mod("leaf_treegen")
public class NeoForgeMod {

    private static final Logger LOGGER = Logger.getLogger("LeafTreeGen");

    public NeoForgeMod() {
        NeoForgePlatform platform = new NeoForgePlatform();
        TreeRegistry registry = new TreeRegistry(platform);
        registry.loadSpecies();

        File configFile = platform.getRootFolder().resolve("config.yml").toFile();
        RtpYamlConfig yaml = new RtpYamlConfig(configFile);
        TreeGenConfig config = TreeGenConfig.fromYaml(yaml, registry, platform);

        if (config.needsDatapack()) {
            DatapackGenerator datapackGenerator = new DatapackGenerator(platform);
            for (String world : platform.getWorldNames()) {
                int written = datapackGenerator.generate(world, config, registry);
                LOGGER.info("Generated worldgen datapack for world '" + world + "' with " + written + " species.");
            }
        }
        LOGGER.info("LeafTreeGen (NeoForge) initialized.");
    }
}
