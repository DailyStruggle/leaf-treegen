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

    public TreeModel(String speciesId, Map<BlockPos, String> blocks, boolean copy) {
        this.speciesId = speciesId;
        this.blocks = copy ? new HashMap<>(blocks) : blocks;
    }

    public TreeModel(String speciesId, Map<BlockPos, String> blocks) {
        this(speciesId, blocks, false);
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

    public void rotate(int quadrants) {
        quadrants = ((quadrants % 4) + 4) % 4;
        if (quadrants == 0) return;

        Map<BlockPos, String> newBlocks = new HashMap<>();
        for (Map.Entry<BlockPos, String> entry : blocks.entrySet()) {
            BlockPos pos = entry.getKey();
            String state = entry.getValue();

            int nx = pos.x, nz = pos.z;
            for (int i = 0; i < quadrants; i++) {
                int temp = nx;
                nx = -nz;
                nz = temp;
            }

            // Rotate block states if they have orientation
            if (state.contains("axis=")) {
                if (quadrants % 2 != 0) {
                    if (state.contains("axis=x")) {
                        state = state.replace("axis=x", "axis=z");
                    } else if (state.contains("axis=z")) {
                        state = state.replace("axis=z", "axis=x");
                    }
                }
            } else if (state.contains("facing=")) {
                for (int i = 0; i < quadrants; i++) {
                    if (state.contains("facing=north")) state = state.replace("facing=north", "facing=east_tmp");
                    else if (state.contains("facing=east")) state = state.replace("facing=east", "facing=south_tmp");
                    else if (state.contains("facing=south")) state = state.replace("facing=south", "facing=west_tmp");
                    else if (state.contains("facing=west")) state = state.replace("facing=west", "facing=north_tmp");
                    
                    state = state.replace("_tmp", "");
                }
            }

            newBlocks.put(new BlockPos(nx, pos.y, nz), state);
        }
        blocks.clear();
        blocks.putAll(newBlocks);
    }

    public String getSpeciesId() {
        return speciesId;
    }

    public Set<BlockPos> getPositions() {
        return blocks.keySet();
    }

    public void place(Platform platform, String worldName, int ox, int oy, int oz) {
        Map<Long, Map<BlockPos, String>> chunks = new java.util.TreeMap<>();
        
        blocks.forEach((pos, state) -> {
            int worldX = ox + pos.x;
            int worldY = oy + pos.y;
            int worldZ = oz + pos.z;
            
            int cx = worldX >> 4;
            int cz = worldZ >> 4;
            long key = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
            
            chunks.computeIfAbsent(key, k -> new java.util.TreeMap<>(java.util.Comparator
                        .comparingInt(BlockPos::y)
                        .thenComparingInt(BlockPos::x)
                        .thenComparingInt(BlockPos::z)))
                  .put(new BlockPos(worldX, worldY, worldZ), state);
        });

        chunks.forEach((key, chunkBlocks) -> {
            int cx = (int) (key.longValue() >> 32);
            int cz = (int) key.longValue();
            platform.setBlocks(worldName, cx, cz, chunkBlocks);
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
