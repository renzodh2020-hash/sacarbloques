# Crop Route — Minecraft 1.20.4 Fabric

Mod cliente para grabar **varias rutas**, elegir una y repetirla en un bucle infinito.

## Requisito importante del movimiento

Durante la reproducción, el mod vuelve a mantener estas entradas presionadas **cada tick**:

- **W / avanzar**
- **Sprint**
- **clic izquierdo / atacar-romper**

Mientras la ruta esté activa, el jugador corre y mantiene el clic izquierdo mientras la cámara gira suavemente para seguir los puntos grabados.

El mod **NO cambia el pitch** (ángulo vertical), así que antes de iniciar la ruta apunta tú mismo hacia la altura de los cultivos.

## Controles predeterminados

- **J** — iniciar / terminar grabación.
- **K** — abrir selector de rutas guardadas.
- **O** — iniciar / detener la ruta seleccionada.

Las teclas aparecen en `Opciones > Controles > Crop Route` y pueden cambiarse.

## Cómo grabar una ruta

1. Ponte en el lugar desde el que quieres comenzar.
2. Presiona **J**.
3. Haz manualmente una vuelta completa siguiendo el recorrido deseado.
4. Intenta terminar cerca del punto inicial.
5. Presiona **J** nuevamente.
6. Escribe un nombre, por ejemplo `Trigo exterior`.
7. Pulsa **Guardar**.

La ruta queda seleccionada automáticamente.

## Cómo tener varias rutas

Repite el procedimiento anterior cuantas veces quieras.

Las rutas se guardan en:

`config/crop-route/routes.json`

Por eso siguen existiendo después de cerrar Minecraft.

## Elegir una ruta

1. Presiona **K**.
2. Haz clic en la ruta que quieras.
3. Regresas al juego.
4. Presiona **O** para comenzar.

El selector también permite borrar la ruta seleccionada.

## Reproducción

Al presionar **O**:

1. Busca el punto de la ruta más cercano a tu posición.
2. Empieza desde el siguiente punto.
3. Mantiene W + Sprint + clic izquierdo.
4. Gira horizontalmente hacia los puntos sucesivos.
5. Al llegar al último vuelve al primero.
6. Repite para siempre hasta que vuelvas a presionar O.

Si te desvías bastante por lag, choque o empujón, intenta recuperar la ruta buscando nuevamente el punto cercano.

## Menús y seguridad de las teclas

Si abres inventario, chat o cualquier pantalla:

- W se suelta temporalmente.
- Sprint se suelta temporalmente.
- clic izquierdo se suelta temporalmente.

Al cerrar el menú, si la ruta sigue activa, se vuelven a mantener.

Al salir del mundo también se detiene.

## Requisitos

- Minecraft **1.20.4**
- Fabric Loader
- Fabric API para 1.20.4
- Java **17**

## Compilar con GitHub Actions

El repositorio ya incluye:

`.github/workflows/build.yml`

No necesitas tener Gradle instalado en tu PC para usar GitHub Actions.

### Pasos

1. Crea un repositorio nuevo en GitHub.
2. Sube TODO el contenido de esta carpeta al repositorio.
3. Abre la pestaña **Actions**.
4. Entra a **Build Crop Route**.
5. Pulsa **Run workflow**.
6. Espera a que aparezca un check verde.
7. Abre la ejecución terminada.
8. En la parte inferior, en **Artifacts**, descarga `CropRoute-1.20.4`.
9. Descomprime el artifact.
10. Dentro estará `crop-route-1.0.0.jar`.

## Instalar para probar

Pon en la carpeta `mods`:

- `crop-route-1.0.0.jar`
- Fabric API compatible con Minecraft 1.20.4

Inicia Minecraft usando Fabric 1.20.4.

### Primera prueba recomendada

Hazla primero en un mundo de prueba:

1. Entra al mundo.
2. Presiona J.
3. Camina una vuelta sencilla.
4. J otra vez.
5. Guarda la ruta.
6. K para verificar que aparece.
7. Selecciónala.
8. Colócate cerca de la ruta.
9. Mira hacia el suelo/cultivo.
10. Presiona O.

Deberías ver que mantiene W + sprint + clic izquierdo y sigue el circuito.

## Ajustes principales en el código

En `CropRouteClient.java`:

- `RECORD_SPACING = 0.55` — separación aproximada entre puntos grabados.
- `REACH_DISTANCE = 1.15` — distancia para considerar un punto alcanzado.
- `RECOVERY_DISTANCE = 4.5` — cuándo intenta recuperar la ruta.
- `MAX_YAW_CHANGE_PER_TICK = 6.0` — suavidad/velocidad máxima del giro.

Si el recorrido corta demasiado las curvas, baja `REACH_DISTANCE`.
Si gira demasiado lento, sube `MAX_YAW_CHANGE_PER_TICK`.
