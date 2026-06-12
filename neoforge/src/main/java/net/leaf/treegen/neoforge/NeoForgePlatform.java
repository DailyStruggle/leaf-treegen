package net.leaf.treegen.neoforge;

import net.leaf.treegen.common.Platform;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.logging.Logger;

public class NeoForgePlatform implements Platform {
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
    @Override public void scheduleRepeatingTask(Runnable task, long d, long p) {}
    @Override public String getBiomeKey(String w, int x, int y, int z) { return "minecraft:plains"; }
    @Override public boolean placeStructure(String w, int x, int y, int z, String k) { return false; }
    @Override public void giveSapling(UUID p, String s, int a) {}
    @Override public void sendMessage(UUID p, String m, boolean e) {}
    @Override public boolean setBlock(String w, int x, int y, int z, String b) { return true; }
    @Override public boolean setBlocks(String w, int cx, int cz, java.util.Map<net.leaf.treegen.common.TreeModel.BlockPos, String> blocks) { return true; }
}
