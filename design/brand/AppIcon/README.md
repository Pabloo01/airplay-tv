# AppIcon
Icono y banners de AirPlay TV: Zagalo, la tele con antenas de conejo, sonriendo sobre un cuadrado redondeado `#2A2250`.

Todo lo genera `../generate.py` (ejecútalo desde la raíz del repo: `python3 design/brand/generate.py`, necesita Pillow). No edites a mano los ficheros generados.

- `app-icon.svg` / `app-icon-512.png`: icono completo (ficha de tienda y vista previa; también `fastlane/.../images/icon.png`).
- `adaptive-background.svg`, `adaptive-foreground.svg`: capas del icono adaptativo (108dp: fondo `#2A2250` liso y Zagalo dentro de la zona segura de 66dp). En la app son vectores: `res/drawable/ic_launcher_{background,foreground,monochrome}.xml`; el monocromo es la silueta de Zagalo con la pantalla recortada.
- Niveles de detalle: el icono adaptativo y los PNG de 72–96px usan la versión de 96px (sin brillos ni mejillas); el de 48px quita también las bolas de las antenas y engorda trazos; a partir de 144px, el completo.
- `tv-banner-1280x720.png`: banner de TV a alta resolución (Zagalo con gafas 3D y palomitas, "AirPlay TV" y "Tu sala de cine en casa" en Fredoka). El de la app (`res/drawable-xhdpi/banner.png`, 640×360) sale del mismo script y es el que se ve en la fila de apps de Google TV; `fastlane/.../featureGraphic.png` (1024×500) es la misma composición.
