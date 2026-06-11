# LeafTreeGen

A server-agnostic Minecraft plugin for custom tree generation and natural placement.

## Overview

LeafTreeGen bridges the gap between offline tree structure generation (via `tools/tree-gen`) and in-game world generation. It discovers `.nbt` structure templates in your world's datapacks and enables their natural placement during chunk generation or via special player-planted saplings.

## Architecture

This is a multi-platform Gradle project designed to support multiple server environments from a single codebase:

- **`common`**: Platform-agnostic core logic. Handles configuration, structure registry scanning, and worldgen datapack generation.
- **`paper`**: Implementation for Paper/Folia servers.
- **`fabric`**: (Planned) Implementation for Fabric servers.
- **`neoforge`**: (Planned) Implementation for NeoForge servers.

### Design Decisions

1. **Jigsaw-based Worldgen**: Instead of placing structures at runtime via listeners (which is fragile and heavy), the plugin generates a standard vanilla worldgen datapack on the fly. This allows Minecraft's native jigsaw and structure systems to handle placement efficiently.
2. **Platform Abstraction**: All core logic interacts with a `Platform` interface, making the plugin portable and easy to maintain across different server implementations.
3. **Parameter-based Commands**: Uses `CommandsAPI` to provide a modern, discoverable command interface for administrators.
4. **Offline Generation Integration**: Built specifically to consume the output of the project's Python-based tree generation pipeline.
5. **Procedural Engine**: Includes a Java port of the Python geometry engine, allowing trees to be generated algorithmically from parameters in `config.yml` without pre-generated NBT files.

## Requirements

- **Java 21** or higher.
- **Paper 1.21.4** (for the Paper module).
- **CommandsAPI** (locally built from `GitHub/CommandsAPI` or via JitPack).

## Configuration

The plugin's behavior is configured via `config.yml`, while tree species are defined in individual JSON files within the `species/` directory.

### Species Definitions (`species/*.json`)

Species are loaded from JSON files. The plugin supports both single-object definitions and arrays (used by procedural configs).

Example `species/my_tree.json`:
```json
{
  "name": "jacaranda-grove",
  "trunk": "minecraft:oak_log",
  "leaves": "minecraft:azalea_leaves",
  "heightMin": 5,
  "heightMax": 10,
  "biomes": [ "minecraft:plains" ]
}
```

### Placement Overrides (`config.yml`)

Use `config.yml` to map species to biomes and tune worldgen placement without modifying the base species files.

```yaml
generation-mode: DATAPACK

datapack:
  name: leaf-treegen-generated
  description: "LeafTreeGen generated worldgen."

placement:
  my_tree:
    spacing: 5
    separation: 2
    biomes: ["minecraft:forest"] # Override species biomes
```

## Adding a New Tree (Step-by-Step)

To add a new tree species to the server:

1.  **Define the Geometry**: Use the Python tool in `tools/tree-gen/`.
2.  **Generate the NBT** (Optional): Run the generator script if you want pre-baked structures.
3.  **Deploy**:
    - Place `.nbt` files in a datapack (e.g., `datapacks/leaf-worldgen/data/leaf/structure/my_trees/`).
    - Place the species JSON config in `plugins/leaf-treegen/species/my_tree.json`.
4.  **Reload**: Run `/leaftree reload` in-game.

### Configuration Details

- **`display-name`**: Name used in commands and item tooltips.
- **`namespace`**: The namespace for the generated structures (default: `minecraft`).
- **`group`**: Subfolder under `structure/` where `.nbt` files are located (default: species ID).
- **`biomes`**: List of biome IDs where the tree can spawn or be planted.
- **`sapling-item`**: The vanilla sapling base used for custom saplings (default: `OAK_SAPLING`).
- **`worldgen`**: Boolean to enable/disable natural placement (default: true if biomes are present).
- **`generation-mode`**: How trees are placed (see below).
- **`spacing` / `separation` / `salt`**: Standard vanilla structure placement parameters for the generated datapack.
- **`variants`**: (Optional) Explicit list of variants. If omitted, the plugin scans the world's datapacks for any `.nbt` files in the specified `namespace:group` folder.
  - **`location`**: Full resource location of the `.nbt` (e.g., `leaf:dark_forest/my_tree`).
  - **`weight`**: Relative probability of this variant being chosen.
- **`procedural`**: (Optional) block to enable algorithmic generation.
  - **`trunk-block`** / **`leaf-block`**: Voxel types.
  - **`height-min`** / **`height-max`**: Height range.
  - **`trunk-width`**: Base radius.
  - **`trunk-shape`**: `CONSTANT`, `LINEAR`, `PARABOLIC`.
  - **`lean-angle`** / **`lean-azimuth`**: Tilt controls.
  - **`canopy`**: Define leaf layers with `yOffset` and `radius`.

## Usage

### For Players
Players can plant special NBT-tagged saplings. These saplings will grow into a random custom variant of the configured species if planted in a valid biome.

### For Admins
Use the `/leaftree` command (requires `leaftreegen.admin` permission):

- `/leaftree generate species=<id>`: Test placement at your cursor.
- `/leaftree give species=<id> [player=<name>] [amount=<n>]`: Distribute special saplings.
- `/leaftree list [species=<id>]`: See all registered species or variants.
- `/leaftree reload`: Apply config changes and optionally regenerate worldgen datapacks.

### Generation Modes
LeafTreeGen can place trees using two different methods, switchable in `config.yml` via `generation-mode`:
- **`DATAPACK`**: Native jigsaw-based generation via an auto-generated datapack (Recommended).
- **`PROCEDURAL`**: Runtime placement during chunk load.
- **`BOTH`**: Use both methods.
- **`NONE`**: Disable automatic placement.

## Building

```powershell
# Build all modules
./gradlew jar

# Build specifically for Paper
./gradlew :paper:jar
```

The resulting jars will be in `plugins/leaf-treegen/<platform>/build/libs/`.
