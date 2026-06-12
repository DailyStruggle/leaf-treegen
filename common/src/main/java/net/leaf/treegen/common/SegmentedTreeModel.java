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

        // Decide the order blocks are written, then bucket them into per-chunk
        // LinkedHashMaps that preserve that order.
        //
        // Platforms that apply block physics compute a leaf's decay "distance" the
        // moment it is written (deferred by at most one tick), and a log or closer
        // leaf placed *afterwards* does not re-trigger that computation. With the
        // previous undefined HashMap iteration order, outer leaves of wide canopies
        // (e.g. large cherry trees) were frequently written before the wood/leaves
        // that connect them to the trunk, so they were stamped with distance 7 and
        // decayed on the next random tick even though the finished geometry keeps
        // every leaf within range. (DATAPACK placement is immune because vanilla
        // stamps the whole structure at once and only then recomputes distances.)
        //
        // We therefore emit: (1) all wood, then (2) leaves ordered by their decay
        // distance to the nearest wood (nearest first), then (3) anything else. This
        // guarantees that when a leaf is written, the wood plus every leaf closer to
        // the trunk than it already exist, so its computed distance is final and
        // correct. Runtime placement is throttled across ticks, so this ordering is
        // what makes the canopy survive across batches, not just within one update.
        List<TreeModel.BlockPos> positions = new ArrayList<>();
        Map<TreeModel.BlockPos, String> stateByPos = new HashMap<>();
        for (Segment segment : segments) {
            for (int i = 0; i < segment.blockIndices.length; i++) {
                int worldX = ox + segment.offsetX + (segment.relativePositions[i * 2] & 0xFF);
                int worldY = oy + segment.offsetY;
                int worldZ = oz + segment.offsetZ + (segment.relativePositions[i * 2 + 1] & 0xFF);
                String state = palette.get(segment.blockIndices[i] & 0xFF);
                TreeModel.BlockPos pos = new TreeModel.BlockPos(worldX, worldY, worldZ);
                positions.add(pos);
                stateByPos.put(pos, state);
            }
        }

        List<TreeModel.BlockPos> ordered = orderForPlacement(positions, stateByPos);

        for (TreeModel.BlockPos pos : ordered) {
            int cx = pos.x() >> 4;
            int cz = pos.z() >> 4;
            long key = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
            chunks.computeIfAbsent(key, k -> new LinkedHashMap<>())
                  .put(pos, stateByPos.get(pos));
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

    /** Orthogonal neighbour offsets used both by leaf-decay distance and this ordering. */
    private static final int[][] ORTHOGONAL = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    /**
     * Returns the order blocks should be written so that, when each leaf is placed,
     * the wood and every leaf closer to the trunk already exist: all wood first,
     * then leaves in ascending decay-distance from the nearest wood (a multi-source
     * BFS that mirrors vanilla's leaf-distance propagation), then everything else.
     */
    static List<TreeModel.BlockPos> orderForPlacement(
            List<TreeModel.BlockPos> positions, Map<TreeModel.BlockPos, String> stateByPos) {
        Set<TreeModel.BlockPos> leaves = new HashSet<>();
        Set<TreeModel.BlockPos> wood = new HashSet<>();
        for (TreeModel.BlockPos p : positions) {
            String s = stateByPos.get(p);
            if (isWood(s)) wood.add(p);
            else if (isLeaf(s)) leaves.add(p);
        }

        // Multi-source BFS from wood through leaves -> leaf -> distance to wood.
        Map<TreeModel.BlockPos, Integer> dist = new HashMap<>();
        ArrayDeque<TreeModel.BlockPos> queue = new ArrayDeque<>();
        for (TreeModel.BlockPos leaf : leaves) {
            for (int[] o : ORTHOGONAL) {
                if (wood.contains(new TreeModel.BlockPos(leaf.x() + o[0], leaf.y() + o[1], leaf.z() + o[2]))) {
                    dist.put(leaf, 1);
                    queue.add(leaf);
                    break;
                }
            }
        }
        while (!queue.isEmpty()) {
            TreeModel.BlockPos cur = queue.poll();
            int d = dist.get(cur);
            for (int[] o : ORTHOGONAL) {
                TreeModel.BlockPos n = new TreeModel.BlockPos(cur.x() + o[0], cur.y() + o[1], cur.z() + o[2]);
                if (leaves.contains(n) && !dist.containsKey(n)) {
                    dist.put(n, d + 1);
                    queue.add(n);
                }
            }
        }

        List<TreeModel.BlockPos> woodList = new ArrayList<>();
        List<TreeModel.BlockPos> leafList = new ArrayList<>();
        List<TreeModel.BlockPos> otherList = new ArrayList<>();
        for (TreeModel.BlockPos p : positions) {
            String s = stateByPos.get(p);
            if (isWood(s)) woodList.add(p);
            else if (isLeaf(s)) leafList.add(p);
            else otherList.add(p);
        }
        // Leaves with no path to wood (shouldn't happen post-connector) sort last.
        leafList.sort(Comparator.comparingInt(p -> dist.getOrDefault(p, Integer.MAX_VALUE)));

        List<TreeModel.BlockPos> ordered = new ArrayList<>(positions.size());
        ordered.addAll(woodList);
        ordered.addAll(leafList);
        ordered.addAll(otherList);
        return ordered;
    }

    /**
     * Whether a palette block-state is "wood" (a log/wood/stem) for placement
     * ordering. Matches {@code ProceduralGenerator.isWood} so the leaf-decay
     * skeleton (trunk + decay-prevention branches) is recognised and placed first.
     */
    private static boolean isWood(String state) {
        return state.contains("log") || state.contains("wood") || state.contains("stem");
    }

    private static boolean isLeaf(String state) {
        return state.contains("leaves");
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
