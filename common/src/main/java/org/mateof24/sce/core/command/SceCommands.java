package org.mateof24.sce.core.command;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.world.entity.player.Player;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.core.registries.BuiltInRegistries;
import org.mateof24.sce.core.SceDebug;
import org.mateof24.sce.core.ScePerf;
import org.mateof24.sce.core.state.RecipeOutputIndex;
import org.mateof24.sce.core.state.RecipeState;
import org.mateof24.sce.core.anvil.AnvilRule;
import org.mateof24.sce.core.anvil.AnvilRules;
import org.mateof24.sce.core.state.RecipeStateManager;
import org.mateof24.sce.net.SceNetworking;

import java.util.Collection;
import java.util.stream.Collectors;

/**
 * Temporary operator-only debug commands that drive the recipe-state engine directly, before the
 * in-game editor UI exists. Registered once via Architectury's loader-agnostic command event.
 */
public final class SceCommands {
    private SceCommands() {
    }

    public static void register() {
        CommandRegistrationEvent.EVENT.register((dispatcher, registry, selection) -> build(dispatcher));
    }

    /**
     * Who may see the command at all: the permission half of the editor's own rule.
     *
     * <p>The game-mode half is checked when a command runs rather than here, because Brigadier does not
     * refuse a node whose requirement fails - it hides it, and the player is told the command does not
     * exist. That is a lie: the command exists and the player has the permission for it. It was also
     * stale, since the command tree is sent on join and again on a permission change, but not when a
     * player switches game mode, so someone who went into creative kept being told there was no such
     * command until they reconnected.
     */
    private static boolean mayUse(CommandSourceStack source) {
        return source.hasPermission(2);
    }

    /**
     * Whether the source may edit recipes right now, which is the half that depends on the game mode.
     *
     * <p>The console and command blocks are exempt: they have no game mode, and the rule exists to stop
     * a player editing recipes while playing, not to stop a server operator scripting one.
     */
    private static boolean mayEditNow(CommandSourceStack source) {
        return !(source.getEntity() instanceof Player player) || SceNetworking.mayEdit(player);
    }

    /**
     * The same command, refusing in words when the game mode says no.
     *
     * <p>Wrapped around every endpoint rather than checked inside each one, so that a command added
     * later cannot forget it.
     */
    private static Command<CommandSourceStack> gated(Command<CommandSourceStack> command) {
        return context -> {
            if (!mayEditNow(context.getSource())) {
                context.getSource().sendFailure(Component.translatable("sce.cmd.creative_only"));
                return 0;
            }
            return command.run(context);
        };
    }

    private static void build(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("sce").requires(SceCommands::mayUse)
                .then(Commands.literal("disable")
                        .then(Commands.argument("recipe", ResourceLocationArgument.id())
                                .suggests(suggestEditable())
                                .executes(gated(SceCommands::disable))))
                .then(Commands.literal("enable")
                        .then(Commands.argument("recipe", ResourceLocationArgument.id())
                                .suggests(suggestDisabled())
                                .executes(gated(SceCommands::enable))))
                .then(Commands.literal("clone")
                        .then(Commands.argument("source", ResourceLocationArgument.id())
                                .suggests(suggestEditable())
                                .then(Commands.argument("target", ResourceLocationArgument.id())
                                        .executes(gated(SceCommands::cloneRecipe)))))
                .then(Commands.literal("delete")
                        .then(Commands.argument("recipe", ResourceLocationArgument.id())
                                .suggests(suggestGenerated())
                                .executes(gated(SceCommands::deleteGenerated))))
                .then(Commands.literal("list")
                        .then(Commands.literal("disabled").executes(gated(context -> list(context, "disabled"))))
                        .then(Commands.literal("generated").executes(gated(context -> list(context, "generated")))))
                .then(Commands.literal("reload").executes(gated(SceCommands::reload)))
                .then(buildAnvil())
                .then(buildDebug()));
    }

    /**
     * {@code /sce anvil} lists the repair rules; {@code /sce anvil test <item> <material>} says what the
     * anvil would do with those two and why.
     */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> buildAnvil() {
        com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> anvil =
                Commands.literal("anvil");
        anvil.executes(gated(SceCommands::anvilList));
        anvil.then(Commands.literal("test")
                .then(Commands.argument("item", ResourceLocationArgument.id())
                        .suggests(suggestItems())
                        .then(Commands.argument("material", ResourceLocationArgument.id())
                                .suggests(suggestItems())
                                .executes(gated(SceCommands::anvilTest)))));
        return anvil;
    }

    private static int anvilList(CommandContext<CommandSourceStack> context) {
        java.util.List<AnvilRule> rules = AnvilRules.INSTANCE.rules();
        if (rules.isEmpty()) {
            context.getSource().sendSuccess(() -> Component.translatable("sce.cmd.anvil_none"), false);
            return 1;
        }
        context.getSource().sendSuccess(() ->
                Component.translatable("sce.cmd.anvil_count", rules.size()), false);
        for (AnvilRule rule : rules) {
            context.getSource().sendSuccess(() -> Component.literal("  " + rule.target() + " \u2192 "
                    + rule.material() + "  (" + rule.mode().key() + ")"), false);
        }
        return rules.size();
    }

    /**
     * Answers for one pairing, and says which of the two answers it is: a rule decided it, or nothing
     * did and the game's own table was left to answer.
     */
    private static int anvilTest(CommandContext<CommandSourceStack> context) {
        ResourceLocation itemId = ResourceLocationArgument.getId(context, "item");
        ResourceLocation materialId = ResourceLocationArgument.getId(context, "material");
        if (!BuiltInRegistries.ITEM.containsKey(itemId) || !BuiltInRegistries.ITEM.containsKey(materialId)) {
            context.getSource().sendFailure(Component.literal("No such item: "
                    + (BuiltInRegistries.ITEM.containsKey(itemId) ? materialId : itemId)));
            return 0;
        }
        net.minecraft.world.item.ItemStack target =
                new net.minecraft.world.item.ItemStack(BuiltInRegistries.ITEM.get(itemId));
        net.minecraft.world.item.ItemStack material =
                new net.minecraft.world.item.ItemStack(BuiltInRegistries.ITEM.get(materialId));
        AnvilRules.Verdict verdict = AnvilRules.INSTANCE.verdict(target, material);
        boolean vanilla = target.getItem().isValidRepairItem(target, material);
        String line = switch (verdict) {
            case YES -> "a rule allows it";
            case NO -> "a rule forbids it (one of them replaces the game's material)";
            case UNKNOWN -> "no rule mentions it, so the game answers: " + (vanilla ? "yes" : "no");
        };
        context.getSource().sendSuccess(() -> Component.literal(
                itemId + " mended with " + materialId + ": "
                        + (verdict == AnvilRules.Verdict.UNKNOWN ? vanilla : verdict == AnvilRules.Verdict.YES)
                        + " - " + line), false);
        return 1;
    }

    /**
     * {@code /sce debug true|false} toggles all logging; {@code /sce debug <category> true|false} scopes
     * it; {@code /sce debug status} prints the state and an immediate diagnostic dump. The toggle persists
     * so the next startup begins with it — the load-a-recipe failure happens before a command can run.
     */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> buildDebug() {
        com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> debug = Commands.literal("debug");
        debug.then(Commands.literal("status").executes(gated(SceCommands::debugStatus)));
        // Reports, per authored recipe, whether it reached the live recipe manager — the direct answer to
        // "the editor saved it but the game does not have it", which is what a heavy modpack causes.
        // Where a specific id stands in the live manager vs our state — for a recipe that keeps crafting
        // after it was deleted, this says whether the server still has it or the problem is client-side.
        debug.then(Commands.literal("find")
                .then(Commands.argument("recipe", ResourceLocationArgument.id())
                        .suggests(suggestExisting())
                        .executes(gated(context -> {
                            ResourceLocation id = ResourceLocationArgument.getId(context, "recipe");
                            String report = RecipeStateManager.INSTANCE.findRecipe(context.getSource().getServer(), id);
                            for (String line : report.split("\n")) {
                                context.getSource().sendSuccess(() -> Component.literal(line), false);
                            }
                            org.mateof24.sce.SimpleCraftEditor.LOGGER.info("[SCE-DBG] {}", report);
                            return 1;
                        }))));
        // The direct answer to "the key does not find a recipe I know exists": every recipe that makes an
        // item, whether it makes it as its main result or as one of its other outputs, and whether it can
        // be edited. A machine recipe usually lists the interesting item second or third.
        debug.then(Commands.literal("produces")
                .then(Commands.argument("item", ResourceLocationArgument.id())
                        .suggests(suggestItems())
                        .executes(gated(SceCommands::debugProduces))));
        debug.then(Commands.literal("verify").executes(gated(context -> {
            String report = RecipeStateManager.INSTANCE.verifyGeneratedInManager(context.getSource().getServer());
            for (String line : report.split("\n")) {
                context.getSource().sendSuccess(() -> Component.literal(line), false);
            }
            org.mateof24.sce.SimpleCraftEditor.LOGGER.info("[SCE-DBG] {}", report);
            return 1;
        })));
        debug.then(Commands.argument("on", com.mojang.brigadier.arguments.BoolArgumentType.bool())
                .executes(gated(context -> setDebug(context, null))));
        for (SceDebug.Category category : SceDebug.Category.values()) {
            com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> literal =
                    Commands.literal(category.name().toLowerCase(java.util.Locale.ROOT))
                            .then(Commands.argument("on", com.mojang.brigadier.arguments.BoolArgumentType.bool())
                                    .executes(gated(context -> setDebug(context, category))));
            if (category == SceDebug.Category.PERF) {
                // Timing is the one category that produces a report worth reading on its own, so a bare
                // "/sce debug perf" prints it. Turning it on and off still works like every other area.
                literal.executes(gated(SceCommands::perfReport))
                        .then(Commands.literal("reset").executes(gated(SceCommands::perfReset)));
            }
            debug.then(literal);
        }
        return debug;
    }

    /**
     * Prints everything the stopwatch has recorded. Sent to whoever ran it and written to the log as well,
     * because the log is what ends up attached to a bug report.
     */
    private static int perfReport(CommandContext<CommandSourceStack> context) {
        String report = ScePerf.report();
        for (String line : report.split("\n")) {
            context.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        org.mateof24.sce.SimpleCraftEditor.LOGGER.info("[SCE-DBG/PERF] {}", report);
        return 1;
    }

    /** Clears the measurements, so the next thing tried is timed on its own rather than mixed with the last. */
    private static int perfReset(CommandContext<CommandSourceStack> context) {
        ScePerf.reset();
        context.getSource().sendSuccess(() -> Component.translatable("sce.cmd.perf_reset"), true);
        return 1;
    }

    private static int debugProduces(CommandContext<CommandSourceStack> context) {
        ResourceLocation itemId = ResourceLocationArgument.getId(context, "item");
        if (!BuiltInRegistries.ITEM.containsKey(itemId)) {
            context.getSource().sendFailure(Component.literal("No such item: " + itemId));
            return 0;
        }
        String report = RecipeOutputIndex.INSTANCE.describe(
                context.getSource().getServer(), BuiltInRegistries.ITEM.get(itemId));
        for (String line : report.split("\n")) {
            context.getSource().sendSuccess(() -> Component.literal(line), false);
        }
        org.mateof24.sce.SimpleCraftEditor.LOGGER.info("[SCE-DBG] {}", report);
        return 1;
    }

    private static SuggestionProvider<CommandSourceStack> suggestItems() {
        return (context, builder) -> SharedSuggestionProvider.suggestResource(BuiltInRegistries.ITEM.keySet(), builder);
    }

    private static int setDebug(CommandContext<CommandSourceStack> context, SceDebug.Category category) {
        boolean on = com.mojang.brigadier.arguments.BoolArgumentType.getBool(context, "on");
        if (category == null) {
            SceDebug.setAll(on);
        } else {
            SceDebug.set(category, on);
        }
        SceDebug.persist();
        SceNetworking.syncDebugToAll(context.getSource().getServer());
        context.getSource().sendSuccess(() -> Component.translatable("sce.cmd.debug", SceDebug.describe()), true);
        return 1;
    }

    private static int debugStatus(CommandContext<CommandSourceStack> context) {
        CommandSourceStack source = context.getSource();
        MinecraftServer server = source.getServer();
        source.sendSuccess(() -> Component.translatable("sce.cmd.debug", SceDebug.describe()), false);
        // The numbers that settle the load-a-recipe bug: does the raw cache hold anything, and how does it
        // compare to what the RecipeManager actually has.
        int live = server.getRecipeManager().getRecipes().size();
        int rawCache = RecipeStateManager.INSTANCE.rawJsonCacheSize();
        source.sendSuccess(() -> Component.translatable("sce.cmd.debug_status", live, rawCache,
                RecipeStateManager.INSTANCE.baseSnapshotSize()), false);
        // Spelled out rather than left to the tab-completion, so someone reading a bug report knows what
        // to turn on without having to learn the command first.
        source.sendSuccess(() -> Component.translatable("sce.cmd.debug_help"), false);
        SceDebug.reportEnvironment();
        return 1;
    }

    private static int disable(CommandContext<CommandSourceStack> context) {
        ResourceLocation id = ResourceLocationArgument.getId(context, "recipe");
        // Checked here rather than left to the engine's own refusal so the reason can be given, and a
        // recipe that simply does not exist says that rather than borrowing the script one's excuse.
        RecipeStateManager.Editability verdict =
                RecipeStateManager.INSTANCE.editability(context.getSource().getServer(), id);
        if (verdict.refusal() != null) {
            context.getSource().sendFailure(Component.translatable(verdict.refusal(), id.toString()));
            return 0;
        }
        boolean ok = RecipeStateManager.INSTANCE.disable(context.getSource().getServer(), id);
        if (ok) {
            context.getSource().sendSuccess(() -> Component.translatable("sce.cmd.disabled", id.toString()), true);
        } else {
            context.getSource().sendFailure(Component.translatable("sce.cmd.disable_failed", id.toString()));
        }
        return ok ? 1 : 0;
    }

    private static int enable(CommandContext<CommandSourceStack> context) {
        ResourceLocation id = ResourceLocationArgument.getId(context, "recipe");
        boolean ok = RecipeStateManager.INSTANCE.enable(context.getSource().getServer(), id);
        if (ok) {
            context.getSource().sendSuccess(() -> Component.translatable("sce.cmd.enabled", id.toString()), true);
        } else {
            context.getSource().sendFailure(Component.translatable("sce.cmd.enable_failed", id.toString()));
        }
        return ok ? 1 : 0;
    }

    private static int cloneRecipe(CommandContext<CommandSourceStack> context) {
        ResourceLocation source = ResourceLocationArgument.getId(context, "source");
        ResourceLocation target = ResourceLocationArgument.getId(context, "target");
        boolean ok = RecipeStateManager.INSTANCE.cloneRecipe(context.getSource().getServer(), source, target);
        if (ok) {
            context.getSource().sendSuccess(() -> Component.translatable("sce.cmd.cloned", source.toString(), target.toString()), true);
        } else {
            context.getSource().sendFailure(Component.translatable("sce.cmd.clone_failed", source.toString()));
        }
        return ok ? 1 : 0;
    }

    private static int deleteGenerated(CommandContext<CommandSourceStack> context) {
        ResourceLocation id = ResourceLocationArgument.getId(context, "recipe");
        boolean ok = RecipeStateManager.INSTANCE.deleteGenerated(context.getSource().getServer(), id);
        if (ok) {
            context.getSource().sendSuccess(() -> Component.translatable("sce.cmd.deleted", id.toString()), true);
        } else {
            context.getSource().sendFailure(Component.translatable("sce.cmd.delete_failed", id.toString()));
        }
        return ok ? 1 : 0;
    }

    private static int reload(CommandContext<CommandSourceStack> context) {
        RecipeStateManager.INSTANCE.forceReapply(context.getSource().getServer());
        context.getSource().sendSuccess(() -> Component.translatable("sce.cmd.reloaded"), true);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> context, String which) {
        RecipeState state = RecipeStateManager.INSTANCE.state();
        Collection<ResourceLocation> ids = which.equals("disabled") ? state.disabled().keySet() : state.generated().keySet();
        Component kind = Component.translatable("sce.cmd.kind." + which);
        if (ids.isEmpty()) {
            context.getSource().sendSuccess(() -> Component.translatable("sce.cmd.list_empty", kind), false);
            return 0;
        }
        String joined = ids.stream().map(ResourceLocation::toString).sorted().collect(Collectors.joining(", "));
        context.getSource().sendSuccess(() -> Component.translatable("sce.cmd.list", ids.size(), kind, joined), false);
        return ids.size();
    }

    /** Every loaded recipe, for the diagnostics, which have to be able to look at anything. */
    private static SuggestionProvider<CommandSourceStack> suggestExisting() {
        return (context, builder) -> SharedSuggestionProvider.suggestResource(
                context.getSource().getServer().getRecipeManager().getRecipeIds(), builder);
    }

    /**
     * Only the recipes that can actually be changed. Offering a script-written one would be inviting
     * exactly the thing that is refused a keystroke later.
     */
    private static SuggestionProvider<CommandSourceStack> suggestEditable() {
        return (context, builder) -> SharedSuggestionProvider.suggestResource(
                context.getSource().getServer().getRecipeManager().getRecipeIds()
                        .filter(RecipeStateManager.INSTANCE::isEditable), builder);
    }

    private static SuggestionProvider<CommandSourceStack> suggestDisabled() {
        return (context, builder) -> SharedSuggestionProvider.suggestResource(
                RecipeStateManager.INSTANCE.state().disabled().keySet(), builder);
    }

    private static SuggestionProvider<CommandSourceStack> suggestGenerated() {
        return (context, builder) -> SharedSuggestionProvider.suggestResource(
                RecipeStateManager.INSTANCE.state().generated().keySet(), builder);
    }
}
