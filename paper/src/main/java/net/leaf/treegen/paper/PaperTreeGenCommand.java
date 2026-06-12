package net.leaf.treegen.paper;

import io.github.dailystruggle.commandsapi.bukkit.BukkitParameter;
import io.github.dailystruggle.commandsapi.bukkit.LocalParameters.OnlinePlayerParameter;
import io.github.dailystruggle.commandsapi.bukkit.localCommands.BukkitTreeCommand;
import io.github.dailystruggle.commandsapi.common.CommandsAPICommand;
import io.github.dailystruggle.commandsapi.common.parameters.IntegerParameter;
import net.leaf.treegen.common.TreeSpecies;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

public final class PaperTreeGenCommand extends BukkitTreeCommand {

    private final PaperLeafTreeGenPlugin treePlugin;
    private final Random random = new Random();

    public PaperTreeGenCommand(PaperLeafTreeGenPlugin plugin) {
        super(plugin, null);
        this.treePlugin = plugin;
        addSubCommand(new GenerateSubcommand(plugin, this));
        addSubCommand(new GiveSubcommand(plugin, this));
        addSubCommand(new ListSubcommand(plugin, this));
        addSubCommand(new DebugSubcommand(plugin, this));
        addSubCommand(new ReloadSubcommand(plugin, this));
        addSubCommand(new StatsSubcommand(plugin, this));
    }

    @Override public String name() { return "leaftree"; }
    @Override public String permission() { return "leaftreegen.admin"; }
    @Override public String description() { return "LeafTreeGen root command."; }

    @Override
    public void msgBadParameter(java.util.UUID callerId, String parameterName, String parameterValue) {
        CommandSender sender = getSender(callerId);
        if (sender != null) sender.sendMessage(Component.text("Invalid value for " + parameterName + ": " + parameterValue, NamedTextColor.RED));
    }

    @Override
    public void msgBadParameter(java.util.UUID callerId, String parameterName, String parameterValue, java.util.function.Consumer<String> messageMethod) {
        messageMethod.accept("Invalid value for " + parameterName + ": " + parameterValue);
    }

    @Override
    public void msgInvalidCommand(java.util.UUID callerId, String argument) {
        CommandSender sender = getSender(callerId);
        if (sender != null) sender.sendMessage(Component.text("Unknown subcommand: " + argument, NamedTextColor.RED));
    }

    @Override
    public void msgInvalidCommand(java.util.UUID callerId, String argument, java.util.function.Consumer<String> messageMethod) {
        messageMethod.accept("Unknown subcommand: " + argument);
    }

    @Override
    public boolean onCommand(CommandSender sender, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
        if (nextCommand == null) sender.sendMessage(Component.text("Usage: /leaftree <generate|give|list|reload> ...", NamedTextColor.YELLOW));
        return true;
    }

    private CommandSender getSender(java.util.UUID id) {
        if (id.equals(io.github.dailystruggle.commandsapi.common.CommandsAPI.serverId)) return Bukkit.getConsoleSender();
        return Bukkit.getPlayer(id);
    }

    private class GenerateSubcommand extends BukkitTreeCommand {
        GenerateSubcommand(PaperLeafTreeGenPlugin p, PaperTreeGenCommand parent) { 
            super(p, parent); 
            addParameter("species", new SpeciesParameter()); 
        }
        @Override public String name() { return "generate"; }
        @Override public String permission() { return "leaftreegen.admin"; }
        @Override public String description() { return "Generate a tree."; }
        @Override public boolean onCommand(CommandSender sender, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
            if (!(sender instanceof Player player)) { sender.sendMessage("Only players can use this."); return true; }
            String id = parameterValues.get("species").get(0);
            TreeSpecies species = treePlugin.config().get(id);
            if (species == null) { sender.sendMessage("Unknown species."); return true; }
            Block target = player.getTargetBlockExact(64);
            Location base = target != null ? target.getLocation().add(0, 1, 0) : player.getLocation();
            
            treePlugin.proceduralGenerator().generate(base.getWorld().getName(), base.getBlockX(), base.getBlockY(), base.getBlockZ(), species, random);
            sender.sendMessage("Generated " + species.displayName() + ".");
            return true;
        }
        @Override public void msgBadParameter(java.util.UUID c, String n, String v) { PaperTreeGenCommand.this.msgBadParameter(c, n, v); }
        @Override public void msgBadParameter(java.util.UUID c, String n, String v, java.util.function.Consumer<String> m) { PaperTreeGenCommand.this.msgBadParameter(c, n, v, m); }
        @Override public void msgInvalidCommand(java.util.UUID c, String a) { PaperTreeGenCommand.this.msgInvalidCommand(c, a); }
        @Override public void msgInvalidCommand(java.util.UUID c, String a, java.util.function.Consumer<String> m) { PaperTreeGenCommand.this.msgInvalidCommand(c, a, m); }
    }

    private class GiveSubcommand extends BukkitTreeCommand {
        GiveSubcommand(PaperLeafTreeGenPlugin p, PaperTreeGenCommand parent) { 
            super(p, parent); 
            addParameter("species", new SpeciesParameter());
            addParameter("player", new OnlinePlayerParameter("leaftreegen.admin", "Target player", (s, v) -> true));
            addParameter("amount", new IntegerParameter("leaftreegen.admin", "Amount", (u, v) -> true, 1, 64));
            addParameter("variant", new VariantParameter());
        }
        @Override public String name() { return "give"; }
        @Override public String permission() { return "leaftreegen.admin"; }
        @Override public String description() { return "Give tree saplings."; }
        @Override public boolean onCommand(CommandSender sender, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
            String id = parameterValues.get("species").get(0);
            TreeSpecies species = treePlugin.config().get(id);
            if (species == null) { sender.sendMessage("Unknown species."); return true; }
            Player target = (parameterValues.containsKey("player")) ? Bukkit.getPlayerExact(parameterValues.get("player").get(0)) : (sender instanceof Player ? (Player)sender : null);
            if (target == null) { sender.sendMessage("Player not found."); return true; }
            int amount = parameterValues.containsKey("amount") ? Integer.parseInt(parameterValues.get("amount").get(0)) : 1;

            // Optional: bind the sapling to a specific template variant. When omitted the variant is
            // left unspecified and chosen at random when the sapling is planted.
            String variant = null;
            if (parameterValues.containsKey("variant") && !parameterValues.get("variant").isEmpty()) {
                String requested = parameterValues.get("variant").get(0);
                List<String> available = treePlugin.registry().variantsFor(Bukkit.getWorlds().get(0).getName(), species);
                variant = available.stream()
                        .filter(v -> v.equals(requested) || v.endsWith("/" + requested) || v.endsWith(":" + requested))
                        .findFirst().orElse(null);
                if (variant == null) {
                    sender.sendMessage(Component.text("Unknown variant '" + requested + "' for " + species.displayName()
                            + ". Available: " + String.join(", ", available), NamedTextColor.RED));
                    return true;
                }
            }

            ItemStack item = PaperSaplingItem.create(species, amount, variant);
            target.getInventory().addItem(item).values().forEach(l -> target.getWorld().dropItemNaturally(target.getLocation(), l));
            sender.sendMessage("Gave " + amount + "x " + species.displayName() + " saplings"
                    + (variant != null ? " (" + variant + ")" : "") + ".");
            return true;
        }
        @Override public void msgBadParameter(java.util.UUID c, String n, String v) { PaperTreeGenCommand.this.msgBadParameter(c, n, v); }
        @Override public void msgBadParameter(java.util.UUID c, String n, String v, java.util.function.Consumer<String> m) { PaperTreeGenCommand.this.msgBadParameter(c, n, v, m); }
        @Override public void msgInvalidCommand(java.util.UUID c, String a) { PaperTreeGenCommand.this.msgInvalidCommand(c, a); }
        @Override public void msgInvalidCommand(java.util.UUID c, String a, java.util.function.Consumer<String> m) { PaperTreeGenCommand.this.msgInvalidCommand(c, a, m); }
    }

    private class ListSubcommand extends BukkitTreeCommand {
        ListSubcommand(PaperLeafTreeGenPlugin p, PaperTreeGenCommand parent) { 
            super(p, parent); 
            addParameter("species", new SpeciesParameter()); 
        }
        @Override public String name() { return "list"; }
        @Override public String permission() { return "leaftreegen.admin"; }
        @Override public String description() { return "List tree species."; }
        @Override public boolean onCommand(CommandSender sender, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
            if (parameterValues.containsKey("species")) {
                TreeSpecies s = treePlugin.config().get(parameterValues.get("species").get(0));
                if (s == null) return true;
                sender.sendMessage(s.id() + " variants: " + String.join(", ", treePlugin.registry().variantsFor(Bukkit.getWorlds().get(0).getName(), s)));
            } else {
                sender.sendMessage("Species: " + String.join(", ", treePlugin.config().ids()));
            }
            return true;
        }
        @Override public void msgBadParameter(java.util.UUID c, String n, String v) { PaperTreeGenCommand.this.msgBadParameter(c, n, v); }
        @Override public void msgBadParameter(java.util.UUID c, String n, String v, java.util.function.Consumer<String> m) { PaperTreeGenCommand.this.msgBadParameter(c, n, v, m); }
        @Override public void msgInvalidCommand(java.util.UUID c, String a) { PaperTreeGenCommand.this.msgInvalidCommand(c, a); }
        @Override public void msgInvalidCommand(java.util.UUID c, String a, java.util.function.Consumer<String> m) { PaperTreeGenCommand.this.msgInvalidCommand(c, a, m); }
    }

    private class ReloadSubcommand extends BukkitTreeCommand {
        ReloadSubcommand(PaperLeafTreeGenPlugin p, PaperTreeGenCommand parent) { 
            super(p, parent); 
        }
        @Override public String name() { return "reload"; }
        @Override public String permission() { return "leaftreegen.admin"; }
        @Override public String description() { return "Reload the config."; }
        @Override public boolean onCommand(CommandSender sender, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
            treePlugin.reload();
            sender.sendMessage("Reloaded.");
            return true;
        }
        @Override public void msgBadParameter(java.util.UUID c, String n, String v) { PaperTreeGenCommand.this.msgBadParameter(c, n, v); }
        @Override public void msgBadParameter(java.util.UUID c, String n, String v, java.util.function.Consumer<String> m) { PaperTreeGenCommand.this.msgBadParameter(c, n, v, m); }
        @Override public void msgInvalidCommand(java.util.UUID c, String a) { PaperTreeGenCommand.this.msgInvalidCommand(c, a); }
        @Override public void msgInvalidCommand(java.util.UUID c, String a, java.util.function.Consumer<String> m) { PaperTreeGenCommand.this.msgInvalidCommand(c, a, m); }
    }

    private class DebugSubcommand extends BukkitTreeCommand {
        DebugSubcommand(PaperLeafTreeGenPlugin p, PaperTreeGenCommand parent) { 
            super(p, parent); 
        }
        @Override public String name() { return "debug"; }
        @Override public String permission() { return "leaftreegen.admin"; }
        @Override public String description() { return "Dump debug status."; }
        @Override public boolean onCommand(CommandSender sender, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
            sender.sendMessage(Component.text("--- LeafTreeGen Debug ---", NamedTextColor.GOLD));
            sender.sendMessage("Generation Mode: " + treePlugin.config().mode());
            sender.sendMessage("Active Species: " + treePlugin.config().ids().size());
            for (String id : treePlugin.config().ids()) {
                TreeSpecies s = treePlugin.config().get(id);
                sender.sendMessage(Component.text("- " + s.id() + ": biomes=" + s.biomes().size() + ", worldgen=" + s.worldgen() + ", replace=" + s.replaceVanilla(), NamedTextColor.GRAY));
            }
            sender.sendMessage("Worlds: " + Bukkit.getWorlds().stream().map(w -> w.getName()).toList());
            return true;
        }
        @Override public void msgBadParameter(java.util.UUID c, String n, String v) { PaperTreeGenCommand.this.msgBadParameter(c, n, v); }
        @Override public void msgBadParameter(java.util.UUID c, String n, String v, java.util.function.Consumer<String> m) { PaperTreeGenCommand.this.msgBadParameter(c, n, v, m); }
        @Override public void msgInvalidCommand(java.util.UUID c, String a) { PaperTreeSuppressionListener suppression = new PaperTreeSuppressionListener(treePlugin); // dummy to ensure class is loaded
            PaperTreeGenCommand.this.msgInvalidCommand(c, a); }
        @Override public void msgInvalidCommand(java.util.UUID c, String a, java.util.function.Consumer<String> m) { PaperTreeGenCommand.this.msgInvalidCommand(c, a, m); }
    }

    private class StatsSubcommand extends BukkitTreeCommand {
        StatsSubcommand(PaperLeafTreeGenPlugin p, PaperTreeGenCommand parent) {
            super(p, parent);
            addParameter("reset", new io.github.dailystruggle.commandsapi.common.parameters.BooleanParameter("leaftreegen.admin", "Reset stats", (s, v) -> true));
        }
        @Override public String name() { return "stats"; }
        @Override public String permission() { return "leaftreegen.admin"; }
        @Override public String description() { return "Show tick budget consumption stats."; }
        @Override public boolean onCommand(CommandSender sender, Map<String, List<String>> parameterValues, CommandsAPICommand nextCommand) {
            PaperPlatform platform = (PaperPlatform) treePlugin.platform();
            if (parameterValues.containsKey("reset") && Boolean.parseBoolean(parameterValues.get("reset").get(0))) {
                platform.resetStats();
                sender.sendMessage(Component.text("Stats reset.", NamedTextColor.GREEN));
                return true;
            }

            long blocks = platform.getTotalBlocksPlaced();
            long timeNanos = platform.getTotalTimeSpentNanos();
            long batches = platform.getTotalBatches();
            long exceeded = platform.getBudgetExceededCount();
            
            double timeMs = timeNanos / 1_000_000.0;
            double avgTimeBatch = batches > 0 ? timeMs / batches : 0;
            double avgBlocksBatch = batches > 0 ? (double) blocks / batches : 0;

            sender.sendMessage(Component.text("--- LeafTreeGen Tick Budget Stats ---", NamedTextColor.GOLD));
            sender.sendMessage(Component.text("Total Blocks Placed: ", NamedTextColor.GRAY).append(Component.text(blocks, NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("Total Time Spent: ", NamedTextColor.GRAY).append(Component.text(String.format("%.2f ms", timeMs), NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("Total Batches: ", NamedTextColor.GRAY).append(Component.text(batches, NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("Budget Exceeded Events: ", NamedTextColor.GRAY).append(Component.text(exceeded, NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("Avg Time per Batch: ", NamedTextColor.GRAY).append(Component.text(String.format("%.2f ms", avgTimeBatch), NamedTextColor.WHITE)));
            sender.sendMessage(Component.text("Avg Blocks per Batch: ", NamedTextColor.GRAY).append(Component.text(String.format("%.2f", avgBlocksBatch), NamedTextColor.WHITE)));
            
            return true;
        }
        @Override public void msgBadParameter(java.util.UUID c, String n, String v) { PaperTreeGenCommand.this.msgBadParameter(c, n, v); }
        @Override public void msgBadParameter(java.util.UUID c, String n, String v, java.util.function.Consumer<String> m) { PaperTreeGenCommand.this.msgBadParameter(c, n, v, m); }
        @Override public void msgInvalidCommand(java.util.UUID c, String a) { PaperTreeGenCommand.this.msgInvalidCommand(c, a); }
        @Override public void msgInvalidCommand(java.util.UUID c, String a, java.util.function.Consumer<String> m) { PaperTreeGenCommand.this.msgInvalidCommand(c, a, m); }
    }

    private class SpeciesParameter extends BukkitParameter {
        SpeciesParameter() { super("leaftreegen.admin", "Species", (s, v) -> true); }
        @Override public Set<String> values() { return new HashSet<>(treePlugin.config().ids()); }
    }

    /** Tab-completes the template variants available across configured species (full structure keys). */
    private class VariantParameter extends BukkitParameter {
        VariantParameter() { super("leaftreegen.admin", "Variant", (s, v) -> true); }
        @Override public Set<String> values() {
            if (Bukkit.getWorlds().isEmpty()) return new HashSet<>();
            String world = Bukkit.getWorlds().get(0).getName();
            Set<String> out = new HashSet<>();
            for (String id : treePlugin.config().ids()) {
                TreeSpecies s = treePlugin.config().get(id);
                if (s != null) out.addAll(treePlugin.registry().variantsFor(world, s));
            }
            return out;
        }
    }
}
