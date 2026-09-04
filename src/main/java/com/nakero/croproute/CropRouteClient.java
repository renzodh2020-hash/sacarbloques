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
     * MEJORAS:
     *
     * - Ya NO obliga al jugador a tocar exactamente cada waypoint.
     * - Sigue segmentos completos de la ruta.
     * - Detecta cuando sobrepasaste un punto.
     * - Look-ahead dinámico dependiendo de la velocidad.
     * - Giro dinámico dependiendo de la velocidad.
     * - Reinterpola rutas grabadas rápidamente.
     * - Reduce muchísimo el problema de empezar a dar círculos.
     * - W + Sprint + clic izquierdo siguen mantenidos cada tick.
     */

    /*
     * Distancia mínima recorrida para guardar una muestra mientras grabas.
     *
     * Es pequeña porque queremos capturar bien las curvas.
     */
    private static final double RECORD_MIN_SPACING = 0.08D;

    /*
     * Después de terminar una grabación, reconstruimos toda la ruta
     * aproximadamente cada 0.40 bloques.
     *
     * Incluso aunque estuvieras volando muy rápido y entre dos ticks
     * hubiera una separación grande.
     */
    private static final double RESAMPLE_SPACING = 0.40D;

    /*
     * Si una ruta vieja tiene puntos separados más de esta distancia,
     * se optimiza automáticamente cuando la reproduces.
     */
    private static final double OLD_ROUTE_GAP_LIMIT = 0.80D;

    /*
     * LOOK-AHEAD
     *
     * El jugador no mira exactamente al siguiente punto.
     * Mira varios bloques más adelante.
     *
     * Esto hace muchísimo más estable el movimiento rápido.
     */
    private static final double MIN_LOOKAHEAD = 2.0D;
    private static final double MAX_LOOKAHEAD = 8.0D;

    /*
     * Minecraft usa bloques/tick para la velocidad.
     *
     * Cuanto más rápido vamos, más lejos miramos.
     */
    private static final double LOOKAHEAD_SPEED_MULTIPLIER = 10.0D;

    /*
     * Máximo cambio de yaw por tick.
     *
     * Caminando gira suavemente.
     * Con mucha velocidad puede corregir más rápido.
     */
    private static final float BASE_MAX_YAW_PER_TICK = 8.0F;
    private static final float MAX_YAW_PER_TICK = 22.0F;
    private static final float YAW_SPEED_MULTIPLIER = 20.0F;

    /*
     * Cantidad máxima de segmentos futuros que puede analizar.
     *
     * IMPORTANTE:
     * solo busca HACIA DELANTE.
     *
     * Esto evita que después de sobrepasar un waypoint quiera regresar.
     */
    private static final int MAX_FORWARD_SEARCH_SEGMENTS = 120;

    /*
     * Si quedamos absurdamente lejos de la ruta, permitimos una
     * recuperación global.
     */
    private static final double EMERGENCY_RECOVERY_DISTANCE = 12.0D;

    private static KeyBinding recordKey;
    private static KeyBinding routesKey;
    private static KeyBinding playKey;

    private static boolean recording = false;
    private static boolean playing = false;

    private static final List<RoutePoint> recordingPoints =
            new ArrayList<>();

    private static String recordingDimension = "";

    /*
     * Segmento actual que estamos siguiendo.
     *
     * Ejemplo:
     *
     * points[20] -------- points[21]
     *
     * progressSegment = 20
     */
    private static int progressSegment = 0;

    /*
     * Posición dentro del segmento.
     *
     * 0.0 = inicio
     * 0.5 = mitad
     * 1.0 = final
     */
    private static double progressT = 0.0D;

    @Override
    public void onInitializeClient() {

        RouteManager.load();

        recordKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding(
                        "key.crop_route.record",
                        InputUtil.Type.KEYSYM,
                        GLFW.GLFW_KEY_J,
                        "category.crop_route"
                )
        );

        routesKey = KeyBindingHelper.registerKeyBinding(
                new KeyBinding(
                        "key.crop_route.routes",
                        InputUtil.Type.KEYSYM,
                        GLFW.GLFW_KEY_K,
                        "category.crop_route"
                )
        );

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

    private void onEndTick(MinecraftClient client) {

        /*
         * Si salimos del mundo, soltamos absolutamente todo.
         */
        if (client.player == null || client.world == null) {

            stopPlayback(client, false);

            recording = false;
            recordingPoints.clear();

            return;
        }

        handleKeys(client);

        /*
         * GRABACIÓN
         */
        if (recording && client.currentScreen == null) {
            recordCurrentPosition(client);
        }

        if (!playing) {
            return;
        }

        /*
         * No mantener botones presionados dentro de inventario,
         * chat, menú, etc.
         */
        if (client.currentScreen != null) {

            releaseAutomationKeys(client);

            return;
        }

        runSelectedRoute(client);
    }

    /*
     * ============================================================
     * TECLAS
     * ============================================================
     */

    private void handleKeys(MinecraftClient client) {

        /*
         * K = abrir rutas.
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
         * J = grabar / terminar grabación.
         */
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

        /*
         * O = iniciar / detener ruta.
         */
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

    /*
     * ============================================================
     * GRABACIÓN
     * ============================================================
     */

    private void startRecording(MinecraftClient client) {

        recording = true;

        recordingPoints.clear();

        recordingDimension =
                currentDimension(client);

        /*
         * Guardamos inmediatamente la posición inicial.
         */
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

        double x = client.player.getX();
        double y = client.player.getY();
        double z = client.player.getZ();

        RoutePoint last =
                recordingPoints.get(
                        recordingPoints.size() - 1
                );

        double dx = x - last.x;
        double dy = y - last.y;
        double dz = z - last.z;

        double distanceSquared =
                dx * dx +
                dy * dy +
                dz * dz;

        /*
         * Solo guardamos una nueva muestra si realmente
         * nos movimos.
         */
        if (distanceSquared >=
                RECORD_MIN_SPACING *
                RECORD_MIN_SPACING) {

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

        /*
         * Guardamos también la última posición.
         */
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
         * ========================================================
         * CAMBIO IMPORTANTE
         * ========================================================
         *
         * Antes:
         *
         * ●------------●---●----------------●
         *
         * dependiendo de la velocidad.
         *
         * Ahora:
         *
         * ●--●--●--●--●--●--●--●--●--●--●
         *
         * aproximadamente cada 0.40 bloques.
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
     * REPRODUCCIÓN
     * ============================================================
     */

    private void startPlayback(
            MinecraftClient client
    ) {

        RouteData route =
                RouteManager.getSelectedRoute();

        if (route == null) {

            showActionBar(
                    "No hay ruta seleccionada. Presiona K."
            );

            return;
        }

        if (route.points == null ||
                route.points.size() < 4) {

            showActionBar(
                    "La ruta seleccionada no tiene suficientes puntos"
            );

            return;
        }

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

        if (recording) {

            recording = false;
            recordingPoints.clear();
        }

        /*
         * ========================================================
         * COMPATIBILIDAD CON RUTAS V1
         * ========================================================
         *
         * Si detectamos huecos grandes entre puntos, reconstruimos
         * automáticamente la ruta vieja.
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
         * Encontramos dónde estamos realmente sobre la ruta.
         *
         * NO buscamos solamente el waypoint más cercano.
         *
         * Buscamos el punto más cercano SOBRE UN SEGMENTO.
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

        playing = true;

        showActionBar(
                "Ruta ACTIVADA V2: "
                        + route.name
        );
    }

    private void stopPlayback(
            MinecraftClient client,
            boolean showMessage
    ) {

        boolean wasPlaying =
                playing;

        playing = false;

        progressT = 0.0D;

        releaseAutomationKeys(client);

        if (showMessage && wasPlaying) {

            showActionBar(
                    "Ruta DESACTIVADA"
            );
        }
    }

    /*
     * ============================================================
     * SEGUIDOR DE RUTA V2
     * ============================================================
     */

    private void runSelectedRoute(
            MinecraftClient client
    ) {

        RouteData route =
                RouteManager.getSelectedRoute();

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
         * MUY IMPORTANTE
         * ========================================================
         *
         * ESTO SE EJECUTA CADA TICK.
         *
         * W = PRESIONADA
         * Sprint = PRESIONADO
         * clic izquierdo = PRESIONADO
         */
        client.options.forwardKey.setPressed(true);

        client.options.sprintKey.setPressed(true);

        client.options.attackKey.setPressed(true);

        client.player.setSprinting(true);

        double playerX =
                client.player.getX();

        double playerZ =
                client.player.getZ();

        /*
         * Ahora buscamos el mejor segmento HACIA ADELANTE.
         *
         * Si volamos rápido y sobrepasamos:
         *
         * punto 50
         * punto 51
         * punto 52
         *
         * podemos saltar directamente al segmento 52-53.
         *
         * NO intenta regresar al 50.
         */
        PathProjection projection =
                findBestForwardProjection(
                        route,
                        playerX,
                        playerZ,
                        progressSegment
                );

        /*
         * Si por alguna razón quedamos extremadamente lejos,
         * hacemos recuperación de emergencia.
         */
        if (projection.distanceSquared >
                EMERGENCY_RECOVERY_DISTANCE *
                        EMERGENCY_RECOVERY_DISTANCE) {

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
         * Si llegamos prácticamente al final del segmento,
         * pasamos al siguiente inmediatamente.
         */
        if (progressT >= 0.985D) {

            progressSegment =
                    (progressSegment + 1)
                            % route.points.size();

            progressT = 0.0D;
        }

        /*
         * ========================================================
         * VELOCIDAD ACTUAL
         * ========================================================
         */
        double velocityX =
                client.player.getVelocity().x;

        double velocityZ =
                client.player.getVelocity().z;

        double horizontalSpeed =
                Math.sqrt(
                        velocityX * velocityX +
                        velocityZ * velocityZ
                );

        /*
         * ========================================================
         * LOOK-AHEAD DINÁMICO
         * ========================================================
         *
         * Caminando:
         *
         * objetivo ~2-3 bloques adelante
         *
         * Muy rápido:
         *
         * objetivo hasta ~8 bloques adelante
         */
        double lookAhead =
                clamp(
                        MIN_LOOKAHEAD +
                                horizontalSpeed *
                                        LOOKAHEAD_SPEED_MULTIPLIER,

                        MIN_LOOKAHEAD,

                        MAX_LOOKAHEAD
                );

        /*
         * Buscamos un punto REAL sobre la ruta varios bloques
         * hacia adelante.
         */
        RoutePoint target =
                pointAheadOnRoute(
                        route,
                        progressSegment,
                        progressT,
                        lookAhead
                );

        /*
         * Giramos hacia ese punto.
         */
        aimSmoothlyAt(
                client,
                target,
                horizontalSpeed
        );
    }

    /*
     * ============================================================
     * GIRO
     * ============================================================
     */

    private void aimSmoothlyAt(
            MinecraftClient client,
            RoutePoint target,
            double horizontalSpeed
    ) {

        double dx =
                target.x -
                        client.player.getX();

        double dz =
                target.z -
                        client.player.getZ();

        if (dx * dx + dz * dz
                < 0.0001D) {

            return;
        }

        /*
         * Minecraft:
         *
         * yaw 0 = +Z
         */
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
                        desiredYaw -
                                currentYaw
                );

        /*
         * Cuanto más rápido vamos,
         * más capacidad de giro permitimos.
         */
        float maxYawThisTick =
                (float) clamp(
                        BASE_MAX_YAW_PER_TICK +
                                horizontalSpeed *
                                        YAW_SPEED_MULTIPLIER,

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
                currentYaw +
                        yawStep;

        client.player.setYaw(
                newYaw
        );

        client.player.setHeadYaw(
                newYaw
        );

        /*
         * NO modificamos pitch.
         *
         * Tú puedes dejar la mira mirando hacia abajo
         * para romper cultivos.
         */
    }

    /*
     * ============================================================
     * PROYECCIÓN SOBRE LA RUTA
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

        /*
         * Solo:
         *
         * segmento actual
         * segmento + 1
         * segmento + 2
         * ...
         *
         * NUNCA:
         *
         * segmento - 1
         * segmento - 2
         */
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
             * Pequeña penalización por saltar demasiados
             * segmentos de golpe.
             *
             * Pero si realmente los sobrepasaste rápidamente,
             * la distancia gana y el progreso avanza.
             */
            double score =
                    candidate.distanceSquared +
                            offset * 0.003D;

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
     * Para comenzar una ruta o recuperación extrema.
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
     * Encuentra matemáticamente el punto más cercano
     * dentro de:
     *
     * A ---------------- B
     *
     * respecto al jugador.
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
                segmentX * segmentX +
                        segmentZ * segmentZ;

        double t;

        if (segmentLengthSquared
                < 0.00000001D) {

            t = 0.0D;

        } else {

            t =
                    (
                            (playerX - a.x) *
                                    segmentX
                                    +
                                    (playerZ - a.z) *
                                            segmentZ
                    )
                            /
                            segmentLengthSquared;

            t =
                    clamp(
                            t,
                            0.0D,
                            1.0D
                    );
        }

        double projectionX =
                a.x +
                        segmentX * t;

        double projectionZ =
                a.z +
                        segmentZ * t;

        double dx =
                playerX -
                        projectionX;

        double dz =
                playerZ -
                        projectionZ;

        double distanceSquared =
                dx * dx +
                        dz * dz;

        return new PathProjection(
                segmentIndex,
                t,
                distanceSquared
        );
    }

    /*
     * ============================================================
     * PURE PURSUIT / LOOK-AHEAD
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

        /*
         * Recorremos los segmentos hacia delante hasta encontrar
         * exactamente el punto situado "distanceAhead" bloques
         * más adelante.
         */
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

            /*
             * Para conducción utilizamos principalmente X/Z.
             */
            double segmentLength =
                    Math.sqrt(
                            dx * dx +
                                    dz * dz
                    );

            if (segmentLength
                    < 0.00000001D) {

                segment =
                        (segment + 1)
                                % size;

                t = 0.0D;

                continue;
            }

            double availableDistance =
                    segmentLength *
                            (1.0D - t);

            if (remaining
                    <= availableDistance) {

                double finalT =
                        t +
                                remaining /
                                        segmentLength;

                return new RoutePoint(
                        a.x +
                                dx * finalT,

                        a.y +
                                dy * finalT,

                        a.z +
                                dz * finalT
                );
            }

            remaining -=
                    availableDistance;

            segment =
                    (segment + 1)
                            % size;

            t = 0.0D;
        }

        /*
         * Fallback.
         */
        return route.points.get(
                (startSegment + 1)
                        % size
        );
    }

    /*
     * ============================================================
     * RECONSTRUCCIÓN DE RUTA
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
                result.addAll(original);
            }

            return result;
        }

        int size =
                original.size();

        /*
         * Cada elemento representa la longitud horizontal
         * del segmento i -> i+1.
         */
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
                            dx * dx +
                                    dz * dz
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

        /*
         * Número total de puntos finales.
         */
        int sampleCount =
                Math.max(
                        4,
                        (int) Math.round(
                                totalLength /
                                        spacing
                        )
                );

        /*
         * De esta forma todos quedan distribuidos
         * uniformemente alrededor del circuito.
         */
        double actualSpacing =
                totalLength /
                        sampleCount;

        int currentSegment =
                0;

        double currentSegmentStartDistance =
                0.0D;

        for (int sample = 0;
             sample < sampleCount;
             sample++) {

            double wantedDistance =
                    sample *
                            actualSpacing;

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

            double t;

            if (length
                    < 0.00000001D) {

                t = 0.0D;

            } else {

                t =
                        (
                                wantedDistance -
                                        currentSegmentStartDistance
                        )
                                /
                                length;
            }

            t =
                    clamp(
                            t,
                            0.0D,
                            1.0D
                    );

            result.add(
                    new RoutePoint(
                            a.x +
                                    (b.x - a.x) *
                                            t,

                            a.y +
                                    (b.y - a.y) *
                                            t,

                            a.z +
                                    (b.z - a.z) *
                                            t
                    )
            );
        }

        return result;
    }

    /*
     * Busca el hueco más grande entre puntos.
     *
     * Se utiliza para saber si una ruta V1 necesita
     * optimización.
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
                            dx * dx +
                                    dz * dz
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
     * UTILIDADES
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

        return dx * dx +
                dy * dy +
                dz * dz;
    }

    private static String currentDimension(
            MinecraftClient client
    ) {

        return client.world
                .getRegistryKey()
                .getValue()
                .toString();
    }

    /*
     * Suelta todo lo que el mod estaba manteniendo.
     */
    private static void releaseAutomationKeys(
            MinecraftClient client
    ) {

        if (client == null) {
            return;
        }

        client.options.forwardKey
                .setPressed(false);

        client.options.sprintKey
                .setPressed(false);

        client.options.attackKey
                .setPressed(false);

        if (client.player != null) {

            client.player.setSprinting(
                    false
            );
        }
    }

    /*
     * Mensaje encima de la hotbar.
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
     * RESULTADO DE PROYECCIÓN
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
