package net.leaf.treegen.common;

import java.util.*;

/**
 * Port of tools/tree-gen/ procedural logic to Java.
 */
public final class ProceduralGenerator {
    private final Platform platform;
    private final TreeRegistry registry;
    private final TreeCache cache;

    public ProceduralGenerator(Platform platform, TreeRegistry registry) {
        this.platform = platform;
        this.registry = registry;
        this.cache = new TreeCache(100, 10); // N=100 cached trees, remove one on M=10 reuses
    }

    private TreeSpecies speciesMapLookup(String id) {
        return registry.getSpeciesMap().get(id.toLowerCase(java.util.Locale.ROOT));
    }

    public void generate(String worldName, int x, int y, int z, TreeSpecies species, Random random) {
        long seed = random.nextLong();
        platform.logger().info("[DEBUG_LOG] Generating tree for species: " + species.id() + " at " + x + "," + y + "," + z + " (seed: " + seed + ")");
        SegmentedTreeModel model = cache.getOrGenerate(species.id(), seed, s -> {
            platform.logger().info("[DEBUG_LOG] Cache miss for " + species.id() + ":" + s + " - Building new model...");
            return buildModel(species, new Random(s));
        });
        if (model != null) {
            platform.logger().info("[DEBUG_LOG] Placement successful for model: " + model.getSpeciesId() + " (" + model.getBlockCount() + " blocks)");
            model.place(platform, worldName, x, y, z);
        } else {
            platform.logger().warning("[DEBUG_LOG] Placement failed: buildModel returned null for species " + species.id());
        }
    }

    public TreeModel buildModel(TreeSpecies species, Random random) {
        platform.logger().info("[DEBUG_LOG] Selecting tree definition for species: " + species.id());
        if (species.trees() != null && !species.trees().isEmpty()) {
            // Select from group
            int totalWeight = species.trees().stream().mapToInt(TreeSpecies.WeightedSpecies::weight).sum();
            int r = random.nextInt(totalWeight);
            int current = 0;
            for (TreeSpecies.WeightedSpecies ws : species.trees()) {
                current += ws.weight();
                if (r < current) {
                    String[] parts = ws.id().split(":", 2);
                    TreeSpecies child = speciesMapLookup(parts[0]);
                    if (child != null) {
                        if (parts.length > 1) {
                            // Named tree within species
                            TreeSpecies.ProceduralParams named = child.treeDefinitions() != null ? child.treeDefinitions().get(parts[1]) : null;
                            if (named != null) {
                                platform.logger().info("[DEBUG_LOG] Group selection: " + species.id() + " -> " + child.id() + ":" + parts[1]);
                                return buildProceduralModel(child.id() + ":" + parts[1], named, random);
                            } else {
                                platform.logger().warning("[DEBUG_LOG] Group selection failed: definition '" + parts[1] + "' not found in child species '" + child.id() + "'");
                            }
                        }
                        platform.logger().info("[DEBUG_LOG] Group selection: " + species.id() + " -> " + child.id());
                        return buildModel(child, random);
                    } else {
                        platform.logger().warning("[DEBUG_LOG] Group selection failed: child species '" + parts[0] + "' not found");
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
                platform.logger().info("[DEBUG_LOG] Skipping buildModel for non-procedural (structure) species: " + species.id());
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
                platform.logger().info("[DEBUG_LOG] Selected definition '" + selected + "' for species '" + species.id() + "'");
                return buildProceduralModel(species.id() + ":" + selected, named, random);
            }
            return null;
        }

        return buildProceduralModel(species.id(), params, random);
    }

    private TreeModel buildProceduralModel(String speciesId, TreeSpecies.ProceduralParams params, Random random) {
        if (params == null) {
            platform.logger().warning("[DEBUG_LOG] buildProceduralModel called with null params for " + speciesId);
            return null;
        }
        int height = params.minHeight() + (params.maxHeight() > params.minHeight() ? random.nextInt(params.maxHeight() - params.minHeight() + 1) : 0);
        List<double[]> offsets = new ArrayList<>();

        // 1. Generate Trunk
        double prevCx = 0, prevCz = 0;
        platform.logger().info("[DEBUG_LOG] Generating procedural tree height=" + height);
        
        Map<TreeModel.BlockPos, String> trunkBlocks = new HashMap<>();

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

            placeTrunkSlice(trunkBlocks, 0, i, 0, cx, cz, w, orientedBlock, params.roundTrunk(), random);
            
            // Fill gaps
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
                        placeTrunkSlice(trunkBlocks, 0, i - 1, 0, icx, icz, w, orientedBlock, params.roundTrunk(), random);
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
                layers = generatePresetLayers(profile, height, canopy.branches() != null);
            }

            double[] topOffset = offsets.get(height - 1);
            
            // Volume layers
            for (TreeSpecies.CanopyParams.Layer layer : layers) {
                int cy = layer.yOffset();
                placeLeafDisc(canopyBlocks, topOffset[0], cy, topOffset[1], layer.radius(), params.leafBlock(), canopy.mode(), canopy.density(), canopyRandom, canopy.secondaryLeaves(), canopy.secondaryFraction(), trunkBlocks);
            }
            
            // Branches
            if (canopy.branches() != null && canopy.branches().count() > 0) {
                generateBranches(canopyBlocks, height, offsets, params.trunkBlock(), params.leafBlock(), canopy, canopyRandom, trunkBlocks);
            }

            // Cap trunk tip
            capTrunkTip(canopyBlocks, trunkBlocks, params.leafBlock(), canopy.mode(), canopy.density(), canopyRandom, canopy.secondaryLeaves(), canopy.secondaryFraction());
        }

        Map<TreeModel.BlockPos, String> finalBlocks = new HashMap<>(trunkBlocks);
        canopyBlocks.forEach((pos, b) -> {
            if (!finalBlocks.containsKey(pos)) {
                finalBlocks.put(pos, b);
            }
        });

        return new TreeModel(speciesId, finalBlocks);
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

    private void capTrunkTip(Map<TreeModel.BlockPos, String> canopy, Map<TreeModel.BlockPos, String> trunk, String leafBlock, String mode, double density, Random random, String secondaryLeaves, double secondaryFraction) {
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

        double sumX = 0, sumZ = 0;
        for (TreeModel.BlockPos p : exposed) { sumX += p.x(); sumZ += p.z(); }
        int cx = (int) Math.round(sumX / exposed.size());
        int cz = (int) Math.round(sumZ / exposed.size());

        double half = 0;
        for (TreeModel.BlockPos p : exposed) {
            half = Math.max(half, Math.max(Math.abs(p.x() - cx), Math.abs(p.z() - cz)));
        }

        // Simulating the Python cap_rows
        // (top_y + 1, half + 0.5), (top_y + 2, max(1.0, half - 0.5)), (top_y + 3, 0.0)
        double[][] capRows = {{topY + 1, half + 0.5}, {topY + 2, Math.max(1.0, half - 0.5)}, {topY + 3, 0.0}};
        for (double[] row : capRows) {
            int ry = (int) row[0];
            double rad = row[1];
            if (rad < 0.5) {
                TreeModel.BlockPos p = new TreeModel.BlockPos(cx, ry, cz);
                if (!combined.containsKey(p)) {
                    String leaf = (secondaryLeaves != null && random.nextDouble() < secondaryFraction) ? secondaryLeaves : leafBlock;
                    canopy.put(p, leaf);
                    combined.put(p, leaf);
                }
            } else {
                placeLeafDisc(canopy, cx, ry, cz, rad, leafBlock, mode, density, random, secondaryLeaves, secondaryFraction, combined);
                combined.putAll(canopy);
            }
        }
    }

    private void generateBranches(Map<TreeModel.BlockPos, String> canopyBlocks, int height, List<double[]> offsets, String trunkBlock, String leafBlock, TreeSpecies.CanopyParams canopy, Random random, Map<TreeModel.BlockPos, String> trunkBlocks) {
        TreeSpecies.BranchParams b = canopy.branches();
        int startY = (int) (height * b.startHeight());
        for (int i = 0; i < b.count(); i++) {
            int by = startY + (height > startY ? random.nextInt(height - startY) : 0);
            
            double length = b.minLength() + random.nextDouble() * (b.maxLength() - b.minLength());
            double az = random.nextDouble() * 360.0;
            double el = Math.toRadians(b.minElevation() + random.nextDouble() * (b.maxElevation() - b.minElevation()));
            
            double[] offset = offsets.get(by);
            double ox = offset[0];
            double oy = by;
            double oz = offset[1];
            
            double ex = ox + length * Math.cos(el) * Math.sin(Math.toRadians(az));
            double ey = oy + length * Math.sin(el);
            double ez = oz + length * Math.cos(el) * Math.cos(Math.toRadians(az));
            
            rasterizeBranch(canopyBlocks, ox, oy, oz, ex, ey, ez, trunkBlock, trunkBlocks);
            placeLeafDisc(canopyBlocks, ex, ey, ez, 2.5, leafBlock, canopy.mode(), canopy.density(), random, canopy.secondaryLeaves(), canopy.secondaryFraction(), trunkBlocks);
        }
    }

    private void rasterizeBranch(Map<TreeModel.BlockPos, String> blocks, double x0, double y0, double z0, double x1, double y1, double z1, String block, Map<TreeModel.BlockPos, String> existing) {
        double dx = x1 - x0;
        double dy = y1 - y0;
        double dz = z1 - z0;
        
        int steps = (int) Math.max(Math.max(Math.abs(x1 - x0), Math.abs(y1 - y0)), Math.max(Math.abs(z1 - z0), 1));
        
        String axis = calculateAxis(dx, dy, dz);
        String orientedBlock = applyAxis(block, axis);

        for (int i = 0; i <= steps; i++) {
            double f = (double) i / steps;
            TreeModel.BlockPos p = new TreeModel.BlockPos((int)Math.round(x0 + dx * f), (int)Math.round(y0 + dy * f), (int)Math.round(z0 + dz * f));
            if (!existing.containsKey(p)) {
                blocks.put(p, orientedBlock);
            }
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
        if (y % 10 == 0 || y == height - 1) {
            platform.logger().info("[DEBUG_LOG] Lean offset y=" + y + "/" + height + " az=" + az + " reach=" + reach + " progress=" + progress);
        }
        return new double[]{reach * Math.sin(azRad), reach * Math.cos(azRad)};
    }

    private double calculateWidth(int y, int height, double baseWidth, String shape, Map<String, Double> params) {
        double t = (double) y / Math.max(height - 1, 1);
        double mult = switch (shape.toUpperCase()) {
            case "LINEAR" -> params.getOrDefault("start", 1.0) + (params.getOrDefault("end", 0.5) - params.getOrDefault("start", 1.0)) * t;
            case "SIGMOID" -> 1.0 / (1.0 + Math.exp(params.getOrDefault("steepness", 5.0) * (t - 0.5)));
            case "LOG" -> t <= 0 ? 1.0 : Math.max(0, 1.0 - Math.log(1.0 + t * (Math.E - 1.0)));
            case "SINE" -> 1.0 + params.getOrDefault("amplitude", 0.2) * Math.sin(2.0 * Math.PI * t / params.getOrDefault("period", 1.0));
            case "PARABOLIC" -> {
                double peak = params.getOrDefault("peak_offset", 0.5);
                double floor = params.getOrDefault("floor", 0.5);
                double denom = Math.pow(Math.max(peak, 1.0 - peak), 2);
                yield floor + (1.0 - floor) * Math.pow(t - peak, 2) / denom;
            }
            default -> 1.0;
        };
        return baseWidth * mult;
    }

    private void placeTrunkSlice(Map<TreeModel.BlockPos, String> blocks, int ox, int oy, int oz, double cx, double cz, double width, String block, boolean round, Random random) {
        int w = (int) Math.round(width);
        if (w % 2 == 1) {
            int half = w / 2;
            int icx = (int) Math.round(cx);
            int icz = (int) Math.round(cz);
            double rad = half + 0.5;
            for (int dx = -half; dx <= half; dx++) {
                for (int dz = -half; dz <= half; dz++) {
                    if (round && Math.hypot(dx, dz) > irregularRadius(rad, dx, dz, random)) continue;
                    blocks.put(new TreeModel.BlockPos(ox + icx + dx, oy, oz + icz + dz), block);
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
                    if (round && Math.hypot(ddx, ddz) > irregularRadius(rad, ddx, ddz, random)) continue;
                    blocks.put(new TreeModel.BlockPos(ox + iox + dx, oy, oz + ioz + dz), block);
                }
            }
        }
    }

    private double irregularRadius(double rad, double dx, double dz, Random random) {
        double theta = Math.atan2(dz, dx);
        // Python uses fixed phases per object. We'll simulate with a seeded random if we had it,
        // but for now we follow the same harmonic structure.
        double factor = 1.0 + 0.2 * Math.sin(3 * theta) + 0.13 * Math.sin(5 * theta) + 0.09 * Math.sin(2 * theta);
        return rad * factor;
    }

    private void placeLeafDisc(Map<TreeModel.BlockPos, String> blocks, double cx, double cy, double cz, double radius, String block, String mode, double density, Random random, String secondaryLeaves, double secondaryFraction, Map<TreeModel.BlockPos, String> existing) {
        int r = (int) Math.ceil(radius);
        int icx = (int) Math.round(cx);
        int icy = (int) Math.round(cy);
        int icz = (int) Math.round(cz);

        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                double dist = Math.sqrt(dx * dx + dz * dz);
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
                    String activeBlock = (secondaryLeaves != null && random.nextDouble() < secondaryFraction) ? secondaryLeaves : block;
                    blocks.put(p, activeBlock);
                }
            }
        }
    }
}
