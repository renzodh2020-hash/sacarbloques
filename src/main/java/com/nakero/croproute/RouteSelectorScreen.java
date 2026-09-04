package com.nakero.croproute;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

import java.util.List;

public class RouteSelectorScreen extends Screen {
    private static final int ROUTES_PER_PAGE = 6;
    private int page;

    public RouteSelectorScreen() {
        this(0);
    }

    private RouteSelectorScreen(int page) {
        super(Text.literal("Rutas guardadas"));
        this.page = Math.max(0, page);
    }

    @Override
    protected void init() {
        List<RouteData> routes = RouteManager.getRoutesNewestFirst();
        int maxPage = Math.max(0, (routes.size() - 1) / ROUTES_PER_PAGE);
        page = Math.min(page, maxPage);

        int panelWidth = Math.min(330, width - 30);
        int x = (width - panelWidth) / 2;
        int startY = Math.max(50, height / 2 - 92);

        int from = page * ROUTES_PER_PAGE;
        int to = Math.min(routes.size(), from + ROUTES_PER_PAGE);

        for (int i = from; i < to; i++) {
            RouteData route = routes.get(i);
            RouteData selected = RouteManager.getSelectedRoute();
            boolean isSelected = selected != null && selected.id.equals(route.id);

            String label = (isSelected ? "▶ " : "")
                    + route.name
                    + "  [" + route.points.size() + " pts]";

            int row = i - from;

            addDrawableChild(ButtonWidget.builder(
                    Text.literal(label),
                    button -> {
                        RouteManager.select(route.id);
                        CropRouteClient.showActionBar("Ruta seleccionada: " + route.name);
                        if (client != null) {
                            client.setScreen(null);
                        }
                    }
            ).dimensions(x, startY + row * 24, panelWidth, 20).build());
        }

        int bottomY = startY + ROUTES_PER_PAGE * 24 + 7;

        if (page > 0) {
            addDrawableChild(ButtonWidget.builder(
                    Text.literal("< Anterior"),
                    button -> {
                        if (client != null) {
                            client.setScreen(new RouteSelectorScreen(page - 1));
                        }
                    }
            ).dimensions(x, bottomY, 90, 20).build());
        }

        if (page < maxPage) {
            addDrawableChild(ButtonWidget.builder(
                    Text.literal("Siguiente >"),
                    button -> {
                        if (client != null) {
                            client.setScreen(new RouteSelectorScreen(page + 1));
                        }
                    }
            ).dimensions(x + panelWidth - 90, bottomY, 90, 20).build());
        }

        RouteData selected = RouteManager.getSelectedRoute();
        boolean canDelete = selected != null;

        ButtonWidget deleteButton = ButtonWidget.builder(
                Text.literal("Eliminar seleccionada"),
                button -> {
                    RouteData current = RouteManager.getSelectedRoute();
                    if (current != null) {
                        String deletedName = current.name;
                        RouteManager.delete(current.id);
                        CropRouteClient.showActionBar("Ruta eliminada: " + deletedName);
                        if (client != null) {
                            client.setScreen(new RouteSelectorScreen(page));
                        }
                    }
                }
        ).dimensions(x + (panelWidth - 150) / 2, bottomY + 27, 150, 20).build();
        deleteButton.active = canDelete;
        addDrawableChild(deleteButton);

        addDrawableChild(ButtonWidget.builder(
                Text.literal("Cerrar"),
                button -> {
                    if (client != null) {
                        client.setScreen(null);
                    }
                }
        ).dimensions(x + (panelWidth - 90) / 2, bottomY + 54, 90, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        renderBackground(context, mouseX, mouseY, delta);

        context.drawCenteredTextWithShadow(
                textRenderer,
                Text.literal("Crop Route — elige una ruta"),
                width / 2,
                22,
                0xFFFFFF
        );

        RouteData selected = RouteManager.getSelectedRoute();
        String selectedText = selected == null
                ? "Seleccionada: ninguna"
                : "Seleccionada: " + selected.name
                    + " | ~" + Math.round(selected.approximateLength()) + " bloques";

        context.drawCenteredTextWithShadow(
                textRenderer,
                Text.literal(selectedText),
                width / 2,
                37,
                0xCFCFCF
        );

        if (RouteManager.getRoutes().isEmpty()) {
            context.drawCenteredTextWithShadow(
                    textRenderer,
                    Text.literal("Todavía no hay rutas. Presiona J en el mundo para grabar una."),
                    width / 2,
                    height / 2 - 5,
                    0xFFFFFF
            );
        }

        super.render(context, mouseX, mouseY, delta);
    }
}
