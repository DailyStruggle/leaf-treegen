package net.leaf.treegen.common;

import java.util.*;

/**
 * A compressed version of TreeModel that stores blocks in segments.
 * Optimized for memory by linearizing 3D space and using relative positions.
 */
public final class SegmentedTreeModel {
    private final String speciesId;
    private final List<Segment> segments;
    private final Map<Integer, String> palette;

    public record Segment(int offsetX, int offsetY, int offsetZ, byte[] relativePositions, byte[] blockIndices) {}

    public SegmentedTreeModel(TreeModel model) {
        this.speciesId = model.getSpeciesId();
        this.palette = new HashMap<>();
        this.segments = new ArrayList<>();
        compress(model);
    }

    private void compress(TreeModel model) {
        Map<String, Integer> reversePalette = new HashMap<>();
        Map<TreeModel.BlockPos, String> blocks = model.getBlocks();
        if (blocks.isEmpty()) return;

        // Group by Y levels and sort them descending (top-to-bottom)
        Map<Integer, Map<TreeModel.BlockPos, String>> ySlices = new TreeMap<>(Collections.reverseOrder());
        for (Map.Entry<TreeModel.BlockPos, String> entry : blocks.entrySet()) {
            ySlices.computeIfAbsent(entry.getKey().y(), k -> new HashMap<>()).put(entry.getKey(), entry.getValue());
        }

        for (Map.Entry<Integer, Map<TreeModel.BlockPos, String>> slice : ySlices.entrySet()) {
            int y = slice.getKey();
            Map<TreeModel.BlockPos, String> sliceBlocks = slice.getValue();
            
            // Find bounds of slice to set anchor
            int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
            for (TreeModel.BlockPos pos : sliceBlocks.keySet()) {
                minX = Math.min(minX, pos.x());
                minZ = Math.min(minZ, pos.z());
            }

            byte[] relPos = new byte[sliceBlocks.size() * 2]; // store relative x, z as bytes (trees are rarely > 256 wide)
            byte[] indices = new byte[sliceBlocks.size()];
            int i = 0;
            for (Map.Entry<TreeModel.BlockPos, String> entry : sliceBlocks.entrySet()) {
                TreeModel.BlockPos pos = entry.getKey();
                String state = entry.getValue();

                int paletteIdx = reversePalette.computeIfAbsent(state, k -> {
                    int id = palette.size();
                    palette.put(id, k);
                    return id;
                });

                relPos[i * 2] = (byte) (pos.x() - minX);
                relPos[i * 2 + 1] = (byte) (pos.z() - minZ);
                indices[i] = (byte) paletteIdx;
                i++;
            }
            segments.add(new Segment(minX, y, minZ, relPos, indices));
        }
    }

    public void place(Platform platform, String worldName, int ox, int oy, int oz) {
        long startTime = System.nanoTime();
        Map<Long, Map<TreeModel.BlockPos, String>> chunks = new HashMap<>();
        
        for (Segment segment : segments) {
            for (int i = 0; i < segment.blockIndices.length; i++) {
                int worldX = ox + segment.offsetX + (segment.relativePositions[i * 2] & 0xFF);
                int worldY = oy + segment.offsetY;
                int worldZ = oz + segment.offsetZ + (segment.relativePositions[i * 2 + 1] & 0xFF);
                String state = palette.get(segment.blockIndices[i] & 0xFF);
                
                int cx = worldX >> 4;
                int cz = worldZ >> 4;
                long key = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
                
                chunks.computeIfAbsent(key, k -> new LinkedHashMap<>())
                      .put(new TreeModel.BlockPos(worldX, worldY, worldZ), state);
            }
        }
        long groupTime = System.nanoTime();

        int placed = 0;
        // Use a list and sort chunks by key once to ensure stable order if needed, 
        // but avoid TreeMap for the inner collections.
        List<Long> chunkKeys = new ArrayList<>(chunks.keySet());
        Collections.sort(chunkKeys);

        for (long key : chunkKeys) {
            int cx = (int) (key >> 32);
            int cz = (int) key;
            Map<TreeModel.BlockPos, String> chunkBlocks = chunks.get(key);
            
            if (!platform.setBlocks(worldName, cx, cz, chunkBlocks)) {
                platform.logger().warning("[LeafTreeGen] Ceasing placements for model " + speciesId + " due to previous error.");
                return;
            }
            placed += chunkBlocks.size();
        }
        long endTime = System.nanoTime();
        
        double totalMs = (endTime - startTime) / 1_000_000.0;
        double groupMs = (groupTime - startTime) / 1_000_000.0;
        if (totalMs > 10.0) {
            platform.logger().info(String.format("[LeafTreeGen Profiling] %s placed at %d,%d,%d: %.2fms total (Grouping: %.2fms, Blocks: %d)", 
                speciesId, ox, oy, oz, totalMs, groupMs, placed));
        }
    }

    public String getSpeciesId() {
        return speciesId;
    }

    public int getBlockCount() {
        int count = 0;
        for (Segment segment : segments) {
            count += segment.blockIndices.length;
        }
        return count;
    }
}
