package com.nakero.croproute;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.util.List;

public class RouteNameScreen extends Screen {
    private final List<RoutePoint> points;
    private final String dimension;
    private TextFieldWidget nameField;

    public RouteNameScreen(List<RoutePoint> points, String dimension) {
        super(Text.literal("Guardar ruta"));
        this.points = points;
        this.dimension = dimension;
    }

    @Override
    protected void init() {
        int boxWidth = 240;
        int x = (width - boxWidth) / 2;
        int y = height / 2 - 20;

        nameField = new TextFieldWidget(
                textRenderer,
                x,
                y,
                boxWidth,
                20,
                Text.literal("Nombre de la ruta")
        );
        nameField.setMaxLength(40);
        nameField.setText(RouteManager.makeUniqueName("Ruta"));
        addDrawableChild(nameField);
        setInitialFocus(nameField);

        addDrawableChild(ButtonWidget.builder(
                Text.literal("Guardar"),
                button -> saveAndClose()
        ).dimensions(x, y + 32, 116, 20).build());

        addDrawableChild(ButtonWidget.builder(
                Text.literal("Cancelar"),
                button -> cancelAndClose()
        ).dimensions(x + 124, y + 32, 116, 20).build());
    }

    private void saveAndClose() {
        RouteData route = RouteManager.addRoute(nameField.getText(), dimension, points);
        CropRouteClient.showActionBar(
                "Ruta guardada: " + route.name + " | " + route.points.size() + " puntos"
        );
        if (client != null) {
            client.setScreen(null);
        }
    }

    private void cancelAndClose() {
        CropRouteClient.showActionBar("Grabación descartada");
        if (client != null) {
            client.setScreen(null);
        }
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER) {
            saveAndClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);

        context.drawCenteredTextWithShadow(
                textRenderer,
                Text.literal("Ponle un nombre a esta ruta"),
                width / 2,
                height / 2 - 58,
                0xFFFFFF
        );

        context.drawCenteredTextWithShadow(
                textRenderer,
                Text.literal(points.size() + " puntos grabados"),
                width / 2,
                height / 2 - 43,
                0xBFBFBF
        );

        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
