package net.leaf.treegen.fabric;

import net.fabricmc.api.ModInitializer;
import net.leaf.treegen.common.DatapackGenerator;
import net.leaf.treegen.common.TreeGenConfig;
import net.leaf.treegen.common.TreeRegistry;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;

import java.io.File;
import java.util.logging.Logger;

public class FabricMod implements ModInitializer {

    private static final Logger LOGGER = Logger.getLogger("LeafTreeGen");

    @Override
    public void onInitialize() {
        FabricPlatform platform = new FabricPlatform();
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
        LOGGER.info("LeafTreeGen (Fabric) initialized.");
    }
}
