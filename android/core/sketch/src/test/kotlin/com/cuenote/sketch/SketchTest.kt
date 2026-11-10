package com.cuenote.sketch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.imageio.ImageIO
import java.awt.image.BufferedImage
import kotlin.random.Random

class SketchTest {

    /**
     * A phone photo of a sheet on a dark table: the sheet is tilted and shot
     * at an angle, lit unevenly (brighter at the top left), with a few dark
     * pen strokes and a red marker stroke on it, and some sensor noise.
     */
    private fun photo(corners: Quad, w: Int = 1200, h: Int = 900, seed: Int = 1): Raster {
        val img = Raster(w, h)
        val rnd = Random(seed)
        val sheet = Homography.squareTo(corners, 1.0, 1.0)
        // inverse check by point-in-polygon, then where on the sheet (u, v) the pixel is
        for (y in 0 until h) for (x in 0 until w) {
            val inside = insideQuad(corners, x + 0.5, y + 0.5)
            val noise = rnd.nextInt(-6, 7)
            if (!inside) {
                img[x, y] = Raster.argb(58 + noise, 50 + noise, 44 + noise)
                continue
            }
            val light = 205 - (x + y) * 50 / (w + h)          // 205 at top left, ~155 at bottom right
            img[x, y] = Raster.argb(light + noise, light + noise, light - 6 + noise)
        }
        // strokes, drawn in sheet coordinates so they sit on the paper
        fun stroke(u0: Double, v0: Double, u1: Double, v1: Double, color: Int) {
            for (i in 0..400) {
                val t = i / 400.0
                val p = sheet.map(u0 + (u1 - u0) * t, v0 + (v1 - v0) * t)
                for (dy in -3..3) for (dx in -3..3) {
                    val xx = p.x.toInt() + dx; val yy = p.y.toInt() + dy
                    if (xx in 0 until w && yy in 0 until h) img[xx, yy] = color
                }
            }
        }
        stroke(0.1, 0.2, 0.9, 0.2, Raster.argb(30, 30, 40))
        stroke(0.1, 0.5, 0.6, 0.8, Raster.argb(30, 30, 40))
        stroke(0.3, 0.3, 0.3, 0.9, Raster.argb(190, 40, 40))
        return img
    }

    private fun insideQuad(q: Quad, x: Double, y: Double): Boolean {
        val p = q.points
        var sign = 0
        for (i in 0 until 4) {
            val a = p[i]; val b = p[(i + 1) % 4]
            val c = (b.x - a.x) * (y - a.y) - (b.y - a.y) * (x - a.x)
            val s = if (c > 0) 1 else if (c < 0) -1 else 0
            if (s != 0) { if (sign == 0) sign = s else if (s != sign) return false }
        }
        return true
    }

    private val tilted = Quad(Pt(180.0, 120.0), Pt(1010.0, 70.0), Pt(1080.0, 820.0), Pt(130.0, 790.0))

    @Test fun `finds the four corners of a tilted sheet`() {
        val found = PageFinder.find(photo(tilted))
        assertNotNull("no page found", found)
        for ((got, want) in found!!.corners.points.zip(tilted.points)) {
            assertTrue("corner $got is not near $want", got.dist(want) < 14.0)
        }
        assertTrue("confidence ${found.confidence}", found.confidence > 0.5)
    }

    @Test fun `corners come back in reading order whatever order they are given`() {
        val shuffled = listOf(tilted.br, tilted.tl, tilted.bl, tilted.tr)
        assertEquals(tilted, Quad.ordered(shuffled))
        val n = tilted.normalized(1200, 900)
        val back = Quad.fromNormalized(n, 1200, 900)
        for ((a, b) in back.points.zip(tilted.points)) assertTrue(a.dist(b) < 1e-6)
    }

    @Test fun `the perspective transform puts each corner where it belongs`() {
        val h = Homography.squareTo(tilted, 400.0, 300.0)
        val got = listOf(h.map(0.0, 0.0), h.map(400.0, 0.0), h.map(400.0, 300.0), h.map(0.0, 300.0))
        for ((a, b) in got.zip(tilted.points)) assertTrue("$a vs $b", a.dist(b) < 1e-6)
    }

    @Test fun `a photo with no sheet in it is not straightened`() {
        val rnd = Random(3)
        val img = Raster(600, 400)
        for (i in img.pixels.indices) { val v = rnd.nextInt(60, 200); img.pixels[i] = Raster.argb(v, v, v) }
        assertNull(PageFinder.find(img))
        val r = SketchCleaner.clean(img)
        assertNull(r.corners)
        assertEquals(0.0, r.confidence, 0.0)
    }

    @Test fun `a sheet that fills the whole frame is found as the frame`() {
        val full = Quad(Pt(0.0, 0.0), Pt(800.0, 0.0), Pt(800.0, 600.0), Pt(0.0, 600.0))
        val found = PageFinder.find(photo(full, 800, 600))
        assertNotNull(found)
        for ((a, b) in found!!.corners.points.zip(full.points)) assertTrue("$a vs $b", a.dist(b) < 10.0)
    }

    @Test fun `cleaning makes the paper white, keeps the ink dark and the red marker red`() {
        val r = SketchCleaner.clean(photo(tilted))
        val img = r.image
        assertNotNull(r.corners)
        // straightened: roughly the sheet's own proportions (~900 × 710)
        assertTrue("size ${img.width}x${img.height}", img.width in 800..1000 && img.height in 640..800)
        // paper, away from any stroke, near both the bright and the shadowed corner
        val paper = listOf(img[img.width / 20, img.height / 20], img[img.width * 19 / 20, img.height * 19 / 20],
            img[img.width * 3 / 4, img.height * 2 / 5])
        for (p in paper) assertTrue("paper is ${Raster.luma(p)}", Raster.luma(p) >= 245)
        // the black pen line across the top at v = 0.2
        val ink = img[img.width / 2, (img.height * 0.2).toInt()]
        assertTrue("ink is ${Raster.luma(ink)}", Raster.luma(ink) < 80)
        // the red marker at u = 0.3
        val red = img[(img.width * 0.3).toInt(), (img.height * 0.7).toInt()]
        val (rr, gg) = (red shr 16 and 0xFF) to (red shr 8 and 0xFF)
        assertTrue("marker is r=$rr g=$gg", rr > 150 && gg < 110)

        // For a look by eye: build/sketch-test/{before,after}.png
        File("build/sketch-test").mkdirs()
        write(photo(tilted), File("build/sketch-test/before.png"))
        write(img, File("build/sketch-test/after.png"))
    }

    @Test fun `corners set by hand are used as they are`() {
        val hand = Quad(Pt(200.0, 130.0), Pt(1000.0, 80.0), Pt(1070.0, 810.0), Pt(140.0, 780.0))
        val r = SketchCleaner.clean(photo(tilted), hand)
        assertEquals(hand, r.corners)
        assertEquals(1.0, r.confidence, 0.0)
    }

    private fun write(r: Raster, f: File) {
        val bi = BufferedImage(r.width, r.height, BufferedImage.TYPE_INT_RGB)
        bi.setRGB(0, 0, r.width, r.height, r.pixels, 0, r.width)
        ImageIO.write(bi, "png", f)
    }
}
