package com.cuenote.sketch

/**
 * Finds the sheet of paper (or whiteboard) in a photo.
 *
 * Paper is the biggest bright patch: the photo is shrunk to about 400 px, split
 * into bright and dark at the level that best separates the two (Otsu), and
 * the largest bright region is kept. Its outline's convex hull gives the four
 * corners: the four hull points that enclose the most area. When that region
 * is small, or does not fill its four corners like a sheet would, there is no
 * page and the photo is only whitened, not straightened.
 */
object PageFinder {
    data class Found(val corners: Quad, val confidence: Double)

    /** Working size for the search; corners are scaled back to the full photo. */
    const val WORK_SIDE = 400
    /** The page must cover at least this much of the photo. */
    const val MIN_AREA = 0.12
    /** And fill at least this much of the four-cornered shape around it. */
    const val MIN_FILL = 0.82

    fun find(img: Raster): Found? {
        val small = img.downscale(WORK_SIDE)
        val scale = img.width.toDouble() / small.width
        val gray = small.gray()
        val t = otsu(gray)
        val w = small.width; val h = small.height
        val bright = BooleanArray(gray.size) { gray[it] > t }

        val (component, size) = largestComponent(bright, w, h) ?: return null
        if (size < MIN_AREA * w * h) return null

        val outline = ArrayList<Pt>()
        for (y in 0 until h) for (x in 0 until w) {
            if (!component[y * w + x]) continue
            val edge = x == 0 || y == 0 || x == w - 1 || y == h - 1 ||
                !component[y * w + x - 1] || !component[y * w + x + 1] ||
                !component[(y - 1) * w + x] || !component[(y + 1) * w + x]
            // pixel corners, so a page that fills the frame reaches its edges
            if (edge) {
                outline += Pt(x.toDouble(), y.toDouble()); outline += Pt(x + 1.0, y.toDouble())
                outline += Pt(x.toDouble(), y + 1.0); outline += Pt(x + 1.0, y + 1.0)
            }
        }
        val hull = convexHull(outline)
        if (hull.size < 4) return null
        val quad = largestQuad(hull) ?: return null
        val fill = size / quad.area()
        if (fill < MIN_FILL) return null
        val coverage = quad.area() / (w.toDouble() * h)
        val confidence = (fill.coerceAtMost(1.0) - MIN_FILL) / (1 - MIN_FILL) * 0.6 +
            ((coverage - MIN_AREA) / (0.5 - MIN_AREA)).coerceIn(0.0, 1.0) * 0.4
        return Found(quad.scaled(scale), confidence.coerceIn(0.0, 1.0))
    }

    /** The threshold that best splits the histogram into two groups. */
    internal fun otsu(gray: IntArray): Int {
        val hist = IntArray(256)
        for (g in gray) hist[g]++
        val total = gray.size.toDouble()
        var sumAll = 0.0
        for (i in 0..255) sumAll += i * hist[i].toDouble()
        var sumB = 0.0; var wB = 0.0
        var best = 0.0; var threshold = 127
        for (i in 0..255) {
            wB += hist[i]
            if (wB == 0.0) continue
            val wF = total - wB
            if (wF == 0.0) break
            sumB += i * hist[i].toDouble()
            val mB = sumB / wB
            val mF = (sumAll - sumB) / wF
            val between = wB * wF * (mB - mF) * (mB - mF)
            if (between > best) { best = between; threshold = i }
        }
        return threshold
    }

    /** The largest 4-connected region of true pixels, and its size. */
    internal fun largestComponent(mask: BooleanArray, w: Int, h: Int): Pair<BooleanArray, Int>? {
        val label = IntArray(mask.size)
        var next = 0
        var bestLabel = 0; var bestSize = 0
        val stack = IntArray(mask.size)
        for (start in mask.indices) {
            if (!mask[start] || label[start] != 0) continue
            next++
            var top = 0; var size = 0
            stack[top++] = start; label[start] = next
            while (top > 0) {
                val i = stack[--top]; size++
                val x = i % w; val y = i / w
                if (x > 0 && mask[i - 1] && label[i - 1] == 0) { label[i - 1] = next; stack[top++] = i - 1 }
                if (x < w - 1 && mask[i + 1] && label[i + 1] == 0) { label[i + 1] = next; stack[top++] = i + 1 }
                if (y > 0 && mask[i - w] && label[i - w] == 0) { label[i - w] = next; stack[top++] = i - w }
                if (y < h - 1 && mask[i + w] && label[i + w] == 0) { label[i + w] = next; stack[top++] = i + w }
            }
            if (size > bestSize) { bestSize = size; bestLabel = next }
        }
        if (bestSize == 0) return null
        return BooleanArray(mask.size) { label[it] == bestLabel } to bestSize
    }

    /** Andrew's monotone chain; returns the hull clockwise on screen. */
    internal fun convexHull(points: List<Pt>): List<Pt> {
        val p = points.distinct().sortedWith(compareBy<Pt> { it.x }.thenBy { it.y })
        if (p.size < 3) return p
        fun cross(o: Pt, a: Pt, b: Pt) = (a.x - o.x) * (b.y - o.y) - (a.y - o.y) * (b.x - o.x)
        val lower = ArrayList<Pt>()
        for (pt in p) {
            while (lower.size >= 2 && cross(lower[lower.size - 2], lower[lower.size - 1], pt) <= 0) lower.removeAt(lower.size - 1)
            lower += pt
        }
        val upper = ArrayList<Pt>()
        for (pt in p.asReversed()) {
            while (upper.size >= 2 && cross(upper[upper.size - 2], upper[upper.size - 1], pt) <= 0) upper.removeAt(upper.size - 1)
            upper += pt
        }
        return lower.dropLast(1) + upper.dropLast(1)
    }

    /**
     * The four hull points enclosing the most area. For each diagonal (i, k)
     * the best third and fourth points lie on opposite sides and can be
     * chosen apart, so this is cubic in the hull size, which stays small.
     */
    internal fun largestQuad(hullIn: List<Pt>): Quad? {
        // keep the hull small: a sheet's hull has few real corners
        val hull = if (hullIn.size > 80) List(80) { hullIn[it * hullIn.size / 80] } else hullIn
        val n = hull.size
        if (n < 4) return null
        fun tri(a: Pt, b: Pt, c: Pt) = kotlin.math.abs((b.x - a.x) * (c.y - a.y) - (b.y - a.y) * (c.x - a.x)) / 2
        var best = -1.0
        var pick = IntArray(4)
        for (i in 0 until n) for (k in i + 2 until n) {
            if (i == 0 && k == n - 1) continue
            var bj = -1; var aj = -1.0
            for (j in i + 1 until k) { val a = tri(hull[i], hull[j], hull[k]); if (a > aj) { aj = a; bj = j } }
            var bl = -1; var al = -1.0
            for (l in (k + 1 until n) + (0 until i)) { val a = tri(hull[k], hull[l], hull[i]); if (a > al) { al = a; bl = l } }
            if (bj < 0 || bl < 0) continue
            if (aj + al > best) { best = aj + al; pick = intArrayOf(i, bj, k, bl) }
        }
        if (best <= 0) return null
        return Quad.ordered(pick.map { hull[it] })
    }
}
