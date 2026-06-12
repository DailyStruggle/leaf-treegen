package net.leaf.treegen.neoforge;

import net.leaf.treegen.common.Platform;
import net.leaf.treegen.common.TreeModel;

import com.mojang.brigadier.exceptions.CommandSyntaxException;
import net.minecraft.commands.arguments.blocks.BlockStateParser;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.Vec3i;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructurePlaceSettings;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplate;

import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Logger;

public class NeoForgePlatform implements Platform {
    /**
     * Flags for {@link ServerLevel#setBlock}: notify clients and skip neighbour-shape
     * updates / drops. This mirrors the "no physics" placement the Paper platform uses,
     * so freshly-written logs do not trigger leaf-decay or pop off as items while the
     * tree skeleton is still being filled in.
     */
    private static final int PLACE_FLAGS =
        Block.UPDATE_CLIENTS | Block.UPDATE_KNOWN_SHAPE | Block.UPDATE_SUPPRESS_DROPS;

    /** Set once the server has started (see {@link NeoForgeMod}); null during bootstrap. */
    private volatile MinecraftServer server;

    /** Cache of parsed block-states keyed by their string form to avoid re-parsing. */
    private final Map<String, BlockState> blockStateCache = new ConcurrentHashMap<>();

    /** Console sentinel id: {@link #sendMessage} routes this to the server console. */
    private static final UUID CONSOLE_ID = new UUID(0, 0);

    /** Repeating tasks driven from the server tick (see {@link #onServerTick()}). */
    private final List<ScheduledTask> scheduledTasks = new CopyOnWriteArrayList<>();

    void setServer(MinecraftServer server) {
        this.server = server;
    }

    /**
     * Advances all repeating tasks by one tick. Called from the server-thread tick handler
     * in {@link NeoForgeMod}, so scheduled work runs on the main thread just like Paper's
     * region scheduler callbacks.
     */
    void onServerTick() {
        for (ScheduledTask t : scheduledTasks) {
            t.tick();
        }
    }

    /** A repeating task with a tick countdown; mirrors Bukkit's runAtFixedRate semantics. */
    private static final class ScheduledTask {
        private final Runnable task;
        private final long period;
        private long remaining;

        ScheduledTask(Runnable task, long delayTicks, long periodTicks) {
            this.task = task;
            this.period = Math.max(1L, periodTicks);
            this.remaining = Math.max(0L, delayTicks);
        }

        void tick() {
            if (remaining > 0) {
                remaining--;
                return;
            }
            task.run();
            remaining = period - 1;
        }
    }

    @Override public Logger logger() { return Logger.getLogger("LeafTreeGen"); }
    @Override public Path getRootFolder() { return Path.of("config", "leaf-treegen"); }
    @Override public Path getWorldFolder(String worldName) { return Path.of(worldName); }
    @Override public List<String> getWorldNames() { return List.of(readLevelName()); }

    private static String readLevelName() {
        java.io.File props = new java.io.File("server.properties");
        if (props.exists()) {
            Properties p = new Properties();
            try (FileInputStream in = new FileInputStream(props)) {
                p.load(in);
                String name = p.getProperty("level-name");
                if (name != null && !name.isBlank()) return name.trim();
            } catch (IOException ignored) {}
        }
        return "world";
    }
    @Override public void scheduleRepeatingTask(Runnable task, long d, long p) {
        scheduledTasks.add(new ScheduledTask(task, d, p));
    }

    @Override public String getBiomeKey(String w, int x, int y, int z) {
        ServerLevel level = resolveLevel(w);
        if (level == null) return "minecraft:plains";
        return level.getBiome(new BlockPos(x, y, z))
            .unwrapKey().map(key -> key.identifier().toString()).orElse("minecraft:plains");
    }

    @Override public boolean placeStructure(String w, int x, int y, int z, String k) {
        ServerLevel level = resolveLevel(w);
        MinecraftServer srv = this.server;
        if (level == null || srv == null) return false;
        Identifier id;
        try {
            id = Identifier.parse(k);
        } catch (Exception ex) {
            logger().warning("Invalid structure key '" + k + "': " + ex.getMessage());
            return false;
        }
        Optional<StructureTemplate> template = srv.getStructureManager().get(id);
        if (template.isEmpty()) return false;
        StructureTemplate tpl = template.get();
        // Centre the structure on the requested position (mirrors the Paper platform).
        Vec3i size = tpl.getSize();
        BlockPos origin = new BlockPos(x - size.getX() / 2, y, z - size.getZ() / 2);
        try {
            return tpl.placeInWorld(level, origin, origin,
                new StructurePlaceSettings(), RandomSource.create(), PLACE_FLAGS);
        } catch (Exception ex) {
            logger().warning("Failed to place structure '" + k + "': " + ex.getMessage());
            return false;
        }
    }

    // No custom sapling item is registered on this platform; give the vanilla sapling instead.
    @Override public void giveSapling(UUID p, String s, int a) {}

    /**
     * Delivers {@code amount} copies of the (already-built, e.g. species-tagged) {@code prototype}
     * stack to the named online player, splitting across max-size stacks and dropping any overflow.
     * Returns {@code true} only when the player was found and the items delivered.
     */
    boolean giveStackToPlayer(String playerName, ItemStack prototype, int amount) {
        MinecraftServer srv = this.server;
        if (srv == null || playerName == null || prototype == null || prototype.isEmpty()) return false;
        ServerPlayer player = srv.getPlayerList().getPlayerByName(playerName);
        if (player == null) return false;

        int remaining = Math.max(1, amount);
        int maxStack = prototype.getMaxStackSize();
        while (remaining > 0) {
            int give = Math.min(remaining, maxStack);
            ItemStack stack = prototype.copyWithCount(give);
            if (!player.getInventory().add(stack)) {
                player.drop(stack, false);
            }
            remaining -= give;
        }
        return true;
    }

    @Override public void sendMessage(UUID p, String m, boolean e) {
        MinecraftServer srv = this.server;
        if (srv == null) return;
        Component message = Component.literal(m);
        if (p == null || p.equals(CONSOLE_ID)) {
            srv.sendSystemMessage(message);
            return;
        }
        ServerPlayer player = srv.getPlayerList().getPlayer(p);
        if (player != null) {
            player.sendSystemMessage(message);
        } else {
            srv.sendSystemMessage(message);
        }
    }

    @Override public boolean setBlock(String w, int x, int y, int z, String b) {
        ServerLevel level = resolveLevel(w);
        if (level == null) return false;
        BlockState state = parse(b);
        if (state == null) return false;
        return level.setBlock(new BlockPos(x, y, z), state, PLACE_FLAGS);
    }

    @Override public boolean setBlocks(String w, int cx, int cz, Map<TreeModel.BlockPos, String> blocks) {
        ServerLevel level = resolveLevel(w);
        if (level == null) return false;
        if (blocks.isEmpty()) return true;

        BlockPos.MutableBlockPos pos = new BlockPos.MutableBlockPos();
        for (Map.Entry<TreeModel.BlockPos, String> entry : blocks.entrySet()) {
            String data = entry.getValue();
            // Never stamp plain air into the world; it would carve holes around the
            // tree. (void_air is the deliberate "clear space" block and parses fine.)
            String baseBlock = data.split("\\[")[0].toLowerCase(Locale.ROOT);
            if (baseBlock.equals("minecraft:air") || baseBlock.equals("minecraft:cave_air")
                || baseBlock.equals("air") || baseBlock.equals("cave_air")) {
                continue;
            }
            BlockState state = parse(data);
            if (state == null) continue;
            TreeModel.BlockPos p = entry.getKey();
            pos.set(p.x(), p.y(), p.z());
            level.setBlock(pos, state, PLACE_FLAGS);
        }
        return true;
    }

    /**
     * Resolves the {@link ServerLevel} for the given world name. The name supplied by the
     * command path is typically the save's {@code level-name} (e.g. {@code "world"}), which
     * does not map directly to a dimension id, so we match on the dimension's path and fall
     * back to the overworld.
     */
    private ServerLevel resolveLevel(String worldName) {
        MinecraftServer srv = this.server;
        if (srv == null) return null;
        if (worldName != null) {
            for (ServerLevel level : srv.getAllLevels()) {
                if (level.dimension().identifier().getPath().equalsIgnoreCase(worldName)) {
                    return level;
                }
            }
        }
        return srv.overworld();
    }

    private BlockState parse(String blockData) {
        BlockState cached = blockStateCache.get(blockData);
        if (cached != null) return cached;
        MinecraftServer srv = this.server;
        if (srv == null) return null;
        try {
            HolderLookup<Block> lookup = srv.registryAccess().lookupOrThrow(Registries.BLOCK);
            BlockState state = BlockStateParser.parseForBlock(lookup, blockData, false).blockState();
            blockStateCache.put(blockData, state);
            return state;
        } catch (CommandSyntaxException ex) {
            logger().warning("Failed to parse block '" + blockData + "': " + ex.getMessage());
            return null;
        }
    }
}
