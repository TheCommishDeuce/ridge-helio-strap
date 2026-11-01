package app.strap.integrations

import androidx.compose.ui.unit.IntSize
import app.strap.ui.activity.Mercator
import app.strap.ui.activity.fit
import app.strap.ui.activity.world
import org.junit.Test
import kotlin.math.floor
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The map's arithmetic: tiles are cut in Web Mercator, and a fitted view holds the whole path. */
class TrackMapTest {
    @Test
    fun `positions land on the tiles OpenStreetMap cuts`() {
        val origin = Mercator.of(LatLon(0.0, 0.0))
        assertEquals(0.5f, origin.x, 1e-6f)
        assertEquals(0.5f, origin.y, 1e-6f)
        val berlin = Mercator.of(LatLon(52.52, 13.405)) // tile 12/2200/1343
        assertEquals(2200, floor(berlin.x * 4096).toInt())
        assertEquals(1343, floor(berlin.y * 4096).toInt())
    }

    @Test
    fun `a fitted view shows every point of the path`() {
        val path = listOf(LatLon(52.50, 13.38), LatLon(52.53, 13.45), LatLon(52.51, 13.41)).map(Mercator::of)
        val size = IntSize(1080, 840)
        val (center, zoom) = fit(path, size)
        val w = world(zoom)
        for (p in path) {
            val x = (p.x - center.x) * w + size.width / 2
            val y = (p.y - center.y) * w + size.height / 2
            assertTrue(x in 0f..size.width.toFloat() && y in 0f..size.height.toFloat(), "($x, $y) off the map")
        }
        assertTrue(zoom in 11f..17f, "zoom $zoom")
    }
}
