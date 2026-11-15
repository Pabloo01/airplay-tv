# AppIcon
Icono y banners de AirPlay TV: la tele con el botón de play y las ondas, en blanco sobre el degradado de marca (#fc5858 → #8c2a2e).

Todo lo genera `../generate.py` (ejecútalo desde la raíz del repo: `python3 design/brand/generate.py`, necesita Pillow). No edites a mano los ficheros generados.

- `app-icon.svg` / `app-icon-512.png`: icono completo (ficha de tienda y vista previa).
- `adaptive-background.svg`, `adaptive-foreground.svg`: capas del icono adaptativo (108dp, el símbolo dentro de la zona segura). En la app son vectores: `res/drawable/ic_launcher_{background,foreground,monochrome}.xml`.
- `tv-banner-1280x720.png`: banner de TV a alta resolución. El de la app (`res/drawable-xhdpi/banner.png`, 640×360) sale del mismo script y es el que se ve en la fila de apps de Google TV.
