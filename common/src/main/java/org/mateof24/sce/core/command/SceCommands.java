package org.mateof24.sce.core.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.commands.arguments.ResourceLocationArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import org.mateof24.sce.core.state.RecipeState;
import org.mateof24.sce.core.state.RecipeStateManager;

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

    private static void build(CommandDispatcher<CommandSourceStack> dispatcher) {
        dispatcher.register(Commands.literal("sce").requires(source -> source.hasPermission(2))
                .then(Commands.literal("disable")
                        .then(Commands.argument("recipe", ResourceLocationArgument.id())
                                .suggests(suggestExisting())
                                .executes(SceCommands::disable)))
                .then(Commands.literal("enable")
                        .then(Commands.argument("recipe", ResourceLocationArgument.id())
                                .suggests(suggestDisabled())
                                .executes(SceCommands::enable)))
                .then(Commands.literal("clone")
                        .then(Commands.argument("source", ResourceLocationArgument.id())
                                .suggests(suggestExisting())
                                .then(Commands.argument("target", ResourceLocationArgument.id())
                                        .executes(SceCommands::cloneRecipe))))
                .then(Commands.literal("delete")
                        .then(Commands.argument("recipe", ResourceLocationArgument.id())
                                .suggests(suggestGenerated())
                                .executes(SceCommands::deleteGenerated)))
                .then(Commands.literal("list")
                        .then(Commands.literal("disabled").executes(context -> list(context, "disabled")))
                        .then(Commands.literal("generated").executes(context -> list(context, "generated"))))
                .then(Commands.literal("reload").executes(SceCommands::reload)));
    }

    private static int disable(CommandContext<CommandSourceStack> context) {
        ResourceLocation id = ResourceLocationArgument.getId(context, "recipe");
        boolean ok = RecipeStateManager.INSTANCE.disable(context.getSource().getServer(), id);
        if (ok) {
            context.getSource().sendSuccess(() -> Component.literal("Disabled recipe " + id), true);
        } else {
            context.getSource().sendFailure(Component.literal("Recipe " + id + " is not loaded, or is already disabled."));
        }
        return ok ? 1 : 0;
    }

    private static int enable(CommandContext<CommandSourceStack> context) {
        ResourceLocation id = ResourceLocationArgument.getId(context, "recipe");
        boolean ok = RecipeStateManager.INSTANCE.enable(context.getSource().getServer(), id);
        if (ok) {
            context.getSource().sendSuccess(() -> Component.literal("Re-enabled recipe " + id), true);
        } else {
            context.getSource().sendFailure(Component.literal("Recipe " + id + " was not disabled."));
        }
        return ok ? 1 : 0;
    }

    private static int cloneRecipe(CommandContext<CommandSourceStack> context) {
        ResourceLocation source = ResourceLocationArgument.getId(context, "source");
        ResourceLocation target = ResourceLocationArgument.getId(context, "target");
        boolean ok = RecipeStateManager.INSTANCE.cloneRecipe(context.getSource().getServer(), source, target);
        if (ok) {
            context.getSource().sendSuccess(() -> Component.literal("Cloned " + source + " into new recipe " + target), true);
        } else {
            context.getSource().sendFailure(Component.literal("No cached JSON for " + source + " to clone from."));
        }
        return ok ? 1 : 0;
    }

    private static int deleteGenerated(CommandContext<CommandSourceStack> context) {
        ResourceLocation id = ResourceLocationArgument.getId(context, "recipe");
        boolean ok = RecipeStateManager.INSTANCE.deleteGenerated(context.getSource().getServer(), id);
        if (ok) {
            context.getSource().sendSuccess(() -> Component.literal("Deleted generated recipe " + id), true);
        } else {
            context.getSource().sendFailure(Component.literal("No generated recipe with id " + id + "."));
        }
        return ok ? 1 : 0;
    }

    private static int reload(CommandContext<CommandSourceStack> context) {
        RecipeStateManager.INSTANCE.forceReapply(context.getSource().getServer());
        context.getSource().sendSuccess(() -> Component.literal("Re-applied recipe state."), true);
        return 1;
    }

    private static int list(CommandContext<CommandSourceStack> context, String which) {
        RecipeState state = RecipeStateManager.INSTANCE.state();
        Collection<ResourceLocation> ids = which.equals("disabled") ? state.disabled().keySet() : state.generated().keySet();
        if (ids.isEmpty()) {
            context.getSource().sendSuccess(() -> Component.literal("No " + which + " recipes."), false);
            return 0;
        }
        String joined = ids.stream().map(ResourceLocation::toString).sorted().collect(Collectors.joining(", "));
        context.getSource().sendSuccess(() -> Component.literal(ids.size() + " " + which + ": " + joined), false);
        return ids.size();
    }

    private static SuggestionProvider<CommandSourceStack> suggestExisting() {
        return (context, builder) -> SharedSuggestionProvider.suggestResource(
                context.getSource().getServer().getRecipeManager().getRecipeIds(), builder);
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
