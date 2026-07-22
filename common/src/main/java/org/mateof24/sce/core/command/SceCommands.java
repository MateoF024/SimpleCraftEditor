package org.mateof24.sce.core.command;

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
import org.mateof24.sce.core.SceDebug;
import org.mateof24.sce.core.state.RecipeState;
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
     * The same rule the editor screens follow, so a command cannot do what the interface refuses.
     *
     * <p>The console and command blocks are exempt from the game-mode half: they have no game mode, and
     * the rule exists to stop a player editing recipes while playing, not to stop a server operator
     * scripting one.
     */
    private static boolean mayUse(CommandSourceStack source) {
        if (!source.hasPermission(2)) {
            return false;
        }
        return !(source.getEntity() instanceof Player player) || SceNetworking.mayEdit(player);
    }

    private static void build(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("sce").requires(SceCommands::mayUse)
                .then(Commands.literal("disable")
                        .then(Commands.argument("recipe", ResourceLocationArgument.id())
                                .suggests(suggestEditable())
                                .executes(SceCommands::disable)))
                .then(Commands.literal("enable")
                        .then(Commands.argument("recipe", ResourceLocationArgument.id())
                                .suggests(suggestDisabled())
                                .executes(SceCommands::enable)))
                .then(Commands.literal("clone")
                        .then(Commands.argument("source", ResourceLocationArgument.id())
                                .suggests(suggestEditable())
                                .then(Commands.argument("target", ResourceLocationArgument.id())
                                        .executes(SceCommands::cloneRecipe))))
                .then(Commands.literal("delete")
                        .then(Commands.argument("recipe", ResourceLocationArgument.id())
                                .suggests(suggestGenerated())
                                .executes(SceCommands::deleteGenerated)))
                .then(Commands.literal("list")
                        .then(Commands.literal("disabled").executes(context -> list(context, "disabled")))
                        .then(Commands.literal("generated").executes(context -> list(context, "generated"))))
                .then(Commands.literal("reload").executes(SceCommands::reload))
                .then(buildDebug()));
    }

    /**
     * {@code /sce debug true|false} toggles all logging; {@code /sce debug <category> true|false} scopes
     * it; {@code /sce debug status} prints the state and an immediate diagnostic dump. The toggle persists
     * so the next startup begins with it — the load-a-recipe failure happens before a command can run.
     */
    private static com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> buildDebug() {
        com.mojang.brigadier.builder.LiteralArgumentBuilder<CommandSourceStack> debug = Commands.literal("debug");
        debug.then(Commands.literal("status").executes(SceCommands::debugStatus));
        // Reports, per authored recipe, whether it reached the live recipe manager — the direct answer to
        // "the editor saved it but the game does not have it", which is what a heavy modpack causes.
        // Where a specific id stands in the live manager vs our state — for a recipe that keeps crafting
        // after it was deleted, this says whether the server still has it or the problem is client-side.
        debug.then(Commands.literal("find")
                .then(Commands.argument("recipe", ResourceLocationArgument.id())
                        .suggests(suggestExisting())
                        .executes(context -> {
                            ResourceLocation id = ResourceLocationArgument.getId(context, "recipe");
                            String report = RecipeStateManager.INSTANCE.findRecipe(context.getSource().getServer(), id);
                            for (String line : report.split("\n")) {
                                context.getSource().sendSuccess(() -> Component.literal(line), false);
                            }
                            org.mateof24.sce.SimpleCraftEditor.LOGGER.info("[SCE-DBG] {}", report);
                            return 1;
                        })));
        debug.then(Commands.literal("verify").executes(context -> {
            String report = RecipeStateManager.INSTANCE.verifyGeneratedInManager(context.getSource().getServer());
            for (String line : report.split("\n")) {
                context.getSource().sendSuccess(() -> Component.literal(line), false);
            }
            org.mateof24.sce.SimpleCraftEditor.LOGGER.info("[SCE-DBG] {}", report);
            return 1;
        }));
        debug.then(Commands.argument("on", com.mojang.brigadier.arguments.BoolArgumentType.bool())
                .executes(context -> setDebug(context, null)));
        for (SceDebug.Category category : SceDebug.Category.values()) {
            debug.then(Commands.literal(category.name().toLowerCase(java.util.Locale.ROOT))
                    .then(Commands.argument("on", com.mojang.brigadier.arguments.BoolArgumentType.bool())
                            .executes(context -> setDebug(context, category))));
        }
        return debug;
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
        // Checked here rather than left to the engine's own refusal so the reason can be given: the
        // command would otherwise report the same failure as a recipe that simply does not exist.
        if (!RecipeStateManager.INSTANCE.isEditable(id)) {
            context.getSource().sendFailure(Component.translatable("sce.msg.not_editable", id.toString()));
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
