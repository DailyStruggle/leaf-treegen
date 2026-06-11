package net.leaf.treegen.common;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * In-memory representation of a generated tree.
 * Can be selectively output to a platform (world) or structure format (NBT).
 */
public final class TreeModel {
    public record BlockPos(int x, int y, int z) {
        public BlockPos add(int dx, int dy, int dz) {
            return new BlockPos(x + dx, y + dy, z + dz);
        }
    }

    private final Map<BlockPos, String> blocks;
    private final String speciesId;

    public TreeModel(String speciesId) {
        this.speciesId = speciesId;
        this.blocks = new HashMap<>();
    }

    public TreeModel(String speciesId, Map<BlockPos, String> blocks) {
        this.speciesId = speciesId;
        this.blocks = new HashMap<>(blocks);
    }

    public void setBlock(int x, int y, int z, String block) {
        blocks.put(new BlockPos(x, y, z), block);
    }

    public String getBlock(int x, int y, int z) {
        return blocks.get(new BlockPos(x, y, z));
    }

    public Map<BlockPos, String> getBlocks() {
        return Collections.unmodifiableMap(blocks);
    }

    public String getSpeciesId() {
        return speciesId;
    }

    public Set<BlockPos> getPositions() {
        return blocks.keySet();
    }

    public void place(Platform platform, String worldName, int ox, int oy, int oz) {
        blocks.forEach((pos, state) -> {
            platform.setBlock(worldName, ox + pos.x, oy + pos.y, oz + pos.z, state);
        });
    }

    /**
     * Returns the bounding box of the tree model.
     * [minX, minY, minZ, maxX, maxY, maxZ]
     */
    public int[] getBounds() {
        if (blocks.isEmpty()) return new int[]{0, 0, 0, 0, 0, 0};
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos pos : blocks.keySet()) {
            minX = Math.min(minX, pos.x);
            minY = Math.min(minY, pos.y);
            minZ = Math.min(minZ, pos.z);
            maxX = Math.max(maxX, pos.x);
            maxY = Math.max(maxY, pos.y);
            maxZ = Math.max(maxZ, pos.z);
        }
        return new int[]{minX, minY, minZ, maxX, maxY, maxZ};
    }
}
