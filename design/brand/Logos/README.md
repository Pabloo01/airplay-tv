# Logos
El símbolo es Pepe en pequeño: una tele coral con antenas amarillas y la pantalla crema con ojos. Lo genera `../generate.py`.

- `airplaytv-mark.svg`: símbolo a color (30×30), para fondos oscuros. En la app: `res/drawable/logo_mark.xml` (`Mark()` en `ui/design/Components.kt`).
- `airplaytv-mark-white.svg`: símbolo en blanco con la pantalla recortada, sobre fotos o vídeo (la app no lo usa).
- `airplaytv-wordmark.svg`: símbolo + "AirPlay TV" en Fredoka 600, para fondos oscuros. En la app el logotipo se compone con el símbolo y el texto `app_name` (`Wordmark()` en `ui/design/Components.kt`).

`../Pepe/pepe-*.svg`: Pepe en reposo en cada uno de sus 10 estados de ánimo (la app lo dibuja con `Pepe()` en `ui/design/Pepe.kt`, con la misma geometría y animado).
