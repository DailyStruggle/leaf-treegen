package net.leaf.treegen.common.command;

import io.github.dailystruggle.commandsapi.common.CommandParameter;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.localCommands.TreeCommand;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Common boilerplate for a {@link TreeCommand} node without any platform (Bukkit) coupling.
 * Mirrors the structure of {@code BukkitTreeCommand} but stores its metadata and lookups
 * directly so it can live in the platform-agnostic {@code common} module.
 */
abstract class AbstractLeafCommand implements TreeCommand {
    private final String name;
    private final String permission;
    private final String description;
    private final CommandsAPICommand parent;
    private final Consumer<String> feedback;
    private final Map<String, CommandParameter> parameterLookup = new ConcurrentHashMap<>();
    private final Map<String, CommandsAPICommand> commandLookup = new ConcurrentHashMap<>();

    AbstractLeafCommand(String name, String permission, String description,
                        CommandsAPICommand parent, Consumer<String> feedback) {
        this.name = name;
        this.permission = permission;
        this.description = description;
        this.parent = parent;
        this.feedback = feedback;
    }

    protected Consumer<String> feedback() {
        return feedback;
    }

    @Override public String name() { return name; }
    @Override public String permission() { return permission; }
    @Override public String description() { return description; }
    @Override public CommandsAPICommand parent() { return parent; }
    @Override public long avgTime() { return 0L; }
    @Override public Map<String, CommandParameter> getParameterLookup() { return parameterLookup; }
    @Override public Map<String, CommandsAPICommand> getCommandLookup() { return commandLookup; }

    @Override
    public void msgBadParameter(UUID callerId, String parameterName, String parameterValue) {
        feedback.accept("Invalid value for " + parameterName + ": " + parameterValue);
    }

    @Override
    public void msgInvalidCommand(UUID callerId, String argument) {
        feedback.accept("Unknown subcommand: " + argument);
    }
}
