package com.cuenote.sketch

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Turns a phone photo of a sketch into a clean, flat page:
 *
 * 1. **Find the page** ([PageFinder]), unless the corners are given (the
 *    student dragged them by hand).
 * 2. **Straighten**: map the four corners onto a rectangle (a perspective
 *    transform), so a sheet shot at an angle comes out square.
 * 3. **Whiten**: estimate the paper's own brightness everywhere (the
 *    brightest value in each small block, smoothed), divide it out so shadows
 *    and the lamp's gradient vanish, then push near-white to white. Pen and
 *    marker colors are kept.
 *
 * The original photo is never changed; this makes a second picture.
 */
object SketchCleaner {
    data class Result(
        val image: Raster,
        /** The corners used, in the original photo's pixels; null when no page was found. */
        val corners: Quad?,
        /** How sure the page finder was, 0..1; 1 for corners set by hand, 0 when no page. */
        val confidence: Double,
    )

    /** The cleaned page's long side, at most; plenty for a phone screen or a laptop. */
    const val MAX_SIDE = 1600
    private const val INSET = 0.006

    fun clean(photo: Raster, corners: Quad? = null): Result {
        val found = if (corners == null) PageFinder.find(photo) else null
        val quad = corners ?: found?.corners
        val flat = if (quad != null) straighten(photo, quad) else photo.downscale(MAX_SIDE)
        return Result(whiten(flat), quad, if (corners != null) 1.0 else found?.confidence ?: 0.0)
    }

    /** The page inside [q] as an upright rectangle of about its own proportions. */
    fun straighten(photo: Raster, q: Quad, maxSide: Int = MAX_SIDE): Raster {
        var w = (q.tl.dist(q.tr) + q.bl.dist(q.br)) / 2
        var h = (q.tl.dist(q.bl) + q.tr.dist(q.br)) / 2
        val s = min(1.0, maxSide / max(w, h))
        w *= s; h *= s
        val ow = max(1, w.roundToInt()); val oh = max(1, h.roundToInt())
        val hm = Homography.squareTo(q, ow.toDouble(), oh.toDouble())
        val out = Raster(ow, oh)
        // a hair inside the corners, so no sliver of table shows at the edges
        val ix = ow * INSET; val iy = oh * INSET
        val sx = (ow - 2 * ix) / ow; val sy = (oh - 2 * iy) / oh
        for (y in 0 until oh) for (x in 0 until ow) {
            val p = hm.map(ix + (x + 0.5) * sx, iy + (y + 0.5) * sy)
            out[x, y] = sample(photo, p.x - 0.5, p.y - 0.5)
        }
        return out
    }

    /** Bilinear sample, clamped at the edges. */
    private fun sample(img: Raster, fx: Double, fy: Double): Int {
        val x = fx.coerceIn(0.0, img.width - 1.0); val y = fy.coerceIn(0.0, img.height - 1.0)
        val x0 = floor(x).toInt(); val y0 = floor(y).toInt()
        val x1 = min(x0 + 1, img.width - 1); val y1 = min(y0 + 1, img.height - 1)
        val ax = x - x0; val ay = y - y0
        val a = img[x0, y0]; val b = img[x1, y0]; val c = img[x0, y1]; val d = img[x1, y1]
        fun ch(shift: Int): Int {
            val va = (a shr shift) and 0xFF; val vb = (b shr shift) and 0xFF
            val vc = (c shr shift) and 0xFF; val vd = (d shr shift) and 0xFF
            val top = va + (vb - va) * ax; val bottom = vc + (vd - vc) * ax
            return (top + (bottom - top) * ay).roundToInt()
        }
        return Raster.argb(ch(16), ch(8), ch(0))
    }

    /** White paper, dark ink: divides out the paper's brightness and lifts it to white. */
    fun whiten(img: Raster, white: Int = 225, black: Int = 25): Raster {
        val bg = paperLevel(img)
        val out = Raster(img.width, img.height)
        val range = (white - black).toDouble()
        for (i in img.pixels.indices) {
            val p = img.pixels[i]
            val b = max(bg[i], 24).toDouble()
            fun ch(shift: Int): Int {
                val v = ((p shr shift) and 0xFF) * 255.0 / b         // paper becomes ~255 everywhere
                return ((v - black) * 255.0 / range).roundToInt()      // and near-white becomes white
            }
            out.pixels[i] = Raster.argb(ch(16), ch(8), ch(0))
        }
        return out
    }

    /**
     * How bright the paper is at every pixel: the brightest luminance in each
     * block (ink strokes are thinner than a block), smoothed, then spread back
     * over the pixels.
     */
    internal fun paperLevel(img: Raster): IntArray {
        val gray = img.gray()
        val block = max(8, max(img.width, img.height) / 40)
        val gw = (img.width + block - 1) / block
        val gh = (img.height + block - 1) / block
        var grid = DoubleArray(gw * gh)
        for (gy in 0 until gh) for (gx in 0 until gw) {
            var m = 0
            val y1 = min(img.height, (gy + 1) * block); val x1 = min(img.width, (gx + 1) * block)
            for (y in gy * block until y1) for (x in gx * block until x1) m = max(m, gray[y * img.width + x])
            grid[gy * gw + gx] = m.toDouble()
        }
        repeat(2) { grid = boxBlur(grid, gw, gh) }
        val out = IntArray(gray.size)
        for (y in 0 until img.height) {
            val gyf = ((y + 0.5) / block - 0.5).coerceIn(0.0, gh - 1.0)
            val gy0 = floor(gyf).toInt(); val gy1 = min(gy0 + 1, gh - 1); val ay = gyf - gy0
            for (x in 0 until img.width) {
                val gxf = ((x + 0.5) / block - 0.5).coerceIn(0.0, gw - 1.0)
                val gx0 = floor(gxf).toInt(); val gx1 = min(gx0 + 1, gw - 1); val ax = gxf - gx0
                val top = grid[gy0 * gw + gx0] * (1 - ax) + grid[gy0 * gw + gx1] * ax
                val bottom = grid[gy1 * gw + gx0] * (1 - ax) + grid[gy1 * gw + gx1] * ax
                out[y * img.width + x] = (top * (1 - ay) + bottom * ay).roundToInt()
            }
        }
        return out
    }

    private fun boxBlur(g: DoubleArray, w: Int, h: Int): DoubleArray {
        val out = DoubleArray(g.size)
        for (y in 0 until h) for (x in 0 until w) {
            var s = 0.0; var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx; val yy = y + dy
                if (xx in 0 until w && yy in 0 until h) { s += g[yy * w + xx]; n++ }
            }
            out[y * w + x] = s / n
        }
        return out
    }
}

/** A 3×3 perspective transform, mapping output pixels back to the photo. */
class Homography(private val m: DoubleArray) {
    fun map(x: Double, y: Double): Pt {
        val d = m[6] * x + m[7] * y + m[8]
        return Pt((m[0] * x + m[1] * y + m[2]) / d, (m[3] * x + m[4] * y + m[5]) / d)
    }

    companion object {
        /** The transform taking the rectangle (0,0)–(w,h) onto [q]'s corners. */
        fun squareTo(q: Quad, w: Double, h: Double): Homography {
            val src = listOf(Pt(0.0, 0.0), Pt(w, 0.0), Pt(w, h), Pt(0.0, h))
            val dst = q.points
            val a = Array(8) { DoubleArray(9) }
            for (i in 0 until 4) {
                val (x, y) = src[i]; val (u, v) = dst[i]
                a[i * 2] = doubleArrayOf(x, y, 1.0, 0.0, 0.0, 0.0, -u * x, -u * y, u)
                a[i * 2 + 1] = doubleArrayOf(0.0, 0.0, 0.0, x, y, 1.0, -v * x, -v * y, v)
            }
            val sol = solve(a)
            return Homography(sol + 1.0)
        }

        /** Gaussian elimination with partial pivoting on an 8×9 augmented matrix. */
        private fun solve(a: Array<DoubleArray>): DoubleArray {
            val n = 8
            for (c in 0 until n) {
                val p = (c until n).maxBy { kotlin.math.abs(a[it][c]) }
                val t = a[c]; a[c] = a[p]; a[p] = t
                require(kotlin.math.abs(a[c][c]) > 1e-12) { "corners are degenerate" }
                for (r in 0 until n) if (r != c) {
                    val f = a[r][c] / a[c][c]
                    for (k in c..n) a[r][k] -= f * a[c][k]
                }
            }
            return DoubleArray(n) { a[it][n] / a[it][it] }
        }
    }
}
