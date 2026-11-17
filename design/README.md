# Diseño de AirPlay TV

**Noche de peli en el sofá.** La app es la sala de estar a oscuras: fondo Noche (`#16122b`) con
alguna estrella, tarjetas sólidas y redondeadas (Butaca `#231d3d`), letra Fredoka en los títulos y
Nunito en los textos, y todo lo que el mando tiene enfocado se pinta de amarillo palomita
(`#ffc94a`). El coral (`#ff6b5b`) es el color de marca y de lo que está "en directo"; el rojo
(`#d63b3b`) solo aparece en las acciones que paran algo.

**Zagalo** es la mascota: una tele con orejas de conejo que vive en cada pantalla y pone cara según el
momento (saluda en el inicio, se asoma curioso cuando alguien llama, come palomitas con gafas 3D
mientras ves la peli, se marea si se corta la conexión, duerme en el salvapantallas…). Está dibujado
en código (`app/src/main/kotlin/…/ui/design/Zagalo.kt`) a partir de `brand/Zagalo/`.

- `brand/`: logo, icono de la app, banner de Android TV y las 10 caras de Zagalo, generados con `brand/generate.py`.
- `fonts/`: licencias de Fredoka y Nunito, las fuentes variables de `app/src/main/res/font`.
- `mocks/`: los prototipos aprobados a 960×540 (1 px = 1 dp en la tele), fuente `.dc.html`, y el lienzo que los ordena (`canvas.json`). Los textos entre corchetes y los nombres de ejemplo («iPhone de Ana», «TV Pablo», «4827»…) son datos de muestra.

## Marca

| Tablero | Fichero |
|---|---|
| Mascota: candidatas y caras de Zagalo | `mocks/Mascota.dc.html` |
| Zagalo (componente con 10 caras) | `mocks/Zagalo.dc.html` |
| Icono y banner de la tele | `mocks/Icono.dc.html` |
| Estilo: color, letra y botones | `mocks/Estilo.dc.html` |

## Pantallas

| # | Pantalla | Zagalo | Fichero |
|---|---|---|---|
| 1 | Inicio: esperando | saluda | `mocks/Main.dc.html` |
| 2 | Alguien quiere conectarse | curioso | `mocks/Solicitud.dc.html` |
| 3 | Código de emparejamiento | con su lista | `mocks/Codigo.dc.html` |
| 4 | Peli en marcha + menú (OK) | palomitas | `mocks/Duplicando.dc.html` |
| 5 | ¿Dejar de compartir? | triste | `mocks/Detener.dc.html` |
| 6 | Solo música | con cascos | `mocks/Musica.dc.html` |
| 7 | Ajustes | herramientas | `mocks/Ajustes.dc.html` |
| 8 | Dispositivos permitidos | con su lista | `mocks/Dispositivos.dc.html` |
| 9 | Conexión perdida | mareado | `mocks/Perdida.dc.html` |
| 10 | Sin Wi-Fi | buscando | `mocks/SinRed.dc.html` |
| 11 | Salvapantallas (tras 2 min en espera) | dormido | `mocks/Salvapantallas.dc.html` |

En las pantallas 4, 5 y 9 el paisaje nocturno solo representa la película: en la app se ve el vídeo
real y encima únicamente el menú. Los demás estados del inicio (conectando, receptor detenido,
error y falta de permiso) usan la misma composición que la pantalla 1 con su texto, su acción y otra
cara de Zagalo. Los ajustes avanzados y los registros no tienen prototipo: siguen la misma estética.
