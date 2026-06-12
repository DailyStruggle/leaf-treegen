package net.leaf.treegen.common.command;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import net.leaf.treegen.common.ProceduralGenerator;
import net.leaf.treegen.common.TreeGenConfig;
import net.leaf.treegen.common.TreeRegistry;
import net.leaf.treegen.common.TreeSpecies;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Platform-agnostic {@code /leaftree} command tree built on the dailystruggle
 * {@link io.github.dailystruggle.commandsapi.common.CommandsAPI CommandsAPI} model.
 *
 * <p>This is the single source of truth for command <em>behaviour</em> on every non-Bukkit
 * platform (Fabric, NeoForge). Platform adapters register it on their Brigadier dispatcher via
 * {@code io.github.dailystruggle.commandsapi.brigadier.BrigadierCommandAdapter#toBrigadier}.
 *
 * <p>Crucially, command <em>registration</em> is distinct from command <em>execution</em>: the
 * Brigadier callback only enqueues a {@code CommandExecutor} onto
 * {@link io.github.dailystruggle.commandsapi.common.CommandsAPI#commandPipeline}; the terminal
 * {@link #onCommand(UUID, Map, CommandsAPICommand)} method below runs later, when the platform
 * drains the pipeline with a per-tick time budget via
 * {@link io.github.dailystruggle.commandsapi.common.CommandsAPI#execute(long)}. That separation is
 * what gives the API its timeboxing guarantee, so we never compute the (potentially heavy) tree
 * generation inline on the command thread.
 */
public final class LeafTreeGenCommand extends AbstractLeafCommand {

    /**
     * Equivalent to {@link #LeafTreeGenCommand(Supplier, Supplier, ProceduralGenerator, Runnable,
     * Consumer, CallerLocation.Resolver)} with no caller-location resolver, so {@code generate}
     * defaults its coordinates to the origin (0,64,0).
     */
    public LeafTreeGenCommand(Supplier<TreeGenConfig> configRef,
                              Supplier<TreeRegistry> registryRef,
                              ProceduralGenerator generator,
                              Runnable reloadHook,
                              Consumer<String> feedback) {
        this(configRef, registryRef, generator, reloadHook, feedback, CallerLocation.Resolver.NONE);
    }

    /**
     * Equivalent to {@link #LeafTreeGenCommand(Supplier, Supplier, ProceduralGenerator, Runnable,
     * Consumer, CallerLocation.Resolver, SaplingGiver)} with no sapling giver, so {@code give}
     * cannot deliver any item (used by platforms that do not register a sapling item).
     */
    public LeafTreeGenCommand(Supplier<TreeGenConfig> configRef,
                              Supplier<TreeRegistry> registryRef,
                              ProceduralGenerator generator,
                              Runnable reloadHook,
                              Consumer<String> feedback,
                              CallerLocation.Resolver callerLocation) {
        this(configRef, registryRef, generator, reloadHook, feedback, callerLocation, SaplingGiver.NONE);
    }

    /**
     * @param configRef   supplier returning the current live config (supports reload)
     * @param registryRef supplier returning the current live registry
     * @param generator   procedural generator
     * @param reloadHook  runnable that reloads config/registry in the owning mod
     * @param feedback    sink for user-facing feedback (typically the platform logger)
     * @param callerLocation resolves the caller's live position, used as the default origin for
     *                       {@code generate} (so omitted/{@code ~} coordinates presume the player's
     *                       location); may be {@link CallerLocation.Resolver#NONE}
     */
    public LeafTreeGenCommand(Supplier<TreeGenConfig> configRef,
                              Supplier<TreeRegistry> registryRef,
                              ProceduralGenerator generator,
                              Runnable reloadHook,
                              Consumer<String> feedback,
                              CallerLocation.Resolver callerLocation,
                              SaplingGiver saplingGiver) {
        super("leaftree", "leaftreegen.admin", "LeafTreeGen root command.", null, feedback);
        addSubCommand(new GenerateSub(this, configRef, generator, feedback, callerLocation));
        addSubCommand(new GiveSub(this, configRef, feedback,
            saplingGiver == null ? SaplingGiver.NONE : saplingGiver));
        addSubCommand(new ListSub(this, configRef, feedback));
        addSubCommand(new ReloadSub(this, reloadHook, feedback));
    }

    @Override
    public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
        // Root with no subcommand: print usage. Sub-command dispatch is handled by TreeCommand.
        if (nextCommand == null) {
            feedback().accept("Usage: /leaftree <generate|give|list|reload> ...");
        }
        return true;
    }

    // ------------------------------------------------------------------
    // Sub-commands
    // ------------------------------------------------------------------

    private static final class GenerateSub extends AbstractLeafCommand {
        private final Supplier<TreeGenConfig> configRef;
        private final ProceduralGenerator generator;
        private final CallerLocation.Resolver callerLocation;

        GenerateSub(CommandsAPICommand parent, Supplier<TreeGenConfig> configRef,
                    ProceduralGenerator generator, Consumer<String> feedback,
                    CallerLocation.Resolver callerLocation) {
            super("generate", "leaftreegen.admin", "Generate a tree at x,y,z (default ~ ~ ~, i.e. the caller's location, or origin 0,64,0 from console; supports relative ~ and ~n).", parent, feedback);
            this.configRef = configRef;
            this.generator = generator;
            this.callerLocation = callerLocation == null ? CallerLocation.Resolver.NONE : callerLocation;
            addParameter("species", new SpeciesParameter(configRef));
            addParameter("world", new FreeParameter("leaftreegen.admin", "World name"));
            addParameter("x", new FreeParameter("leaftreegen.admin", "X coordinate"));
            addParameter("y", new FreeParameter("leaftreegen.admin", "Y coordinate"));
            addParameter("z", new FreeParameter("leaftreegen.admin", "Z coordinate"));
        }

        @Override
        public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
            TreeGenConfig cfg = configRef.get();
            if (cfg == null) { feedback().accept("Config not loaded."); return true; }
            List<String> speciesArg = parameterValues.get("species");
            if (speciesArg == null || speciesArg.isEmpty()) { feedback().accept("Usage: /leaftree generate species=<id>"); return true; }
            String id = speciesArg.get(0);
            TreeSpecies species = cfg.get(id);
            if (species == null) { feedback().accept("Unknown species: " + id); return true; }

            // Coordinates default to relative (~), anchored to the caller's live location when the
            // platform can resolve it (so an omitted or ~ argument presumes the player's position).
            // Console / unknown callers have no position, so we fall back to the origin (0,64,0).
            CallerLocation loc = callerLocation.apply(callerId);
            int baseX = loc != null ? loc.x() : 0;
            int baseY = loc != null ? loc.y() : 64;
            int baseZ = loc != null ? loc.z() : 0;
            String defaultWorld = loc != null && loc.world() != null ? loc.world() : "world";

            String world = firstOr(parameterValues.get("world"), defaultWorld);
            int x = relativeOr(parameterValues.get("x"), baseX);
            int y = relativeOr(parameterValues.get("y"), baseY);
            int z = relativeOr(parameterValues.get("z"), baseZ);

            generator.generate(world, x, y, z, species, new Random());
            feedback().accept("Generated " + species.displayName() + " at " + x + "," + y + "," + z + " in " + world + ".");
            return true;
        }
    }

    private static final class GiveSub extends AbstractLeafCommand {
        private final Supplier<TreeGenConfig> configRef;
        private final SaplingGiver saplingGiver;

        GiveSub(CommandsAPICommand parent, Supplier<TreeGenConfig> configRef, Consumer<String> feedback,
                SaplingGiver saplingGiver) {
            super("give", "leaftreegen.admin", "Give tree saplings.", parent, feedback);
            this.configRef = configRef;
            this.saplingGiver = saplingGiver == null ? SaplingGiver.NONE : saplingGiver;
            addParameter("species", new SpeciesParameter(configRef));
            addParameter("player", new FreeParameter("leaftreegen.admin", "Target player"));
            addParameter("amount", new FreeParameter("leaftreegen.admin", "Amount"));
        }

        @Override
        public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
            TreeGenConfig cfg = configRef.get();
            if (cfg == null) { feedback().accept("Config not loaded."); return true; }
            List<String> speciesArg = parameterValues.get("species");
            if (speciesArg == null || speciesArg.isEmpty()) { feedback().accept("Usage: /leaftree give species=<id> [player=<name>] [amount=<n>]"); return true; }
            String id = speciesArg.get(0);
            TreeSpecies species = cfg.get(id);
            if (species == null) { feedback().accept("Unknown species: " + id); return true; }
            int amount = intOr(parameterValues.get("amount"), 1);
            List<String> playerArg = parameterValues.get("player");
            String playerName = playerArg == null || playerArg.isEmpty() ? null : playerArg.get(0);

            // Actually deliver the item; only claim success when it was handed over.
            boolean delivered = saplingGiver.give(callerId, playerName, species.id(), amount);
            String target = playerName != null ? playerName : "(self)";
            if (delivered) {
                feedback().accept("Gave " + amount + "x " + species.displayName() + " to " + target + ".");
            } else {
                feedback().accept("Could not give " + species.displayName() + " to " + target
                    + " (player not online, or this platform has no sapling item).");
            }
            return true;
        }
    }

    private static final class ListSub extends AbstractLeafCommand {
        private final Supplier<TreeGenConfig> configRef;

        ListSub(CommandsAPICommand parent, Supplier<TreeGenConfig> configRef, Consumer<String> feedback) {
            super("list", "leaftreegen.admin", "List tree species.", parent, feedback);
            this.configRef = configRef;
            addParameter("species", new SpeciesParameter(configRef));
        }

        @Override
        public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
            TreeGenConfig cfg = configRef.get();
            if (cfg == null) { feedback().accept("Config not loaded."); return true; }
            List<String> speciesArg = parameterValues.get("species");
            if (speciesArg != null && !speciesArg.isEmpty()) {
                TreeSpecies species = cfg.get(speciesArg.get(0));
                if (species == null) { feedback().accept("Unknown species: " + speciesArg.get(0)); return true; }
                feedback().accept(species.id() + ": " + species.displayName());
            } else {
                feedback().accept("Species: " + String.join(", ", cfg.ids()));
            }
            return true;
        }
    }

    private static final class ReloadSub extends AbstractLeafCommand {
        private final Runnable reloadHook;

        ReloadSub(CommandsAPICommand parent, Runnable reloadHook, Consumer<String> feedback) {
            super("reload", "leaftreegen.admin", "Reload the config.", parent, feedback);
            this.reloadHook = reloadHook;
        }

        @Override
        public boolean onCommand(UUID callerId, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
            reloadHook.run();
            feedback().accept("LeafTreeGen reloaded.");
            return true;
        }
    }

    // ------------------------------------------------------------------
    // Parameters
    // ------------------------------------------------------------------

    /** Species parameter; values are the live config ids (drives tab-completion). */
    private static final class SpeciesParameter extends CommandParameter {
        private final Supplier<TreeGenConfig> configRef;

        SpeciesParameter(Supplier<TreeGenConfig> configRef) {
            super("leaftreegen.admin", "Species", (u, v) -> true);
            this.configRef = configRef;
        }

        @Override
        public Set<String> values() {
            TreeGenConfig cfg = configRef.get();
            return cfg == null ? new HashSet<>() : new HashSet<>(cfg.ids());
        }
    }

    /** A free-form parameter that accepts any value and offers no fixed suggestions. */
    private static final class FreeParameter extends CommandParameter {
        FreeParameter(String permission, String description) {
            super(permission, description, (u, v) -> true);
        }

        @Override
        public Set<String> values() {
            return new HashSet<>();
        }
    }

    // ------------------------------------------------------------------
    // Helpers shared by sub-commands
    // ------------------------------------------------------------------

    private static String firstOr(List<String> values, String fallback) {
        return (values == null || values.isEmpty()) ? fallback : values.get(0);
    }

    private static int intOr(List<String> values, int fallback) {
        if (values == null || values.isEmpty()) return fallback;
        try {
            return Integer.parseInt(values.get(0).trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /**
     * Resolves a coordinate argument supporting Minecraft-style relative syntax.
     *
     * <p>The default is {@code ~}: an omitted, empty/blank, or unparseable argument is
     * treated as relative and yields {@code base}. {@code ~} yields {@code base},
     * {@code ~n} yields {@code base + n} (n may be negative), and a bare integer is taken
     * as absolute.
     */
    private static int relativeOr(List<String> values, int base) {
        if (values == null || values.isEmpty()) return base;
        String s = values.get(0).trim();
        if (s.isEmpty()) return base;
        try {
            if (s.startsWith("~")) {
                String rest = s.substring(1).trim();
                return rest.isEmpty() ? base : base + Integer.parseInt(rest);
            }
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return base;
        }
    }
}
