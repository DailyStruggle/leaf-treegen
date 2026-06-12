package net.leaf.treegen.neoforge;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import io.github.dailystruggle.commandsapi.brigadier.BrigadierBridgeContext;
import io.github.dailystruggle.commandsapi.brigadier.BrigadierCommandAdapter;
import io.github.dailystruggle.commandsapi.common.CommandsAPI;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.leaf.treegen.common.DatapackGenerator;
import net.leaf.treegen.common.ProceduralGenerator;
import net.leaf.treegen.common.TreeGenConfig;
import net.leaf.treegen.common.TreeRegistry;
import net.leaf.treegen.common.command.CallerLocation;
import net.leaf.treegen.common.command.LeafTreeGenCommand;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;

import java.io.File;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Logger;

@Mod("leaf_treegen")
public class NeoForgeMod {

    private static final Logger LOGGER = Logger.getLogger("LeafTreeGen");

    private final NeoForgePlatform platform;
    private TreeRegistry registry;
    private TreeGenConfig config;
    private final ProceduralGenerator proceduralGenerator;

    /**
     * Most-recent live position per caller id, captured from the Brigadier source when a command
     * is dispatched. Used so {@code /leaftree generate} defaults to the caller's location.
     */
    private final Map<UUID, CallerLocation> callerLocations = new ConcurrentHashMap<>();

    public NeoForgeMod() {
        platform = new NeoForgePlatform();
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
        NeoForgeSaplingListener saplingListener =
            new NeoForgeSaplingListener(() -> config, () -> registry, platform);
        NeoForge.EVENT_BUS.addListener(saplingListener::onRightClickBlock);

        LOGGER.info("LeafTreeGen (NeoForge) initialized.");
    }

    private void reload() {
        registry.loadSpecies();
        File configFile = platform.getRootFolder().resolve("config.yml").toFile();
        RtpYamlConfig yaml = new RtpYamlConfig(configFile);
        config = TreeGenConfig.fromYaml(yaml, registry, platform);
        proceduralGenerator.setConfigSpecies(config.species());
        LOGGER.info("LeafTreeGen (NeoForge) reloaded.");
    }

    /**
     * Wires the {@code /leaftree} command into NeoForge.
     *
     * <p>Registration and execution are deliberately separate concerns (per the dailystruggle
     * CommandsAPI design): the {@link RegisterCommandsEvent} only builds the Brigadier tree, whose
     * callbacks enqueue {@code CommandExecutor}s onto {@link CommandsAPI#commandPipeline}. The
     * actual command work runs later when {@link CommandsAPI#execute(long)} drains that pipeline
     * with a per-tick time budget, which we drive from {@link ServerTickEvent.Post}. This keeps
     * heavy tree generation off the command thread and gives us timeboxing.
     */
    private void registerCommands() {
        NeoForge.EVENT_BUS.addListener(this::onRegisterCommands);
        // Drain the CommandsAPI pipeline once per server tick (50ms budget per execute()).
        // Capturing the live MinecraftServer here gives the platform a handle it needs to
        // resolve worlds and actually place blocks; the tick runs on the server thread, so
        // command-driven generation (and its placement) happens safely on that thread.
        NeoForge.EVENT_BUS.addListener((ServerTickEvent.Post event) -> {
            platform.setServer(event.getServer());
            platform.onServerTick();
            CommandsAPI.execute();
        });
    }

    @SuppressWarnings("unchecked")
    private void onRegisterCommands(RegisterCommandsEvent event) {
        try {
            // getDispatcher() returns CommandDispatcher<CommandSourceStack>; we invoke it
            // reflectively so this module does not need the Minecraft classes on its
            // compile classpath, then drive the generic command tree off the raw dispatcher.
            CommandDispatcher<Object> dispatcher = (CommandDispatcher<Object>)
                RegisterCommandsEvent.class.getMethod("getDispatcher").invoke(event);

            // Build the platform-agnostic CommandsAPI tree and register it via the
            // Brigadier bridge. Feedback is logged; permission is open (admin command);
            // every source maps to the console sentinel id since we never resolve players here.
            LeafTreeGenCommand root = new LeafTreeGenCommand(
                () -> config, () -> registry, proceduralGenerator, this::reload,
                msg -> LOGGER.info("[LeafTreeGen] " + msg),
                callerLocations::get,
                this::giveSapling);
            // Every source still maps to the console sentinel id, but we capture the source's live
            // position under that id so the command can default coordinates to the caller location.
            BrigadierBridgeContext<Object> ctx = new BrigadierBridgeContext<>(
                source -> { captureCallerLocation(CommandsAPI.serverId, source); return CommandsAPI.serverId; },
                (source, permission) -> true,
                (source, msg) -> LOGGER.info("[LeafTreeGen] " + msg));
            LiteralArgumentBuilder<Object> builder = BrigadierCommandAdapter.toBrigadier(root, ctx);
            dispatcher.register(builder);
            LOGGER.info("LeafTreeGen: registered /leaftree commands (NeoForge).");
        } catch (Exception e) {
            LOGGER.warning("LeafTreeGen: could not register commands - " + e.getMessage());
        }
    }

    /**
     * Backs {@code /leaftree give}: resolves the species' vanilla sapling item from the live config
     * and hands {@code amount} of it to the named online player. NeoForge has no custom sapling
     * item, so the configured vanilla {@code sapling-item} (e.g. {@code OAK_SAPLING}) is delivered.
     * Returns {@code true} only when the item was actually delivered.
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
        // Hand out a species-tagged sapling so it is accurate to the tree and is recognised when planted.
        return platform.giveStackToPlayer(playerName, NeoForgeSaplingItem.create(species, amount), amount);
    }

    /**
     * Reflectively reads the caller's block position from a Brigadier {@code CommandSourceStack}
     * and records it under {@code id}. Reflection keeps Minecraft types off this module's compile
     * classpath; any failure (e.g. console source with no position) is silently ignored so the
     * command simply falls back to its default origin.
     */
    private void captureCallerLocation(UUID id, Object source) {
        if (source == null) return;
        try {
            // CommandSourceStack#getPosition() -> Vec3 (Mojang) / method_9222 -> Vec3d (intermediary).
            Object pos = null;
            for (String name : new String[]{"getPosition", "method_9222"}) {
                try { pos = source.getClass().getMethod(name).invoke(source); break; }
                catch (NoSuchMethodException ignored) { /* try next mapping */ }
            }
            if (pos == null) return;
            // Vec3 / Vec3d both expose public x, y, z double fields under every mapping.
            double x = pos.getClass().getField("x").getDouble(pos);
            double y = pos.getClass().getField("y").getDouble(pos);
            double z = pos.getClass().getField("z").getDouble(pos);
            callerLocations.put(id, new CallerLocation(null,
                (int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z)));
        } catch (Exception ignored) {
            // Source has no resolvable position (e.g. console): leave the default origin in place.
        }
    }
}
