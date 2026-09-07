# Simple Craft Editor - Changelog

## Version 1.2.0

Five Minecraft versions, three more recipe types, and recipes that can care about the data on an item.

### Added

- Minecraft **1.21.11**, **26.1.2** and **26.2**, on Fabric and NeoForge
- **REI** support, beside JEI and EMI: drag items and fluids onto the editor's slots, and press the key over its list to open a recipe
- **Cobblemon's campfire pot**: both of its recipe types, with the seasoning rules — which item tag a player may add to the pot, and which of the seven properties the dish takes from it
- **Smithing table upgrades**: diamond gear into netherite, and whatever else a pack adds
- **Data as part of a crafting recipe.** A recipe can require the data on the items placed in the grid, so it works with the chest actually named "Pepito" and not with any chest; and the result can be baked in exactly as it was left. Four ways to decide where the crafted result's data comes from
- **Shift + the editor key** over an item with no recipe starts a new one that makes it
- A tag in a slot now shows the items the tag holds, one after another, instead of a stand-in
- Every button whose label does not say what it will do has a line that does

### Fixed

- The editor key could not find a recipe whose interesting output was not its main result — Create's crushing recipes, among others
- Editing a recipe with JEI installed froze the game for a second or two while JEI rebuilt itself
- Typing a recipe id that does not exist said the recipe had been written by a script
- Large parts of the interface appeared in English when playing in Spanish

### Changed

- Saving returns to the recipe list, where the recipe now is, and messages fade instead of staying on screen
- A recipe whose result carries components — a suspicious stew, a named item — opens in the visual editor and keeps those components, rather than falling back to raw JSON

---

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
