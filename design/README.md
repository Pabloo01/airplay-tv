# Diseño de AirPlay TV

- `brand/`: logo, icono de app y banner de Android TV de AirPlay TV, generados con `brand/generate.py`. Color de marca: `#fc5858`.
- `mocks/`: las 10 pantallas de TV a 960×540 (fuente `.dc.html`), con los tokens (`ds/airplayjd/tokens.json`, `tokens.css`) y los estilos de componentes (`components/bundle.css`) del design system.

Versión interactiva de los mocks: https://claude.ai/artifact/7VoeriGH98DemQXVziTgSG
Design system: https://claude.ai/artifact/6nancAmD3HzieqHsU26a91

| # | Pantalla | Fichero |
|---|---|---|
| 1 | Espera | `mocks/Main.dc.html` |
| 2 | Solicitud de conexión | `mocks/Request.dc.html` |
| 3 | Código de emparejamiento | `mocks/Pin.dc.html` |
| 4 | Duplicado de pantalla | `mocks/Mirroring.dc.html` |
| 5 | Audio con metadatos | `mocks/Audio.dc.html` |
| 6 | Ajustes | `mocks/Settings.dc.html` |
| 7 | Error: sin red | `mocks/ErrorNetwork.dc.html` |
| 8 | Error: conexión perdida | `mocks/ErrorLost.dc.html` |
| 9 | Salvapantallas (tras 2 min en espera) | `mocks/Screensaver.dc.html` |
| 10 | Dispositivos permitidos | `mocks/Devices.dc.html` |

Los mocks son de la versión anterior de la marca (Air Play JD): sirven de referencia de pantallas, pero el logo y el nombre vigentes son los de `brand/`.
