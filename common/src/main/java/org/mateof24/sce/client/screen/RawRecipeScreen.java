package org.mateof24.sce.client.screen;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonParser;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import org.mateof24.sce.net.SceNetworking;

/**
 * Universal fallback editor: edits any recipe as raw JSON. Used for recipe types the typed editors do not
 * handle, and reachable from the typed editor via the Raw button. The server validates the JSON against the
 * real serializer when saving, so an invalid definition is rejected there.
 */
@Environment(EnvType.CLIENT)
public class RawRecipeScreen extends Screen {
    private static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private final ResourceLocation id;
    private final String initialJson;
    private MultiLineEditBox editor;
    private String status = "";

    public RawRecipeScreen(ResourceLocation id, String json) {
        super(Component.literal("Raw Recipe: " + id));
        this.id = id;
        String pretty = json;
        try {
            pretty = PRETTY.toJson(JsonParser.parseString(json));
        } catch (Exception ignored) {
            // leave the text as-is if it is not valid JSON
        }
        this.initialJson = pretty;
    }

    @Override
    protected void init() {
        int boxWidth = Math.min(width - 40, 420);
        int boxHeight = height - 92;
        editor = new MultiLineEditBox(font, width / 2 - boxWidth / 2, 40, boxWidth, boxHeight,
                Component.literal("recipe json"), Component.literal("Recipe JSON"));
        editor.setCharacterLimit(1024 * 1024);
        editor.setValue(initialJson);
        addRenderableWidget(editor);

        addRenderableWidget(Button.builder(Component.literal("Save"), b -> save())
                .bounds(width / 2 - 154, height - 40, 100, 20).build());
        addRenderableWidget(Button.builder(CommonComponents.GUI_CANCEL, b -> onClose())
                .bounds(width / 2 + 54, height - 40, 100, 20).build());
    }

    private void save() {
        String text = editor.getValue();
        try {
            JsonParser.parseString(text).getAsJsonObject();
        } catch (Exception e) {
            status = "Invalid JSON.";
            return;
        }
        SceNetworking.sendSave(id, text);
        status = "Sent " + id + " to the server.";
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        renderBackground(graphics);
        graphics.drawCenteredString(font, title, width / 2, 20, 0xFFFFFF);
        super.render(graphics, mouseX, mouseY, partialTick);
        if (!status.isEmpty()) {
            graphics.drawCenteredString(font, status, width / 2, height - 58, 0xE0E070);
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
