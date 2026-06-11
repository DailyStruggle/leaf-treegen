package net.leaf.treegen.fabric;

import net.leaf.treegen.common.Platform;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.logging.Logger;

public class FabricPlatform implements Platform {
    @Override public Logger logger() { return Logger.getLogger("LeafTreeGen"); }
    @Override public Path getRootFolder() { return Path.of("."); }
    @Override public Path getWorldFolder(String worldName) { return Path.of("world"); }
    @Override public List<String> getWorldNames() { return List.of("world"); }
    @Override public void scheduleRepeatingTask(Runnable task, long d, long p) {}
    @Override public String getBiomeKey(String w, int x, int y, int z) { return "minecraft:plains"; }
    @Override public boolean placeStructure(String w, int x, int y, int z, String k) { return false; }
    @Override public void giveSapling(UUID p, String s, int a) {}
    @Override public void sendMessage(UUID p, String m, boolean e) {}
    @Override public void setBlock(String w, int x, int y, int z, String b) {}
}
