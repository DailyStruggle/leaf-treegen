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

        // Simple segmentation: group by Y levels or small 3D regions
        // For trees, Y-levels (slices) are often good segments
        Map<Integer, Map<TreeModel.BlockPos, String>> ySlices = new TreeMap<>();
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
        int placed = 0;
        for (Segment segment : segments) {
            for (int i = 0; i < segment.blockIndices.length; i++) {
                int rx = segment.relativePositions[i * 2] & 0xFF;
                int rz = segment.relativePositions[i * 2 + 1] & 0xFF;
                String state = palette.get(segment.blockIndices[i] & 0xFF);
                platform.setBlock(worldName, ox + segment.offsetX + rx, oy + segment.offsetY, oz + segment.offsetZ + rz, state);
                placed++;
            }
        }
        platform.logger().info("[DEBUG_LOG] Model " + speciesId + " placed " + placed + " blocks at " + ox + "," + oy + "," + oz);
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
