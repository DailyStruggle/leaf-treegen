# Changelog

All notable changes to LeafTreeGen are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [0.1.0] - 2026-06-12

Initial release.

### Added

- Procedural tree engine that generates trees in Java from per-species parameters (height, trunk shape, lean angle, canopy layers), with no pre-baked NBT files required.
- Biome-matched species, including savanna acacias, dark-forest canopies, glacier pines, cherry groves, spiral-crown forests, and rainbow groves, each assigned to the biomes they fit.
- Jigsaw worldgen integration: a vanilla worldgen datapack is generated on first load so Minecraft's structure system handles placement.
- Plantable saplings via `/leaftree give`, delivered as NBT-tagged items that grow into a random variant of the species when planted in a valid biome.
- Commands:
  - `/leaftree generate species=<id>` - place a tree at your cursor (admin test).
  - `/leaftree give species=<id> [player] [amount]` - hand out custom saplings.
  - `/leaftree list [species=<id>]` - list registered species or variants.
  - `/leaftree reload` - apply config changes and regenerate the datapack.
- Admin commands gated behind the `leaftreegen.admin` permission node.
- Server-friendly placement: multi-block tree placement is timeboxed against a per-tick budget rather than run inline, so large trees do not stall the main thread.
- Configuration via JSON species files in the `species/` folder, with biome assignments and placement tuning (spacing, separation, salt) in `config.yml`, overridable per-species.
- Generation modes: `DATAPACK`, `PROCEDURAL`, `BOTH`, and `NONE`.
- Platform support for Paper (and compatible Folia builds), Fabric, and NeoForge.

[0.1.0]: https://github.com/leaf/leaf-treegen/releases/tag/v0.1.0
