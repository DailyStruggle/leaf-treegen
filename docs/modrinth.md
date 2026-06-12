# LeafTreeGen

Procedural, biome-aware custom trees that generate through vanilla worldgen.

LeafTreeGen adds dozens of hand-tuned and procedurally generated tree species. Every tree is baked into vanilla `.nbt` structure templates at startup and wired into jigsaw template pools, so placement is handled by the game's own worldgen pipeline rather than by runtime listeners.

---

## Features

- **Biome-matched species** - savanna acacias, dark-forest canopies, glacier pines, cherry groves, spiral-crown forests, and rainbow groves, each assigned to the biomes they fit
- **Procedural engine** - trees are generated in Java from per-species parameters (height, trunk shape, lean angle, canopy layers), so no pre-baked NBT files are required
- **Jigsaw worldgen** - the plugin generates a vanilla worldgen datapack on first load; Minecraft's structure system handles placement
- **Plantable saplings** - players can receive NBT-tagged saplings via `/leaftree give` that grow into a random variant of the species when planted in a valid biome
- **Reload command** - `/leaftree reload` rebakes all variants and regenerates the datapack
- **Server-friendly placement** - multi-block tree placement is timeboxed against a per-tick budget rather than run inline, so large trees don't stall the main thread
- **Paper, Folia, Fabric, and NeoForge** - one unified jar runs on Paper (and Folia builds), Fabric, and NeoForge, with full feature parity (worldgen, saplings, and the `/leaftree` command) across every platform

---

## Species Highlights

| Category | Examples |
|---|---|
| Temperate | Forest, Birch Forest, Old-Growth Birch, Maple-Birch Giants, Ashwood |
| Tropical | Jungle, Cherry Grove, Jacaranda Grove |
| Cold | Taiga, Old-Growth Pine Taiga, Glacier Pine |
| Dark & Exotic | Dark Forest Giants, Spiral-Crown Forest, Rainbow Grove, Cloudcap |
| Special | Blightroot, Embervine, Glowcap, Lavatree, Volcano |

---

## Commands

| Command | Description |
|---|---|
| `/leaftree generate species=<id>` | Place a tree at your cursor (admin test) |
| `/leaftree give species=<id> [player] [amount]` | Hand out custom saplings |
| `/leaftree list [species=<id>]` | List registered species or variants |
| `/leaftree reload` | Apply config changes and regenerate the datapack |

Requires the `leaftreegen.admin` permission node.

---

## Configuration

Species are defined as JSON files in the `species/` folder. Biome assignments and placement tuning (spacing, separation, salt) live in `config.yml` and can be overridden per-species without touching the species files themselves.

```yaml
generation-mode: DATAPACK   # DATAPACK | PROCEDURAL | BOTH | NONE

placement:
  glacierpine:
    spacing: 6
    separation: 3
    biomes:
      - minecraft:snowy_taiga
      - minecraft:grove
```

```json
{
  "name": "glacierpine",
  "procedural": {
    "trunk-block": "minecraft:spruce_log",
    "leaf-block": "minecraft:spruce_leaves",
    "height-min": 10,
    "height-max": 18,
    "trunk-shape": "LINEAR",
    "canopy": [
      { "yOffset": 0, "radius": 4 },
      { "yOffset": 3, "radius": 3 },
      { "yOffset": 6, "radius": 2 }
    ]
  }
}
```

## Requirements

- Java 25+
- One of:
  - Paper or Folia 1.21+
  - Fabric 26.1+ (Fabric Loader 0.18.4+, Fabric API)
  - NeoForge 26.1+
