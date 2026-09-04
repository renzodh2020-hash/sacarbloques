package com.nakero.croproute;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class RouteManager {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path CONFIG_DIR =
            FabricLoader.getInstance().getConfigDir().resolve("crop-route");
    private static final Path ROUTES_FILE = CONFIG_DIR.resolve("routes.json");

    private static RouteStore store = new RouteStore();

    private RouteManager() {
    }

    public static void load() {
        try {
            Files.createDirectories(CONFIG_DIR);

            if (!Files.exists(ROUTES_FILE)) {
                store = new RouteStore();
                save();
                return;
            }

            try (Reader reader = Files.newBufferedReader(ROUTES_FILE)) {
                RouteStore loaded = GSON.fromJson(reader, RouteStore.class);
                store = loaded != null ? loaded : new RouteStore();
            }

            if (store.routes == null) {
                store.routes = new ArrayList<>();
            }

            // Limpieza defensiva por si se editó el JSON manualmente.
            store.routes.removeIf(route ->
                    route == null || route.id == null || route.points == null);

            if (store.selectedRouteId != null && getSelectedRoute() == null) {
                store.selectedRouteId = null;
            }
        } catch (Exception e) {
            System.err.println("[CropRoute] No se pudieron cargar las rutas: " + e.getMessage());
            store = new RouteStore();
        }
    }

    public static void save() {
        try {
            Files.createDirectories(CONFIG_DIR);
            try (Writer writer = Files.newBufferedWriter(ROUTES_FILE)) {
                GSON.toJson(store, writer);
            }
        } catch (IOException e) {
            System.err.println("[CropRoute] No se pudieron guardar las rutas: " + e.getMessage());
        }
    }

    public static List<RouteData> getRoutes() {
        return store.routes;
    }

    public static List<RouteData> getRoutesNewestFirst() {
        List<RouteData> copy = new ArrayList<>(store.routes);
        copy.sort(Comparator.comparingLong((RouteData r) -> r.createdAt).reversed());
        return copy;
    }

    public static RouteData getSelectedRoute() {
        if (store.selectedRouteId == null) {
            return null;
        }

        for (RouteData route : store.routes) {
            if (store.selectedRouteId.equals(route.id)) {
                return route;
            }
        }
        return null;
    }

    public static void select(String routeId) {
        store.selectedRouteId = routeId;
        save();
    }

    public static RouteData addRoute(String requestedName, String dimension, List<RoutePoint> points) {
        String name = makeUniqueName(sanitizeName(requestedName));
        RouteData route = new RouteData(name, dimension, points);
        store.routes.add(route);
        store.selectedRouteId = route.id;
        save();
        return route;
    }

    public static void delete(String routeId) {
        store.routes.removeIf(route -> route.id.equals(routeId));

        if (routeId != null && routeId.equals(store.selectedRouteId)) {
            store.selectedRouteId = store.routes.isEmpty()
                    ? null
                    : store.routes.get(store.routes.size() - 1).id;
        }

        save();
    }

    public static String makeUniqueName(String baseName) {
        String base = sanitizeName(baseName);
        String candidate = base;
        int number = 2;

        while (nameExists(candidate)) {
            candidate = base + " (" + number + ")";
            number++;
        }

        return candidate;
    }

    private static boolean nameExists(String name) {
        for (RouteData route : store.routes) {
            if (route.name != null && route.name.equalsIgnoreCase(name)) {
                return true;
            }
        }
        return false;
    }

    private static String sanitizeName(String name) {
        if (name == null) {
            return "Ruta";
        }

        String clean = name.trim();
        if (clean.isEmpty()) {
            clean = "Ruta";
        }

        if (clean.length() > 40) {
            clean = clean.substring(0, 40);
        }

        return clean;
    }
}
