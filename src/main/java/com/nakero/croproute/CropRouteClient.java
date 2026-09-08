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

    /*
     * ============================================================
     * CROP ROUTE V2
     * ============================================================
     *
     * - Varias rutas guardadas.
     * - J = grabar / terminar grabación.
     * - K = seleccionar ruta.
     * - O = iniciar / detener ruta.
     *
     * Durante reproducción:
     *
     * - W permanece presionada.
     * - Sprint permanece presionado.
     * - Clic izquierdo permanece presionado.
     * - La cámara gira automáticamente.
     *
     * Mejoras V2:
     *
     * - Seguimiento por segmentos.
     * - No necesita tocar exactamente cada waypoint.
     * - Look-ahead dinámico.
     * - Mejor funcionamiento con velocidades altas.
     * - Reprocesamiento de rutas rápidas.
     * - Límite máximo de 3 horas por cada inicio con O.
     */

    private static final double RECORD_MIN_SPACING = 0.08D;

    private static final double RESAMPLE_SPACING = 0.40D;

    private static final double OLD_ROUTE_GAP_LIMIT = 0.80D;

    /*
     * Look-ahead dinámico.
     */
    private static final double MIN_LOOKAHEAD = 2.0D;
    private static final double MAX_LOOKAHEAD = 8.0D;
    private static final double LOOKAHEAD_SPEED_MULTIPLIER = 10.0D;

    /*
     * Velocidad máxima de giro.
     */
    private static final float BASE_MAX_YAW_PER_TICK = 8.0F;
    private static final float MAX_YAW_PER_TICK = 22.0F;
    private static final float YAW_SPEED_MULTIPLIER = 20.0F;

    /*
     * Máximo de segmentos futuros a analizar.
     */
    private static final int MAX_FORWARD_SEARCH_SEGMENTS = 120;

    /*
     * Recuperación extrema si nos alejamos demasiado de la ruta.
     */
    private static final double EMERGENCY_RECOVERY_DISTANCE = 12.0D;

    /*
     * ============================================================
     * LÍMITE DE TIEMPO: 3 HORAS
     * ============================================================
     *
     * 3 horas
     * = 180 minutos
     * = 10,800 segundos
     * = 10,800,000 milisegundos
     */
    private static final long MAX_ROUTE_TIME_MS =
            3L * 60L * 60L * 1000L;

    /*
     * Momento exacto en el que debe finalizar la ruta actual.
     */
    private static long routeEndTime = 0L;

    /*
     * Keybinds.
     */
    private static KeyBinding recordKey;
    private static KeyBinding routesKey;
    private static KeyBinding playKey;

    /*
     * Estados.
     */
    private static boolean recording = false;
    private static boolean playing = false;

    /*
     * Puntos temporales durante grabación.
     */
    private static final List<RoutePoint> recordingPoints =
            new ArrayList<>();

    private static String recordingDimension = "";

    /*
     * Segmento actual.
     */
    private static int progressSegment = 0;

    /*
     * Posición dentro del segmento:
     *
     * 0.0 = inicio
     * 0.5 = mitad
     * 1.0 = final
     */
    private static double progressT = 0.0D;

    /*
     * ============================================================
     * INICIALIZACIÓN
     * ============================================================
     */

    @Override
    public void onInitializeClient() {

        RouteManager.load();

        /*
         * J = grabar.
         */
        recordKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding(
                        "key.crop_route.record",
                        InputUtil.Type.KEYSYM,
                        GLFW.GLFW_KEY_J,
                        "category.crop_route"
                )
        );

        /*
         * K = rutas guardadas.
         */
        routesKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding(
                        "key.crop_route.routes",
                        InputUtil.Type.KEYSYM,
                        GLFW.GLFW_KEY_K,
                        "category.crop_route"
                )
        );

        /*
         * O = iniciar/detener.
         */
        playKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding(
                        "key.crop_route.play",
                        InputUtil.Type.KEYSYM,
                        GLFW.GLFW_KEY_O,
                        "category.crop_route"
                )
        );

        ClientTickEvents.END_CLIENT_TICK.register(
                this::onEndTick
        );
    }

    /*
     * ============================================================
     * TICK PRINCIPAL
     * ============================================================
     */

    private void onEndTick(MinecraftClient client) {

        /*
         * Si salimos del mundo:
         *
         * - detener ruta
         * - soltar teclas
         * - cancelar grabación
         */
        if (client.player == null || client.world == null) {

            stopPlayback(
                    client,
                    false
            );

            recording = false;
            recordingPoints.clear();

            return;
        }

        handleKeys(client);

        /*
         * Continuar grabando.
         */
        if (recording && client.currentScreen == null) {

            recordCurrentPosition(client);
        }

        /*
         * Si no estamos reproduciendo, no hacemos nada más.
         */
        if (!playing) {

            return;
        }

        /*
         * Si abrimos chat, inventario o menú:
         *
         * soltamos temporalmente las teclas.
         *
         * El temporizador de 3 horas SIGUE corriendo.
         */
        if (client.currentScreen != null) {

            releaseAutomationKeys(client);

            return;
        }

        runSelectedRoute(client);
    }

    /*
     * ============================================================
     * CONTROLES
     * ============================================================
     */

    private void handleKeys(MinecraftClient client) {

        /*
         * K = abrir selector.
         */
        while (routesKey.wasPressed()) {

            if (client.currentScreen == null) {

                releaseAutomationKeys(client);

                client.setScreen(
                        new RouteSelectorScreen()
                );
            }
        }

        /*
         * J = iniciar/finalizar grabación.
         */
        while (recordKey.wasPressed()) {

            if (client.currentScreen != null) {

                continue;
            }

            if (!recording) {

                if (playing) {

                    stopPlayback(
                            client,
                            false
                    );
                }

                startRecording(client);

            } else {

                finishRecording(client);
            }
        }

        /*
         * O = iniciar/detener ruta.
         */
        while (playKey.wasPressed()) {

            if (client.currentScreen != null) {

                continue;
            }

            if (playing) {

                stopPlayback(
                        client,
                        true
                );

            } else {

                startPlayback(client);
            }
        }
    }

    /*
     * ============================================================
     * GRABACIÓN
     * ============================================================
     */

    private void startRecording(
            MinecraftClient client
    ) {

        recording = true;

        recordingPoints.clear();

        recordingDimension =
                currentDimension(client);

        recordingPoints.add(
                new RoutePoint(
                        client.player.getX(),
                        client.player.getY(),
                        client.player.getZ()
                )
        );

        showActionBar(
                "Grabando ruta V2... J para terminar"
        );
    }

    private void recordCurrentPosition(
            MinecraftClient client
    ) {

        double x =
                client.player.getX();

        double y =
                client.player.getY();

        double z =
                client.player.getZ();

        RoutePoint last =
                recordingPoints.get(
                        recordingPoints.size() - 1
                );

        double dx =
                x - last.x;

        double dy =
                y - last.y;

        double dz =
                z - last.z;

        double distanceSquared =
                dx * dx
                        + dy * dy
                        + dz * dz;

        if (distanceSquared >=
                RECORD_MIN_SPACING
                        * RECORD_MIN_SPACING) {

            recordingPoints.add(
                    new RoutePoint(
                            x,
                            y,
                            z
                    )
            );

            if (recordingPoints.size() % 100 == 0) {

                showActionBar(
                        "Grabando... "
                                + recordingPoints.size()
                                + " muestras"
                );
            }
        }
    }

    private void finishRecording(
            MinecraftClient client
    ) {

        recording = false;

        RoutePoint finalPoint =
                new RoutePoint(
                        client.player.getX(),
                        client.player.getY(),
                        client.player.getZ()
                );

        RoutePoint previous =
                recordingPoints.get(
                        recordingPoints.size() - 1
                );

        if (distanceSquared3D(
                finalPoint,
                previous
        ) > 0.01D) {

            recordingPoints.add(
                    finalPoint
            );
        }

        if (recordingPoints.size() < 4) {

            recordingPoints.clear();

            showActionBar(
                    "Ruta demasiado corta; no se guardó"
            );

            return;
        }

        /*
         * Normalizar ruta para que los puntos estén
         * distribuidos aproximadamente cada 0.40 bloques.
         */
        List<RoutePoint> normalized =
                resampleClosedPath(
                        new ArrayList<>(
                                recordingPoints
                        ),
                        RESAMPLE_SPACING
                );

        recordingPoints.clear();

        if (normalized.size() < 4) {

            showActionBar(
                    "La ruta procesada quedó demasiado corta"
            );

            return;
        }

        showActionBar(
                "Ruta procesada: "
                        + normalized.size()
                        + " puntos"
        );

        client.setScreen(
                new RouteNameScreen(
                        normalized,
                        recordingDimension
                )
        );
    }

    /*
     * ============================================================
     * INICIAR RUTA
     * ============================================================
     */

    private void startPlayback(
            MinecraftClient client
    ) {

        RouteData route =
                RouteManager.getSelectedRoute();

        /*
         * No hay ruta seleccionada.
         */
        if (route == null) {

            showActionBar(
                    "No hay ruta seleccionada. Presiona K."
            );

            return;
        }

        /*
         * Ruta inválida.
         */
        if (route.points == null ||
                route.points.size() < 4) {

            showActionBar(
                    "La ruta seleccionada no tiene suficientes puntos"
            );

            return;
        }

        /*
         * Comprobar dimensión.
         */
        String dimension =
                currentDimension(client);

        if (route.dimension != null &&
                !route.dimension.isBlank() &&
                !route.dimension.equals(dimension)) {

            showActionBar(
                    "Esta ruta pertenece a otra dimensión"
            );

            return;
        }

        /*
         * Cancelar grabación actual.
         */
        if (recording) {

            recording = false;
            recordingPoints.clear();
        }

        /*
         * Optimizar rutas antiguas si tienen huecos grandes.
         */
        if (largestSegmentGap(route.points)
                > OLD_ROUTE_GAP_LIMIT) {

            route.points =
                    resampleClosedPath(
                            route.points,
                            RESAMPLE_SPACING
                    );

            RouteManager.save();

            showActionBar(
                    "Ruta antigua optimizada para alta velocidad"
            );
        }

        /*
         * Encontrar el segmento de ruta más cercano.
         */
        PathProjection nearest =
                findNearestProjectionGlobal(
                        route,
                        client.player.getX(),
                        client.player.getZ()
                );

        progressSegment =
                nearest.segmentIndex;

        progressT =
                nearest.t;

        /*
         * Activar reproducción.
         */
        playing = true;

        /*
         * ========================================================
         * NUEVO TEMPORIZADOR DE 3 HORAS
         * ========================================================
         *
         * Cada vez que presionas O para iniciar,
         * comienza un periodo nuevo completo de 3 horas.
         */
        routeEndTime =
                System.currentTimeMillis()
                        + MAX_ROUTE_TIME_MS;

        showActionBar(
                "Ruta ACTIVADA: "
                        + route.name
                        + " | máximo 3 horas"
        );
    }

    /*
     * ============================================================
     * DETENER RUTA
     * ============================================================
     */

    private void stopPlayback(
            MinecraftClient client,
            boolean showMessage
    ) {

        boolean wasPlaying =
                playing;

        playing = false;

        progressT = 0.0D;

        /*
         * Limpiar temporizador.
         */
        routeEndTime = 0L;

        /*
         * Soltar entradas.
         */
        releaseAutomationKeys(client);

        if (showMessage && wasPlaying) {

            showActionBar(
                    "Ruta DESACTIVADA"
            );
        }
    }

    /*
     * ============================================================
     * EJECUTAR RUTA
     * ============================================================
     */

    private void runSelectedRoute(
            MinecraftClient client
    ) {

        /*
         * ========================================================
         * COMPROBAR LÍMITE DE 3 HORAS
         * ========================================================
         */
        if (routeEndTime > 0L &&
                System.currentTimeMillis() >= routeEndTime) {

            stopPlayback(
                    client,
                    false
            );

            showActionBar(
                    "Ruta detenida: se cumplieron las 3 horas"
            );

            return;
        }

        RouteData route =
                RouteManager.getSelectedRoute();

        /*
         * Ruta eliminada/no disponible.
         */
        if (route == null ||
                route.points == null ||
                route.points.size() < 4) {

            stopPlayback(
                    client,
                    false
            );

            showActionBar(
                    "La ruta ya no está disponible"
            );

            return;
        }

        /*
         * Cambiaste de dimensión.
         */
        if (route.dimension != null &&
                !route.dimension.isBlank() &&
                !currentDimension(client)
                        .equals(route.dimension)) {

            stopPlayback(
                    client,
                    false
            );

            showActionBar(
                    "Ruta detenida: cambiaste de dimensión"
            );

            return;
        }

        /*
         * ========================================================
         * MANTENER PRESIONADAS LAS ENTRADAS
         * ========================================================
         *
         * Esto se ejecuta cada tick.
         */
        client.options.forwardKey.setPressed(true);

        client.options.sprintKey.setPressed(true);

        client.options.attackKey.setPressed(true);

        client.player.setSprinting(true);

        /*
         * Posición actual.
         */
        double playerX =
                client.player.getX();

        double playerZ =
                client.player.getZ();

        /*
         * Buscar el mejor segmento hacia delante.
         */
        PathProjection projection =
                findBestForwardProjection(
                        route,
                        playerX,
                        playerZ,
                        progressSegment
                );

        /*
         * Si estamos extremadamente lejos,
         * recuperar usando toda la ruta.
         */
        if (projection.distanceSquared >
                EMERGENCY_RECOVERY_DISTANCE
                        * EMERGENCY_RECOVERY_DISTANCE) {

            projection =
                    findNearestProjectionGlobal(
                            route,
                            playerX,
                            playerZ
                    );
        }

        progressSegment =
                projection.segmentIndex;

        progressT =
                projection.t;

        /*
         * Avanzar al siguiente segmento.
         */
        if (progressT >= 0.985D) {

            progressSegment =
                    (progressSegment + 1)
                            % route.points.size();

            progressT =
                    0.0D;
        }

        /*
         * Velocidad horizontal.
         */
        double velocityX =
                client.player.getVelocity().x;

        double velocityZ =
                client.player.getVelocity().z;

        double horizontalSpeed =
                Math.sqrt(
                        velocityX * velocityX
                                + velocityZ * velocityZ
                );

        /*
         * Look-ahead dinámico.
         */
        double lookAhead =
                clamp(
                        MIN_LOOKAHEAD
                                + horizontalSpeed
                                * LOOKAHEAD_SPEED_MULTIPLIER,

                        MIN_LOOKAHEAD,

                        MAX_LOOKAHEAD
                );

        /*
         * Buscar objetivo varios bloques más adelante.
         */
        RoutePoint target =
                pointAheadOnRoute(
                        route,
                        progressSegment,
                        progressT,
                        lookAhead
                );

        /*
         * Girar hacia el objetivo.
         */
        aimSmoothlyAt(
                client,
                target,
                horizontalSpeed
        );
    }

    /*
     * ============================================================
     * GIRO AUTOMÁTICO
     * ============================================================
     */

    private void aimSmoothlyAt(
            MinecraftClient client,
            RoutePoint target,
            double horizontalSpeed
    ) {

        double dx =
                target.x
                        - client.player.getX();

        double dz =
                target.z
                        - client.player.getZ();

        if (dx * dx + dz * dz
                < 0.0001D) {

            return;
        }

        float desiredYaw =
                (float) Math.toDegrees(
                        Math.atan2(
                                -dx,
                                dz
                        )
                );

        float currentYaw =
                client.player.getYaw();

        float yawError =
                MathHelper.wrapDegrees(
                        desiredYaw
                                - currentYaw
                );

        /*
         * Giro dinámico según velocidad.
         */
        float maxYawThisTick =
                (float) clamp(
                        BASE_MAX_YAW_PER_TICK
                                + horizontalSpeed
                                * YAW_SPEED_MULTIPLIER,

                        BASE_MAX_YAW_PER_TICK,

                        MAX_YAW_PER_TICK
                );

        float yawStep =
                MathHelper.clamp(
                        yawError,
                        -maxYawThisTick,
                        maxYawThisTick
                );

        float newYaw =
                currentYaw
                        + yawStep;

        client.player.setYaw(
                newYaw
        );

        client.player.setHeadYaw(
                newYaw
        );

        /*
         * El pitch no se modifica.
         */
    }

    /*
     * ============================================================
     * BUSCAR SEGMENTO HACIA DELANTE
     * ============================================================
     */

    private PathProjection findBestForwardProjection(
            RouteData route,
            double playerX,
            double playerZ,
            int startSegment
    ) {

        int size =
                route.points.size();

        int segmentsToSearch =
                Math.min(
                        MAX_FORWARD_SEARCH_SEGMENTS,
                        size
                );

        PathProjection best =
                null;

        double bestScore =
                Double.MAX_VALUE;

        for (int offset = 0;
             offset < segmentsToSearch;
             offset++) {

            int segment =
                    (startSegment + offset)
                            % size;

            PathProjection candidate =
                    projectOntoSegment(
                            route,
                            segment,
                            playerX,
                            playerZ
                    );

            /*
             * Pequeña penalización por saltar
             * demasiados segmentos.
             */
            double score =
                    candidate.distanceSquared
                            + offset * 0.003D;

            if (score < bestScore) {

                bestScore =
                        score;

                best =
                        candidate;
            }
        }

        if (best == null) {

            return projectOntoSegment(
                    route,
                    startSegment,
                    playerX,
                    playerZ
            );
        }

        return best;
    }

    /*
     * ============================================================
     * PROYECCIÓN GLOBAL MÁS CERCANA
     * ============================================================
     */

    private PathProjection findNearestProjectionGlobal(
            RouteData route,
            double playerX,
            double playerZ
    ) {

        PathProjection best =
                null;

        double nearestDistance =
                Double.MAX_VALUE;

        for (int segment = 0;
             segment < route.points.size();
             segment++) {

            PathProjection candidate =
                    projectOntoSegment(
                            route,
                            segment,
                            playerX,
                            playerZ
                    );

            if (candidate.distanceSquared
                    < nearestDistance) {

                nearestDistance =
                        candidate.distanceSquared;

                best =
                        candidate;
            }
        }

        if (best == null) {

            return new PathProjection(
                    0,
                    0.0D,
                    Double.MAX_VALUE
            );
        }

        return best;
    }

    /*
     * ============================================================
     * PROYECTAR JUGADOR SOBRE SEGMENTO
     * ============================================================
     */

    private PathProjection projectOntoSegment(
            RouteData route,
            int segmentIndex,
            double playerX,
            double playerZ
    ) {

        int size =
                route.points.size();

        RoutePoint a =
                route.points.get(
                        segmentIndex
                );

        RoutePoint b =
                route.points.get(
                        (segmentIndex + 1)
                                % size
                );

        double segmentX =
                b.x - a.x;

        double segmentZ =
                b.z - a.z;

        double segmentLengthSquared =
                segmentX * segmentX
                        + segmentZ * segmentZ;

        double t;

        if (segmentLengthSquared
                < 0.00000001D) {

            t = 0.0D;

        } else {

            t =
                    (
                            (playerX - a.x)
                                    * segmentX
                                    +
                                    (playerZ - a.z)
                                            * segmentZ
                    )
                            / segmentLengthSquared;

            t =
                    clamp(
                            t,
                            0.0D,
                            1.0D
                    );
        }

        double projectionX =
                a.x
                        + segmentX * t;

        double projectionZ =
                a.z
                        + segmentZ * t;

        double dx =
                playerX
                        - projectionX;

        double dz =
                playerZ
                        - projectionZ;

        double distanceSquared =
                dx * dx
                        + dz * dz;

        return new PathProjection(
                segmentIndex,
                t,
                distanceSquared
        );
    }

    /*
     * ============================================================
     * LOOK-AHEAD SOBRE LA RUTA
     * ============================================================
     */

    private RoutePoint pointAheadOnRoute(
            RouteData route,
            int startSegment,
            double startT,
            double distanceAhead
    ) {

        int size =
                route.points.size();

        int segment =
                startSegment;

        double t =
                clamp(
                        startT,
                        0.0D,
                        1.0D
                );

        double remaining =
                distanceAhead;

        for (int guard = 0;
             guard < size + 2;
             guard++) {

            RoutePoint a =
                    route.points.get(
                            segment
                    );

            RoutePoint b =
                    route.points.get(
                            (segment + 1)
                                    % size
                    );

            double dx =
                    b.x - a.x;

            double dy =
                    b.y - a.y;

            double dz =
                    b.z - a.z;

            double segmentLength =
                    Math.sqrt(
                            dx * dx
                                    + dz * dz
                    );

            if (segmentLength
                    < 0.00000001D) {

                segment =
                        (segment + 1)
                                % size;

                t =
                        0.0D;

                continue;
            }

            double availableDistance =
                    segmentLength
                            * (1.0D - t);

            if (remaining
                    <= availableDistance) {

                double finalT =
                        t
                                + remaining
                                / segmentLength;

                return new RoutePoint(
                        a.x
                                + dx * finalT,

                        a.y
                                + dy * finalT,

                        a.z
                                + dz * finalT
                );
            }

            remaining -=
                    availableDistance;

            segment =
                    (segment + 1)
                            % size;

            t =
                    0.0D;
        }

        return route.points.get(
                (startSegment + 1)
                        % size
        );
    }

    /*
     * ============================================================
     * NORMALIZAR RUTA
     * ============================================================
     */

    private List<RoutePoint> resampleClosedPath(
            List<RoutePoint> original,
            double spacing
    ) {

        List<RoutePoint> result =
                new ArrayList<>();

        if (original == null ||
                original.size() < 2) {

            if (original != null) {

                result.addAll(
                        original
                );
            }

            return result;
        }

        int size =
                original.size();

        double[] segmentLengths =
                new double[size];

        double totalLength =
                0.0D;

        for (int i = 0;
             i < size;
             i++) {

            RoutePoint a =
                    original.get(i);

            RoutePoint b =
                    original.get(
                            (i + 1)
                                    % size
                    );

            double dx =
                    b.x - a.x;

            double dz =
                    b.z - a.z;

            double length =
                    Math.sqrt(
                            dx * dx
                                    + dz * dz
                    );

            segmentLengths[i] =
                    length;

            totalLength +=
                    length;
        }

        if (totalLength
                < spacing * 3.0D) {

            result.addAll(
                    original
            );

            return result;
        }

        int sampleCount =
                Math.max(
                        4,
                        (int) Math.round(
                                totalLength
                                        / spacing
                        )
                );

        double actualSpacing =
                totalLength
                        / sampleCount;

        int currentSegment =
                0;

        double currentSegmentStartDistance =
                0.0D;

        for (int sample = 0;
             sample < sampleCount;
             sample++) {

            double wantedDistance =
                    sample
                            * actualSpacing;

            while (
                    currentSegment
                            < size - 1
                            &&
                            wantedDistance
                                    >
                                    currentSegmentStartDistance
                                            +
                                            segmentLengths[
                                                    currentSegment
                                            ]
            ) {

                currentSegmentStartDistance +=
                        segmentLengths[
                                currentSegment
                        ];

                currentSegment++;
            }

            RoutePoint a =
                    original.get(
                            currentSegment
                    );

            RoutePoint b =
                    original.get(
                            (currentSegment + 1)
                                    % size
                    );

            double length =
                    segmentLengths[
                            currentSegment
                    ];

            double interpolation;

            if (length
                    < 0.00000001D) {

                interpolation =
                        0.0D;

            } else {

                interpolation =
                        (
                                wantedDistance
                                        -
                                        currentSegmentStartDistance
                        )
                                / length;
            }

            interpolation =
                    clamp(
                            interpolation,
                            0.0D,
                            1.0D
                    );

            result.add(
                    new RoutePoint(
                            a.x
                                    +
                                    (b.x - a.x)
                                            * interpolation,

                            a.y
                                    +
                                    (b.y - a.y)
                                            * interpolation,

                            a.z
                                    +
                                    (b.z - a.z)
                                            * interpolation
                    )
            );
        }

        return result;
    }

    /*
     * ============================================================
     * DETECTAR MAYOR HUECO
     * ============================================================
     */

    private double largestSegmentGap(
            List<RoutePoint> points
    ) {

        if (points == null ||
                points.size() < 2) {

            return 0.0D;
        }

        double largest =
                0.0D;

        for (int i = 0;
             i < points.size();
             i++) {

            RoutePoint a =
                    points.get(i);

            RoutePoint b =
                    points.get(
                            (i + 1)
                                    % points.size()
                    );

            double dx =
                    b.x - a.x;

            double dz =
                    b.z - a.z;

            double distance =
                    Math.sqrt(
                            dx * dx
                                    + dz * dz
                    );

            if (distance > largest) {

                largest =
                        distance;
            }
        }

        return largest;
    }

    /*
     * ============================================================
     * DISTANCIA 3D
     * ============================================================
     */

    private static double distanceSquared3D(
            RoutePoint a,
            RoutePoint b
    ) {

        double dx =
                a.x - b.x;

        double dy =
                a.y - b.y;

        double dz =
                a.z - b.z;

        return dx * dx
                + dy * dy
                + dz * dz;
    }

    /*
     * ============================================================
     * DIMENSIÓN
     * ============================================================
     */

    private static String currentDimension(
            MinecraftClient client
    ) {

        return client.world
                .getRegistryKey()
                .getValue()
                .toString();
    }

    /*
     * ============================================================
     * SOLTAR TECLAS
     * ============================================================
     */

    private static void releaseAutomationKeys(
            MinecraftClient client
    ) {

        if (client == null) {

            return;
        }

        /*
         * W.
         */
        client.options.forwardKey
                .setPressed(false);

        /*
         * Sprint.
         */
        client.options.sprintKey
                .setPressed(false);

        /*
         * Clic izquierdo.
         */
        client.options.attackKey
                .setPressed(false);

        if (client.player != null) {

            client.player.setSprinting(
                    false
            );
        }
    }

    /*
     * ============================================================
     * MENSAJE SOBRE HOTBAR
     * ============================================================
     */

    public static void showActionBar(
            String message
    ) {

        MinecraftClient client =
                MinecraftClient.getInstance();

        if (client.player != null) {

            client.player.sendMessage(
                    Text.literal(message),
                    true
            );
        }
    }

    /*
     * ============================================================
     * CLAMP
     * ============================================================
     */

    private static double clamp(
            double value,
            double min,
            double max
    ) {

        return Math.max(
                min,
                Math.min(
                        max,
                        value
                )
        );
    }

    /*
     * ============================================================
     * PROYECCIÓN
     * ============================================================
     */

    private static final class PathProjection {

        private final int segmentIndex;

        private final double t;

        private final double distanceSquared;

        private PathProjection(
                int segmentIndex,
                double t,
                double distanceSquared
        ) {

            this.segmentIndex =
                    segmentIndex;

            this.t =
                    t;

            this.distanceSquared =
                    distanceSquared;
        }
    }
}
