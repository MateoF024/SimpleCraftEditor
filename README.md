# Simple Craft Editor

In-game editor for vanilla and modded crafting recipes.

> **License:** All Rights Reserved. See [`LICENSE.txt`](LICENSE.txt).

## Supported targets

| Minecraft | Loaders            | Source tree | Architectury | Java |
|-----------|--------------------|-------------|--------------|------|
| 1.20.1    | Forge + Fabric     | repo root   | 9.x          | 17   |
| 1.21.1    | NeoForge + Fabric  | `v1.21.1/`  | 13.x         | 21   |

Each Minecraft version is built independently and produces its own set of jars, so
`1.21.x` compatibility is incidental — development targets `1.20.1` and `1.21.1` in isolation.

## Project layout

The build is driven by the `minecraft_version` property in `gradle.properties`.
`settings.gradle` maps that value to the matching source tree:

- `1.20.x` → root-level `common/`, `fabric/`, `forge/` (legacy Architectury / Java 17).
- `1.21.1` → `v1.21.1/common`, `v1.21.1/fabric`, `v1.21.1/neoforge` (Architectury 13.x / Java 21).

Loader-agnostic logic lives in each tree's `common` module; keep as much code there as possible.

## Building

Set the target in `gradle.properties` (or override on the command line) and run `build`:

```bash
# 1.20.1 (Forge + Fabric) — the default in gradle.properties
./gradlew build

# 1.21.1 (NeoForge + Fabric)
./gradlew build -Pminecraft_version=1.21.1
```

Remapped loader jars are collected in `build/libs/`, named
`SimpleCraftEditor-<loader>-<mcversion>-<modversion>.jar`.

## Adding a new Minecraft version

1. Create a `v<version>/` tree with `common`, `fabric`, and the appropriate Forge/NeoForge module.
2. Add the version → directory mapping to `treeByVersion` in `settings.gradle`.
3. Add its dependency versions to `gradle.properties`.
