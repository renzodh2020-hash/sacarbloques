package com.nakero.croproute;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.math.MathHelper;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;

public class CropRouteClient implements ClientModInitializer {
    private static final double RECORD_SPACING = 0.55D;
    private static final double REACH_DISTANCE = 1.15D;
    private static final double RECOVERY_DISTANCE = 4.5D;
    private static final float MAX_YAW_CHANGE_PER_TICK = 6.0F;

    private static KeyBinding recordKey;
    private static KeyBinding routesKey;
    private static KeyBinding playKey;

    private static boolean recording = false;
    private static boolean playing = false;

    private static final List<RoutePoint> recordingPoints = new ArrayList<>();
    private static String recordingDimension = "";

    private static int targetIndex = 0;
    private static long playbackTicks = 0L;

    @Override
    public void onInitializeClient() {
        RouteManager.load();

        recordKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.crop_route.record",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_J,
                "category.crop_route"
        ));

        routesKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.crop_route.routes",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_K,
                "category.crop_route"
        ));

        playKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.crop_route.play",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_O,
                "category.crop_route"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(this::onEndTick);
    }

    private void onEndTick(MinecraftClient client) {
        if (client.player == null || client.world == null) {
            stopPlayback(client, false);
            recording = false;
            recordingPoints.clear();
            return;
        }

        handleKeys(client);

        if (recording && client.currentScreen == null) {
            recordCurrentPosition(client);
        }

        if (!playing) {
            return;
        }

        // Muy importante: no dejar W / Sprint / ataque pegados dentro de menús.
        if (client.currentScreen != null) {
            releaseAutomationKeys(client);
            return;
        }

        runSelectedRoute(client);
    }

    private void handleKeys(MinecraftClient client) {
        while (routesKey.wasPressed()) {
            if (client.currentScreen == null) {
                releaseAutomationKeys(client);
                client.setScreen(new RouteSelectorScreen());
            }
        }

        while (recordKey.wasPressed()) {
            if (client.currentScreen != null) {
                continue;
            }

            if (!recording) {
                if (playing) {
                    stopPlayback(client, false);
                }
                startRecording(client);
            } else {
                finishRecording(client);
            }
        }

        while (playKey.wasPressed()) {
            if (client.currentScreen != null) {
                continue;
            }

            if (playing) {
                stopPlayback(client, true);
            } else {
                startPlayback(client);
            }
        }
    }

    private void startRecording(MinecraftClient client) {
        recording = true;
        recordingPoints.clear();
        recordingDimension = currentDimension(client);

        RoutePoint first = new RoutePoint(
                client.player.getX(),
                client.player.getY(),
                client.player.getZ()
        );
        recordingPoints.add(first);

        showActionBar("Grabando ruta... J para terminar");
    }

    private void finishRecording(MinecraftClient client) {
        recording = false;

        // Asegura que la posición final también quede guardada.
        RoutePoint last = new RoutePoint(
                client.player.getX(),
                client.player.getY(),
                client.player.getZ()
        );

        if (recordingPoints.isEmpty()
                || last.distanceSquaredXZ(
                        recordingPoints.get(recordingPoints.size() - 1).x,
                        recordingPoints.get(recordingPoints.size() - 1).z
                ) > 0.04D) {
            recordingPoints.add(last);
        }

        if (recordingPoints.size() < 4) {
            recordingPoints.clear();
            showActionBar("Ruta demasiado corta; no se guardó");
            return;
        }

        List<RoutePoint> copy = new ArrayList<>(recordingPoints);
        recordingPoints.clear();

        // Al terminar, pide un nombre y lo guarda en config/crop-route/routes.json
        client.setScreen(new RouteNameScreen(copy, recordingDimension));
    }

    private void recordCurrentPosition(MinecraftClient client) {
        double x = client.player.getX();
        double y = client.player.getY();
        double z = client.player.getZ();

        RoutePoint last = recordingPoints.get(recordingPoints.size() - 1);
        double dx = x - last.x;
        double dy = y - last.y;
        double dz = z - last.z;

        double distanceSquared = dx * dx + dy * dy + dz * dz;

        if (distanceSquared >= RECORD_SPACING * RECORD_SPACING) {
            recordingPoints.add(new RoutePoint(x, y, z));

            if (recordingPoints.size() % 50 == 0) {
                showActionBar("Grabando... " + recordingPoints.size() + " puntos");
            }
        }
    }

    private void startPlayback(MinecraftClient client) {
        RouteData route = RouteManager.getSelectedRoute();

        if (route == null) {
            showActionBar("No hay ruta seleccionada. Presiona K.");
            return;
        }

        if (route.points == null || route.points.size() < 4) {
            showActionBar("La ruta seleccionada no tiene suficientes puntos");
            return;
        }

        String dimension = currentDimension(client);
        if (route.dimension != null
                && !route.dimension.isBlank()
                && !route.dimension.equals(dimension)) {
            showActionBar("Esta ruta pertenece a otra dimensión");
            return;
        }

        if (recording) {
            recording = false;
            recordingPoints.clear();
        }

        int nearest = findNearestPointIndex(route, client.player.getX(), client.player.getZ());
        targetIndex = (nearest + 1) % route.points.size();
        playbackTicks = 0L;
        playing = true;

        showActionBar("Ruta ACTIVADA: " + route.name);
    }

    private void stopPlayback(MinecraftClient client, boolean showMessage) {
        boolean wasPlaying = playing;
        playing = false;
        playbackTicks = 0L;
        releaseAutomationKeys(client);

        if (showMessage && wasPlaying) {
            showActionBar("Ruta DESACTIVADA");
        }
    }

    private void runSelectedRoute(MinecraftClient client) {
        RouteData route = RouteManager.getSelectedRoute();

        if (route == null || route.points == null || route.points.size() < 4) {
            stopPlayback(client, false);
            showActionBar("La ruta ya no está disponible");
            return;
        }

        if (!currentDimension(client).equals(route.dimension)) {
            stopPlayback(client, false);
            showActionBar("Ruta detenida: cambiaste de dimensión");
            return;
        }

        playbackTicks++;

        /*
         * REQUISITO PRINCIPAL:
         * Estas tres teclas se vuelven a marcar como presionadas CADA TICK.
         * Mientras la ruta esté activa y no haya menú, permanecen sostenidas.
         */
        client.options.forwardKey.setPressed(true); // W
        client.options.sprintKey.setPressed(true);  // Sprint
        client.options.attackKey.setPressed(true);  // clic izquierdo
        client.player.setSprinting(true);

        double px = client.player.getX();
        double pz = client.player.getZ();

        RoutePoint target = route.points.get(targetIndex);
        double targetDistanceSq = target.distanceSquaredXZ(px, pz);

        // Si llegamos suficientemente cerca, pasamos al siguiente punto.
        int advances = 0;
        while (targetDistanceSq <= REACH_DISTANCE * REACH_DISTANCE
                && advances < 8) {
            targetIndex = (targetIndex + 1) % route.points.size();
            target = route.points.get(targetIndex);
            targetDistanceSq = target.distanceSquaredXZ(px, pz);
            advances++;
        }

        /*
         * Recuperación automática:
         * cada 10 ticks, si nos alejamos mucho del punto esperado
         * (lag, choque, empujón), buscamos el punto de ruta más cercano.
         */
        if (playbackTicks % 10L == 0L
                && targetDistanceSq > RECOVERY_DISTANCE * RECOVERY_DISTANCE) {
            int nearest = findNearestPointIndex(route, px, pz);
            targetIndex = (nearest + 1) % route.points.size();
            target = route.points.get(targetIndex);
        }

        aimSmoothlyAt(client, target);
    }

    private void aimSmoothlyAt(MinecraftClient client, RoutePoint target) {
        double dx = target.x - client.player.getX();
        double dz = target.z - client.player.getZ();

        if (dx * dx + dz * dz < 0.0001D) {
            return;
        }

        // Minecraft: X de avance = -sin(yaw), Z de avance = cos(yaw)
        float desiredYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float currentYaw = client.player.getYaw();
        float yawError = MathHelper.wrapDegrees(desiredYaw - currentYaw);

        float step = MathHelper.clamp(
                yawError,
                -MAX_YAW_CHANGE_PER_TICK,
                MAX_YAW_CHANGE_PER_TICK
        );

        float newYaw = currentYaw + step;
        client.player.setYaw(newYaw);
        client.player.setHeadYaw(newYaw);

        // NO tocamos el pitch: tú eliges qué tan abajo mira para romper el cultivo.
    }

    private int findNearestPointIndex(RouteData route, double x, double z) {
        int nearestIndex = 0;
        double nearestDistance = Double.MAX_VALUE;

        for (int i = 0; i < route.points.size(); i++) {
            double distance = route.points.get(i).distanceSquaredXZ(x, z);
            if (distance < nearestDistance) {
                nearestDistance = distance;
                nearestIndex = i;
            }
        }

        return nearestIndex;
    }

    private static String currentDimension(MinecraftClient client) {
        return client.world.getRegistryKey().getValue().toString();
    }

    private static void releaseAutomationKeys(MinecraftClient client) {
        if (client == null) {
            return;
        }

        client.options.forwardKey.setPressed(false);
        client.options.sprintKey.setPressed(false);
        client.options.attackKey.setPressed(false);

        if (client.player != null) {
            client.player.setSprinting(false);
        }
    }

    public static void showActionBar(String message) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client.player != null) {
            client.player.sendMessage(Text.literal(message), true);
        }
    }
}
