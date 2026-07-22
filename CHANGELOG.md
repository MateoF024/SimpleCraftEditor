# Simple Craft Editor - Changelog

## Version 1.1.0

A stability release. Simple Craft Editor now behaves the same in a large modpack as it does on its own.

### Added

- Debug logging, off by default, for reporting problems: `/sce debug true` writes what the mod does to the log. It can be narrowed to one area, it survives a restart, and it can be turned on before the world loads with `-Dsce.debug=true`
- `/sce debug status`, `/sce debug verify` and `/sce debug find` report what the mod is doing and where a given recipe stands

### Fixed

- A modpack that builds its recipes from scripts could lose them: editing a single recipe emptied the pack's recipe list
- Opening an existing recipe in the editor showed an empty screen in some modpacks
- Creating, editing, disabling and deleting took effect only after a reload when certain performance mods were installed
- A deleted recipe could still be crafted, and stayed craftable until the player logged out, when a recipe-choice mod was installed
- The editor key appeared in Controls but did nothing on Forge 1.20.1
- Deleting an edit of an existing recipe left the recipe missing instead of restoring the original

### Changed

- Recipes created by script mods can no longer be opened or disabled. Those scripts rewrite their recipes every time the pack loads, so any change made here would be undone. The editor now says so instead. Recipes from datapacks, from the game and from other mods are unaffected

---

## Version 1.0.0

First release.

- Disable any recipe from the game or from a mod, and restore it later
- Edit existing recipes, or create new ones, from a visual editor
- Shaped and shapeless crafting, smelting, blasting, smoking, campfire cooking and stonecutting
- Item tags as ingredients
- Create support: its processing machines, the Mechanical Crafter grid and recipe sequences, including fluids
- Raw JSON editing for any recipe type the visual editor does not cover
- Drag items and fluids in from JEI or EMI, and jump to a recipe with a key
- Operator only, and changes are shared with everyone on the server
- Available in English and Spanish
