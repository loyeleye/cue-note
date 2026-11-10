package com.cuenote.sketch

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * An image as plain ARGB ints, row by row, so the clean-up runs the same in
 * JVM tests as on the phone (where it comes from and goes back to a Bitmap).
 */
class Raster(val width: Int, val height: Int, val pixels: IntArray = IntArray(width * height)) {
    init { require(pixels.size == width * height) { "pixels must be width × height" } }

    operator fun get(x: Int, y: Int): Int = pixels[y * width + x]
    operator fun set(x: Int, y: Int, argb: Int) { pixels[y * width + x] = argb }

    /** Luminance 0..255 of every pixel (Rec. 601 weights, integer maths). */
    fun gray(): IntArray = IntArray(pixels.size) { luma(pixels[it]) }

    /** A smaller copy where each pixel is the average of the block it covers. */
    fun downscale(maxSide: Int): Raster {
        val scale = max(width, height).toDouble() / maxSide
        if (scale <= 1.0) return this
        val w = max(1, (width / scale).roundToInt())
        val h = max(1, (height / scale).roundToInt())
        val out = Raster(w, h)
        for (y in 0 until h) {
            val y0 = (y * height) / h
            val y1 = max(y0 + 1, ((y + 1) * height) / h)
            for (x in 0 until w) {
                val x0 = (x * width) / w
                val x1 = max(x0 + 1, ((x + 1) * width) / w)
                var r = 0L; var g = 0L; var b = 0L; var n = 0
                for (yy in y0 until y1) for (xx in x0 until x1) {
                    val p = pixels[yy * width + xx]
                    r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF; n++
                }
                out[x, y] = argb((r / n).toInt(), (g / n).toInt(), (b / n).toInt())
            }
        }
        return out
    }

    companion object {
        fun argb(r: Int, g: Int, b: Int): Int =
            (0xFF shl 24) or (clamp(r) shl 16) or (clamp(g) shl 8) or clamp(b)

        fun luma(p: Int): Int = (((p shr 16) and 0xFF) * 299 + ((p shr 8) and 0xFF) * 587 + (p and 0xFF) * 114) / 1000

        fun clamp(v: Int): Int = min(255, max(0, v))
    }
}

/** A point in pixels; x right, y down. */
data class Pt(val x: Double, val y: Double) {
    operator fun minus(o: Pt) = Pt(x - o.x, y - o.y)
    fun dist(o: Pt): Double = kotlin.math.hypot(x - o.x, y - o.y)
    fun scaled(s: Double) = Pt(x * s, y * s)
}

/**
 * The page's four corners, always in the order top-left, top-right,
 * bottom-right, bottom-left, whatever order they were found in.
 */
data class Quad(val tl: Pt, val tr: Pt, val br: Pt, val bl: Pt) {
    val points: List<Pt> get() = listOf(tl, tr, br, bl)

    fun scaled(s: Double) = Quad(tl.scaled(s), tr.scaled(s), br.scaled(s), bl.scaled(s))

    fun area(): Double = polygonArea(points)

    /** Corners as fractions of the image size, the form the phone and the cloud store. */
    fun normalized(width: Int, height: Int): List<Double> =
        points.flatMap { listOf(it.x / width, it.y / height) }

    companion object {
        /** Puts four points in reading order: the one nearest the top-left first, then clockwise. */
        fun ordered(points: List<Pt>): Quad {
            require(points.size == 4)
            val cx = points.sumOf { it.x } / 4
            val cy = points.sumOf { it.y } / 4
            // clockwise on screen (y down) is increasing atan2
            val around = points.sortedBy { kotlin.math.atan2(it.y - cy, it.x - cx) }
            val start = around.indices.minBy { around[it].x + around[it].y }
            val o = List(4) { around[(start + it) % 4] }
            return Quad(o[0], o[1], o[2], o[3])
        }

        fun fromNormalized(v: List<Double>, width: Int, height: Int): Quad {
            require(v.size == 8) { "corners need 8 numbers" }
            return ordered(List(4) { Pt(v[it * 2] * width, v[it * 2 + 1] * height) })
        }
    }
}

internal fun polygonArea(p: List<Pt>): Double {
    var s = 0.0
    for (i in p.indices) {
        val a = p[i]; val b = p[(i + 1) % p.size]
        s += a.x * b.y - b.x * a.y
    }
    return kotlin.math.abs(s) / 2
}
