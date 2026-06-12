package net.leaf.treegen.common;

import java.util.*;

/**
 * Port of tools/tree-gen/ procedural logic to Java.
 */
public final class ProceduralGenerator {
    private static final int[] P = new int[512];
    static {
        int[] permutation = {151,160,137,91,90,15,
                131,13,201,95,96,53,194,233,7,225,140,36,103,30,69,142,8,99,37,240,21,10,23,
                190, 6,148,247,120,234,75,0,26,197,62,94,252,219,203,117,35,11,32,57,177,33,
                88,237,149,56,87,174,20,125,136,171,168, 68,175,74,165,71,134,139,48,27,166,
                77,146,158,231,83,111,229,122,60,211,133,230,220,105,92,41,55,46,245,40,244,
                102,143,54, 65,25,63,161, 1,216,80,73,209,76,132,187,208, 89,18,169,200,196,
                135,130,116,188,159,86,164,100,109,198,173,186, 3,64,52,217,226,250,124,123,
                5,202,38,147,118,126,255,82,85,212,207,206,59,227,47,16,58,17,182,189,28,42,
                223,183,170,213,119,248,152, 2,44,154,163, 70,221,153,101,155,167, 43,172,9,
                129,22,39,253, 19,98,108,110,79,113,224,232,178,185, 112,104,218,246,97,228,
                251,34,242,193,238,210,144,12,191,179,162,241, 81,51,145,235,249,14,239,107,
                49,192,214, 31,181,199,106,157,184, 84,204,176,115,121,50,45,127, 4,150,254,
                138,236,205,93,222,114,67,29,24,72,243,141,128,195,78,66,215,61,156,180
        };
        for (int i=0; i < 256 ; i++) P[256+i] = P[i] = permutation[i];
    }

    private static double fade(double t) { return t * t * t * (t * (t * 6 - 15) + 10); }
    private static double lerp(double t, double a, double b) { return a + t * (b - a); }
    private static double grad(int hash, double x, double y, double z) {
        int h = hash & 15;
        double u = h<8 ? x : y, v = h<4 ? y : h==12||h==14 ? x : z;
        return ((h&1) == 0 ? u : -u) + ((h&2) == 0 ? v : -v);
    }

    public static double perlin(double x, double y, double z) {
        int X = (int)Math.floor(x) & 255, Y = (int)Math.floor(y) & 255, Z = (int)Math.floor(z) & 255;
        x -= Math.floor(x); y -= Math.floor(y); z -= Math.floor(z);
        double u = fade(x), v = fade(y), w = fade(z);
        int A = P[X]+Y, AA = P[A]+Z, AB = P[A+1]+Z, B = P[X+1]+Y, BA = P[B]+Z, BB = P[B+1]+Z;
        return lerp(w, lerp(v, lerp(u, grad(P[AA  ], x  , y  , z   ), grad(P[BA  ], x-1, y  , z   )),
                lerp(u, grad(P[AB  ], x  , y-1, z   ), grad(P[BB  ], x-1, y-1, z   ))),
                lerp(v, lerp(u, grad(P[AA+1], x  , y  , z-1 ), grad(P[BA+1], x-1, y  , z-1 )),
                        lerp(u, grad(P[AB+1], x  , y-1, z-1 ), grad(P[BB+1], x-1, y-1, z-1 ))));
    }

    private final Platform platform;
    private final TreeRegistry registry;
    private final TreeCache cache;
    private Map<String, TreeSpecies> configSpecies = null;

    public ProceduralGenerator(Platform platform, TreeRegistry registry) {
        this.platform = platform;
        this.registry = registry;
        this.cache = new TreeCache(100, 10); // N=100 cached trees, remove one on M=10 reuses
    }

    public void setConfigSpecies(Map<String, TreeSpecies> configSpecies) {
        this.configSpecies = configSpecies;
    }

    private TreeSpecies speciesMapLookup(String id) {
        String key = id.toLowerCase(java.util.Locale.ROOT);
        if (configSpecies != null && configSpecies.containsKey(key)) {
            return configSpecies.get(key);
        }
        return registry.getSpeciesMap().get(key);
    }

    /**
     * Resolves a group child species, given the id of the enclosing group.
     * <p>
     * A placement group authored in config.yml (e.g. {@code placement.forest})
     * shadows the JSON species of the same name in {@link #configSpecies}. When
     * such a group lists a child with its own id (e.g. {@code trees: { forest: 100 }}),
     * the intent is to reference the underlying JSON species, not the config
     * group itself. Resolving it through {@link #speciesMapLookup} would return
     * the group again, producing an infinite "cyclic species reference" loop.
     * For self-references we therefore resolve directly against the registry's
     * JSON species, falling back to the normal lookup otherwise.
     */
    private TreeSpecies resolveChild(String childId, String groupId) {
        String childKey = childId.toLowerCase(java.util.Locale.ROOT);
        if (groupId != null && childKey.equals(groupId.toLowerCase(java.util.Locale.ROOT))) {
            TreeSpecies jsonSpecies = registry.getSpeciesMap().get(childKey);
            if (jsonSpecies != null) {
                return jsonSpecies;
            }
        }
        return speciesMapLookup(childId);
    }

    public void generate(String worldName, int x, int y, int z, TreeSpecies species, Random random) {
        // Rather than computing a fresh tree for every placement, restrict the runtime
        // to a small pool of pre-computed variants (one per the species' configured
        // {@code count}). Each placement randomly picks a variant; the deterministic
        // per-variant seed lets the TreeCache hand back (and reuse) an already-built
        // model, saving the CPU cost of rebuilding identical geometry every time.
        int variantCount = effectiveVariantCount(species);
        int variant = random.nextInt(variantCount);
        long seed = variantSeed(species.id(), variant);
        // platform.logger().info("[DEBUG_LOG] Generating tree for species: " + species.id() + " at " + x + "," + y + "," + z + " (variant " + variant + "/" + variantCount + ", seed: " + seed + ")");
        SegmentedTreeModel model = cache.getOrGenerate(species.id(), seed, s -> {
            // platform.logger().info("[DEBUG_LOG] Cache miss for " + species.id() + ":" + s + " - Building new model...");
            TreeModel built = buildModel(species, new Random(s));
            if (built != null) {
                built.rotate(new Random(s).nextInt(4));
            }
            return built;
        });
        if (model != null) {
            // platform.logger().info("[DEBUG_LOG] Placement successful for model: " + model.getSpeciesId() + " (" + model.getBlockCount() + " blocks)");
            model.place(platform, worldName, x, y, z);
        } else {
            platform.logger().warning("Placement failed: buildModel returned null for species " + species.id());
        }
    }

    /**
     * Number of distinct pre-computed variants kept in memory for a species at
     * runtime. Derived from the configured {@code count} (the same value the
     * datapack path bakes into N structure templates): a single procedural model
     * contributes its own {@code count}, a multi-definition container sums the
     * {@code count} of every definition, and a group contributes one slot per
     * referenced child. Always at least one.
     */
    private int effectiveVariantCount(TreeSpecies species) {
        int count = 0;
        if (species.procedural() != null) {
            count = species.procedural().count();
        } else if (species.treeDefinitions() != null && !species.treeDefinitions().isEmpty()) {
            for (TreeSpecies.ProceduralParams p : species.treeDefinitions().values()) {
                count += Math.max(1, p.count());
            }
        } else if (species.trees() != null && !species.trees().isEmpty()) {
            count = species.trees().size();
        }
        return Math.max(1, count);
    }

    /** Deterministic seed for the given species variant, so the TreeCache can reuse models. */
    private static long variantSeed(String speciesId, int variant) {
        return ((long) speciesId.hashCode() & 0xFFFFFFFFL) * 1_000_003L + variant;
    }

    public TreeModel buildModel(TreeSpecies species, Random random) {
        return buildModel(species, random, new HashSet<>());
    }

    private TreeModel buildModel(TreeSpecies species, Random random, Set<String> visited) {
        // platform.logger().info("[DEBUG_LOG] Selecting tree definition for species: " + species.id());
        if (species.trees() != null && !species.trees().isEmpty()) {
            if (!visited.add(species.id().toLowerCase(java.util.Locale.ROOT))) {
                // A group references itself (directly or indirectly). Before aborting,
                // fall back to the registry's JSON species of the same id when that
                // species is a buildable, non-group definition. This mirrors
                // resolveChild() for cases the id match there cannot catch (e.g. a
                // group resolved via the normal lookup back to the config group).
                TreeSpecies jsonSpecies = registry.getSpeciesMap().get(species.id().toLowerCase(java.util.Locale.ROOT));
                if (jsonSpecies != null && jsonSpecies != species
                        && (jsonSpecies.trees() == null || jsonSpecies.trees().isEmpty())) {
                    return buildModel(jsonSpecies, random, visited);
                }
                platform.logger().warning("Group selection aborted: cyclic species reference detected at '" + species.id() + "' (visited: " + visited + ")");
                return null;
            }
            // Select from group
            int totalWeight = species.trees().stream().mapToInt(TreeSpecies.WeightedSpecies::weight).sum();
            int r = random.nextInt(totalWeight);
            int current = 0;
            for (TreeSpecies.WeightedSpecies ws : species.trees()) {
                current += ws.weight();
                if (r < current) {
                    String[] parts = ws.id().split(":", 2);
                    TreeSpecies child = resolveChild(parts[0], species.id());
                    if (child != null) {
                        if (parts.length > 1) {
                            // Named tree within species
                            TreeSpecies.ProceduralParams named = child.treeDefinitions() != null ? child.treeDefinitions().get(parts[1]) : null;
                            if (named != null) {
                                // platform.logger().info("[DEBUG_LOG] Group selection: " + species.id() + " -> " + child.id() + ":" + parts[1]);
                                return buildProceduralModel(child.id() + ":" + parts[1], named, random);
                            } else {
                                platform.logger().warning("Group selection failed: definition '" + parts[1] + "' not found in child species '" + child.id() + "'");
                            }
                        }
                        // platform.logger().info("[DEBUG_LOG] Group selection: " + species.id() + " -> " + child.id());
                        return buildModel(child, random, visited);
                    } else {
                        platform.logger().warning("Group selection failed: child species '" + parts[0] + "' not found");
                    }
                    return null;
                }
            }
            return null;
        }

        TreeSpecies.ProceduralParams params = species.procedural();
        if (params == null) {
            List<String> variants = species.variantLocations();
            if (!variants.isEmpty()) {
                // Not a procedural tree, can't build a procedural model from a structure easily
                // platform.logger().info("[DEBUG_LOG] Skipping buildModel for non-procedural (structure) species: " + species.id());
                return null; 
            } else if (species.treeDefinitions() != null && !species.treeDefinitions().isEmpty()) {
                // Select a random named tree if no root procedural exists
                Map<String, TreeSpecies.ProceduralParams> defs = species.treeDefinitions();
                List<String> names = new ArrayList<>(defs.keySet());
                
                int totalWeight = 0;
                for (TreeSpecies.ProceduralParams p : defs.values()) {
                    totalWeight += Math.max(1, p.weight());
                }
                
                int target = random.nextInt(totalWeight);
                int current = 0;
                String selected = names.get(0);
                for (String name : names) {
                    TreeSpecies.ProceduralParams p = defs.get(name);
                    current += Math.max(1, p.weight());
                    if (target < current) {
                        selected = name;
                        break;
                    }
                }
                
                TreeSpecies.ProceduralParams named = defs.get(selected);
                return buildProceduralModel(species.id() + ":" + selected, named, random);
            }
            return null;
        }

        return buildProceduralModel(species.id(), params, random);
    }

    public TreeModel buildProceduralModel(String speciesId, TreeSpecies.ProceduralParams params, Random random) {
        if (params == null) {
            platform.logger().warning("buildProceduralModel called with null params for " + speciesId);
            return null;
        }
        int height = params.minHeight() + (params.maxHeight() > params.minHeight() ? random.nextInt(params.maxHeight() - params.minHeight() + 1) : 0);
        List<double[]> offsets = new ArrayList<>();

        // 1. Generate Trunk
        double prevCx = 0, prevCz = 0;
        
        Map<TreeModel.BlockPos, String> trunkBlocks = new HashMap<>();
        List<double[]> branchEndpoints = new ArrayList<>();

        // Per-object irregular phases
        double[] phases = {
                random.nextDouble() * Math.PI * 2,
                random.nextDouble() * Math.PI * 2,
                random.nextDouble() * Math.PI * 2
        };

        for (int i = 0; i < height; i++) {
            double[] offset = calculateLeanOffset(i, height, params.leanAzimuth(), params.leanAngle(), params.curveFn(), params.curveParams(), params.azimuthFn(), params.azimuthParams());
            offsets.add(offset);

            double cx = offset[0];
            double cz = offset[1];
            double w = calculateWidth(i, height, params.trunkWidth(), params.trunkShape(), params.trunkShapeParams());
            
            String block = params.trunkBlock();
            if (params.secondaryTrunk() != null) {
                double t = (double) i / Math.max(height - 1, 1);
                if (t >= params.secondaryTrunkStart() && t <= params.secondaryTrunkEnd()) {
                    block = params.secondaryTrunk();
                }
            }
            
            // Add axis if orientable
            String axis = calculateAxis(cx - prevCx, 1.0, cz - prevCz);
            String orientedBlock = applyAxis(block, axis);

            placeTrunkSlice(trunkBlocks, 0, i, 0, cx, cz, w, orientedBlock, params.roundTrunk(), random, phases, false);
            
            // Fill gaps. Mirrors trunk.py: intermediate slices are placed at the
            // rounded fractional height between the two layers (so a leaning/spiral
            // trunk bridges diagonally instead of smearing flat) and they only fill
            // empty cells, never overwriting already-placed trunk blocks.
            if (i > 0) {
                double dx = cx - prevCx;
                double dz = cz - prevCz;
                double dist = Math.sqrt(dx * dx + dz * dz);
                if (dist > 1.0) {
                    int steps = (int) Math.ceil(dist);
                    for (int s = 1; s < steps; s++) {
                        double f = (double) s / steps;
                        double icx = prevCx + dx * f;
                        double icz = prevCz + dz * f;
                        int fillY = (int) Math.round((i - 1) + f);
                        placeTrunkSlice(trunkBlocks, 0, fillY, 0, icx, icz, w, orientedBlock, params.roundTrunk(), random, phases, true);
                    }
                }
            }
            prevCx = cx;
            prevCz = cz;
        }

        // Apply wood variants for exposed trunk ends
        applyTrunkWoodVariants(trunkBlocks, params.trunkBlock(), params.secondaryTrunk());

            // 2. Generate Canopy
        Map<TreeModel.BlockPos, String> canopyBlocks = new HashMap<>();
        TreeSpecies.CanopyParams canopy = params.canopy();
        if (canopy != null) {
            String profile = params.profile() != null ? params.profile().toUpperCase() : "OAK";
            Random canopyRandom = new Random(random.nextLong());

            List<TreeSpecies.CanopyParams.Layer> layers = canopy.layers();
            if (layers == null || layers.isEmpty()) {
                // Mirror to_procedural.py / canopy.py: a tree is "branch-driven" whenever
                // a branches block is present (independent of any explicit count). Branch
                // driven trees anchor the volume crown at the trunk tip (crown_extra=0) so
                // the canopy stays attached; using count>0 here left configs that omit
                // "count" (e.g. the dark-oak titans) with a crown floating height/4 blocks
                // above the trunk.
                layers = generatePresetLayers(profile, height, canopy.branches() != null);
            }

            // Volume layers
            if (canopy.volumeLayers()) {
                double squish = 1.0; // Defaults
                double startAngle = 90.0;
                
                List<TreeSpecies.CanopyParams.Layer> volumeLayers = layers;
                if (canopy.branches() != null && layers != null && !layers.isEmpty()) {
                    Double crownFrac = canopy.crownVolumeFraction();
                    if (crownFrac != null) {
                        int n = Math.max(1, (int) Math.round(layers.size() * crownFrac));
                        volumeLayers = layers.subList(layers.size() - n, layers.size());
                    } else {
                        volumeLayers = List.of(layers.get(layers.size() - 1));
                    }
                }

                if (volumeLayers != null) {
                    for (TreeSpecies.CanopyParams.Layer layer : volumeLayers) {
                        int cy = layer.yOffset();
                        double radius = layer.radius();
                        
                        int halfH = (startAngle >= 180.0) ? 0 : (int) Math.ceil(radius * squish);
                        int downH = (startAngle < 90.0) ? (int) Math.ceil(radius * Math.cos(Math.toRadians(startAngle)) * squish) : 0;
                        
                        for (int y = cy - downH; y <= cy + halfH; y++) {
                            int dy = y - cy;
                            double layerR;
                            if (halfH > 0 && dy >= 0) {
                                layerR = radius * Math.sqrt(Math.max(0.0, 1.0 - Math.pow((double) dy / halfH, 2)));
                            } else if (downH > 0 && dy < 0) {
                                layerR = radius * Math.sqrt(Math.max(0.0, 1.0 - Math.pow((double) dy / downH, 2)));
                            } else {
                                layerR = radius;
                            }
                            
                            if (layerR < 0.5) continue;
                            
                            // Use trunk offset at THIS height
                            double[] layerOffset = (y >= 0 && y < offsets.size()) ? offsets.get(y) : offsets.get(offsets.size() - 1);
                            placeLeafDisc(canopyBlocks, layerOffset[0], y, layerOffset[1], layerR, params.leafBlock(), canopy.mode(), canopy.density(), canopyRandom, canopy.secondaryLeaves(), canopy.secondaryFraction(), trunkBlocks, phases, canopy.leafVerticalScale());
                        }
                    }
                }
            }
            
            // Branches
            if (canopy.branches() != null) {
                generateBranches(canopyBlocks, height, offsets, params.trunkBlock(), params.leafBlock(), canopy, canopyRandom, trunkBlocks, branchEndpoints);
            }

            // Cap trunk tip
            if (params.capTrunk()) {
                capTrunkTip(canopyBlocks, trunkBlocks, params.leafBlock(), canopy.mode(), canopy.density(), canopyRandom, canopy.secondaryLeaves(), canopy.secondaryFraction(), phases, canopy.leafVerticalScale());
            }

            // Underside glow: replace the lowest leaf block in each (x,z) column of the
            // canopy with the configured underside block (e.g. shroomlight), producing a
            // single glow layer across the bottom of the cap rather than scattered blocks.
            if (canopy.undersideLeaves() != null) {
                applyUndersideLeaves(canopyBlocks, params.leafBlock(), canopy.secondaryLeaves(),
                        canopy.undersideLeaves(), canopy.undersideChance(), canopyRandom);
            }
        }

        // Accent decorators (vines, cobwebs, fungus, base scatter, etc.). Mirrors the
        // offline Python decorators.py so the live/sapling generator renders the same
        // accents the NBT export tool would. canopy_bottom is intentionally handled by
        // the undersideLeaves path above and excluded from this list at parse time.
        if (params.decorators() != null && !params.decorators().isEmpty()) {
            applyDecorators(params.decorators(), trunkBlocks, canopyBlocks, branchEndpoints, new Random(random.nextLong()));
        }

        Map<TreeModel.BlockPos, String> finalBlocks = new HashMap<>(trunkBlocks);
        canopyBlocks.forEach((pos, b) -> {
            if (!finalBlocks.containsKey(pos)) {
                finalBlocks.put(pos, b);
            }
        });

        connectLeavesWithBranches(finalBlocks, params.trunkBlock());

        return new TreeModel(speciesId, finalBlocks, false);
    }

    /** Vanilla destroys any leaf whose distance to the nearest log exceeds this. */
    private static final int MAX_LEAF_DISTANCE = 6;

    /** The six orthogonal neighbour offsets vanilla leaf-decay propagates through. */
    private static final int[][] ORTHOGONAL = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0}, {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    /**
     * Ensures every leaf stays within Minecraft's leaf-decay radius by extending
     * wood "branches" out to any leaf that would otherwise be too far from a log.
     *
     * <p>Vanilla recomputes each leaf's distance to the nearest log (propagating
     * through up to six orthogonal leaf-to-leaf steps) and destroys leaves whose
     * distance exceeds {@value #MAX_LEAF_DISTANCE}. That prunes the wide canopies
     * our procedural and datapack-baked trees produce. Rather than tracking grown
     * trees in a side store (which datapack-placed trees never populate), we make
     * the geometry self-sufficient: a thin branch of wood is rasterised out to
     * each orphaned leaf so the standing tree keeps its full canopy, yet the
     * leaves decay normally once the player removes the wood.
     */
    private void connectLeavesWithBranches(Map<TreeModel.BlockPos, String> blocks, String trunkBlock) {
        String branchWood = (trunkBlock == null || trunkBlock.isBlank())
                ? "minecraft:oak_log"
                : trunkBlock.split("\\[")[0];

        // Each pass connects one orphan and (usually) many of its neighbours, so the
        // guard only needs to exceed the worst-case number of disconnected clusters.
        for (int guard = 0; guard < 4096; guard++) {
            Set<TreeModel.BlockPos> wood = new HashSet<>();
            List<TreeModel.BlockPos> leaves = new ArrayList<>();
            for (Map.Entry<TreeModel.BlockPos, String> e : blocks.entrySet()) {
                String v = e.getValue();
                if (isWood(v)) {
                    wood.add(e.getKey());
                } else if (isLeaf(v)) {
                    leaves.add(e.getKey());
                }
            }
            if (wood.isEmpty() || leaves.isEmpty()) return;

            Set<TreeModel.BlockPos> leafSet = new HashSet<>(leaves);

            // Multi-source BFS of leaf distances, seeded by leaves orthogonally
            // adjacent to any log (distance 1) and stopping once propagation would
            // exceed the decay radius, mirroring vanilla's distance calculation.
            Map<TreeModel.BlockPos, Integer> dist = new HashMap<>();
            ArrayDeque<TreeModel.BlockPos> queue = new ArrayDeque<>();
            for (TreeModel.BlockPos leaf : leaves) {
                if (adjacentToWood(leaf, wood)) {
                    dist.put(leaf, 1);
                    queue.add(leaf);
                }
            }
            while (!queue.isEmpty()) {
                TreeModel.BlockPos cur = queue.poll();
                int d = dist.get(cur);
                if (d >= MAX_LEAF_DISTANCE) continue;
                for (int[] o : ORTHOGONAL) {
                    TreeModel.BlockPos n = new TreeModel.BlockPos(cur.x() + o[0], cur.y() + o[1], cur.z() + o[2]);
                    if (leafSet.contains(n) && !dist.containsKey(n)) {
                        dist.put(n, d + 1);
                        queue.add(n);
                    }
                }
            }

            // The orphan farthest from the trunk is connected first so its branch can
            // also rescue intermediate leaves it threads through.
            TreeModel.BlockPos orphan = null;
            long bestSq = -1;
            for (TreeModel.BlockPos leaf : leaves) {
                if (dist.containsKey(leaf)) continue;
                TreeModel.BlockPos nearest = nearestWood(leaf, wood);
                if (nearest == null) continue;
                long sq = squaredDistance(leaf, nearest);
                if (sq > bestSq) {
                    bestSq = sq;
                    orphan = leaf;
                }
            }
            if (orphan == null) return; // every leaf is within range

            rasterizeWoodLine(blocks, nearestWood(orphan, wood), orphan, branchWood);
        }
    }

    private static boolean isWood(String v) {
        return v.contains("log") || v.contains("wood") || v.contains("stem");
    }

    private static boolean isLeaf(String v) {
        return v.contains("leaves");
    }

    private static boolean adjacentToWood(TreeModel.BlockPos leaf, Set<TreeModel.BlockPos> wood) {
        for (int[] o : ORTHOGONAL) {
            if (wood.contains(new TreeModel.BlockPos(leaf.x() + o[0], leaf.y() + o[1], leaf.z() + o[2]))) {
                return true;
            }
        }
        return false;
    }

    private static TreeModel.BlockPos nearestWood(TreeModel.BlockPos leaf, Set<TreeModel.BlockPos> wood) {
        TreeModel.BlockPos best = null;
        long bestSq = Long.MAX_VALUE;
        for (TreeModel.BlockPos w : wood) {
            long sq = squaredDistance(leaf, w);
            if (sq < bestSq) {
                bestSq = sq;
                best = w;
            }
        }
        return best;
    }

    private static long squaredDistance(TreeModel.BlockPos a, TreeModel.BlockPos b) {
        long dx = a.x() - b.x();
        long dy = a.y() - b.y();
        long dz = a.z() - b.z();
        return dx * dx + dy * dy + dz * dz;
    }

    /**
     * Walks orthogonally (one axis per step, largest remaining delta first) from a
     * log towards an orphan leaf, turning the air/leaf cells it crosses into wood.
     * The orphan cell itself is left as a leaf; because the final step is
     * orthogonal, the wood now sits directly beside it, dropping its decay
     * distance back to 1.
     */
    private void rasterizeWoodLine(Map<TreeModel.BlockPos, String> blocks,
                                   TreeModel.BlockPos from, TreeModel.BlockPos to, String branchWood) {
        if (from == null || to == null) return;
        int x = from.x(), y = from.y(), z = from.z();
        while (x != to.x() || y != to.y() || z != to.z()) {
            int dx = to.x() - x, dy = to.y() - y, dz = to.z() - z;
            int adx = Math.abs(dx), ady = Math.abs(dy), adz = Math.abs(dz);
            if (adx >= ady && adx >= adz) {
                x += Integer.signum(dx);
            } else if (ady >= adz) {
                y += Integer.signum(dy);
            } else {
                z += Integer.signum(dz);
            }
            TreeModel.BlockPos pos = new TreeModel.BlockPos(x, y, z);
            if (pos.equals(to)) break;
            String cur = blocks.get(pos);
            if (cur == null || isLeaf(cur)) {
                blocks.put(pos, branchWood);
            }
        }
    }

    private record BlockPos(int x, int y, int z) {}

    private void applyTrunkWoodVariants(Map<TreeModel.BlockPos, String> blocks, String trunkBlock, String secondaryTrunk) {
        String wood = logToWood(trunkBlock);
        String sWood = secondaryTrunk != null ? logToWood(secondaryTrunk) : null;
        
        String trunkBase = trunkBlock.split("\\[")[0];
        String sTrunkBase = secondaryTrunk != null ? secondaryTrunk.split("\\[")[0] : null;

        Map<TreeModel.BlockPos, String> updates = new HashMap<>();
        blocks.forEach((pos, block) -> {
            String base = block.split("\\[")[0];
            if (base.equals(trunkBase)) {
                if (!blocks.containsKey(new TreeModel.BlockPos(pos.x(), pos.y() + 1, pos.z())) || !blocks.containsKey(new TreeModel.BlockPos(pos.x(), pos.y() - 1, pos.z()))) {
                    updates.put(pos, wood);
                }
            } else if (sTrunkBase != null && base.equals(sTrunkBase)) {
                if (!blocks.containsKey(new TreeModel.BlockPos(pos.x(), pos.y() + 1, pos.z())) || !blocks.containsKey(new TreeModel.BlockPos(pos.x(), pos.y() - 1, pos.z()))) {
                    updates.put(pos, sWood);
                }
            }
        });
        blocks.putAll(updates);
    }

    private String logToWood(String log) {
        String base = log.split("\\[")[0];
        String wood = switch (base) {
            case "minecraft:oak_log" -> "minecraft:oak_wood";
            case "minecraft:birch_log" -> "minecraft:birch_wood";
            case "minecraft:spruce_log" -> "minecraft:spruce_wood";
            case "minecraft:jungle_log" -> "minecraft:jungle_wood";
            case "minecraft:acacia_log" -> "minecraft:acacia_wood";
            case "minecraft:dark_oak_log" -> "minecraft:dark_oak_wood";
            case "minecraft:cherry_log" -> "minecraft:cherry_wood";
            case "minecraft:mangrove_log" -> "minecraft:mangrove_wood";
            default -> base;
        };
        return wood;
    }

    private void capTrunkTip(Map<TreeModel.BlockPos, String> canopy, Map<TreeModel.BlockPos, String> trunk, String leafBlock, String mode, double density, Random random, String secondaryLeaves, double secondaryFraction, double[] phases, double verticalScale) {
        Map<TreeModel.BlockPos, String> combined = new HashMap<>(trunk);
        combined.putAll(canopy);

        List<TreeModel.BlockPos> logs = combined.entrySet().stream()
                .filter(e -> e.getValue().contains("log") || e.getValue().contains("wood") || e.getValue().contains("stem"))
                .map(Map.Entry::getKey)
                .toList();
        
        if (logs.isEmpty()) return;

        int topY = logs.stream().mapToInt(TreeModel.BlockPos::y).max().orElse(0);
        List<TreeModel.BlockPos> exposed = logs.stream()
                .filter(p -> p.y() >= topY - 1 && !combined.containsKey(new TreeModel.BlockPos(p.x(), p.y() + 1, p.z())))
                .toList();
        
        if (exposed.isEmpty()) return;

        // Enclose the apex by filling the cell directly above EVERY exposed top log.
        // The previous approach placed a single shared leaf disc centred on the
        // exposed-log centroid; when the exposed set was spread out (a leaning/spiral
        // trunk tip plus nearby branch logs) the trunk-tip column landed on the
        // disc's sparse density edge and was skipped, leaving a one-block air gap
        // between the trunk and its canopy. Capping each log individually guarantees
        // no log top is left bare and no gap appears above the trunk apex.
        for (TreeModel.BlockPos p : exposed) {
            TreeModel.BlockPos above = new TreeModel.BlockPos(p.x(), p.y() + 1, p.z());
            if (!combined.containsKey(above)) {
                String leaf = (secondaryLeaves != null && random.nextDouble() < secondaryFraction) ? secondaryLeaves : leafBlock;
                canopy.put(above, leaf);
                combined.put(above, leaf);
            }
        }
    }

    private void generateBranches(Map<TreeModel.BlockPos, String> canopyBlocks, int height, List<double[]> offsets, String trunkBlock, String leafBlock, TreeSpecies.CanopyParams canopy, Random random, Map<TreeModel.BlockPos, String> trunkBlocks, List<double[]> branchEndpoints) {
        TreeSpecies.BranchParams b = canopy.branches();
        int startY = (int) (height * b.startHeight());

        for (int y = startY; y < height; y++) {
            double t = (double) y / Math.max(height - 1, 1);
            double p = calculateBranchProbability(t, b.probFn(), b.probParams());

            if (random.nextDouble() > p) {
                // platform.logger().info("[DEBUG_LOG] Skipping branch at y=" + y + " t=" + t + " p=" + p);
                continue;
            }

            double length = calculateBranchLength(t, b.lengthFn(), b.lengthParams(), b.minLength(), b.maxLength());
            double azDeg = random.nextDouble() * 360.0;
            double elDeg = b.minElevation() + random.nextDouble() * (b.maxElevation() - b.minElevation());
            double az = Math.toRadians(azDeg);
            double el = Math.toRadians(elDeg);

            double[] offset = (y < offsets.size()) ? offsets.get(y) : offsets.get(offsets.size() - 1);
            double ox = offset[0];
            double oy = y;
            double oz = offset[1];

            double ex = ox + length * Math.cos(el) * Math.sin(az);
            double ey = oy + length * Math.sin(el);
            double ez = oz + length * Math.cos(el) * Math.cos(az);

            rasterizeBranch(canopyBlocks, ox, oy, oz, ex, ey, ez, trunkBlock, trunkBlocks);
            placeLeafCluster(canopyBlocks, ex, ey, ez, b.clusterRadius(), leafBlock, b.clusterMode(), b.clusterDensity(), random, canopy.secondaryLeaves(), canopy.secondaryFraction(), trunkBlocks, canopy.leafVerticalScale());
            if (branchEndpoints != null) branchEndpoints.add(new double[]{ex, ey, ez, ox, oz});

            // Sub-branches: split each primary branch tip into several shorter offshoots,
            // each carrying its own leaf cluster. This mirrors canopy.py's
            // generate_branch_canopy sub-branch pass and is what gives the crown its
            // width/density (previously the runtime ignored sub_branches entirely).
            TreeSpecies.BranchParams.SubBranchParams sub = b.subBranches();
            if (sub != null && sub.count() > 0) {
                for (int si = 0; si < sub.count(); si++) {
                    double yawOffset = sub.yawDelta() * (si - (sub.count() - 1) / 2.0);
                    double subAz = Math.toRadians(azDeg + yawOffset);
                    double subEl = Math.toRadians(elDeg + sub.pitchDelta());
                    double subLen = length * sub.lengthScale();

                    double sx = ex + subLen * Math.cos(subEl) * Math.sin(subAz);
                    double sy = ey + subLen * Math.sin(subEl);
                    double sz = ez + subLen * Math.cos(subEl) * Math.cos(subAz);

                    rasterizeBranch(canopyBlocks, ex, ey, ez, sx, sy, sz, trunkBlock, trunkBlocks);
                    placeLeafCluster(canopyBlocks, sx, sy, sz, sub.clusterRadius(), leafBlock, sub.clusterMode(), sub.clusterDensity(), random, canopy.secondaryLeaves(), canopy.secondaryFraction(), trunkBlocks, canopy.leafVerticalScale());
                    if (branchEndpoints != null) branchEndpoints.add(new double[]{sx, sy, sz, ex, ez});
                }
            }
        }
    }

    private double calculateBranchProbability(double t, String fn, Map<String, Double> params) {
        if (fn == null) return t * t; // Default to TOP_HEAVY (exponent=2)
        return switch (fn.toUpperCase()) {
            case "CONSTANT" -> params != null ? params.getOrDefault("p", 0.5) : 0.5;
            case "LINEAR" -> {
                double baseP = params != null ? params.getOrDefault("base_p", 0.0) : 0.0;
                double crownP = params != null ? params.getOrDefault("crown_p", 1.0) : 1.0;
                yield baseP + (crownP - baseP) * t;
            }
            case "SIGMOID" -> {
                double steepness = params != null ? params.getOrDefault("steepness", 10.0) : 10.0;
                double midpoint = params != null ? params.getOrDefault("midpoint", 0.7) : 0.7;
                yield 1.0 / (1.0 + Math.exp(-steepness * (t - midpoint)));
            }
            case "GAUSSIAN" -> {
                double mean = params != null ? params.getOrDefault("mean", 0.7) : 0.7;
                double std = params != null ? params.getOrDefault("std", 0.15) : 0.15;
                yield Math.exp(-0.5 * Math.pow((t - mean) / Math.max(std, 1e-6), 2));
            }
            default -> Math.pow(t, params != null ? params.getOrDefault("exponent", 2.0) : 2.0);
        };
    }

    private double calculateBranchLength(double t, String fn, Map<String, Double> params, double minL, double maxL) {
        if (fn == null) return minL + (maxL - minL) * t;
        double len = switch (fn.toUpperCase()) {
            case "CONSTANT" -> params != null ? params.getOrDefault("length", 3.0) : 3.0;
            case "SIGMOID" -> {
                double maxLen = params != null ? params.getOrDefault("max_len", 4.0) : 4.0;
                double steepness = params != null ? params.getOrDefault("steepness", 5.0) : 5.0;
                yield maxLen / (1.0 + Math.exp(-steepness * (t - 0.5)));
            }
            case "LOG" -> {
                double maxLen = params != null ? params.getOrDefault("max_len", 4.0) : 4.0;
                yield maxLen * Math.log(1.0 + t * (Math.E - 1.0));
            }
            case "PARABOLIC" -> {
                double maxLen = params != null ? params.getOrDefault("max_len", 4.0) : 4.0;
                yield maxLen * (1.0 - Math.pow(2.0 * t - 1.0, 2));
            }
            default -> {
                double base = params != null ? params.getOrDefault("base", 1.0) : 1.0;
                double crown = params != null ? params.getOrDefault("crown", 4.0) : 4.0;
                yield base + (crown - base) * t;
            }
        };
        return Math.max(1.0, len);
    }

    private void rasterizeBranch(Map<TreeModel.BlockPos, String> blocks, double x0, double y0, double z0, double x1, double y1, double z1, String block, Map<TreeModel.BlockPos, String> existing) {
        double dx = x1 - x0;
        double dy = y1 - y0;
        double dz = z1 - z0;

        int steps = (int) Math.max(Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0)), Math.max(Math.abs(z1 - z0), 1));

        String axis = calculateAxis(dx, dy, dz);
        String orientedBlock = applyAxis(block, axis);

        // Emit a fully 6-connected (face-adjacent) path. The plain DDA above can step
        // diagonally (changing two or three coordinates between samples), which leaves
        // branch logs only edge/corner touching - some tree-feller plugins only follow
        // face neighbours and treat those branches as disconnected. Bridging each pair
        // of consecutive cells one axis at a time guarantees every wood block is
        // face-adjacent to the next, so the whole branch (and trunk) stays connected.
        int lx = (int) Math.round(x0);
        int ly = (int) Math.round(y0);
        int lz = (int) Math.round(z0);
        placeBranchBlock(blocks, existing, lx, ly, lz, orientedBlock);

        for (int i = 1; i <= steps; i++) {
            double f = (double) i / steps;
            int nx = (int) Math.round(x0 + dx * f);
            int ny = (int) Math.round(y0 + dy * f);
            int nz = (int) Math.round(z0 + dz * f);
            // Walk from the previous cell to this one one axis at a time.
            while (lx != nx || ly != ny || lz != nz) {
                if (lx != nx) lx += Integer.signum(nx - lx);
                else if (ly != ny) ly += Integer.signum(ny - ly);
                else if (lz != nz) lz += Integer.signum(nz - lz);
                placeBranchBlock(blocks, existing, lx, ly, lz, orientedBlock);
            }
        }
    }

    private void placeBranchBlock(Map<TreeModel.BlockPos, String> blocks, Map<TreeModel.BlockPos, String> existing, int x, int y, int z, String block) {
        TreeModel.BlockPos p = new TreeModel.BlockPos(x, y, z);
        if (!existing.containsKey(p)) {
            blocks.put(p, block);
        }
    }

    private String calculateAxis(double dx, double dy, double dz) {
        double ax = Math.abs(dx);
        double ay = Math.abs(dy) * 1.8;
        double az = Math.abs(dz);
        if (ay >= ax && ay >= az) return "y";
        if (ax >= az) return "x";
        return "z";
    }

    private String applyAxis(String block, String axis) {
        if (block.contains("[")) return block;
        String low = block.toLowerCase();
        // mushroom_stem has no "axis" property (unlike crimson_stem / warped_stem),
        // so appending [axis=...] produces an unparseable block state.
        if (low.contains("mushroom_stem")) {
            return block;
        }
        if (low.contains("log") || low.contains("wood") || low.contains("stem") || low.contains("hyphae") || low.contains("pillar")) {
            return block + "[axis=" + axis + "]";
        }
        return block;
    }

    private List<TreeSpecies.CanopyParams.Layer> generatePresetLayers(String profile, int height, boolean branchDriven) {
        double scale = switch (profile) {
            case "BIRCH" -> 0.7;
            case "SPRUCE", "PINE", "TAIGA" -> 0.55;
            case "JUNGLE", "MEGA_JUNGLE" -> 0.45;
            case "ACACIA" -> 0.6;
            case "DARK_OAK_FLAT" -> 1.25;
            default -> 1.0;
        };
        
        int baseRadius = Math.max(3, (int) Math.round(height / 2.0 * scale));
        
        double[][] fractions = switch (profile) {
            case "BIRCH" -> new double[][]{{0.6, 0.45}, {0.7, 0.75}, {0.8, 0.85}, {0.88, 0.75}, {0.94, 0.45}, {0.99, 0.2}};
            case "SPRUCE", "PINE", "TAIGA" -> new double[][]{{0.3, 1.0}, {0.42, 0.85}, {0.54, 0.7}, {0.65, 0.55}, {0.75, 0.4}, {0.84, 0.25}, {0.91, 0.15}, {0.97, 0.05}};
            case "JUNGLE", "MEGA_JUNGLE" -> new double[][]{{0.82, 0.4}, {0.88, 0.8}, {0.93, 1.0}, {0.97, 0.7}, {1.0, 0.3}};
            case "ACACIA" -> new double[][]{{0.9, 0.5}, {0.95, 0.8}, {0.99, 0.4}};
            case "DARK_OAK" -> new double[][]{{0.5, 0.7}, {0.62, 1.0}, {0.72, 1.0}, {0.82, 0.9}, {0.91, 0.6}, {0.97, 0.3}};
            case "DARK_OAK_FLAT" -> new double[][]{{0.74, 0.62}, {0.83, 1.0}, {0.92, 1.0}, {1.0, 0.6}};
            case "CHERRY" -> new double[][]{{0.5, 0.5}, {0.62, 0.9}, {0.74, 1.0}, {0.84, 1.0}, {0.92, 0.7}, {0.98, 0.4}};
            default -> new double[][]{{0.55, 0.6}, {0.65, 0.9}, {0.75, 1.0}, {0.85, 0.9}, {0.92, 0.65}, {0.98, 0.35}};
        };

        int crownExtra = branchDriven ? 0 : Math.max(2, height / 4);
        int yTop = (height - 1) + crownExtra;

        List<TreeSpecies.CanopyParams.Layer> layers = new ArrayList<>();
        for (double[] f : fractions) {
            int yOff = (int) Math.round(f[0] * yTop);
            double radius = Math.max(1.5, baseRadius * f[1]);
            layers.add(new TreeSpecies.CanopyParams.Layer(yOff, radius));
        }
        return layers;
    }

    private double[] calculateLeanOffset(int y, int height, double azimuthDeg, double angleDeg, String curveFn, Map<String, Double> cParams, String azFn, Map<String, Double> azParams) {
        double t = (double) y / Math.max(height - 1, 1);
        double progress = switch (curveFn.toUpperCase()) {
            case "LOG" -> Math.log(1.0 + t * (Math.E - 1.0));
            case "SIGMOID" -> 1.0 / (1.0 + Math.exp(-cParams.getOrDefault("steepness", 8.0) * (t - 0.5)));
            case "PARABOLIC" -> 3.0 * t * t - 2.0 * t * t * t;
            default -> t;
        };
        
        double maxReach = height * Math.tan(Math.toRadians(angleDeg));
        double reach = maxReach * progress;
        
        double az = switch (azFn.toUpperCase()) {
            case "LINEAR" -> azParams.getOrDefault("start", 0.0) + (azParams.getOrDefault("end", 0.0) - azParams.getOrDefault("start", 0.0)) * t;
            case "SPIRAL" -> azParams.getOrDefault("start", 0.0) + azParams.getOrDefault("turns", 1.0) * 360.0 * t;
            case "SINE" -> azParams.getOrDefault("offset", 0.0) + azParams.getOrDefault("amplitude", 90.0) * Math.sin(2.0 * Math.PI * t / Math.max(azParams.getOrDefault("period", 1.0), 1e-6));
            default -> azimuthDeg;
        };

        double azRad = Math.toRadians(az);
        // if (y % 10 == 0 || y == height - 1) {
        //     platform.logger().info("[DEBUG_LOG] Lean offset y=" + y + "/" + height + " az=" + az + " reach=" + reach + " progress=" + progress);
        // }
        return new double[]{reach * Math.sin(azRad), reach * Math.cos(azRad)};
    }

    private double calculateWidth(int y, int height, double baseWidth, String shape, Map<String, Double> params) {
        double t = (double) y / Math.max(height - 1, 1);
        double mult = switch (shape.toUpperCase()) {
            case "LINEAR" -> params.getOrDefault("start", 1.0) + (params.getOrDefault("end", 0.5) - params.getOrDefault("start", 1.0)) * t;
            case "SIGMOID" -> 1.0 / (1.0 + Math.exp(params.getOrDefault("steepness", 5.0) * (t - 0.5)));
            case "LOG" -> {
                if (t <= 0) {
                    yield 1.0;
                }
                double base = params.getOrDefault("base", Math.E);
                yield Math.max(0.0, 1.0 - Math.log(1.0 + t * (base - 1.0)) / Math.log(base));
            }
            case "SINE" -> 1.0 + params.getOrDefault("amplitude", 0.2) * Math.sin(2.0 * Math.PI * t / params.getOrDefault("period", 1.0));
            case "PARABOLIC" -> {
                double peak = params.getOrDefault("peak_offset", 0.5);
                double floor = params.getOrDefault("floor", 0.5);
                double denom = Math.pow(Math.max(peak, 1.0 - peak), 2);
                yield floor + (1.0 - floor) * Math.pow(t - peak, 2) / denom;
            }
            default -> 1.0;
        };
        // Mirror trunk.py width_at: never let the trunk taper below a single block.
        // Without this clamp, log/sigmoid shapes round to width 0 in the upper
        // trunk, leaving gaps so the trunk is non-contiguous and can stop short of
        // the canopy.
        return Math.max(1, Math.round(baseWidth * mult));
    }

    private void placeTrunkSlice(Map<TreeModel.BlockPos, String> blocks, int ox, int oy, int oz, double cx, double cz, double width, String block, boolean round, Random random, double[] phases, boolean fillEmptyOnly) {
        int w = (int) Math.round(width);
        if (w % 2 == 1) {
            int half = w / 2;
            int icx = (int) Math.round(cx);
            int icz = (int) Math.round(cz);
            double rad = half + 0.5;
            for (int dx = -half; dx <= half; dx++) {
                for (int dz = -half; dz <= half; dz++) {
                    if (round && Math.hypot(dx, dz) > irregularRadius(rad, dx, dz, random, phases)) continue;
                    TreeModel.BlockPos p = new TreeModel.BlockPos(ox + icx + dx, oy, oz + icz + dz);
                    if (!fillEmptyOnly || !blocks.containsKey(p)) blocks.put(p, block);
                }
            }
        } else {
            int half = w / 2;
            int iox = (int) Math.floor(cx) - half + 1;
            int ioz = (int) Math.floor(cz) - half + 1;
            double rad = width / 2.0;
            double ccx = iox + (w - 1) / 2.0;
            double ccz = ioz + (w - 1) / 2.0;
            for (int dx = 0; dx < w; dx++) {
                for (int dz = 0; dz < w; dz++) {
                    double ddx = (iox + dx) - ccx;
                    double ddz = (ioz + dz) - ccz;
                    if (round && Math.hypot(ddx, ddz) > irregularRadius(rad, ddx, ddz, random, phases)) continue;
                    TreeModel.BlockPos p = new TreeModel.BlockPos(ox + iox + dx, oy, oz + ioz + dz);
                    if (!fillEmptyOnly || !blocks.containsKey(p)) blocks.put(p, block);
                }
            }
        }
    }

    private double irregularRadius(double rad, double dx, double dz, Random random, double[] phases) {
        double theta = Math.atan2(dz, dx);
        if (phases == null) {
            double factor = 1.0 + 0.2 * Math.sin(3 * theta) + 0.13 * Math.sin(5 * theta) + 0.09 * Math.sin(2 * theta);
            return rad * factor;
        }
        double factor = 1.0 
                + 0.20 * Math.sin(3 * theta + phases[0]) 
                + 0.13 * Math.sin(5 * theta + phases[1]) 
                + 0.09 * Math.sin(2 * theta + phases[2]);
        return rad * factor;
    }

    private void placeLeafDisc(Map<TreeModel.BlockPos, String> blocks, double cx, double cy, double cz, double radius, String block, String mode, double density, Random random, String secondaryLeaves, double secondaryFraction, Map<TreeModel.BlockPos, String> existing, double[] phases, double verticalScale) {
        int r = (int) Math.ceil(radius);
        int icx = (int) Math.round(cx);
        int icy = (int) Math.round(cy);
        int icz = (int) Math.round(cz);

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz);
                // Leaf discs are plain circles (matches canopy.py _place_leaf_disc).
                // The angular-noise irregular radius is reserved for the round trunk
                // cross-section; applying it to leaf discs produced ragged edges and
                // stray single leaves that read as detached/floating in-world.
                if (mode.equalsIgnoreCase("TRIMMED")) {
                    if (dist > radius || (Math.abs(dx) == r && Math.abs(dz) == r)) continue;
                } else {
                    if (dist > radius) continue;
                }

                if (mode.equalsIgnoreCase("DENSITY")) {
                    double falloff = 1.0 - (dist / (radius + 1.0));
                    if (random.nextDouble() > density * falloff) continue;
                }

                TreeModel.BlockPos p = new TreeModel.BlockPos(icx + dx, icy, icz + dz);
                if (!existing.containsKey(p) && !blocks.containsKey(p)) {
                    String activeBlock = resolveLeaf(block, secondaryLeaves, secondaryFraction, random);
                    blocks.put(p, activeBlock);
                }
            }
        }
    }

    private void placeLeafCluster(Map<TreeModel.BlockPos, String> blocks, double cx, double cy, double cz, int radius, String block, String mode, double density, Random random, String secondaryLeaves, double secondaryFraction, Map<TreeModel.BlockPos, String> existing, double verticalScale) {
        int icx = (int) Math.round(cx);
        int icy = (int) Math.round(cy);
        int icz = (int) Math.round(cz);

        int yRad = (int) Math.ceil(radius * verticalScale);
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -yRad; dy <= yRad; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    double dyScaled = dy / Math.max(0.01, verticalScale);
                    double dist = Math.sqrt(dx * dx + dyScaled * dyScaled + dz * dz);
                    if (mode.equalsIgnoreCase("TRIMMED")) {
                        if (dist > radius || (Math.abs(dx) == radius && Math.abs(dz) == radius)) continue;
                    } else {
                        if (dist > radius) continue;
                    }

                    if (mode.equalsIgnoreCase("DENSITY")) {
                        double falloff = 1.0 - (dist / (radius + 1.0));
                        if (random.nextDouble() > density * falloff) continue;
                    }

                    TreeModel.BlockPos p = new TreeModel.BlockPos(icx + dx, icy + dy, icz + dz);
                    if (!existing.containsKey(p) && !blocks.containsKey(p)) {
                        String activeBlock = resolveLeaf(block, secondaryLeaves, secondaryFraction, random);
                        blocks.put(p, activeBlock);
                    }
                }
            }
        }
    }

    private String resolveLeaf(String leafBlock, String secondaryLeaves, double secondaryFraction, Random random) {
        return (secondaryLeaves == null || secondaryFraction <= 0.0) ? leafBlock : (random.nextDouble() < secondaryFraction ? secondaryLeaves : leafBlock);
    }

    /**
     * Replaces the bottom-most leaf block of every (x,z) column in the canopy with the
     * configured underside block (e.g. shroomlight), creating a single glow layer across
     * the underside of the cap. Only leaf blocks (the primary leaf block or the configured
     * secondary leaves) are considered, so branch logs/wood are never overwritten.
     */
    private void applyUndersideLeaves(Map<TreeModel.BlockPos, String> canopyBlocks, String leafBlock,
                                      String secondaryLeaves, String undersideLeaves, double chance, Random random) {
        String leafBase = leafBlock == null ? null : leafBlock.split("\\[")[0];
        String secondaryBase = secondaryLeaves == null ? null : secondaryLeaves.split("\\[")[0];

        // Find the lowest leaf block position for each (x,z) column.
        Map<Long, TreeModel.BlockPos> lowest = new HashMap<>();
        canopyBlocks.forEach((pos, block) -> {
            String base = block.split("\\[")[0];
            boolean isLeaf = base.equals(leafBase) || (secondaryBase != null && base.equals(secondaryBase));
            if (!isLeaf) return;
            long key = (((long) pos.x()) << 32) ^ (pos.z() & 0xffffffffL);
            TreeModel.BlockPos current = lowest.get(key);
            if (current == null || pos.y() < current.y()) {
                lowest.put(key, pos);
            }
        });

        for (TreeModel.BlockPos pos : lowest.values()) {
            if (chance >= 1.0 || random.nextDouble() < chance) {
                canopyBlocks.put(pos, undersideLeaves);
            }
        }
    }

    /**
     * Applies accent decorators to the assembled tree, mirroring the offline Python
     * {@code decorators.py}. Decorator blocks are only placed into empty cells (never
     * overwriting trunk/canopy blocks or each other) and are added into the canopy map
     * so they become part of the final model.
     */
    private void applyDecorators(List<TreeSpecies.Decorator> decorators,
                                 Map<TreeModel.BlockPos, String> trunkBlocks,
                                 Map<TreeModel.BlockPos, String> canopyBlocks,
                                 List<double[]> branchEndpoints, Random random) {
        // Existing occupancy: trunk + canopy blocks. Decorators never overwrite these.
        Map<TreeModel.BlockPos, String> existing = new HashMap<>(trunkBlocks);
        canopyBlocks.forEach(existing::putIfAbsent);
        Map<TreeModel.BlockPos, String> result = new HashMap<>();

        for (TreeSpecies.Decorator dec : decorators) {
            String target = dec.target() == null ? "branch_tip" : dec.target();
            switch (target) {
                case "branch_tip" -> applyBranchTip(result, existing, dec, branchEndpoints, random);
                case "trunk_surface" -> applyTrunkSurface(result, existing, dec, trunkBlocks.keySet(), random);
                case "canopy_top" -> applyCanopyTop(result, existing, dec, random);
                case "trunk_base" -> applyTrunkBase(result, existing, dec, trunkBlocks.keySet(), random);
                default -> { /* unknown target: ignore (e.g. canopy_bottom handled elsewhere) */ }
            }
        }

        // Merge decorator blocks into the canopy (they live in the final model).
        result.forEach(canopyBlocks::putIfAbsent);
    }

    /** Cardinal direction facing away from the trunk center, for orientable accents. */
    private String facingAway(double cx, double cz, int x, int z) {
        double dx = x - cx;
        double dz = z - cz;
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? "east" : "west";
        }
        return dz >= 0 ? "south" : "north";
    }

    /** Append a facing blockstate if not already present and a facing is supplied. */
    private String expandBlock(String block, String facing) {
        if (block.contains("[")) return block;
        return facing != null ? block + "[facing=" + facing + "]" : block;
    }

    /** Place the decorator at each branch endpoint with the configured chance. */
    private void applyBranchTip(Map<TreeModel.BlockPos, String> result, Map<TreeModel.BlockPos, String> existing,
                                TreeSpecies.Decorator dec, List<double[]> branchEndpoints, Random random) {
        if (branchEndpoints == null || branchEndpoints.isEmpty()) return;
        for (double[] ep : branchEndpoints) {
            if (random.nextDouble() > dec.chance()) continue;
            TreeModel.BlockPos pos = new TreeModel.BlockPos(
                    (int) Math.round(ep[0]), (int) Math.round(ep[1]), (int) Math.round(ep[2]));
            if (existing.containsKey(pos) || result.containsKey(pos)) continue;
            String facing = dec.axisAware() ? facingAway(ep[3], ep[4], pos.x(), pos.z()) : null;
            result.put(pos, expandBlock(dec.block(), facing));
        }
    }

    /** Place the decorator on air-facing horizontal sides of trunk blocks (vines, lichen). */
    private void applyTrunkSurface(Map<TreeModel.BlockPos, String> result, Map<TreeModel.BlockPos, String> existing,
                                   TreeSpecies.Decorator dec, Set<TreeModel.BlockPos> trunkPositions, Random random) {
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        String[] facings = {"west", "east", "north", "south"};
        for (TreeModel.BlockPos t : trunkPositions) {
            for (int i = 0; i < dirs.length; i++) {
                TreeModel.BlockPos neighbor = new TreeModel.BlockPos(t.x() + dirs[i][0], t.y(), t.z() + dirs[i][1]);
                if (existing.containsKey(neighbor) || result.containsKey(neighbor)) continue;
                if (random.nextDouble() > dec.chance()) continue;
                result.put(neighbor, expandBlock(dec.block(), facings[i]));
            }
        }
    }

    /** Place the decorator on top of the highest occupied block in each (x,z) column. */
    private void applyCanopyTop(Map<TreeModel.BlockPos, String> result, Map<TreeModel.BlockPos, String> existing,
                                TreeSpecies.Decorator dec, Random random) {
        Map<Long, Integer> colTop = new HashMap<>();
        for (TreeModel.BlockPos pos : existing.keySet()) {
            long key = (((long) pos.x()) << 32) ^ (pos.z() & 0xffffffffL);
            Integer top = colTop.get(key);
            if (top == null || pos.y() > top) colTop.put(key, pos.y());
        }
        for (Map.Entry<Long, Integer> e : colTop.entrySet()) {
            int x = (int) (e.getKey() >> 32);
            int z = (int) (e.getKey() & 0xffffffffL);
            TreeModel.BlockPos above = new TreeModel.BlockPos(x, e.getValue() + 1, z);
            if (existing.containsKey(above) || result.containsKey(above)) continue;
            if (random.nextDouble() > dec.chance()) continue;
            result.put(above, expandBlock(dec.block(), null));
        }
    }

    /** Scatter the decorator in the y=0 ring around the trunk base (deadwood, mushrooms). */
    private void applyTrunkBase(Map<TreeModel.BlockPos, String> result, Map<TreeModel.BlockPos, String> existing,
                                TreeSpecies.Decorator dec, Set<TreeModel.BlockPos> trunkPositions, Random random) {
        Set<Long> baseXz = new HashSet<>();
        for (TreeModel.BlockPos t : trunkPositions) {
            if (t.y() == 0) baseXz.add((((long) t.x()) << 32) ^ (t.z() & 0xffffffffL));
        }
        int[][] dirs = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}};
        for (long key : baseXz) {
            int x = (int) (key >> 32);
            int z = (int) (key & 0xffffffffL);
            for (int[] d : dirs) {
                TreeModel.BlockPos pos = new TreeModel.BlockPos(x + d[0], 0, z + d[1]);
                if (existing.containsKey(pos) || result.containsKey(pos)) continue;
                if (random.nextDouble() > dec.chance()) continue;
                result.put(pos, expandBlock(dec.block(), null));
            }
        }
    }
}
