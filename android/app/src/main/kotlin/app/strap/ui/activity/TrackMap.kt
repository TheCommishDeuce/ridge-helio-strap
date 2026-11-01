package app.strap.ui.activity

import android.content.Context
import android.graphics.BitmapFactory
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import app.strap.BuildConfig
import app.strap.integrations.LatLon
import app.strap.ui.setup.MapTiles
import app.strap.ui.theme.LocalRidgeColors
import app.strap.ui.theme.RidgeType
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.tan

/**
 * Dawarich paths over map tiles (D42), drawn here rather than by a map library (MapLibre made the
 * APK 55 MB). Web Mercator image tiles from the owner's tile setting ([MapTiles], OpenStreetMap
 * by default), fetched only while a map is on screen and cached on the phone for two weeks, as
 * the OSM tile policy asks. Drag to pan, pinch or double-tap to zoom.
 */
/** A line on the map: a part the owner moved, or one they were carried (drawn grey and dashed). */
data class MapLine(val path: List<LatLon>, val carried: Boolean = false)

@Composable
fun TrackMap(lines: List<MapLine>, color: Color, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val template = remember { MapTiles.get(context) }
    val tiles = remember { TileStore(context, template) }
    val scope = rememberCoroutineScope()
    val points = remember(lines) { lines.filter { it.path.isNotEmpty() }.map { l -> l.carried to l.path.map { Mercator.of(it) } } }
    var size by remember { mutableStateOf(IntSize.Zero) }
    // The view: the centre in Mercator units (0..1 across the world) and a fractional zoom.
    var zoom by remember { mutableFloatStateOf(Float.NaN) }
    var center by remember { mutableStateOf(Offset.Zero) }
    val card = LocalRidgeColors.current.surface3
    val ink = MaterialTheme.colorScheme.onSurface
    Box(modifier.clipToBounds().onSizeChanged { s ->
        size = s
        if (zoom.isNaN() && s.width > 0) fit(points.flatMap { it.second }, s).let { (c, z) -> center = c; zoom = z }
    }.pointerInput(Unit) {
        detectTransformGestures { centroid, pan, gestureZoom, _ ->
            if (zoom.isNaN()) return@detectTransformGestures
            val before = world(zoom)
            val z = (zoom + log2(gestureZoom)).coerceIn(MIN_ZOOM, MAX_ZOOM)
            val after = world(z)
            val fromMiddle = centroid - Offset(size.width / 2f, size.height / 2f)
            // The point under the fingers stays under them; then the drag moves the map with them.
            val anchor = center + fromMiddle / before
            center = anchor - fromMiddle / after - pan / after
            zoom = z
        }
    }.pointerInput(Unit) {
        detectTapGestures(onDoubleTap = { at ->
            if (zoom.isNaN()) return@detectTapGestures
            val fromMiddle = at - Offset(size.width / 2f, size.height / 2f)
            val anchor = center + fromMiddle / world(zoom)
            zoom = (zoom + 1).coerceAtMost(MAX_ZOOM)
            center = anchor - fromMiddle / world(zoom)
        })
    }) {
        Canvas(Modifier.matchParentSize()) {
            drawRect(card)
            if (zoom.isNaN()) return@Canvas
            val z = zoom.roundToInt().coerceIn(0, MAX_TILE_ZOOM)
            val scale = 2.0.pow((zoom - z).toDouble()).toFloat()
            val tilePx = TILE * scale
            val worldZ = world(z.toFloat()) * scale // = world(zoom)
            val topLeft = center * worldZ - Offset(this.size.width / 2, this.size.height / 2)
            val n = 1 shl z
            val x0 = floor(topLeft.x / tilePx).toInt()
            val y0 = floor(topLeft.y / tilePx).toInt()
            val x1 = floor((topLeft.x + this.size.width) / tilePx).toInt()
            val y1 = floor((topLeft.y + this.size.height) / tilePx).toInt()
            for (ty in y0..y1) for (tx in x0..x1) {
                if (ty !in 0 until n) continue
                val wx = ((tx % n) + n) % n
                val key = Tile(z, wx, ty)
                val at = Offset(tx * tilePx - topLeft.x, ty * tilePx - topLeft.y)
                val image = tiles.loaded[key]
                if (image != null) {
                    drawImage(image, dstOffset = IntOffset(at.x.roundToInt(), at.y.roundToInt()),
                        dstSize = IntSize(tilePx.roundToInt() + 1, tilePx.roundToInt() + 1))
                } else {
                    tiles.request(key, scope)
                }
            }
            val stroke = Stroke(4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
            fun pathOf(p: List<Offset>) = Path().apply {
                p.forEachIndexed { i, m -> val o = m * worldZ - topLeft; if (i == 0) moveTo(o.x, o.y) else lineTo(o.x, o.y) }
            }
            // Carried legs first and quiet: grey, dashed, under the owner's own line.
            val dash = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx())))
            for ((carried, p) in points) if (carried) drawPath(pathOf(p), Color(0xFF5F6B6D), style = dash)
            for ((carried, p) in points) if (!carried) {
                val path = pathOf(p)
                drawPath(path, Color.White.copy(alpha = 0.85f), style = Stroke(stroke.width + 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                drawPath(path, color, style = stroke)
            }
            points.firstOrNull()?.second?.firstOrNull()?.let { a ->
                drawCircle(Color.White, 5.dp.toPx(), a * worldZ - topLeft)
                drawCircle(color, 3.5.dp.toPx(), a * worldZ - topLeft)
            }
            points.lastOrNull()?.second?.lastOrNull()?.let { b -> drawCircle(color, 5.dp.toPx(), b * worldZ - topLeft) }
        }
        Text(MapTiles.ATTRIBUTION, style = RidgeType.caption, color = ink,
            modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp)
                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.75f), androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                .padding(horizontal = 6.dp, vertical = 2.dp))
    }
}

/** Where the paths fit with a margin: the centre and the zoom (at most a street's). */
internal fun fit(all: List<Offset>, size: IntSize): Pair<Offset, Float> {
    if (all.isEmpty()) return Offset(0.5f, 0.5f) to 2f
    val minX = all.minOf { it.x }; val maxX = all.maxOf { it.x }
    val minY = all.minOf { it.y }; val maxY = all.maxOf { it.y }
    val c = Offset((minX + maxX) / 2, (minY + maxY) / 2)
    val w = (maxX - minX).coerceAtLeast(1e-9f); val h = (maxY - minY).coerceAtLeast(1e-9f)
    val margin = 0.8f
    val z = minOf(log2(size.width * margin / (w * TILE)), log2(size.height * margin / (h * TILE)))
    return c to z.coerceIn(MIN_ZOOM, 17f)
}

internal fun world(zoom: Float): Float = TILE * 2.0.pow(zoom.toDouble()).toFloat()

private data class Tile(val z: Int, val x: Int, val y: Int)

/** Web Mercator in 0..1 across the world, as the tiles are cut. */
internal object Mercator {
    fun of(p: LatLon): Offset {
        val lat = p.lat.coerceIn(-85.0511, 85.0511) * PI / 180
        return Offset(((p.lon + 180) / 360).toFloat(), ((1 - ln(tan(lat) + 1 / kotlin.math.cos(lat)) / PI) / 2).toFloat())
    }
}

/** Tiles in memory for the shown map, on disk for two weeks, fetched four at a time. */
private class TileStore(context: Context, private val template: String) {
    val loaded = mutableStateMapOf<Tile, ImageBitmap>()
    private val asked = HashSet<Tile>()
    private val gate = Semaphore(4)
    private val dir = File(context.cacheDir, "tiles/" + template.hashCode().toUInt().toString(16)).apply { mkdirs() }

    fun request(t: Tile, scope: CoroutineScope) {
        if (!asked.add(t)) return
        scope.launch {
            val bytes = gate.withPermit { withContext(Dispatchers.IO) { cached(t) ?: fetch(t) } } ?: run { asked.remove(t); return@launch }
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { loaded[t] = it.asImageBitmap() }
            if (loaded.size > MEMORY_TILES) loaded.keys.filter { it.z != t.z }.take(loaded.size - MEMORY_TILES).forEach { loaded.remove(it); asked.remove(it) }
        }
    }

    private fun file(t: Tile) = File(dir, "${t.z}_${t.x}_${t.y}")

    private fun cached(t: Tile): ByteArray? = file(t).takeIf { it.exists() && System.currentTimeMillis() - it.lastModified() < KEEP_MS }?.readBytes()

    private fun fetch(t: Tile): ByteArray? = try {
        val url = template.replace("{z}", "${t.z}").replace("{x}", "${t.x}").replace("{y}", "${t.y}")
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 10_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("User-Agent", "Ridge/${BuildConfig.VERSION_NAME} (+https://github.com/TheCommishDeuce/ridge-helio-strap)")
            if (conn.responseCode == 200) conn.inputStream.use { it.readBytes() }.also { file(t).writeBytes(it) } else null
        } finally {
            conn.disconnect()
        }
    } catch (e: java.io.IOException) {
        null
    }

    private companion object {
        const val MEMORY_TILES = 120
        const val KEEP_MS = 14L * 24 * 3600 * 1000
    }
}

private const val TILE = 256f
private const val MIN_ZOOM = 2f
private const val MAX_ZOOM = 19f
private const val MAX_TILE_ZOOM = 19
