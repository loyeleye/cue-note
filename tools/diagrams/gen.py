"""Draws Cue Note's suggested diagrams as plain SVG files.

Every diagram is 360x240, drawn on white "paper" so it sits beside a
photo of the teacher's own sketch. This file is the one list of diagrams:
it also writes the catalog the edge functions show the model.

Run from the repository root:
    python3 tools/diagrams/gen.py
which writes web/static/diagrams/<id>.svg, web/static/diagrams/index.json
and supabase/functions/_shared/diagrams.ts.
"""
import json
import math
import sys
from pathlib import Path

INK = "#2d2a32"
MUTED = "#9d97a6"
ACC = "#c8553d"     # movement
AIR = "#3a7ca5"     # air and sound
SOFT = "#ece8f1"
FILL2 = "#f7e2da"
FILL3 = "#d9e8f3"

W, H = 360, 240


def svg(body, title):
    return f"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 {W} {H}" width="{W}" height="{H}" role="img" aria-label="{title}">
<title>{title}</title>
<defs>
<marker id="aA" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="{ACC}"/></marker>
<marker id="aB" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="{AIR}"/></marker>
<marker id="aI" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="6" markerHeight="6" orient="auto-start-reverse"><path d="M0,0 L10,5 L0,10 z" fill="{INK}"/></marker>
</defs>
<rect width="{W}" height="{H}" rx="10" fill="#ffffff"/>
<g font-family="system-ui, -apple-system, 'Segoe UI', Roboto, sans-serif" font-size="12" fill="{INK}" stroke-linecap="round" stroke-linejoin="round">
{body}
</g>
</svg>
"""


def t(x, y, s, anchor="start", size=12, weight=400, color=INK, italic=False):
    st = ' font-style="italic"' if italic else ""
    return f'<text x="{x}" y="{y}" text-anchor="{anchor}" font-size="{size}" font-weight="{weight}" fill="{color}"{st}>{s}</text>'


def cap(x, y, s, color=INK):
    return t(x, y, s, "middle", 12.5, 600, color)


def p(d, stroke=INK, width=2, fill="none", dash=None, marker=None, opacity=None):
    a = f'<path d="{d}" stroke="{stroke}" stroke-width="{width}" fill="{fill}"'
    if dash:
        a += f' stroke-dasharray="{dash}"'
    if marker:
        a += f' marker-end="url(#{marker})"'
    if opacity is not None:
        a += f' opacity="{opacity}"'
    return a + "/>"


def arrow(x1, y1, x2, y2, color="A", width=2):
    c = {"A": ACC, "B": AIR, "I": INK}[color]
    return p(f"M{x1},{y1} L{x2},{y2}", c, width, marker="a" + color)


def dot(x, y, r=3.5, color=INK):
    return f'<circle cx="{x}" cy="{y}" r="{r}" fill="{color}"/>'


def circle(x, y, r, stroke=INK, fill="none", width=2, dash=None):
    d = f' stroke-dasharray="{dash}"' if dash else ""
    return f'<circle cx="{x}" cy="{y}" r="{r}" stroke="{stroke}" stroke-width="{width}" fill="{fill}"{d}/>'


def ellipse(x, y, rx, ry, stroke=INK, fill="none", width=2, opacity=None):
    o = f' opacity="{opacity}"' if opacity is not None else ""
    return f'<ellipse cx="{x}" cy="{y}" rx="{rx}" ry="{ry}" stroke="{stroke}" stroke-width="{width}" fill="{fill}"{o}/>'


def divider(x=180):
    return p(f"M{x},22 L{x},218", MUTED, 1, dash="3 4")


def wave(x0, x1, y, amp, period, phase=0.0, step=2):
    pts = []
    x = x0
    while x <= x1:
        pts.append(f"{x:.1f},{y - amp * math.sin(2 * math.pi * (x - x0) / period + phase):.1f}")
        x += step
    return "M" + " L".join(pts)


# ---------------------------------------------------------------- Breath

def diaphragm():
    b = []
    b.append(arrow(180, 8, 180, 34, "B"))
    b.append(t(188, 22, "Air in", color=AIR))
    # rib cage at rest (dashed) and breathing in (solid)
    b.append(p("M152,44 C120,60 112,110 120,158", MUTED, 2, dash="5 4"))
    b.append(p("M208,44 C240,60 248,110 240,158", MUTED, 2, dash="5 4"))
    b.append(p("M152,44 C110,60 100,112 108,162", INK, 2.2))
    b.append(p("M208,44 C250,60 260,112 252,162", INK, 2.2))
    for y, s in ((74, 14), (98, 16), (122, 16)):
        b.append(p(f"M{116 - (y - 74) / 8:.0f},{y} Q180,{y + s} {244 + (y - 74) / 8:.0f},{y}", INK, 1.2, opacity=0.5))
    b.append(p("M120,158 C134,104 226,104 240,158", MUTED, 2, dash="5 4"))
    b.append(p("M108,162 C128,134 232,134 252,162", ACC, 3))
    b.append(arrow(180, 110, 180, 132))
    b.append(arrow(104, 106, 84, 106))
    b.append(arrow(256, 106, 276, 106))
    b.append(t(80, 102, "Ribs", "end"))
    b.append(t(80, 116, "widen", "end"))
    b.append(t(282, 102, "Ribs"))
    b.append(t(282, 116, "widen"))
    b.append(cap(180, 186, "The diaphragm lowers and flattens", ACC))
    b.append(p("M86,214 L110,214", MUTED, 2, dash="5 4"))
    b.append(t(116, 218, "At rest"))
    b.append(p("M190,214 L214,214", INK, 2.2))
    b.append(t(220, 218, "Breathing in"))
    return b


def side_figure(x, head_dx=0, head_dy=0, shoulder_dy=0, slump=0):
    """A simple side view from the head to the hips, facing right."""
    b = []
    hx, hy = x + head_dx, 48 + head_dy
    b.append(circle(hx, hy, 15, INK, SOFT))
    b.append(p(f"M{hx + 13},{hy + 2} L{hx + 19},{hy + 8} L{hx + 12},{hy + 10}", INK, 1.6))
    sy = 82 + shoulder_dy
    b.append(p(f"M{hx - 4},{hy + 15} L{x - 4},{sy - 4}", INK, 2))
    b.append(p(f"M{hx + 6},{hy + 14} L{x + 8},{sy - 6}", INK, 2))
    back = f"M{x - 6},{sy - 4} C{x - 14 - slump},{sy + 30} {x - 14 - slump * 0.5},{sy + 70} {x - 10},175"
    front = f"M{x + 8},{sy - 4} C{x + 26 - slump},{sy + 14} {x + 24 - slump},{sy + 50} {x + 18},175"
    b.append(p(back, INK, 2))
    b.append(p(front, INK, 2))
    return b, (x, sy)


def low_high_breath():
    b = [divider()]
    f, _ = side_figure(85)
    b += f
    b.append(ellipse(89, 150, 26, 16, "none", FILL2, 0))
    b += f[-2:]
    b.append(arrow(110, 150, 132, 150))
    b.append(arrow(72, 146, 50, 146))
    b.append(t(136, 146, "belly"))
    b.append(t(46, 140, "back", "end"))
    b.append(cap(90, 204, "Low breath"))
    b.append(t(90, 220, "Belly and back widen", "middle", 11.5))
    f, (sx, sy) = side_figure(262, shoulder_dy=-6)
    b += f
    b.append(dot(sx + 2, sy - 4, 4, ACC))
    b.append(arrow(sx + 2, sy + 22, sx + 2, sy - 16))
    b.append(t(sx + 16, sy + 4, "shoulders"))
    b.append(t(sx + 16, sy + 18, "lift"))
    b.append(cap(270, 204, "High breath"))
    b.append(t(270, 220, "Shoulders and chest rise", "middle", 11.5))
    return b


def appoggio():
    b = []
    cx = 90
    b.append(p(f"M{cx - 26},40 C{cx - 56},56 {cx - 62},100 {cx - 56},150", INK, 2.2))
    b.append(p(f"M{cx + 26},40 C{cx + 56},56 {cx + 62},100 {cx + 56},150", INK, 2.2))
    for y in (70, 92, 114):
        b.append(p(f"M{cx - 52},{y} Q{cx},{y + 14} {cx + 52},{y}", INK, 1.2, opacity=0.5))
    b.append(arrow(cx - 60, 100, cx - 78, 100))
    b.append(arrow(cx + 60, 100, cx + 78, 100))
    b.append(t(cx, 172, "Ribs stay open", "middle", 12.5, 600, ACC))
    b.append(p(f"M{cx},34 C{cx},22 {cx + 20},16 {cx + 40},16", AIR, 1.5, marker="aB"))
    b.append(t(cx + 46, 20, "a slow, steady stream", color=AIR, size=11))
    # graph
    x0, x1, y0, y1 = 190, 340, 48, 160
    b.append(p(f"M{x0},{y0 - 8} L{x0},{y1} L{x1},{y1}", INK, 1.4))
    b.append(p(f"M{x0 + 2},68 L{x1 - 6},70", ACC, 3))
    b.append(t(x1 - 6, 62, "Rib width", "end", 11.5, 600, ACC))
    b.append(p(f"M{x0 + 2},72 C{x0 + 60},96 {x0 + 110},124 {x1 - 6},150", AIR, 3))
    b.append(t(x0 + 60, 132, "Air in the lungs", "middle", 11.5, 600, AIR))
    b.append(t((x0 + x1) / 2, y1 + 16, "Through the phrase →", "middle", 11, color=MUTED))
    b.append(cap(180, 206, "Support: the ribs stay wide while the voice sings,"))
    b.append(cap(180, 222, "so the air is let out slowly"))
    return b


def steady_air():
    b = []
    x0, x1, y0, y1 = 56, 330, 36, 170
    b.append(p(f"M{x0},{y0} L{x0},{y1} L{x1},{y1}", INK, 1.4))
    b.append(t(x0 - 8, y0 + 6, "Full", "end", 11, color=MUTED))
    b.append(t(x0 - 8, y1, "Empty", "end", 11, color=MUTED))
    b.append(p(f"M{x0},{y0 + 8} L{x1 - 10},{y1 - 12}", AIR, 3))
    b.append(t(196, 82, "Spread across the phrase", color=AIR, weight=600))
    b.append(p(f"M{x0},{y0 + 8} C{x0 + 60},{y0 + 80} {x0 + 110},{y1 - 6} {x0 + 150},{y1 - 4} L{x1 - 10},{y1 - 4}", ACC, 2.5, dash="6 4"))
    b.append(t(96, 152, "Runs out early", color=ACC, weight=600))
    b.append(p(f"M{x1 - 10},{y0} L{x1 - 10},{y1}", MUTED, 1, dash="3 3"))
    b.append(t(x1 - 10, y0 - 6, "end of phrase", "end", 11, color=MUTED))
    b.append(f'<rect x="{x0}" y="186" width="{x1 - 10 - x0}" height="16" rx="8" fill="{SOFT}"/>')
    b.append(t((x0 + x1 - 10) / 2, 198, "one long sung phrase", "middle", 11))
    b.append(t(x0 - 8, 104, "Air", "end", 11, color=MUTED))
    b.append(t(x0 - 8, 118, "left", "end", 11, color=MUTED))
    b.append(cap(180, 226, "Plan the air for the whole phrase"))
    return b


# ---------------------------------------------------------------- Posture

def alignment():
    b = []
    lx = 180
    b.append(p(f"M{lx},24 L{lx},226", AIR, 1.4, dash="5 4"))
    b.append(ellipse(lx + 4, 44, 15, 18, INK, SOFT))
    b.append(p(f"M{lx + 18},44 L{lx + 23},51 L{lx + 17},53", INK, 1.6))
    b.append(p(f"M{lx - 4},61 L{lx - 6},82 M{lx + 8},61 L{lx + 10},80", INK, 2))
    b.append(p(f"M{lx - 7},82 C{lx - 14},116 {lx - 13},146 {lx - 8},162", INK, 2))
    b.append(p(f"M{lx + 10},80 C{lx + 26},104 {lx + 22},140 {lx + 12},162", INK, 2))
    b.append(p(f"M{lx + 2},88 C{lx + 6},110 {lx + 8},126 {lx + 6},140", INK, 1.6))
    b.append(p(f"M{lx},164 L{lx + 5},192 L{lx},218", INK, 2.4))
    b.append(p(f"M{lx - 8},222 L{lx + 24},222", INK, 2.4))
    for (y, name, x) in ((46, "Ear", lx), (86, "Shoulder", lx), (160, "Hip", lx), (192, "Knee, soft", lx + 5), (218, "Ankle", lx)):
        b.append(dot(x, y, 4.2, ACC))
        b.append(p(f"M{x + 8},{y} L{246},{y}", MUTED, 1))
        b.append(t(252, y + 4, name))
    b.append(t(30, 100, "One line from", weight=600))
    b.append(t(30, 116, "ear to ankle", weight=600))
    b.append(t(30, 136, "Side view", size=11, color=MUTED))
    return b


def head_profile(cx, cy, r=28):
    return [circle(cx, cy, r, INK, SOFT), p(f"M{cx + r - 2},{cy - 4} L{cx + r + 8},{cy + 6} L{cx + r - 1},{cy + 10}", INK, 1.8)]


def head_neck():
    b = [divider()]
    # level
    b += head_profile(92, 70)
    b.append(p("M78,96 L78,176 M108,96 L108,176", INK, 2))
    b.append(p("M60,176 L130,176", INK, 2))
    b.append(p("M70,100 L70,170", ACC, 2.5, marker="aA"))
    b.append(p("M70,170 L70,100", ACC, 2.5, marker="aA"))
    b.append(t(64, 140, "long", "end", 11, color=ACC))
    b.append(p("M124,84 L160,84", MUTED, 1, dash="3 3"))
    b.append(cap(95, 204, "Chin level"))
    b.append(t(95, 220, "Back of the neck long", "middle", 11.5))
    # forward
    b += head_profile(286, 82)
    b.append(p("M252,176 C254,140 260,118 268,104 M278,176 C282,150 290,126 298,110", INK, 2))
    b.append(p("M236,176 L306,176", INK, 2))
    b.append(p("M258,100 l-6,6 l8,2 l-6,6", ACC, 2))
    b.append(t(246, 104, "short", "end", 11, color=ACC))
    b.append(arrow(320, 70, 340, 64))
    b.append(p("M264,176 L264,40", MUTED, 1, dash="3 3"))
    b.append(cap(270, 204, "Chin pushed forward"))
    b.append(t(270, 220, "Head ahead of the shoulders", "middle", 11.5))
    return b


def shoulders_chest():
    b = [divider(120), divider(240)]
    f, (x, y) = side_figure(58)
    b += f + [dot(x + 1, y - 4, 4.5, ACC)]
    b.append(cap(60, 204, "Relaxed"))
    f, (x, y) = side_figure(178, head_dx=12, head_dy=12, shoulder_dy=6, slump=10)
    b += f + [dot(x + 6, y - 2, 4.5, ACC)]
    b.append(arrow(x + 20, y + 30, x + 8, y + 40))
    b.append(cap(180, 204, "Collapsed"))
    b.append(t(180, 220, "chest sinks", "middle", 11.5))
    f, (x, y) = side_figure(298, shoulder_dy=-22)
    b += f + [dot(x + 1, y - 4, 4.5, ACC)]
    b.append(arrow(x + 22, y + 18, x + 22, y - 8))
    b.append(cap(300, 204, "Raised"))
    b.append(t(300, 220, "shoulders up", "middle", 11.5))
    return b


def foot(x, y, weight_x, color, faded=False):
    o = 0.45 if faded else 1
    b = [p(f"M{x},{y} C{x - 4},{y - 18} {x + 8},{y - 22} {x + 18},{y - 20} L{x + 60},{y - 10} C{x + 72},{y - 8} {x + 72},{y} {x + 62},{y} Z", INK, 2, SOFT, opacity=o)]
    b.append(p(f"M{x + 10},{y - 66} L{x + 12},{y - 22}", INK, 3, opacity=o))
    b.append(arrow(weight_x, y - 46, weight_x, y - 6, color))
    return b


def feet_weight():
    b = [divider(150)]
    for hx in (55, 95):
        b.append(dot(hx, 58, 5, INK))
        b.append(p(f"M{hx},60 L{hx},176", INK, 3))
        b.append(p(f"M{hx},62 L{hx},190", MUTED, 1, dash="3 3"))
        b.append(ellipse(hx, 182, 12, 7, INK, SOFT))
    b.append(p("M45,50 C60,40 90,40 105,50", INK, 2))
    b.append(t(75, 34, "hips", "middle", 11, color=MUTED))
    b.append(cap(75, 210, "Feet under the hips"))
    b.append(t(75, 225, "about hip-width apart", "middle", 11.5))
    b += foot(190, 100, 222, "A")
    b.append(t(270, 74, "Balanced", weight=600))
    b.append(t(270, 88, "over the middle", size=11.5))
    b.append(t(270, 100, "of the foot", size=11.5))
    b += foot(190, 196, 196, "A", faded=True)
    b.append(t(270, 168, "Back on", weight=600, color=MUTED))
    b.append(t(270, 182, "the heels", weight=600, color=MUTED))
    b.append(t(236, 226, "Side view of one foot", "middle", 11, color=MUTED))
    return b


# ---------------------------------------------------------------- Larynx

def larynx_shape(x, y, fill):
    return p(f"M{x - 14},{y - 10} L{x + 14},{y - 10} L{x + 10},{y + 4} L{x},{y + 12} L{x - 10},{y + 4} Z", INK, 2, fill)


def larynx_height():
    b = [divider(120), divider(240), p("M14,118 L346,118", AIR, 1, dash="5 4"), t(346, 112, "neutral", "end", 10.5, color=AIR)]
    for cx, y, name, arr in ((60, 156, "Low", (60, 132, 60, 142)), (180, 118, "Neutral", None), (300, 78, "High", (300, 104, 300, 94))):
        b.append(p(f"M{cx - 34},36 Q{cx},26 {cx + 34},36", INK, 2))
        b.append(p(f"M{cx - 26},40 L{cx - 26},196 M{cx + 26},40 L{cx + 26},196", INK, 2))
        b.append(p(f"M{cx - 40},198 Q{cx},206 {cx + 40},198", INK, 2))
        b.append(p(f"M{cx - 8},{y + 12} L{cx - 8},196 M{cx + 8},{y + 12} L{cx + 8},196", MUTED, 1.4))
        b.append(larynx_shape(cx, y, FILL2))
        if arr:
            b.append(arrow(*arr))
        b.append(cap(cx, 224, name))
    b.append(t(60, 22, "jaw", "middle", 10.5, color=MUTED))
    return b


def onset():
    b = [divider(120), divider(240)]
    specs = (
        (60, "Breathy", "air leaks first", "gap"),
        (180, "Balanced", "folds meet cleanly", "meet"),
        (300, "Pressed", "a hard, squeezed start", "press"),
    )
    for cx, name, sub, kind in specs:
        top, bot = 36, 104
        if kind == "gap":
            b.append(p(f"M{cx - 3},{top} L{cx - 18},{bot}", INK, 3))
            b.append(p(f"M{cx + 3},{top} L{cx + 18},{bot}", INK, 3))
            b.append(arrow(cx, bot + 6, cx, top + 10, "B", 1.6))
        elif kind == "meet":
            b.append(p(f"M{cx},{top} L{cx - 4},{bot}", INK, 3))
            b.append(p(f"M{cx},{top} L{cx + 4},{bot}", INK, 3))
        else:
            b.append(p(f"M{cx - 1},{top} L{cx - 3},{bot}", INK, 6))
            b.append(p(f"M{cx + 1},{top} L{cx + 3},{bot}", INK, 6))
            b.append(arrow(cx - 30, 70, cx - 12, 70))
            b.append(arrow(cx + 30, 70, cx + 12, 70))
        # sound
        y, x0, x1 = 152, cx - 46, cx + 46
        b.append(p(f"M{x0},{y} L{x1},{y}", MUTED, 1))
        if kind == "gap":
            noise = "M" + " L".join(f"{x0 + i * 2},{y + (3 if i % 2 else -3) * (1 if i % 3 else 0.5)}" for i in range(14))
            b.append(p(noise, AIR, 1.4))
            b.append(p(wave(x0 + 28, x1, y, 12, 14), INK, 1.8))
        elif kind == "meet":
            b.append(p(f"M{x0},{y} L{x0 + 12},{y}", INK, 1.8))
            b.append(p(wave(x0 + 12, x1, y, 12, 14), INK, 1.8))
        else:
            b.append(p(f"M{x0},{y} L{x0 + 10},{y} L{x0 + 13},{y - 24} L{x0 + 16},{y + 18} L{x0 + 19},{y}", ACC, 1.8))
            b.append(p(wave(x0 + 19, x1, y, 12, 14), INK, 1.8))
        b.append(cap(cx, 198, name))
        b.append(t(cx, 214, sub, "middle", 10.5))
    b.append(t(8, 28, "Folds, seen from above", size=10.5, color=MUTED))
    b.append(t(8, 134, "The sound", size=10.5, color=MUTED))
    return b


def folds_thick_thin():
    b = [divider()]
    def pair(cx, a, c, d):
        left = f"M{cx - 70},{a} C{cx - 40},{a} {cx - 4},{c - 10} {cx - 3},{c} L{cx - 3},{d} C{cx - 6},{d + 10} {cx - 40},{d + 26} {cx - 70},{d + 30} Z"
        right = f"M{cx + 70},{a} C{cx + 40},{a} {cx + 4},{c - 10} {cx + 3},{c} L{cx + 3},{d} C{cx + 6},{d + 10} {cx + 40},{d + 26} {cx + 70},{d + 30} Z"
        return [p(left, INK, 2, FILL2), p(right, INK, 2, FILL2)]
    b += pair(90, 60, 94, 140)
    b.append(p("M90,92 L90,142", ACC, 2, dash="2 3"))
    b.append(cap(90, 196, "Chest voice"))
    b.append(t(90, 212, "thicker folds, deeper contact", "middle", 11))
    b += pair(270, 88, 110, 116)
    b.append(arrow(214, 102, 196, 102))
    b.append(arrow(326, 102, 344, 102))
    b.append(t(270, 160, "stretched", "middle", 11, color=ACC))
    b.append(cap(270, 196, "Head voice"))
    b.append(t(270, 212, "thinner folds, light contact", "middle", 11))
    b.append(t(180, 30, "The two vocal folds, cut through and seen from the front", "middle", 11, color=MUTED))
    return b


# ---------------------------------------------------------------- Resonance

def mouth_section(cx, palate_up):
    """Side view inside the head, facing right: nose above, mouth below."""
    b = []
    back = cx - 50
    b.append(p(f"M{back},40 L{back},214", INK, 2))                      # back of the throat
    b.append(p(f"M{back + 12},48 L{cx + 60},48", INK, 2))                 # top of the nose
    b.append(p(f"M{back + 30},96 L{cx + 60},96", INK, 2.4))               # hard palate
    b.append(t(cx + 64, 74, "Nose", size=11))
    b.append(t(cx + 64, 130, "Mouth", size=11))
    b.append(p(f"M{back + 24},214 C{back + 24},150 {cx + 10},120 {cx + 54},150", INK, 2, SOFT))  # tongue
    if palate_up:
        b.append(p(f"M{back + 30},96 C{back + 16},94 {back + 6},88 {back + 2},78", ACC, 4))
        b.append(p(f"M{back + 14},208 C{back + 12},160 {back + 26},124 {cx + 58},118", AIR, 2, marker="aB"))
    else:
        b.append(p(f"M{back + 30},96 C{back + 22},104 {back + 20},118 {back + 22},128", ACC, 4))
        b.append(p(f"M{back + 12},208 C{back + 10},150 {back + 8},90 {cx + 58},70", AIR, 2, marker="aB"))
        b.append(p(f"M{back + 16},180 C{back + 20},150 {back + 40},126 {cx + 50},122", AIR, 1.4, dash="3 3", marker="aB"))
    return b


def soft_palate():
    b = [divider()]
    b += mouth_section(92, True)
    b.append(cap(90, 222, "Lifted"))
    b.append(t(90, 236, "the sound goes out the mouth", "middle", 11))
    b += mouth_section(268, False)
    b.append(cap(270, 222, "Lowered"))
    b.append(t(270, 236, "some of it goes into the nose", "middle", 11))
    b.append(t(56, 74, "soft palate", size=10.5, color=ACC))
    return b


HEAD = ("M150,34 C200,24 240,44 250,80 L256,100 L270,128 L254,134 L258,148 L252,154 L257,162 L250,178 "
        "C246,190 236,194 226,194 L224,232 L154,232 L150,176 C116,160 99,140 96,110 C92,70 112,38 150,34 Z")


def resonance_spaces():
    b = [p(HEAD, INK, 2, SOFT)]
    b.append(p("M206,108 C214,102 240,102 252,112 L250,120 C236,118 216,118 206,120 Z", INK, 1.4, FILL3))
    b.append(p("M204,140 C214,134 236,136 248,150 C236,158 214,158 204,154 Z", INK, 1.4, FILL2))
    b.append(p("M190,118 C192,160 194,196 196,226 L210,226 C208,196 206,160 206,120 Z", INK, 1.4, "#e5dcef"))
    b.append(dot(202, 212, 3.5, ACC))
    for r in (10, 18, 26):
        b.append(p(f"M{270 + r * 0.2},{132 - r} A{r},{r} 0 0 1 {270 + r * 0.2},{132 + r}", AIR, 1.4, opacity=0.9 - r / 50))
    for (y, ty, name, x) in ((112, 70, "Nasal space", 214), (146, 112, "Mouth", 214), (170, 160, "Throat", 196), (212, 206, "Vocal folds", 202)):
        b.append(p(f"M{x},{y} L120,{ty}", MUTED, 1))
        b.append(t(114, ty + 4, name, "end"))
    b.append(t(300, 124, "where it", size=11, color=AIR))
    b.append(t(300, 138, "rings", size=11, color=AIR))
    return b


def mask():
    b = [ellipse(180, 120, 62, 86, INK, SOFT)]
    b.append(p("M150,96 Q160,90 170,96 M190,96 Q200,90 210,96", INK, 2))
    b.append(p("M180,100 L174,136 L186,138", INK, 1.8))
    b.append(p("M162,164 Q180,174 198,164 Q180,158 162,164 Z", INK, 1.8, FILL2))
    for (x, y) in ((150, 124), (210, 124), (180, 106), (180, 166)):
        b.append(circle(x, y, 9, "none", ACC, 0))
        b.append(f'<circle cx="{x}" cy="{y}" r="9" fill="{ACC}" opacity="0.28"/>')
        b.append(circle(x, y, 15, ACC, "none", 1.2, dash="2 3"))
    for (x, y, tx, name, anchor) in ((136, 124, 92, "cheekbones", "end"), (224, 124, 268, "cheekbones", "start"),
                                     (194, 106, 268, "nose bridge", "start"), (166, 166, 92, "lips", "end")):
        b.append(p(f"M{x},{y} L{tx + (4 if anchor == 'end' else -4)},{y}", MUTED, 1))
        b.append(t(tx, y + 4, name, anchor))
    b.append(cap(180, 228, "Where the buzz is felt"))
    return b


# ---------------------------------------------------------------- Vowels

def rot(px, py, ox, oy, deg):
    a = math.radians(deg)
    dx, dy = px - ox, py - oy
    return ox + dx * math.cos(a) - dy * math.sin(a), oy + dx * math.sin(a) + dy * math.cos(a)


def jaw(cx, mode):
    b = []
    hx, hy = cx - 18, 92          # the hinge, in front of the ear
    b.append(p(f"M{cx - 50},100 C{cx - 56},50 {cx - 10},26 {cx + 26},40 C{cx + 44},48 {cx + 50},70 {cx + 54},96 L{cx + 50},104", INK, 2, SOFT))
    b.append(ellipse(cx - 34, 92, 6, 9, INK, "#ffffff", 1.6))
    closed = [(hx, hy), (hx + 2, hy + 40), (cx + 44, hy + 44), (cx + 50, hy + 30), (cx + 50, 104)]
    b.append(p("M" + " L".join(f"{x},{y}" for x, y in closed), MUTED, 1.6, dash="4 3"))
    if mode == "drop":
        pts = [rot(x, y, hx, hy, 24) for x, y in closed]
        pts[0] = (hx, hy)
        b.append(p(f"M{closed[2][0] + 8},{closed[2][1] - 4} A60,60 0 0 1 {pts[2][0] + 10},{pts[2][1] + 4}", ACC, 1.6, marker="aA"))
    else:
        pts = [(x + 18, y + 6) for x, y in closed]
        b.append(arrow(cx + 56, hy + 50, cx + 76, hy + 50))
    b.append(p("M" + " L".join(f"{x:.1f},{y:.1f}" for x, y in pts), ACC, 2.4))
    b.append(dot(hx, hy, 3.5, INK))
    return b


def jaw_drop():
    b = [divider()]
    b += jaw(92, "drop")
    b.append(t(80, 84, "hinge", "start", 10.5, color=MUTED))
    b.append(cap(90, 204, "Down and back"))
    b.append(t(90, 220, "swings open from the hinge", "middle", 11.5))
    b += jaw(262, "jut")
    b.append(cap(270, 204, "Jutting forward"))
    b.append(t(270, 220, "pushes out instead", "middle", 11.5))
    return b


def tongue_vowels():
    b = []
    vowels = (("ee", 0.78, 0.18), ("eh", 0.72, 0.42), ("ah", 0.52, 0.8), ("oh", 0.3, 0.45), ("oo", 0.24, 0.2))
    for i, (name, fx, fy) in enumerate(vowels):
        x = 14 + i * 68
        b.append(f'<rect x="{x}" y="32" width="62" height="150" rx="8" fill="{SOFT}" opacity="0.6"/>')
        b.append(p(f"M{x + 6},60 C{x + 14},40 {x + 42},38 {x + 58},50", INK, 2))      # roof of the mouth
        b.append(p(f"M{x + 6},60 L{x + 6},170", INK, 1.4))                                # back of the throat
        px, py = x + 6 + fx * 50, 58 + fy * 70
        tongue = (f"M{x + 8},170 C{x + 8},{py + 10} {px - 18},{py} {px},{py} "
                  f"C{px + 14},{py} {x + 56},{py + 14} {x + 56},{py + 34} L{x + 56},170 Z")
        b.append(p(tongue, INK, 1.8, FILL2))
        if name in ("oh", "oo"):
            b.append(p(f"M{x + 58},54 q8,{12 if name == 'oo' else 16} 0,{28 if name == 'oo' else 34}", ACC, 2.4))
        b.append(t(x + 31, 204, name, "middle", 15, 700))
    b.append(t(346, 24, "front of the mouth →", "end", 11, color=MUTED))
    b.append(t(180, 228, "Side view: the tongue's high point moves for each vowel", "middle", 11.5))
    return b


def lips():
    b = []
    for cx, name, ox, oy, ix, iy, sub in ((60, "ah", 34, 36, 26, 28, "open, lips relaxed"),
                                          (180, "oh", 28, 30, 18, 22, "rounder"),
                                          (300, "oo", 22, 22, 8, 9, "small, pushed forward")):
        b.append(ellipse(cx, 100, ox, oy, INK, FILL2))
        b.append(ellipse(cx, 100, ix, iy, INK, INK, 1.4))
        b.append(t(cx, 172, name, "middle", 16, 700))
        b.append(t(cx, 190, sub, "middle", 11.5))
    b.append(arrow(116, 100, 136, 100, "I", 1.4))
    b.append(arrow(236, 100, 256, 100, "I", 1.4))
    b.append(cap(180, 226, "Lip shapes, seen from the front"))
    return b


def vowel_mod():
    b = [arrow(36, 204, 36, 34, "I", 1.6), t(30, 120, "Pitch goes up", "middle", 11, color=MUTED)]
    b[-1] = f'<text x="24" y="120" text-anchor="middle" font-size="11" fill="{MUTED}" transform="rotate(-90 24 120)">Pitch goes up</text>'
    cols = ((140, "Opens a little", (("ee", 22, 6), ("ih", 22, 10), ("eh", 22, 15))),
            (272, "Rounds a little", (("ah", 22, 18), ("aw", 18, 18), ("oh", 15, 16))))
    for cx, title, steps in cols:
        b.append(t(cx, 24, title, "middle", 12.5, 600))
        for j, (v, rx, ry) in enumerate(steps):
            y = 186 - j * 64
            b.append(ellipse(cx - 24, y, rx, ry, INK, FILL2))
            b.append(t(cx + 12, y + 6, v, size=16, weight=700))
            if j < 2:
                b.append(arrow(cx - 24, y - ry - 6, cx - 24, y - 64 + steps[j + 1][2] + 8, "A", 1.6))
    b.append(t(352, 232, "Examples only", "end", 10.5, color=MUTED, italic=True))
    return b


# ---------------------------------------------------------------- Registers

def register_map():
    b = []
    x0, y0, kw, kh = 24, 104, 14, 78
    whites = 22  # C3 .. C6
    for i in range(whites):
        b.append(f'<rect x="{x0 + i * kw}" y="{y0}" width="{kw}" height="{kh}" fill="#ffffff" stroke="{INK}" stroke-width="1"/>')
    for i in range(whites - 1):
        if i % 7 in (0, 1, 3, 4, 5):
            b.append(f'<rect x="{x0 + i * kw + kw * 0.65}" y="{y0}" width="{kw * 0.7}" height="{kh * 0.6}" fill="{INK}"/>')
    for i, name in ((0, "C3"), (7, "C4"), (14, "C5"), (21, "C6")):
        b.append(t(x0 + i * kw + kw / 2, y0 + kh + 16, name, "middle", 11, color=MUTED))
    def band(a, z, y, color, name):
        b.append(f'<rect x="{x0 + a * kw}" y="{y}" width="{(z - a) * kw}" height="22" rx="11" fill="{color}"/>')
        b.append(t(x0 + (a + z) / 2 * kw, y + 15, name, "middle", 12, 600))
    band(0, 10, 66, FILL2, "Chest")       # C3 .. F4
    band(8, 18, 40, "#e5dcef", "Mix")     # A3 .. A5
    band(15, 22, 66, FILL3, "Head")       # D5 .. C6
    for k, name in ((9.5, "1st passaggio"), (17.5, "2nd passaggio")):
        x = x0 + k * kw
        b.append(p(f"M{x},34 L{x},{y0 + kh}", ACC, 1.6, dash="4 3"))
        b.append(t(x, 28, name, "middle", 10.5, 600, ACC))
    b.append(t(180, 224, "Drawn for a typical soprano. Her own breaks may sit elsewhere.", "middle", 11, color=MUTED, italic=True))
    return b


def passaggio():
    b = []
    b.append(f'<rect x="158" y="24" width="64" height="168" fill="{SOFT}"/>')
    b.append(t(190, 40, "the break", "middle", 11, 600, color=MUTED))
    b.append(p("M30,200 L30,24", INK, 1.2, marker="aI"))
    b.append(f'<text x="22" y="112" text-anchor="middle" font-size="11" fill="{MUTED}" transform="rotate(-90 22 112)">pitch</text>')
    # smooth path, thinning as it goes
    pts = [(40 + i * 10, 176 - i * 4.6) for i in range(30)]
    top = [(x, y - (8 - i * 0.22)) for i, (x, y) in enumerate(pts)]
    bot = [(x, y + (8 - i * 0.22)) for i, (x, y) in enumerate(pts)]
    poly = "M" + " L".join(f"{x:.1f},{y:.1f}" for x, y in top + bot[::-1]) + " Z"
    b.append(p(poly, "none", 0, AIR, opacity=0.85))
    b.append(t(64, 154, "full", size=11, color=AIR))
    b.append(t(300, 56, "thin", size=11, color=AIR))
    b.append(t(190, 90, "ah → aw", "middle", 12, 600))
    # pushed path
    b.append(p("M40,198 L158,176 L178,170", ACC, 4, dash="1 0", opacity=0.55))
    b.append(p("M178,170 L182,110", ACC, 1.6, dash="2 3"))
    b.append(p("M182,110 L330,90", ACC, 2, opacity=0.55))
    b.append(t(236, 132, "pushed up heavy,", size=11, color=ACC))
    b.append(t(236, 146, "then it flips", size=11, color=ACC))
    b.append(cap(180, 222, "Thin the sound and narrow the vowel through the break"))
    return b


# ---------------------------------------------------------------- Tone, line, diction

def vibrato():
    b = []
    rows = ((52, "Straight", 0, 30), (122, "Even", 8, 30), (192, "Wide", 18, 58))
    for y, name, amp, per in rows:
        b.append(t(20, y + 4, name, size=12.5, weight=600))
        b.append(p(f"M96,{y} L340,{y}", MUTED, 1, dash="2 4"))
        b.append(p(wave(96, 340, y, amp, per) if amp else f"M96,{y} L340,{y}", AIR if name == "Even" else INK, 2.4))
    b.append(t(340, 230, "pitch over time →", "end", 11, color=MUTED))
    return b


def tube(cx, narrow):
    b = []
    pinch = 12 if narrow else 0
    left = f"M{cx - 26},30 L{cx - 26},140 C{cx - 26},152 {cx - 22 + pinch},158 {cx - 22 + pinch},170 L{cx - 22 + pinch},176 L{cx - 26},190 L{cx - 26},210"
    right = f"M{cx + 26},30 L{cx + 26},140 C{cx + 26},152 {cx + 22 - pinch},158 {cx + 22 - pinch},170 L{cx + 22 - pinch},176 L{cx + 26},190 L{cx + 26},210"
    b.append(p(left, INK, 2))
    b.append(p(right, INK, 2))
    if narrow:
        b.append(p(f"M{cx - 22 + pinch},146 L{cx - 22 + pinch},178 L{cx + 22 - pinch},178 L{cx + 22 - pinch},146", "none", 0, FILL2))
        b.append(arrow(cx - 50, 164, cx - 28, 164))
        b.append(arrow(cx + 50, 164, cx + 28, 164))
    b.append(p(f"M{cx - 26},192 L{cx - 3},186 M{cx + 26},192 L{cx + 3},186", INK, 3.4))
    n = 4 if narrow else 2
    for k in range(n):
        r = 10 + k * 8
        b.append(p(f"M{cx - r},{32 - k * 3} A{r},{r * 0.5} 0 0 1 {cx + r},{32 - k * 3}", ACC if narrow else AIR, 1.6))
    return b


def twang():
    b = [divider()]
    b += tube(90, False)
    b.append(cap(90, 230, "Open"))
    b += tube(270, True)
    b.append(cap(270, 230, "Narrowed: brighter, ringing"))
    b.append(t(152, 200, "vocal folds", "middle", 10.5, color=MUTED))
    b.append(t(352, 150, "just above", "end", 10.5, color=ACC))
    b.append(t(352, 186, "the folds", "end", 10.5, color=ACC))
    return b


def legato():
    b = []
    notes = (60, 112, 164, 216, 268, 320)
    hs = (0, -14, -24, -10, -30, -6)
    def row(y, joined, label, sub):
        pts = [(x, y + h) for x, h in zip(notes, hs)]
        if joined:
            d = "M" + " L".join(f"{x},{yy}" for x, yy in pts)
            b.append(p(d, AIR, 12, opacity=0.35))
        else:
            for x, yy in pts:
                b.append(p(f"M{x - 16},{yy} L{x + 16},{yy}", AIR, 12, opacity=0.35))
        for x, yy in pts:
            b.append(ellipse(x, yy, 7, 5.5, INK, INK, 1))
        b.append(t(20, y - 46, label, size=12.5, weight=600))
        b.append(t(20, y - 32, sub, size=11, color=MUTED))
    row(98, True, "Legato", "one unbroken stream of air and vowel")
    row(210, False, "Separated", "gaps between the notes")
    return b


def phrase_arc():
    b = []
    d = "M40,176 C110,150 170,64 236,60 C286,58 314,120 330,170"
    b.append(p(d, AIR, 3))
    b.append(dot(236, 60, 5.5, ACC))
    b.append(t(236, 44, "high point", "middle", 12, 600, ACC))
    b.append(arrow(84, 128, 140, 96, "I", 1.4))
    b.append(t(70, 120, "build", "end", 11, color=MUTED))
    b.append(arrow(286, 92, 316, 132, "I", 1.4))
    b.append(t(294, 80, "ease off", size=11, color=MUTED))
    words = ("all", "the", "way", "back", "to", "you")
    for i, w in enumerate(words):
        b.append(t(52 + i * 54, 206, w, "middle", 13))
    b.append(t(180, 230, "One phrase, shaped toward its peak", "middle", 11.5))
    return b


def consonants_beat():
    b = []
    beats = (80, 170, 260)
    for i, x in enumerate(beats):
        b.append(p(f"M{x},36 L{x},178", MUTED, 1.2, dash="4 3"))
        b.append(t(x, 30, f"beat {i + 1}", "middle", 11, color=MUTED))
    blocks = (("L", 60, 80, ACC, FILL2), ("o", 80, 152, AIR, FILL3), ("v m", 144, 170, ACC, FILL2),
              ("e", 170, 242, AIR, FILL3), ("t", 242, 260, ACC, FILL2), ("ah", 260, 330, AIR, FILL3))
    for s, a, z, c, f in blocks:
        b.append(f'<rect x="{a}" y="{86 if c == ACC else 80}" width="{z - a}" height="{28 if c == ACC else 40}" rx="5" fill="{f}" stroke="{c}" stroke-width="1.6"/>')
        b.append(t((a + z) / 2, 104, s, "middle", 13, 700, c))
    b.append(t(60, 146, "Love", "middle", 13, 600))
    b.append(t(170, 146, "me", "middle", 13, 600))
    b.append(t(260, 146, "too", "middle", 13, 600))
    b.append(f'<rect x="40" y="196" width="14" height="10" rx="3" fill="{FILL2}" stroke="{ACC}" stroke-width="1.4"/>')
    b.append(t(60, 205, "consonant, just before the beat", size=11.5))
    b.append(f'<rect x="40" y="214" width="14" height="10" rx="3" fill="{FILL3}" stroke="{AIR}" stroke-width="1.4"/>')
    b.append(t(60, 223, "vowel, on the beat", size=11.5))
    return b


# ---------------------------------------------------------------- Health

def warmup():
    b = []
    steps = ("Breath", "Lip trills", "Sirens", "Scales", "Songs")
    for i, s in enumerate(steps):
        cx = 40 + i * 70
        b.append(circle(cx, 96, 26, INK, SOFT))
        if i == 0:
            b.append(p(wave(cx - 14, cx + 14, 96, 5, 14), AIR, 2))
        elif i == 1:
            b.append(ellipse(cx, 96, 12, 6, INK, FILL2, 1.6))
            b.append(p(f"M{cx - 18},88 l-4,-3 M{cx - 18},104 l-4,3 M{cx + 18},88 l4,-3 M{cx + 18},104 l4,3", INK, 1.4))
        elif i == 2:
            b.append(p(f"M{cx - 15},106 C{cx - 6},80 {cx + 6},80 {cx + 15},106", ACC, 2.2))
        elif i == 3:
            b.append(p(f"M{cx - 14},108 L{cx - 7},108 L{cx - 7},100 L{cx},100 L{cx},92 L{cx + 7},92 L{cx + 7},84 L{cx + 14},84", INK, 2))
        else:
            b.append(ellipse(cx - 4, 104, 6, 4.5, INK, INK, 1))
            b.append(p(f"M{cx + 2},104 L{cx + 2},82 Q{cx + 8},86 {cx + 12},92", INK, 2))
        b.append(t(cx, 50, str(i + 1), "middle", 12, 700, MUTED))
        b.append(t(cx, 146, s, "middle", 12.5, 600))
        if i < 4:
            b.append(arrow(cx + 29, 96, cx + 40, 96, "I", 1.4))
    b.append(cap(180, 200, "A warm-up, from gentle to full voice"))
    return b


DIAGRAMS = [
    ("breath-diaphragm", "Breath", "Diaphragm on the in-breath", "The diaphragm lowering and the ribs widening as air comes in", diaphragm),
    ("breath-low-high", "Breath", "Low breath vs high breath", "The belly and back expanding, compared with the shoulders lifting", low_high_breath),
    ("breath-support", "Breath", "Appoggio / support", "The ribs staying open while the voice sings, so the air is let out slowly", appoggio),
    ("breath-steady", "Breath", "Steady air on a long phrase", "Air use spread across a phrase, compared with running out at the end", steady_air),
    ("posture-alignment", "Posture", "Singer's alignment, side view", "Ear, shoulder, hip and ankle in one line, with the knees soft", alignment),
    ("posture-head-neck", "Posture", "Head and neck", "Chin level and the back of the neck long, compared with the chin pushed forward", head_neck),
    ("posture-shoulders", "Posture", "Shoulders and chest", "Shoulders relaxed and the chest open, compared with collapsed or raised", shoulders_chest),
    ("posture-feet", "Posture", "Feet and weight", "Feet hip-width apart and weight balanced, not locked back on the heels", feet_weight),
    ("larynx-height", "Larynx", "Larynx height", "A low, neutral and high larynx, side view", larynx_height),
    ("larynx-onset", "Larynx", "Vocal folds: onset", "Breathy, balanced and pressed starts to a note", onset),
    ("larynx-thick-thin", "Larynx", "Vocal folds: thick vs thin", "Chest voice and head voice as how the folds vibrate", folds_thick_thin),
    ("resonance-soft-palate", "Resonance", "Soft palate lifted", "The soft palate up for an open sound, compared with down for a nasal one", soft_palate),
    ("resonance-spaces", "Resonance", "Resonance spaces", "Throat, mouth and nasal spaces, with where the sound feels like it rings", resonance_spaces),
    ("resonance-mask", "Resonance", "Forward placement / mask", "The buzz felt around the cheekbones and lips", mask),
    ("vowels-jaw", "Vowels", "Jaw drop", "The jaw hinging down and back, compared with jutting forward", jaw_drop),
    ("vowels-tongue", "Vowels", "Tongue for each vowel", "Tongue shapes for ee, eh, ah, oh and oo, side view", tongue_vowels),
    ("vowels-lips", "Vowels", "Lip rounding", "Lip shapes for oh and oo compared with ah", lips),
    ("vowels-modification", "Vowels", "Vowel modification", "How a vowel opens up or narrows as the pitch goes up", vowel_mod),
    ("registers-map", "Registers", "Register map", "Chest, mix and head ranges on a keyboard, with the passaggio marked", register_map),
    ("registers-passaggio", "Registers", "Through the passaggio", "Thinning and narrowing the vowel to get through the break smoothly", passaggio),
    ("tone-vibrato", "Tone", "Vibrato", "A pitch line wobbling evenly, compared with straight or wide", vibrato),
    ("tone-twang", "Tone", "Twang / ring", "The narrowed space above the vocal folds that gives a brighter, ringing sound", twang),
    ("line-legato", "Line", "Legato line", "Notes joined by an unbroken stream of air and vowel", legato),
    ("line-phrase-arc", "Line", "Phrase arc", "A phrase shaped to rise toward its high point and ease off at the end", phrase_arc),
    ("diction-consonants", "Diction", "Consonants on the beat", "The vowel landing on the beat, with consonants placed just before it", consonants_beat),
    ("health-warmup", "Health", "Warm-up order", "Breath, then lip trills, then sirens, then scales, then songs", warmup),
]


TS_HEADER = """// Generated by tools/diagrams/gen.py; edit the list there, not here.
// The library of suggested diagrams the model may pick from. The drawings
// are web/static/diagrams/<id>.svg (and the phone's copies).

export interface Diagram {
  id: string;
  area: string;
  title: string;
  shows: string;
}

"""


if __name__ == "__main__":
    root = Path(__file__).resolve().parents[2]
    out = Path(sys.argv[1]) if len(sys.argv) > 1 else root / "web/static/diagrams"
    out.mkdir(parents=True, exist_ok=True)
    index = []
    for did, area, title, shows, fn in DIAGRAMS:
        (out / f"{did}.svg").write_text(svg("\n".join(fn()), title))
        index.append({"id": did, "area": area, "title": title, "shows": shows})
    (out / "index.json").write_text(json.dumps(index, indent=2) + "\n")
    if len(sys.argv) <= 1:
        ts = root / "supabase/functions/_shared/diagrams.ts"
        ts.parent.mkdir(parents=True, exist_ok=True)
        ts.write_text(TS_HEADER + "export const DIAGRAMS: Diagram[] = " + json.dumps(index, indent=2) + ";\n")
    print(len(index), "diagrams")
