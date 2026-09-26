# Simple Craft Editor - Changelog

## Version 1.3.0

Minecraft 26.3, the anvil, three more mods, and recipes that survive moving between Minecraft versions.

### Added

- Minecraft **26.3**, on Fabric and NeoForge
- **Anvil repair rules.** Tell the anvil what mends what: any item can be given its own repair material, or have the one it came with taken away. The rules have their own screen, `/sce anvil` lists them and says what the anvil would do with two given items, and JEI, EMI and REI show them like any other recipe
- **Farmer's Delight**: the cutting board and the cooking pot
- **The Twilight Forest**: the uncrafting table, drying, and repairing a scepter
- **Cobblemon's brewing stand**, beside the campfire pot
- **Recipes now survive a change of Minecraft version.** Move a world or an instance to another version — up, down, or several at once — and your edited, created and disabled recipes are brought with it. Recipe files changed shape twice across the versions this mod supports, and the mods it edits renamed their own fields on the way; every one of those is carried across on startup, checked against the version actually running
- Both lists scroll with the game's own scroll bar, by wheel or by dragging it

### Fixed

- Several of Create's machine editors offered more slots than the machine itself accepts, so a recipe built in them came back as invalid. Every type now has the shape its own machine allows — the spout and manual application take one item and one fluid, the press gives two results, the crushing wheels give seven
- An ingredient that lists several alternatives — Create's compatibility recipes, and any shaped recipe whose key holds a list — was emptied when the recipe was saved
- In a recipe sequence: the filling step refused fluids, the step buttons would not step backwards, a result pool's weights were flattened to even, and part of the pool could not be reached
- A recipe type the editor did not know but whose shape it resembled was opened as that shape, and lost fields it did not model when saved
- Create's deployer and manual application lost their "keep held item" setting, which can now be set from the editor
- On 1.20.1 the editor opened over an undimmed world
- `/sce` commands reported that the command did not exist when the player was not in creative mode, and stayed missing after switching to creative until the player reconnected. They now say what they need

### Changed

- The editor's layout was reworked: a recipe type's own fields are measured, captioned and centred in one place, the space around the recipe is shared evenly, and the completion list stays inside the screen
- A tag in a slot shows the items it holds through the same list a recipe viewer would show, so an ingredient that names several things no longer appears to name only the first

---

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
