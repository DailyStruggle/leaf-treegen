package net.leaf.treegen.fabric;

import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.dailystruggle.commandsapi.brigadier.BrigadierBridgeContext;
import io.github.dailystruggle.commandsapi.brigadier.BrigadierCommandAdapter;
import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.leaf.treegen.common.DatapackGenerator;
import net.leaf.treegen.common.ProceduralGenerator;
import net.leaf.treegen.common.TreeGenConfig;
import net.leaf.treegen.common.TreeRegistry;
import net.leaf.treegen.common.command.CallerLocation;
import net.leaf.treegen.common.command.LeafTreeGenCommand;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.world.phys.Vec3;

import java.io.File;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

public class FabricMod implements ModInitializer {

    private static final Logger LOGGER = Logger.getLogger("LeafTreeGen");

    private FabricPlatform platform;
    private TreeRegistry registry;
    private TreeGenConfig config;
    private ProceduralGenerator proceduralGenerator;

    /**
     * Most-recent live position per caller id, captured from the Brigadier source when a command
     * is dispatched. Used so {@code /leaftree generate} defaults to the caller's location.
     */
    private final Map<UUID, CallerLocation> callerLocations = new ConcurrentHashMap<>();

    @Override
    public void onInitialize() {
        platform = new FabricPlatform();
        registry = new TreeRegistry(platform);
        registry.loadSpecies();

        File configFile = platform.getRootFolder().resolve("config.yml").toFile();
        RtpYamlConfig yaml = new RtpYamlConfig(configFile);
        config = TreeGenConfig.fromYaml(yaml, registry, platform);

        proceduralGenerator = new ProceduralGenerator(platform, registry);
        proceduralGenerator.setConfigSpecies(config.species());

        if (config.needsDatapack()) {
            DatapackGenerator datapackGenerator = new DatapackGenerator(platform);
            for (String world : platform.getWorldNames()) {
                int written = datapackGenerator.generate(world, config, registry);
                LOGGER.info("Generated worldgen datapack for world '" + world + "' with " + written + " species.");
            }
        }

        registerCommands();

        // Detect when a player plants a tagged magical sapling and grow the matching tree.
        new FabricSaplingListener(() -> config, () -> registry, platform).register();

        LOGGER.info("LeafTreeGen (Fabric) initialized.");
    }

    private void reload() {
        registry.loadSpecies();
        File configFile = platform.getRootFolder().resolve("config.yml").toFile();
        RtpYamlConfig yaml = new RtpYamlConfig(configFile);
        config = TreeGenConfig.fromYaml(yaml, registry, platform);
        proceduralGenerator.setConfigSpecies(config.species());
        LOGGER.info("LeafTreeGen (Fabric) reloaded.");
    }

    /**
     * Wires the {@code /leaftree} command into Fabric.
     *
     * <p>Registration and execution are deliberately separate concerns (per the dailystruggle
     * CommandsAPI design): {@link CommandRegistrationCallback} only builds the Brigadier tree,
     * whose callbacks enqueue {@code CommandExecutor}s onto {@link CommandsAPI#commandPipeline}.
     * The actual command work runs later when {@link CommandsAPI#execute(long)} drains that
     * pipeline with a per-tick time budget, which we drive from
     * {@link ServerTickEvents#END_SERVER_TICK}. This keeps heavy tree generation off the command
     * thread and gives us timeboxing.
     *
     * <p>Fabric API and Minecraft are on this module's compile classpath (Loom, unobfuscated
     * 26.1+), so both hooks are wired directly - no reflection / dynamic proxies.
     */
    private void registerCommands() {
        CommandRegistrationCallback.EVENT.register(
            (dispatcher, registryAccess, environment) -> {
                // Build the platform-agnostic CommandsAPI tree and register it via the Brigadier
                // bridge. Feedback is logged; permission is open (admin command); every source
                // maps to the console sentinel id (we never resolve players here).
                LeafTreeGenCommand root = new LeafTreeGenCommand(
                    () -> config, () -> registry, proceduralGenerator, FabricMod.this::reload,
                    msg -> LOGGER.info("[LeafTreeGen] " + msg),
                    callerLocations::get,
                    FabricMod.this::giveSapling);
                // Every source still maps to the console sentinel id, but we capture the source's
                // live position under that id so the command can default coordinates to the caller
                // location (resolved at dispatch time, not at startup).
                BrigadierBridgeContext<CommandSourceStack> ctx = new BrigadierBridgeContext<>(
                    source -> { captureCallerLocation(CommandsAPI.serverId, source); return CommandsAPI.serverId; },
                    (source, permission) -> true,
                    (source, msg) -> LOGGER.info("[LeafTreeGen] " + msg));
                LiteralArgumentBuilder<CommandSourceStack> builder =
                    BrigadierCommandAdapter.toBrigadier(root, ctx);
                dispatcher.register(builder);
            });
        LOGGER.info("LeafTreeGen: registered /leaftree command on "
            + "CommandRegistrationCallback (Fabric).");

        // Drain the CommandsAPI pipeline once per server tick (50ms budget per execute()).
        // Capturing the live MinecraftServer here gives the platform the handle it needs to
        // resolve worlds and place blocks; the tick runs on the server thread, so command-driven
        // generation (and its placement) happens safely on that thread.
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            platform.setServer(server);
            platform.onServerTick();
            CommandsAPI.execute();
        });
        LOGGER.info("LeafTreeGen: scheduled CommandsAPI pipeline drain on "
            + "ServerTickEvents.END_SERVER_TICK (Fabric).");
    }

    /**
     * Backs {@code /leaftree give}: resolves the species from the live config and hands a
     * species-tagged sapling to the named online player so it is accurate to the tree and is
     * recognised when planted. Returns {@code true} only when the item was actually delivered.
     */
    private boolean giveSapling(UUID callerId, String playerName, String speciesId, int amount) {
        if (playerName == null || playerName.isBlank()) {
            // No explicit target and we never resolve the caller to a player on this platform.
            return false;
        }
        TreeGenConfig cfg = config;
        if (cfg == null) return false;
        net.leaf.treegen.common.TreeSpecies species = cfg.get(speciesId);
        if (species == null) return false;
        return platform.giveStackToPlayer(playerName, FabricSaplingItem.create(species, amount), amount);
    }

    /**
     * Reads the caller's block position from the Brigadier {@link CommandSourceStack} and records
     * it under {@code id}, so {@code /leaftree generate} can default its coordinates to the
     * caller. A console source (or any failure) leaves the default origin in place.
     */
    private void captureCallerLocation(UUID id, CommandSourceStack source) {
        if (source == null) return;
        try {
            Vec3 pos = source.getPosition();
            if (pos == null) return;
            callerLocations.put(id, new CallerLocation(null,
                (int) Math.floor(pos.x), (int) Math.floor(pos.y), (int) Math.floor(pos.z)));
        } catch (Exception ignored) {
            // Source has no resolvable position (e.g. console): leave the default origin in place.
        }
    }
}
