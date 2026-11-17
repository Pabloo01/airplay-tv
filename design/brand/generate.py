#!/usr/bin/env python3
"""Generate every AirPlay TV brand asset from Zagalo, the mascot (approved prototypes `Icono` and `Zagalo`).

Outputs (run from the repo root: `python3 design/brand/generate.py`):
  app/src/main/res/drawable/logo_mark.xml                         small Zagalo-TV glyph next to the wordmark (vector)
  app/src/main/res/drawable/ic_launcher_{background,foreground,monochrome}.xml  adaptive icon layers
  app/src/main/res/mipmap-*/ic_launcher.png, ic_launcher_round.png  legacy icons (API < 26), simplified when small
  app/src/main/res/drawable-xhdpi/banner.png                      Android TV banner (320x180 dp)
  design/brand/AppIcon/*, design/brand/Logos/*                    editable SVG sources and PNG previews
  fastlane/metadata/android/en-US/images/{icon,featureGraphic}.png  store listing

Requires Pillow. Text uses the variable Fredoka from app/src/main/res/font (weight set per string).
The Zagalo geometry mirrors app/src/main/kotlin/.../ui/design/Zagalo.kt (both come from the 200x200
prototype SVG); keep them in step.
"""
import math
import os
import re
from PIL import Image, ImageChops, ImageDraw, ImageFont

ROOT = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", ".."))
RES = os.path.join(ROOT, "app", "src", "main", "res")
BRAND = os.path.join(ROOT, "design", "brand")
STORE = os.path.join(ROOT, "fastlane", "metadata", "android", "en-US", "images")
FREDOKA = os.path.join(RES, "font", "fredoka.ttf")

NAME = "AirPlay TV"
TAGLINE = "Tu sala de cine en casa"

# palette (Atv tokens)
NOCHE = "#16122B"
FLOOR = "#221B3D"
ICON_BG = "#2A2250"
PALOMITA = "#FFC94A"
CORAL = "#FF6B5B"
CORAL_DARK = "#D9483B"
CORAL_LIGHT = "#FF8D80"
BLUSH = "#FF9E8F"
SCREEN = "#FFF3DE"
INK = "#2A1633"
CREMA = "#FFF6EA"
WHITE = "#FFFFFF"


# ---------- shapes ----------
# ("rrect", (x, y, w, h, r), style) | ("ellipse", (cx, cy, rx, ry), style) | ("path", d, style)
# | ("text", (x, y, size, weight, text), style). style: fill, stroke, width, cap ("round"/"butt"),
# opacity (fill), evenodd.
def rect(x, y, w, h, r, **st):
    return ("rrect", (x, y, w, h, r), st)


def circle(cx, cy, r, **st):
    return ("ellipse", (cx, cy, r, r), st)


def ellipse(cx, cy, rx, ry, **st):
    return ("ellipse", (cx, cy, rx, ry), st)


def path(d, **st):
    return ("path", d, st)


def line(d, color, width, cap="round"):
    return ("path", d, {"stroke": color, "width": width, "cap": cap})


def text(x, y, size, weight, s, **st):
    return ("text", (x, y, size, weight, s), st)


# ---------- Zagalo (viewBox 200x200), parts in paint order with the moods that show them ----------
MOODS = ["hola", "curioso", "palomitas", "triste", "musica", "mareado", "dormido", "buscando", "herramientas", "lista"]
EVERY = set(MOODS)
ZAGALO_PARTS = [
    (EVERY, [rect(68, 166, 20, 20, 10, fill=CORAL_DARK), rect(112, 166, 20, 20, 10, fill=CORAL_DARK)]),
    (EVERY - {"mareado"}, [line("M86 54 L66 22", PALOMITA, 5), line("M114 54 L136 18", PALOMITA, 5),
                           circle(65, 20, 7, fill=PALOMITA), circle(137, 16, 7, fill=PALOMITA)]),
    ({"mareado"}, [line("M86 54 Q74 36 62 42 Q52 48 48 34", PALOMITA, 5),
                   line("M114 54 Q130 44 140 52 Q150 60 158 46", PALOMITA, 5),
                   circle(48, 32, 7, fill=PALOMITA), circle(159, 44, 7, fill=PALOMITA)]),
    ({"musica"}, [line("M40 104 Q40 38 100 38 Q160 38 160 104", INK, 8)]),
    (EVERY, [rect(30, 50, 140, 124, 32, fill=CORAL), rect(44, 56, 56, 6, 3, fill=CORAL_LIGHT),
             rect(46, 68, 108, 82, 22, fill=SCREEN), line("M54 159 h26 M54 166 h18", CORAL_DARK, 3.5),
             circle(136, 162, 5.5, fill=PALOMITA), circle(120, 162, 5.5, fill="#FFE2A3"),
             ellipse(64, 122, 8, 5, fill=BLUSH, opacity=0.8), ellipse(136, 122, 8, 5, fill=BLUSH, opacity=0.8)]),
    (EVERY - {"dormido", "mareado"}, [ellipse(80, 103, 8, 10, fill=INK), ellipse(120, 103, 8, 10, fill=INK),
                                      circle(83, 99, 3, fill=WHITE), circle(123, 99, 3, fill=WHITE)]),
    ({"dormido"}, [line("M72 104 Q80 110 88 104", INK, 4), line("M112 104 Q120 110 128 104", INK, 4)]),
    ({"mareado"}, [line("M73 96 L87 110 M87 96 L73 110", INK, 4), line("M113 96 L127 110 M127 96 L113 110", INK, 4)]),
    ({"triste"}, [line("M70 88 L88 82", INK, 4), line("M112 82 L130 88", INK, 4)]),
    ({"curioso", "buscando"}, [line("M110 84 Q120 75 131 81", INK, 4)]),
    ({"triste"}, [path("M130 112 Q135 121 130 125 Q125 121 130 112 Z", fill="#7FC8FF")]),
    ({"hola", "herramientas", "lista"}, [line("M86 123 Q100 137 114 123", INK, 4.5)]),
    ({"palomitas", "musica"}, [path("M84 120 Q100 144 116 120 Z", fill=INK),
                               path("M92 131 Q100 139 108 131 Q100 127 92 131 Z", fill=CORAL)]),
    ({"curioso", "buscando", "mareado"}, [ellipse(100, 128, 6, 7, fill=INK)]),
    ({"triste"}, [line("M88 133 Q100 123 112 133", INK, 4.5)]),
    ({"dormido"}, [line("M93 128 Q100 132 107 128", INK, 4)]),
    ({"palomitas"}, [rect(63, 90, 33, 25, 8, fill="#4FB3FF", opacity=0.9),
                     rect(104, 90, 33, 25, 8, fill="#FF5A7A", opacity=0.9),
                     rect(63, 90, 33, 25, 8, stroke=INK, width=4), rect(104, 90, 33, 25, 8, stroke=INK, width=4),
                     line("M96 100 H104 M63 98 L48 94 M137 98 L152 94", INK, 4)]),
    ({"musica"}, [rect(22, 90, 20, 36, 9, fill=PALOMITA, stroke=INK, width=4),
                  rect(158, 90, 20, 36, 9, fill=PALOMITA, stroke=INK, width=4)]),
    ({"palomitas"}, [circle(12, 128, 8, fill="#FFE7A8"), circle(23, 121, 9, fill="#FFF0C7"),
                     circle(35, 124, 9, fill="#FFE7A8"), circle(45, 130, 7, fill="#FFF0C7"),
                     circle(28, 112, 7, fill="#FFF0C7"), path("M6 132 L50 132 L44 184 L12 184 Z", fill=SCREEN),
                     line("M16 132 L19 184 M28 132 L28 184 M40 132 L37 184", "#E5484D", 5, cap="butt"),
                     circle(46, 150, 9, fill=CORAL)]),
    ({"hola"}, [line("M166 124 Q182 106 183 84", CORAL, 12), circle(183, 79, 10, fill=CORAL),
                line("M194 62 Q198 56 195 48 M174 60 Q170 53 173 46", PALOMITA, 3)]),
    ({"musica"}, [ellipse(181, 64, 7, 5.5, fill=PALOMITA), line("M187 63 V38 L197 34", PALOMITA, 4),
                  ellipse(16, 56, 6, 4.5, fill=BLUSH), line("M21 55 V34", BLUSH, 3.5)]),
    ({"dormido"}, [text(148, 46, 20, 600, "z", fill="#C8BFDA"), text(164, 30, 26, 600, "Z", fill="#C8BFDA"),
                   text(184, 14, 16, 600, "z", fill="#C8BFDA")]),
    ({"curioso"}, [text(152, 42, 40, 700, "?", fill=PALOMITA)]),
    ({"mareado"}, [path("M58 26 l3 7 7 3 -7 3 -3 7 -3 -7 -7 -3 7 -3 Z", fill=PALOMITA),
                   path("M140 18 l2.5 6 6 2.5 -6 2.5 -2.5 6 -2.5 -6 -6 -2.5 6 -2.5 Z", fill=PALOMITA),
                   path("M100 6 l2 5 5 2 -5 2 -2 5 -2 -5 -5 -2 5 -2 Z", fill=PALOMITA)]),
    ({"buscando"}, [line("M164 84 L150 102", CORAL, 12),
                    circle(176, 70, 15, fill=SCREEN, opacity=0.3, stroke=INK, width=5),
                    line("M169 70 Q176 63 183 70 M172.5 74 Q176 71 179.5 74", INK, 2.5)]),
    ({"herramientas"}, [line("M166 132 L182 112", CORAL, 12), line("M176 116 L190 96", "#B9C1D1", 7),
                        circle(192, 91, 9, fill="#B9C1D1"), circle(195, 87, 4, fill=NOCHE)]),
    ({"lista"}, [rect(158, 96, 36, 46, 6, fill=SCREEN, stroke=INK, width=4),
                 rect(169, 90, 14, 10, 3, fill=PALOMITA, stroke=INK, width=3),
                 line("M166 112 H186 M166 121 H186 M166 130 H178", INK, 3), circle(158, 132, 9, fill=CORAL)]),
]


def zagalo(mood):
    """Zagalo in `mood`, at rest, with his soft contact shadow."""
    shapes = [ellipse(100, 191, 50, 7, fill="#000000", opacity=0.28)]
    for moods, part in ZAGALO_PARTS:
        if mood in moods:
            shapes += part
    return shapes


# ---------- app icon (prototype `Icono`, 240x240; three levels of detail) ----------
ICON_V = 240
ICON_RADIUS = 54


def icon_zagalo(detail):
    """Zagalo's face for the icon. detail: "full" (large), "medium" (~96 px), "small" (~48 px)."""
    antenna_w, ball_r, eye = {"full": (10, 12, (10, 12)), "medium": (12, 13, (11, 13)), "small": (16, 0, (13, 15))}[detail]
    shapes = [line("M104 82 L80 36 M136 82 L162 32", PALOMITA, antenna_w)]
    if ball_r:
        shapes += [circle(79, 33, ball_r, fill=PALOMITA), circle(163, 29, ball_r, fill=PALOMITA)]
    shapes += [rect(38, 78, 164, 132, 36, fill=CORAL), rect(56, 96, 128, 90, 26, fill=SCREEN),
               ellipse(96, 134, *eye, fill=INK), ellipse(144, 134, *eye, fill=INK)]
    if detail == "full":
        shapes += [circle(99.5, 129.5, 3.5, fill=WHITE), circle(147.5, 129.5, 3.5, fill=WHITE),
                   ellipse(76, 156, 9, 6, fill=BLUSH), ellipse(164, 156, 9, 6, fill=BLUSH),
                   line("M104 158 Q120 174 136 158", INK, 6)]
    elif detail == "medium":
        shapes += [line("M104 158 Q120 174 136 158", INK, 8)]
    else:
        shapes += [line("M104 160 Q120 176 136 160", INK, 12)]
    return shapes


def icon_silhouette(color):
    """One-colour Zagalo for the monochrome layer: body with the screen cut out, face inside."""
    body = f"{rrect_d(38, 78, 202, 210, 36)}{rrect_d(56, 96, 184, 186, 26)}"
    return [line("M104 82 L80 36 M136 82 L162 32", color, 12),
            circle(79, 33, 13, fill=color), circle(163, 29, 13, fill=color),
            path(body, fill=color, evenodd=True),
            ellipse(96, 134, 11, 13, fill=color), ellipse(144, 134, 11, 13, fill=color),
            line("M104 158 Q120 174 136 158", color, 8)]


# adaptive icon: 108 dp layers; the 66 dp safe circle must hold all of Zagalo (the prototype puts the
# 240 artboard on the 72 dp visible square, i.e. 0.3 dp/unit; the antenna tips need 0.29)
ADAPTIVE = 108
ADAPTIVE_SCALE = 0.29

# ---------- small glyph next to the wordmark (prototype `Main`, 30x30) ----------
MARK_V = 30


def mark(color=None):
    if color:  # one-colour version: screen cut out of the body
        return [line("M11 7 L8 2 M19 7 L22 1", color, 2.4),
                path(rrect_d(3, 7, 27, 27, 6) + rrect_d(7, 10, 23, 22, 4), fill=color, evenodd=True),
                circle(12, 15, 1.6, fill=color), circle(18, 15, 1.6, fill=color)]
    return [line("M11 7 L8 2 M19 7 L22 1", PALOMITA, 2.4), rect(3, 7, 24, 20, 6, fill=CORAL),
            rect(7, 10, 16, 12, 4, fill=SCREEN), circle(12, 15, 1.6, fill=INK), circle(18, 15, 1.6, fill=INK)]


# ---------- path data helpers ----------
def num(v):
    return f"{v:.3f}".rstrip("0").rstrip(".")


def rrect_d(x0, y0, x1, y1, r):
    w, h = x1 - x0, y1 - y0
    if not r:
        return f"M{num(x0)},{num(y0)}h{num(w)}v{num(h)}h-{num(w)}z"
    return (f"M{num(x0 + r)},{num(y0)}h{num(w - 2 * r)}a{num(r)},{num(r)} 0 0 1 {num(r)},{num(r)}v{num(h - 2 * r)}"
            f"a{num(r)},{num(r)} 0 0 1 -{num(r)},{num(r)}h-{num(w - 2 * r)}a{num(r)},{num(r)} 0 0 1 -{num(r)},-{num(r)}"
            f"v-{num(h - 2 * r)}a{num(r)},{num(r)} 0 0 1 {num(r)},-{num(r)}z")


def ellipse_d(cx, cy, rx, ry):
    return (f"M{num(cx - rx)},{num(cy)}a{num(rx)},{num(ry)} 0 1 0 {num(2 * rx)},0"
            f"a{num(rx)},{num(ry)} 0 1 0 -{num(2 * rx)},0z")


def shape_d(kind, geo):
    if kind == "rrect":
        x, y, w, h, r = geo
        return rrect_d(x, y, x + w, y + h, r)
    if kind == "ellipse":
        return ellipse_d(*geo)
    return geo


def argb(hex_color, alpha=1.0):
    return f"#{round(alpha * 255):02X}{hex_color.lstrip('#').upper()}"


def write(p, s):
    os.makedirs(os.path.dirname(p), exist_ok=True)
    with open(p, "w") as f:
        f.write(s)


# ---------- Android vector drawables ----------
def vector_paths(shapes, indent="    "):
    out = []
    for kind, geo, st in shapes:
        attrs = [f'android:pathData="{shape_d(kind, geo)}"']
        if st.get("fill"):
            attrs.append(f'android:fillColor="{argb(st["fill"], st.get("opacity", 1.0))}"')
            if st.get("evenodd"):
                attrs.append('android:fillType="evenOdd"')
        if st.get("stroke"):
            attrs += [f'android:strokeColor="{argb(st["stroke"])}"', f'android:strokeWidth="{num(st["width"])}"']
            if kind == "path" and st.get("cap", "butt") == "round":
                attrs.append('android:strokeLineCap="round"')
        out.append(f"{indent}<path\n{indent}    " + f"\n{indent}    ".join(attrs) + " />")
    return "\n".join(out)


def vector_doc(body, size, viewport):
    return ('<?xml version="1.0" encoding="utf-8"?>\n'
            '<!-- Generated by design/brand/generate.py; edit that script instead. -->\n'
            '<vector xmlns:android="http://schemas.android.com/apk/res/android"\n'
            f'    android:width="{size}dp"\n    android:height="{size}dp"\n'
            f'    android:viewportWidth="{viewport}"\n    android:viewportHeight="{viewport}">\n'
            f"{body}\n</vector>\n")


def adaptive_group(shapes):
    s = ADAPTIVE_SCALE
    t = ADAPTIVE / 2 - ICON_V / 2 * s
    return (f'    <group\n        android:scaleX="{num(s)}"\n        android:scaleY="{num(s)}"\n'
            f'        android:translateX="{num(t)}"\n        android:translateY="{num(t)}">\n'
            f"{vector_paths(shapes, '        ')}\n    </group>")


def write_vectors():
    d = os.path.join(RES, "drawable")
    write(os.path.join(d, "logo_mark.xml"), vector_doc(vector_paths(mark()), MARK_V, MARK_V))
    # the TV draws the launcher icon at ~48 dp = ~96 px: the prototype's 96 px level of detail
    write(os.path.join(d, "ic_launcher_foreground.xml"), vector_doc(adaptive_group(icon_zagalo("medium")), ADAPTIVE, ADAPTIVE))
    write(os.path.join(d, "ic_launcher_monochrome.xml"), vector_doc(adaptive_group(icon_silhouette(WHITE)), ADAPTIVE, ADAPTIVE))
    write(os.path.join(d, "ic_launcher_background.xml"),
          vector_doc(vector_paths([rect(0, 0, ADAPTIVE, ADAPTIVE, 0, fill=ICON_BG)]), ADAPTIVE, ADAPTIVE))


# ---------- SVG sources ----------
def svg_shapes(shapes):
    out = []
    for kind, geo, st in shapes:
        a = []
        if kind == "text":
            x, y, size, weight, s = geo
            out.append(f'<text x="{num(x)}" y="{num(y)}" font-family="Fredoka, sans-serif" font-size="{size}" '
                       f'font-weight="{weight}" fill="{st["fill"]}">{s}</text>')
            continue
        a.append(f'fill="{st["fill"]}"' if st.get("fill") else 'fill="none"')
        if st.get("fill") and st.get("opacity", 1.0) != 1.0:
            a.append(f'fill-opacity="{num(st["opacity"])}"')
        if st.get("evenodd"):
            a.append('fill-rule="evenodd"')
        if st.get("stroke"):
            a += [f'stroke="{st["stroke"]}"', f'stroke-width="{num(st["width"])}"']
            if kind == "path" and st.get("cap", "butt") == "round":
                a.append('stroke-linecap="round"')
        if kind == "rrect":
            x, y, w, h, r = geo
            g = f'<rect x="{num(x)}" y="{num(y)}" width="{num(w)}" height="{num(h)}" rx="{num(r)}"'
        elif kind == "ellipse":
            cx, cy, rx, ry = geo
            g = (f'<circle cx="{num(cx)}" cy="{num(cy)}" r="{num(rx)}"' if rx == ry
                 else f'<ellipse cx="{num(cx)}" cy="{num(cy)}" rx="{num(rx)}" ry="{num(ry)}"')
        else:
            g = f'<path d="{geo}"'
        out.append(f"{g} {' '.join(a)}/>")
    return "\n  ".join(out)


def svg(w, h, body, vb=None):
    return (f'<svg xmlns="http://www.w3.org/2000/svg" width="{num(w)}" height="{num(h)}" '
            f'viewBox="{vb or f"0 0 {num(w)} {num(h)}"}">\n  {body}\n</svg>\n')


def font(size, weight):
    f = ImageFont.truetype(FREDOKA, max(1, round(size)))
    f.set_variation_by_axes([weight, 100])  # wght, wdth
    return f


def write_svgs():
    logos, app_icon = os.path.join(BRAND, "Logos"), os.path.join(BRAND, "AppIcon")
    write(os.path.join(logos, "airplaytv-mark.svg"), svg(MARK_V, MARK_V, svg_shapes(mark())))
    write(os.path.join(logos, "airplaytv-mark-white.svg"), svg(MARK_V, MARK_V, svg_shapes(mark(WHITE))))
    text_w = font(20, 600).getlength(NAME)
    wordmark = (f"{svg_shapes(mark())}\n  <text x=\"40\" y=\"21.5\" font-family=\"Fredoka, sans-serif\" "
                f"font-weight=\"600\" font-size=\"20\" letter-spacing=\"0.2\" fill=\"{CREMA}\">{NAME}</text>")
    write(os.path.join(logos, "airplaytv-wordmark.svg"), svg(math.ceil(40 + text_w + 4), MARK_V, wordmark))
    for mood in MOODS:
        write(os.path.join(BRAND, "Zagalo", f"zagalo-{mood}.svg"), svg(200, 200, svg_shapes(zagalo(mood))))
    write(os.path.join(app_icon, "app-icon.svg"),
          svg(ICON_V, ICON_V, svg_shapes([rect(0, 0, ICON_V, ICON_V, ICON_RADIUS, fill=ICON_BG)] + icon_zagalo("full"))))
    s = ADAPTIVE_SCALE
    t = ADAPTIVE / 2 - ICON_V / 2 * s
    fg = f'<g transform="translate({num(t)} {num(t)}) scale({num(s)})">\n  {svg_shapes(icon_zagalo("medium"))}\n  </g>'
    write(os.path.join(app_icon, "adaptive-background.svg"), svg(ADAPTIVE, ADAPTIVE, f'<rect width="108" height="108" fill="{ICON_BG}"/>'))
    write(os.path.join(app_icon, "adaptive-foreground.svg"), svg(ADAPTIVE, ADAPTIVE, fg))


# ---------- raster (Pillow, supersampled) ----------
SS = 6
TOKEN = re.compile(r"[MmLlHhVvQqAaZz]|-?\d*\.?\d+(?:e-?\d+)?")


def arc_points(x1, y1, rx, ry, phi, large, sweep, x2, y2, steps=24):
    """SVG elliptical arc (endpoint form, spec F.6.5) -> points after (x1, y1)."""
    if rx == 0 or ry == 0:
        return [(x2, y2)]
    phi = math.radians(phi)
    c, s = math.cos(phi), math.sin(phi)
    dx, dy = (x1 - x2) / 2, (y1 - y2) / 2
    x1p, y1p = c * dx + s * dy, -s * dx + c * dy
    rx, ry = abs(rx), abs(ry)
    lam = x1p ** 2 / rx ** 2 + y1p ** 2 / ry ** 2
    if lam > 1:
        rx, ry = rx * math.sqrt(lam), ry * math.sqrt(lam)
    den = rx ** 2 * y1p ** 2 + ry ** 2 * x1p ** 2
    coef = math.sqrt(max(0.0, (rx ** 2 * ry ** 2 - den) / den)) * (-1 if large == sweep else 1)
    cxp, cyp = coef * rx * y1p / ry, -coef * ry * x1p / rx
    cx, cy = c * cxp - s * cyp + (x1 + x2) / 2, s * cxp + c * cyp + (y1 + y2) / 2
    t1 = math.atan2((y1p - cyp) / ry, (x1p - cxp) / rx)
    dt = math.atan2((-y1p - cyp) / ry, (-x1p - cxp) / rx) - t1
    if not sweep and dt > 0:
        dt -= 2 * math.pi
    elif sweep and dt < 0:
        dt += 2 * math.pi
    return [(c * rx * math.cos(a) - s * ry * math.sin(a) + cx, s * rx * math.cos(a) + c * ry * math.sin(a) + cy)
            for a in (t1 + dt * k / steps for k in range(1, steps + 1))]


def flatten(d, steps=24):
    """SVG path data (M L H V Q A Z, absolute or relative) -> list of (points, closed)."""
    toks = TOKEN.findall(d)
    subs, pts, i, cmd = [], [], 0, None
    x = y = sx = sy = 0.0

    def val():
        nonlocal i
        i += 1
        return float(toks[i - 1])

    while i < len(toks):
        if toks[i].isalpha():
            cmd = toks[i]
            i += 1
        rel = cmd.islower()
        c = cmd.upper()
        if c == "Z":
            if pts:
                subs.append((pts, True))
            pts, x, y = [], sx, sy
            continue
        if c == "M":
            if pts:
                subs.append((pts, False))
            nx, ny = val(), val()
            x, y = (x + nx, y + ny) if rel else (nx, ny)
            sx, sy, pts = x, y, [(x, y)]
            cmd = "l" if rel else "L"  # extra pairs after M are lines
        elif c == "L":
            nx, ny = val(), val()
            x, y = (x + nx, y + ny) if rel else (nx, ny)
            pts.append((x, y))
        elif c == "H":
            n = val()
            x = x + n if rel else n
            pts.append((x, y))
        elif c == "V":
            n = val()
            y = y + n if rel else n
            pts.append((x, y))
        elif c == "Q":
            cx, cy, nx, ny = val(), val(), val(), val()
            if rel:
                cx, cy, nx, ny = x + cx, y + cy, x + nx, y + ny
            for k in range(1, steps + 1):
                t = k / steps
                pts.append(((1 - t) ** 2 * x + 2 * (1 - t) * t * cx + t * t * nx,
                            (1 - t) ** 2 * y + 2 * (1 - t) * t * cy + t * t * ny))
            x, y = nx, ny
        elif c == "A":
            rx, ry, phi, large, sweep, nx, ny = (val() for _ in range(7))
            if rel:
                nx, ny = x + nx, y + ny
            pts += arc_points(x, y, rx, ry, phi, int(large), int(sweep), nx, ny)
            x, y = nx, ny
    if pts:
        subs.append((pts, False))
    return subs


def rgb(hex_color):
    h = hex_color.lstrip("#")
    return tuple(int(h[k:k + 2], 16) for k in (0, 2, 4))


class Raster:
    """An opaque RGB canvas `w` x `h` px, drawn at SS x and downsampled on `image()`."""

    def __init__(self, w, h, bg):
        self.w, self.h = w, h
        self.img = Image.new("RGB", (w * SS, h * SS), rgb(bg))

    def _paint_mask(self, color, opacity, mask):
        if opacity < 1:
            mask = mask.point(lambda v: round(v * opacity))
        self.img.paste(rgb(color), (0, 0), mask)

    def _paint(self, color, opacity, draw_mask):
        mask = Image.new("L", self.img.size, 0)
        draw_mask(ImageDraw.Draw(mask))
        self._paint_mask(color, opacity, mask)

    def shapes(self, shapes, scale, ox=0.0, oy=0.0):
        """Draw `shapes` with units -> px mapping px = o + unit * scale."""
        k = scale * SS
        P = lambda px, py: ((ox + px * scale) * SS, (oy + py * scale) * SS)
        for kind, geo, st in shapes:
            if kind == "text":
                x, y, size, weight, s = geo
                self._paint(st["fill"], 1.0, lambda d: d.text(P(x, y), s, font=font(size * k, weight), fill=255, anchor="ls"))
                continue
            w = st.get("width", 0) * k
            if kind in ("rrect", "ellipse"):
                if kind == "rrect":
                    x, y, gw, gh, r = geo
                    box, rad = [*P(x, y), *P(x + gw, y + gh)], r * k
                    fill_fn = lambda d, b=box, rr=rad: d.rounded_rectangle(b, radius=rr, fill=255)
                    stroke_fn = lambda d, b=box, rr=rad: d.rounded_rectangle(
                        [b[0] - w / 2, b[1] - w / 2, b[2] + w / 2, b[3] + w / 2], radius=rr + w / 2, outline=255, width=round(w))
                else:
                    cx, cy, rx, ry = geo
                    box = [*P(cx - rx, cy - ry), *P(cx + rx, cy + ry)]
                    fill_fn = lambda d, b=box: d.ellipse(b, fill=255)
                    stroke_fn = lambda d, b=box: d.ellipse(
                        [b[0] - w / 2, b[1] - w / 2, b[2] + w / 2, b[3] + w / 2], outline=255, width=round(w))
                if st.get("fill"):
                    self._paint(st["fill"], st.get("opacity", 1.0), fill_fn)
                if st.get("stroke"):
                    self._paint(st["stroke"], 1.0, stroke_fn)
                continue
            subs = [([P(*p) for p in pts], closed) for pts, closed in flatten(geo)]
            if st.get("fill"):
                # each subpath XORs into the mask: even-odd, which is all these shapes need
                mask = Image.new("L", self.img.size, 0)
                for pts, _ in subs:
                    if len(pts) > 2:
                        layer = Image.new("L", self.img.size, 0)
                        ImageDraw.Draw(layer).polygon(pts, fill=255)
                        mask = ImageChops.difference(mask, layer)
                self._paint_mask(st["fill"], st.get("opacity", 1.0), mask)
            if st.get("stroke"):
                round_cap = st.get("cap", "butt") == "round"

                def stroke_fn(d):
                    for pts, closed in subs:
                        d.line(pts + ([pts[0]] if closed else []), fill=255, width=round(w), joint="curve")
                        if round_cap and not closed:
                            for px, py in (pts[0], pts[-1]):
                                d.ellipse([px - w / 2, py - w / 2, px + w / 2, py + w / 2], fill=255)
                self._paint(st["stroke"], 1.0, stroke_fn)

    def text(self, x, y, s, size, weight, color, anchor="ls"):
        """Text in px: (x, y) is the left baseline by default."""
        self._paint(color, 1.0, lambda d: d.text((x * SS, y * SS), s, font=font(size * SS, weight), fill=255, anchor=anchor))

    def image(self, mask=None):
        out = self.img.resize((self.w, self.h), Image.LANCZOS).convert("RGBA")
        if mask:
            m = Image.new("L", self.img.size, 0)
            mask(ImageDraw.Draw(m), self.img.size[0])
            out.putalpha(m.resize((self.w, self.h), Image.LANCZOS))
        return out


def icon_png(px, shape="square", detail=None):
    """The whole icon (rounded square art) at `px`; `shape` masks it (square = prototype radius, round = circle)."""
    detail = detail or ("small" if px <= 48 else "medium" if px <= 96 else "full")
    r = Raster(px, px, ICON_BG)
    r.shapes(icon_zagalo(detail), px / ICON_V)
    if shape == "round":
        return r.image(lambda d, n: d.ellipse([0, 0, n - 1, n - 1], fill=255))
    return r.image(lambda d, n: d.rounded_rectangle([0, 0, n - 1, n - 1], radius=n * ICON_RADIUS / ICON_V, fill=255))


def legacy_icons():
    for name, size in {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}.items():
        for fname, shape in (("ic_launcher.png", "square"), ("ic_launcher_round.png", "round")):
            p = os.path.join(RES, f"mipmap-{name}", fname)
            os.makedirs(os.path.dirname(p), exist_ok=True)
            icon_png(size, shape).save(p, optimize=True)


# banner composition (prototype `Icono`): 584x328, scaled to the output height and centred
BANNER_W, BANNER_H = 584, 328


def banner(w, h, out):
    k = h / BANNER_H
    ox = (w - BANNER_W * k) / 2
    r = Raster(w, h, NOCHE)
    r.shapes([rect(-ox / k, BANNER_H - 64, w / k, 64, 0, fill=FLOOR),
              circle(333.5, 43.5, 3.5, fill=PALOMITA, opacity=0.5),
              circle(522.5, 92.5, 2.5, fill=CREMA, opacity=0.4)], k, ox)
    r.shapes(zagalo("palomitas"), 1.25 * k, ox + 22 * k, 52 * k)
    # "AirPlay TV": Fredoka 700 56/60 at (290, 108); tagline Fredoka 500 24 in Palomita, 6 below
    title, tag = font(56, 700), font(24, 500)
    asc, desc = title.getmetrics()
    base1 = 108 + (60 - asc - desc) / 2 + asc
    tasc, tdesc = tag.getmetrics()
    base2 = 108 + 60 + 6 + tasc
    width = title.getlength(NAME)
    size1 = 56 if 290 + width <= BANNER_W - 16 else 56 * (BANNER_W - 16 - 290) / width
    r.text(ox + 290 * k, base1 * k, NAME, size1 * k, 700, CREMA)
    r.text(ox + 290 * k, base2 * k, TAGLINE, 24 * k, 500, PALOMITA)
    img = r.image().convert("RGB")
    os.makedirs(os.path.dirname(out), exist_ok=True)
    img.save(out, optimize=True)


def previews():
    icon = icon_png(512, detail="full")
    icon.save(os.path.join(BRAND, "AppIcon", "app-icon-512.png"), optimize=True)
    banner(1280, 720, os.path.join(BRAND, "AppIcon", "tv-banner-1280x720.png"))
    if os.path.isdir(STORE):
        icon.save(os.path.join(STORE, "icon.png"), optimize=True)
        banner(1024, 500, os.path.join(STORE, "featureGraphic.png"))


if __name__ == "__main__":
    write_vectors()
    write_svgs()
    legacy_icons()
    banner(640, 360, os.path.join(RES, "drawable-xhdpi", "banner.png"))
    previews()
    print("brand assets generated")
