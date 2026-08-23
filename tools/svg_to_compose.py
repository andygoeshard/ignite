#!/usr/bin/env python3
"""Convierte un SVG plano (Inkscape 'Plain SVG') en un composable de Compose Multiplatform.

Uso:
    python3 tools/svg_to_compose.py tools/logo.svg

Genera por defecto:
    shared/src/commonMain/kotlin/com/andyl/ignite/presentation/branding/FlameTraceMark.kt

- Ignora <image>, filtros, clips y todo lo que no sea <path>.
- Convierte todos los comandos SVG (M L H V C S Q T A Z, relativos/absolutos,
  repeticiones implicitas, flags pegados en arcos) a secuencias absolutas M/L/C/Z.
- Arcos (A) y quadratics (Q/T) se aplanan a curvas cubicas.
- --white-threshold descarta los paths casi blancos (fondo del trazado).
"""
import argparse
import math
import re
import sys
from pathlib import Path

DEFAULT_OUT = "shared/src/commonMain/kotlin/com/andyl/ignite/presentation/branding/FlameTraceMark.kt"
DEFAULT_PKG = "com.andyl.ignite.presentation.branding"
DEFAULT_FUN = "FlameTraceMark"

CHUNK = 2500          # floats por val generado
FLOATS_PER_LINE = 12


class Reader:
    def __init__(self, s):
        self.s = s
        self.i = 0

    def _skip(self):
        while self.i < len(self.s) and self.s[self.i] in " \t\r\n,":
            self.i += 1

    def peek(self):
        self._skip()
        return self.s[self.i] if self.i < len(self.s) else ""

    def read_number(self):
        self._skip()
        j = self.i
        if j < len(self.s) and self.s[j] in "+-":
            j += 1
        while j < len(self.s) and self.s[j].isdigit():
            j += 1
        if j < len(self.s) and self.s[j] == ".":
            j += 1
            while j < len(self.s) and self.s[j].isdigit():
                j += 1
        if j < len(self.s) and self.s[j] in "eE":
            k = j + 1
            if k < len(self.s) and self.s[k] in "+-":
                k += 1
            if k < len(self.s) and self.s[k].isdigit():
                j = k
                while j < len(self.s) and self.s[j].isdigit():
                    j += 1
        if j == self.i:
            raise ValueError(
                "numero esperado en %d: %r" % (self.i, self.s[self.i:self.i + 24])
            )
        v = float(self.s[self.i:j])
        self.i = j
        return v

    def read_flag(self):
        self._skip()
        if self.i >= len(self.s) or self.s[self.i] not in "01":
            raise ValueError("flag 0|1 esperado en %d" % self.i)
        v = int(self.s[self.i])
        self.i += 1
        return v


def arc_to_cubics(x0, y0, rx, ry, phi_deg, laf, sf, x1, y1):
    """Endpoint parameterization -> segmentos ('C', ...) de <=90 grados."""
    if abs(x1 - x0) < 1e-9 and abs(y1 - y0) < 1e-9:
        return []
    if rx == 0 or ry == 0:
        return [("C", x0, y0, x1, y1, x1, y1)]
    phi = math.radians(phi_deg)
    cos_p, sin_p = math.cos(phi), math.sin(phi)
    dx2, dy2 = (x0 - x1) / 2.0, (y0 - y1) / 2.0
    x1p = cos_p * dx2 + sin_p * dy2
    y1p = -sin_p * dx2 + cos_p * dy2
    rx, ry = max(abs(rx), 1e-9), max(abs(ry), 1e-9)
    lam = (x1p / rx) ** 2 + (y1p / ry) ** 2
    if lam > 1.0:
        s = math.sqrt(lam)
        rx *= s
        ry *= s
    num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
    den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
    co = 0.0 if den == 0 else math.sqrt(max(0.0, num / den))
    if den != 0 and laf == sf:
        co = -co
    cxp = co * rx * y1p / ry
    cyp = -co * ry * x1p / rx
    ccx = cos_p * cxp - sin_p * cyp + (x0 + x1) / 2.0
    ccy = sin_p * cxp + cos_p * cyp + (y0 + y1) / 2.0
    th1 = math.atan2((y1p - cyp) / ry, (x1p - cxp) / rx)
    th2 = math.atan2((-y1p - cyp) / ry, (-x1p - cxp) / rx)
    dth = th2 - th1
    if sf == 0 and dth > 0:
        dth -= 2 * math.pi
    elif sf == 1 and dth < 0:
        dth += 2 * math.pi
    segs = max(1, int(math.ceil(abs(dth) / (math.pi / 2))))
    delta = dth / segs
    t = 4.0 / 3.0 * math.tan(delta / 4.0)
    out = []
    th = th1
    for _ in range(segs):
        th_n = th + delta
        p3x = ccx + rx * math.cos(th_n)
        p3y = ccy + ry * math.sin(th_n)
        p1x = ccx + rx * math.cos(th) + t * (-rx * math.sin(th))
        p1y = ccy + ry * math.sin(th) + t * (ry * math.cos(th))
        p2x = p3x - t * (-rx * math.sin(th_n))
        p2y = p3y - t * (ry * math.cos(th_n))
        out.append(("C", p1x, p1y, p2x, p2y, p3x, p3y))
        th = th_n
    o = out[-1]
    out[-1] = ("C", o[1], o[2], o[3], o[4], x1, y1)
    return out


def parse_path(d):
    """Lista de comandos absolutos: ('M'|'L',x,y) | ('C',x1,y1,x2,y2,x,y) | ('Z',)."""
    r = Reader(d)
    cmds = []
    cx = cy = sx = sy = 0.0
    px = py = None
    prev = None
    while True:
        c = r.peek()
        if c == "":
            break
        if c.isalpha() and c in "MmLlHhVvCcSsQqTtAaZz":
            cmd = c
            r.i += 1
        else:
            if prev is None:
                raise ValueError("comando implicito sin inicial: %r" % d[:40])
            if prev.upper() == "M":
                prev = "l" if prev == "m" else "L"
            cmd = prev
        up, rel = cmd.upper(), cmd.islower()

        if up == "Z":
            cmds.append(("Z",))
            cx, cy = sx, sy
            px = py = None
        elif up == "M":
            x, y = r.read_number(), r.read_number()
            if rel:
                x, y = x + cx, y + cy
            cmds.append(("M", x, y))
            cx, cy, sx, sy = x, y, x, y
            px = py = None
        elif up == "L":
            x, y = r.read_number(), r.read_number()
            if rel:
                x, y = x + cx, y + cy
            cmds.append(("L", x, y))
            cx, cy, px, py = x, y, None, None
        elif up == "H":
            x = r.read_number()
            if rel:
                x += cx
            cmds.append(("L", x, cy))
            cx, px, py = x, None, None
        elif up == "V":
            y = r.read_number()
            if rel:
                y += cy
            cmds.append(("L", cx, y))
            cy, px, py = y, None, None
        elif up == "C":
            a = [r.read_number() for _ in range(6)]
            if rel:
                a = [a[0] + cx, a[1] + cy, a[2] + cx,
                     a[3] + cy, a[4] + cx, a[5] + cy]
            cmds.append(("C", a[0], a[1], a[2], a[3], a[4], a[5]))
            px, py, cx, cy = a[2], a[3], a[4], a[5]
        elif up == "S":
            x1 = cx if px is None else 2 * cx - px
            y1 = cy if py is None else 2 * cy - py
            a = [r.read_number() for _ in range(4)]
            if rel:
                a = [a[0] + cx, a[1] + cy, a[2] + cx, a[3] + cy]
            cmds.append(("C", x1, y1, a[0], a[1], a[2], a[3]))
            px, py, cx, cy = a[0], a[1], a[2], a[3]
        elif up == "Q":
            a = [r.read_number() for _ in range(4)]
            if rel:
                a = [a[0] + cx, a[1] + cy, a[2] + cx, a[3] + cy]
            qx, qy, ex, ey = a
            cmds.append(("C",
                         cx + (qx - cx) * 2.0 / 3.0,
                         cy + (qy - cy) * 2.0 / 3.0,
                         ex + (qx - ex) * 2.0 / 3.0,
                         ey + (qy - ey) * 2.0 / 3.0,
                         ex, ey))
            px, py, cx, cy = qx, qy, ex, ey
        elif up == "T":
            qx = cx if px is None else 2 * cx - px
            qy = cy if py is None else 2 * cy - py
            ex, ey = r.read_number(), r.read_number()
            if rel:
                ex, ey = ex + cx, ey + cy
            cmds.append(("C",
                         cx + (qx - cx) * 2.0 / 3.0,
                         cy + (qy - cy) * 2.0 / 3.0,
                         ex + (qx - ex) * 2.0 / 3.0,
                         ey + (qy - ey) * 2.0 / 3.0,
                         ex, ey))
            px, py, cx, cy = qx, qy, ex, ey
        elif up == "A":
            rx = r.read_number()
            ry = r.read_number()
            rot = r.read_number()
            laf = r.read_flag()
            sf = r.read_flag()
            ex, ey = r.read_number(), r.read_number()
            if rel:
                ex, ey = ex + cx, ey + cy
            cmds.extend(arc_to_cubics(cx, cy, rx, ry, rot, laf, sf, ex, ey))
            cx, cy, px, py = ex, ey, None, None
        else:
            raise ValueError("comando desconocido: %s" % cmd)
        prev = cmd
    return cmds


SVG_RE = re.compile(r"<svg\b[^>]*>", re.S)
VB_RE = re.compile(r'viewBox\s*=\s*"([^"]+)"')
PATH_RE = re.compile(r"<path\b[^>]*?>", re.S)
D_RE = re.compile(r'\bd\s*=\s*"([^"]*)"')
STYLE_FILL_RE = re.compile(r"fill\s*:\s*#([0-9a-fA-F]{6})")
ATTR_FILL_RE = re.compile(r'\bfill\s*=\s*"#([0-9a-fA-F]{6})"')


def fmt(v):
    s = ("%.4f" % v).rstrip("0").rstrip(".")
    return "0" if s in ("", "-", "-0") else s


def to_ops(cmds):
    if not cmds:
        return []
    if cmds[0][0] != "M":
        c = cmds[0]
        x, y = (c[1], c[2]) if len(c) >= 3 else (0.0, 0.0)
        cmds = [("M", x, y)] + list(cmds)
    ops = []
    for cmd in cmds:
        op = cmd[0]
        if op == "M":
            ops += [0, cmd[1], cmd[2]]
        elif op == "L":
            ops += [1, cmd[1], cmd[2]]
        elif op == "C":
            ops += [2, cmd[1], cmd[2], cmd[3], cmd[4], cmd[5], cmd[6]]
        else:
            ops.append(3)
    return ops


KOTLIN_TAIL = """
// opcode 0=moveTo(2), 1=lineTo(2), 2=cubicTo(6), 3=close
private fun parseChunks(chunks: List<String>): Path {
    val p = Path()
    for (c in chunks) {
        val d = c.split(" ")
        var i = 0
        while (i < d.size) {
            when (d[i].toInt()) {
                0 -> {
                    p.moveTo(d[i + 1].toFloat(), d[i + 2].toFloat()); i += 3
                }
                1 -> {
                    p.lineTo(d[i + 1].toFloat(), d[i + 2].toFloat()); i += 3
                }
                2 -> {
                    p.cubicTo(
                        d[i + 1].toFloat(), d[i + 2].toFloat(),
                        d[i + 3].toFloat(), d[i + 4].toFloat(),
                        d[i + 5].toFloat(), d[i + 6].toFloat(),
                    )
                    i += 7
                }
                else -> {
                    p.close(); i += 1
                }
            }
        }
    }
    return p
}

private val CACHE: List<Path> by lazy { TRACED.map { (_, chunks) -> parseChunks(chunks) } }

@Composable
fun __FUN__(modifier: Modifier = Modifier, size: Dp = 200.dp) {
    Canvas(modifier = modifier.size(size)) {
        val s = minOf(this.size.width / VIEW_W, this.size.height / VIEW_H)
        val dx = (this.size.width - VIEW_W * s) / 2f
        val dy = (this.size.height - VIEW_H * s) / 2f
        // Borde fino del mismo color para cerrar las micro-grietas entre capas
        // del trazado (constante en pixeles de pantalla, independiente del tamano).
        val seamFix = SEAM_FIX_PX * VIEW_H / this.size.height.coerceAtLeast(1f)
        withTransform({ translate(dx, dy); scale(s, s, pivot = Offset.Zero) }) {
__DRAW_BLOCK__
        }
    }
}
"""

SOLID_DRAW = """            TRACED.forEachIndexed { idx, entry ->
                val c = entry.first
                val p = CACHE[idx]
                drawPath(p, c)
                drawPath(p, c, style = Stroke(width = seamFix))
            }"""

GRADIENT_DRAW = """            TRACED.forEachIndexed { idx, _ ->
                val p = CACHE[idx]
                drawPath(p, BRUSH)
                drawPath(p, BRUSH, style = Stroke(width = seamFix))
            }"""

SEAM_FIX_CONST = 'private const val SEAM_FIX_PX = 1.25f'

# Tail animado (modo gradiente): respiracion de la llama + barrido de luz.
KOTLIN_TAIL_ANIM = """
// opcode 0=moveTo(2), 1=lineTo(2), 2=cubicTo(6), 3=close
private fun parseChunks(chunks: List<String>): Path {
    val p = Path()
    for (c in chunks) {
        val d = c.split(" ")
        var i = 0
        while (i < d.size) {
            when (d[i].toInt()) {
                0 -> {
                    p.moveTo(d[i + 1].toFloat(), d[i + 2].toFloat()); i += 3
                }
                1 -> {
                    p.lineTo(d[i + 1].toFloat(), d[i + 2].toFloat()); i += 3
                }
                2 -> {
                    p.cubicTo(
                        d[i + 1].toFloat(), d[i + 2].toFloat(),
                        d[i + 3].toFloat(), d[i + 4].toFloat(),
                        d[i + 5].toFloat(), d[i + 6].toFloat(),
                    )
                    i += 7
                }
                else -> {
                    p.close(); i += 1
                }
            }
        }
    }
    return p
}

private val CACHE: List<Path> by lazy { TRACED.map { (_, chunks) -> parseChunks(chunks) } }

@Composable
fun __FUN__(
    modifier: Modifier = Modifier,
    size: Dp = 200.dp,
    animate: Boolean = true,
) {
    if (!animate) {
        Canvas(modifier = modifier.size(size)) { drawFlame(GRAD_TOP, 1f, null) }
        return
    }
    val transition = rememberInfiniteTransition(label = "flame")
    val breathe by transition.animateFloat(
        initialValue = 0.985f,
        targetValue = 1.02f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1500, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "flame-breathe",
    )
    val sweep by transition.animateFloat(
        initialValue = -0.25f,
        targetValue = 1.25f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 3200, easing = LinearEasing),
        ),
        label = "flame-sweep",
    )
    Canvas(modifier = modifier.size(size)) {
        val glow = ((breathe - 0.985f) / 0.035f).coerceIn(0f, 1f)
        drawFlame(mixHot(GRAD_TOP, GRAD_TOP_HOT, glow), breathe, VIEW_H * sweep)
    }
}

private fun mixHot(a: Color, b: Color, t: Float): Color = Color(
    red = a.red + (b.red - a.red) * t,
    green = a.green + (b.green - a.green) * t,
    blue = a.blue + (b.blue - a.blue) * t,
    alpha = 1f,
)

private fun DrawScope.drawFlame(top: Color, breathe: Float, sweepY: Float?) {
    val s = minOf(size.width / VIEW_W, size.height / VIEW_H)
    val dx = (size.width - VIEW_W * s) / 2f
    val dy = (size.height - VIEW_H * s) / 2f
    // Borde fino que cierra las micro-grietas entre capas del trazado
    val seamFix = SEAM_FIX_PX * VIEW_H / size.height.coerceAtLeast(1f)
    val brush = Brush.linearGradient(
        0f to top,
        1f to GRAD_BOTTOM,
        start = GRAD_START,
        end = GRAD_END,
    )
    // Respira anclada a la base: se estira hacia arriba y afina un poco
    val grow = breathe - 1f
    val kx = s * (1f - grow * 0.45f)
    val ky = s * (1f + grow)
    // Compensa la traslacion para que el pie de la llama quede fijo
    val ax = dx + (s - kx) * (VIEW_W / 2f)
    val ay = dy + (s - ky) * VIEW_H
    withTransform({
        translate(ax, ay)
        scale(kx, ky, pivot = Offset.Zero)
    }) {
        for (i in CACHE.indices) {
            val p = CACHE[i]
            drawPath(p, brush)
            drawPath(p, brush, style = Stroke(width = seamFix))
        }
        if (sweepY != null) {
            val half = VIEW_H * 0.16f
            val sheen = Brush.linearGradient(
                0f to Color.Transparent,
                0.5f to Color.White.copy(alpha = 0.15f),
                1f to Color.Transparent,
                start = Offset(0f, sweepY - half),
                end = Offset(0f, sweepY + half),
            )
            for (i in CACHE.indices) drawPath(CACHE[i], sheen)
        }
    }
}
"""


def iter_pts(ops):
    i = 0
    while i < len(ops):
        op = int(ops[i])
        if op in (0, 1):
            yield ops[i + 1], ops[i + 2]
            i += 3
        elif op == 2:
            yield ops[i + 1], ops[i + 2]
            yield ops[i + 3], ops[i + 4]
            yield ops[i + 5], ops[i + 6]
            i += 7
        else:
            i += 1


def gradient_brush_kotlin(kept, vw, vh):
    """Deduce direccion y colores del gradiente desde los paths, y emite el BRUSH."""
    stats = []
    for ops, fill in kept:
        pts = list(iter_pts(ops))
        n = max(1, len(pts))
        mx = sum(p[0] for p in pts) / n
        my = sum(p[1] for p in pts) / n
        lum = (
            0.2126 * int(fill[0:2], 16)
            + 0.7152 * int(fill[2:4], 16)
            + 0.0722 * int(fill[4:6], 16)
        )
        stats.append((lum, mx, my, fill))

    lums = sorted(s[0] for s in stats)
    med = lums[len(lums) // 2]
    lights = [s for s in stats if s[0] > med] or [max(stats, key=lambda s: s[0])]
    darks = [s for s in stats if s[0] <= med] or [min(stats, key=lambda s: s[0])]
    lx = sum(s[1] for s in lights) / len(lights)
    ly = sum(s[2] for s in lights) / len(lights)
    dx_ = sum(s[1] for s in darks) / len(darks)
    dy_ = sum(s[2] for s in darks) / len(darks)

    # Extremo claro: el mas luminoso que no sea un blanco/brillo lavado
    colored = [s for s in stats if s[0] <= 230] or stats
    light_hex = max(colored, key=lambda s: s[0])[3]
    dark_hex = min(colored, key=lambda s: s[0])[3]

    if abs(lx - dx_) > abs(ly - dy_) * 1.4:
        if lx > dx_:   # claro a la derecha
            start, end = (vw, vh / 2), (0.0, vh / 2)
        else:          # claro a la izquierda
            start, end = (0.0, vh / 2), (vw, vh / 2)
    else:
        if ly > dy_:   # claro abajo
            start, end = (vw / 2, vh), (vw / 2, 0.0)
        else:          # claro arriba
            start, end = (vw / 2, 0.0), (vw / 2, vh)

    hot_hex = "".join(
        "%02X" % min(255, round(int(light_hex[k:k + 2], 16) * 0.7 + 255 * 0.3))
        for k in (0, 2, 4)
    )
    return (
        "private val GRAD_TOP = Color(0xFF%s)\n"
        "private val GRAD_TOP_HOT = Color(0xFF%s)\n"
        "private val GRAD_BOTTOM = Color(0xFF%s)\n"
        "private val GRAD_START = Offset(%sf, %sf)\n"
        "private val GRAD_END = Offset(%sf, %sf)\n"
        % (light_hex, hot_hex, dark_hex,
           fmt(start[0]), fmt(start[1]), fmt(end[0]), fmt(end[1]))
    )


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("input")
    ap.add_argument("output", nargs="?", default=DEFAULT_OUT)
    ap.add_argument("--fun", default=DEFAULT_FUN)
    ap.add_argument("--package", default=DEFAULT_PKG)
    ap.add_argument("--white-threshold", type=int, default=235)
    ap.add_argument("--no-gradient", action="store_true",
                    help="usa los colores planos del SVG en vez de un gradiente unico")
    args = ap.parse_args()

    text = Path(args.input).read_text(encoding="utf-8")

    vw, vh = 100.0, 100.0
    vb = VB_RE.search(text)
    if vb:
        n = [float(x) for x in re.findall(r"-?\d+(?:\.\d+)?(?:[eE][+-]?\d+)?", vb.group(1))]
        if len(n) >= 4 and n[2] > n[0] and n[3] > n[1]:
            vw, vh = n[2] - n[0], n[3] - n[1]

    kept, skipped_white, skipped_empty = [], 0, 0
    for m in PATH_RE.finditer(text):
        el = m.group(0)
        dm = D_RE.search(el)
        if not dm or not dm.group(1).strip():
            skipped_empty += 1
            continue
        fm = STYLE_FILL_RE.search(el) or ATTR_FILL_RE.search(el)
        fill = fm.group(1).upper() if fm else "000000"
        rgb = [int(fill[k:k + 2], 16) for k in (0, 2, 4)]
        if min(rgb) >= args.white_threshold:
            skipped_white += 1
            continue
        try:
            ops = to_ops(parse_path(dm.group(1)))
        except ValueError as e:
            sys.exit("ERROR en path #%s: %s" % (fill, e))
        if len(ops) < 4:
            skipped_empty += 1
            continue
        kept.append((ops, fill))

    L = []
    w = L.append
    w("// Generado automaticamente por tools/svg_to_compose.py desde %s." % Path(args.input).name)
    w("// No editar a mano: regenerar con el script.")
    w("package %s" % args.package)
    w("")
    for imp in (
        "androidx.compose.foundation.Canvas",
        "androidx.compose.foundation.layout.size",
        "androidx.compose.runtime.Composable",
        "androidx.compose.ui.Modifier",
        "androidx.compose.ui.geometry.Offset",
        "androidx.compose.ui.graphics.Brush",
        "androidx.compose.ui.graphics.Color",
        "androidx.compose.ui.graphics.Path",
        "androidx.compose.ui.graphics.drawscope.DrawScope",
        "androidx.compose.ui.graphics.drawscope.Stroke",
        "androidx.compose.ui.graphics.drawscope.withTransform",
        "androidx.compose.animation.core.FastOutSlowInEasing",
        "androidx.compose.animation.core.LinearEasing",
        "androidx.compose.animation.core.RepeatMode",
        "androidx.compose.animation.core.animateFloat",
        "androidx.compose.animation.core.infiniteRepeatable",
        "androidx.compose.animation.core.rememberInfiniteTransition",
        "androidx.compose.animation.core.tween",
        "androidx.compose.runtime.getValue",
        "androidx.compose.ui.unit.Dp",
        "androidx.compose.ui.unit.dp",
    ):
        w("import %s" % imp)
    w("")
    w("private const val VIEW_W = %sf" % fmt(vw))
    w("private const val VIEW_H = %sf" % fmt(vh))
    w(SEAM_FIX_CONST)
    total = 0
    groups = []
    for pi, (ops, fill) in enumerate(kept):
        total += len(ops)
        names = []
        for j in range(0, len(ops), CHUNK):
            chunk = ops[j:j + CHUNK]
            name = "D%d_%d" % (pi, j // CHUNK)
            names.append(name)
            w("")
            w('private val %s = "%s"' % (name, " ".join(fmt(v) for v in chunk)))
        groups.append((names, fill))
    w("")
    w("private val TRACED: List<Pair<Color, List<String>>> = listOf(")
    for names, fill in groups:
        w("    Color(0xFF%s) to listOf(%s)," % (fill, ", ".join(names)))
    w(")")
    use_gradient = not args.no_gradient and len(kept) >= 2
    if not use_gradient:
        # en modo solido no se usan Brush ni la animacion; limpiar imports
        unused = {
            "androidx.compose.ui.graphics.Brush",
            "androidx.compose.ui.graphics.drawscope.DrawScope",
                "androidx.compose.animation.core.FastOutSlowInEasing",
            "androidx.compose.animation.core.LinearEasing",
            "androidx.compose.animation.core.RepeatMode",
            "androidx.compose.animation.core.animateFloat",
            "androidx.compose.animation.core.infiniteRepeatable",
            "androidx.compose.animation.core.rememberInfiniteTransition",
            "androidx.compose.animation.core.tween",
            "androidx.compose.runtime.getValue",
        }
        L = [ln for ln in L if ln[len("import "):] not in unused]
        w(KOTLIN_TAIL
          .replace("__FUN__", args.fun)
          .replace("__DRAW_BLOCK__", SOLID_DRAW))
    else:
        w(gradient_brush_kotlin(kept, vw, vh))
        w(KOTLIN_TAIL_ANIM.replace("__FUN__", args.fun))

    out = Path(args.output)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text("\n".join(L), encoding="utf-8")
    print(
        "OK: %d paths (%d blancos descartados, %d vacios), %d floats, viewBox %gx%g -> %s"
        % (len(kept), skipped_white, skipped_empty, total, vw, vh, out)
    )


if __name__ == "__main__":
    main()
