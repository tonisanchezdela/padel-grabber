# Padel Grabber

App Android para grabar partidas de pádel con el propio móvil. El marcador va incrustado en el vídeo, la app monta repeticiones y un resumen, emite en directo por la wifi del club, permite pedir videoarbitraje con un gesto, sube todo a una web para descargarlo y tiene una sección de entrenamiento con biomecánica.

## Qué hace

| Fase | Función |
|---|---|
| 1 | Grabación con **marcador de pádel incrustado** (15-30-40, punto de oro o ventaja, tie-break, super tie-break, saque) y **cortinillas animadas Padel Grabber** al empezar, **entre cada punto** (la app detecta sola cuándo se para el juego), en los cambios de lado, al final de cada set y la de **final de partido** al terminar. |
| 2 | **Repeticiones** del final del punto más largo de cada juego, intercaladas en el vídeo del partido, y **resumen** con bolas de set, punto de partido y los puntos más largos (unos 2,5 min). |
| 3 | **Autopausa**: se para sola en los descansos (cambio de lado: mín. 30 s, entre sets: mín. 60 s) y cuando no hay movimiento en la pista. Vuelve a grabar cuando detecta juego. |
| 4 | **Videoarbitraje**: un jugador mira a cualquier cámara con un brazo levantado 3 segundos → se repiten los últimos segundos desde todas las cámaras, a cámara lenta. También con el botón VAR. |
| 5 | **Multicámara y directo** en la wifi del club: los móviles de los jugadores en modo «Cámara extra» envían su imagen al móvil principal, que hace de realizador. Cualquiera en la wifi ve el directo en el navegador (o en la app), y con el PIN puede apuntar puntos. |
| 6 | **Plataforma web**: los vídeos se suben a Supabase y se ven y descargan desde una web (GitHub Pages). |
| 7 | **Entrenamiento**: análisis de golpes en directo o desde un vídeo (codo, hombro, rodillas, giro del tronco, altura del impacto, terminación) con nota y consejos, y guía de biomecánica de derecha, revés, volea, bandeja, víbora, remate y saque. |

## 1. Conseguir la APK

El proyecto se compila solo en GitHub:

1. Sube esta carpeta a un repositorio de GitHub (por ejemplo `padel-grabber`).
2. En la pestaña **Actions** se lanza «Compilar APK» (tarda unos 5-8 minutos).
3. En **Releases** aparece **PadelGrabber.apk**. Descárgala desde el móvil y ábrela (Android pedirá permiso para instalar apps de esa fuente).

Cada vez que se cambie el código se publica una APK nueva que se instala encima de la anterior sin perder los partidos.

Requisitos: Android 8.0 o superior. Recomendado: móvil de gama media o alta.

## 2. Plataforma de vídeos (opcional pero recomendado)

1. Crea un proyecto gratis en [supabase.com](https://supabase.com).
2. En **SQL Editor** pega el contenido de `supabase/setup.sql` y pulsa **Run**. Crea las tablas, el almacén de vídeos «partidos» y los permisos.
3. En **Project Settings › API** copia la **Project URL** y la clave **anon public**.
4. En la app: **Ajustes › Plataforma** → pega la URL y la clave.
5. Web: edita `docs/config.js` con la misma URL y clave. En GitHub, **Settings › Pages › Source: GitHub Actions**. La web quedará en `https://TU_USUARIO.github.io/padel-grabber/`. Pon esa dirección también en Ajustes de la app.

**Límite del plan gratuito de Supabase:** 50 MB por archivo y 1 GB en total. El resumen se comprime para caber en 50 MB. El partido completo y el de repeticiones pesan más: para subirlos necesitas el plan Pro o subir el límite en *Storage › Settings*. Si falla por tamaño, la app lo dice y sigue con el resto.

## 3. Cómo se usa

**Grabar un partido**
1. *Grabar un partido* → nombres de las parejas, club, pista y reglas.
2. Coloca el móvil en horizontal, detrás de la pista y a algo de altura, con toda la pista en el encuadre.
3. *Empezar a grabar*. Apunta cada punto con los botones grandes **+1**, con los **botones de volumen** (subir = A, bajar = B) o con un **mando Bluetooth de fotos**.
4. ↶ deshace el último punto. *Pausa* para pausar a mano. *Fin* para terminar.
5. Al acabar, la app crea el resumen y el partido con repeticiones y lo sube a la plataforma (se puede desactivar en Ajustes).

**Cámaras extra (otros ángulos)**
- En otro móvil, conectado a la **misma wifi**: *Cámara extra*. Se conecta solo al móvil principal (o con «Conectar por IP»).
- Graba su ángulo con el marcador, empieza y para con el principal, y envía su imagen al directo.
- En el principal, *Cámaras* muestra todas: toca una para ponerla en antena.

**Ver el directo**
- En el principal, *Directo* enseña un QR y la dirección (ej. `http://192.168.1.20:8080`). Se abre en cualquier navegador conectado a la wifi del club, o desde la app en *Ver un directo del club*.
- Con el **PIN** que sale en pantalla se pueden apuntar puntos, deshacer, pausar o pedir videoarbitraje desde la web.

**Videoarbitraje**
- Un jugador se acerca a una cámara, mira hacia ella y levanta un brazo por encima de la cabeza durante 3 segundos (también vale el botón **VAR**).
- Se abre la repetición desde todas las cámaras (×0,5, ×0,25 o normal, con barra para moverse). Queda marcada en el vídeo final.

**Entrenamiento**
- *Entrenamiento › Analizar golpes en directo*: elige el golpe (o «Automático»), ponte de cuerpo entero delante del móvil y golpea. Cada golpe sale con su nota, sus ángulos y un consejo (también hablado).
- *Analizar un vídeo de la galería*: hasta 40 s; se analiza fotograma a fotograma y puedes ver cada golpe con el esqueleto.
- *Guía de biomecánica*: fases, claves, errores y ejercicios de cada golpe, más cadena cinética, split-step, empuñadura y prevención de lesiones.

## Ajustes útiles

- **Calidad**: 720p por defecto (ocupa menos y se monta antes); 1080p en Ajustes.
- **Autopausa**: segundos sin movimiento y sensibilidad (1-5). Si se pausa de más, baja la sensibilidad o sube los segundos.
- **PIN** para apuntar desde otros móviles.
- **Nombre** del móvil cuando hace de cámara extra.

## Cosas a saber

- La pantalla debe seguir encendida y la app delante mientras graba (la app impide que se apague la pantalla). Si se sale de la app, la grabación se cierra y se guarda lo grabado.
- La duración de cada punto se estima por el movimiento en la imagen; si no apuntas los puntos, no habrá resumen ni repeticiones.
- El directo funciona dentro de la wifi del club (no por internet). Algunas wifis de invitados aíslan los móviles entre sí: si no se ven, usa una wifi normal o el punto de acceso de un móvil.
- El montaje del partido con repeticiones vuelve a codificar todo el vídeo: un partido de 90 min puede tardar 20-40 min en el móvil.
- Las medidas de biomecánica son en 2D y los rangos son orientativos para jugadores aficionados.

## Estructura del código

```
app/src/main/java/com/padelgrabber/
  score/      reglas y marcador de pádel
  match/      datos de cada partido y guardado
  overlay/    dibujo del marcador y las cortinillas
  record/     grabación (CameraX + marcador incrustado)
  analysis/   movimiento, autopausa y gesto de videoarbitraje (ML Kit)
  net/        servidor del directo, cámaras por wifi, descubrimiento
  camera/     modo cámara extra
  viewer/     ver el directo
  review/     pantalla de videoarbitraje
  process/    montaje de repeticiones y resumen (Media3)
  upload/     subida a Supabase
  training/   entrenamiento y biomecánica
  ui/         resto de pantallas
app/src/main/assets/viewer.html   web del directo
docs/                             web de la plataforma (GitHub Pages)
supabase/setup.sql                configuración de Supabase
```
