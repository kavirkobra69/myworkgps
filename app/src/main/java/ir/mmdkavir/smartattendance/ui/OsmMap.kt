package ir.mmdkavir.smartattendance.ui

import android.graphics.Color as AColor
import android.graphics.drawable.GradientDrawable
import android.location.Location
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

@Composable
fun OsmMap(
    modifier: Modifier,
    workplace: GeoPoint?,
    radius: Int,
    workplaceName: String,
    user: Location?,
    zone: Zone,
    focus: GeoPoint?,
    focusToken: Int,
    interactive: Boolean = true,
    onTap: ((Double, Double) -> Unit)? = null,
    zoom: Double = 17.0
) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    val mapView = remember {
        MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            minZoomLevel = 3.0
            controller.setZoom(zoom)
        }
    }

    DisposableEffect(lifecycle, mapView) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose {
            lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onDetach()
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { mapView },
        update = { map ->
            map.setOnTouchListener { v, _ ->
                if (!interactive) {
                    true
                } else {
                    v.parent?.requestDisallowInterceptTouchEvent(true)
                    false
                }
            }

            map.overlays.clear()
            val color = zoneColor(zone)

            if (workplace != null) {
                val circle = Polygon(map).apply {
                    points = Polygon.pointsAsCircle(workplace, radius.toDouble())
                    fillPaint.color = color.copy(alpha = 0.18f).toArgb()
                    outlinePaint.color = color.toArgb()
                    outlinePaint.strokeWidth = 4f
                    setOnClickListener { _, _, _ -> true }
                }
                map.overlays.add(circle)

                val pin = Marker(map).apply {
                    position = workplace
                    title = workplaceName
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
                }
                map.overlays.add(pin)
            }

            if (user != null) {
                val dot = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(color.toArgb())
                    setStroke(5, AColor.WHITE)
                    setSize(46, 46)
                }
                val me = Marker(map).apply {
                    position = GeoPoint(user.latitude, user.longitude)
                    icon = dot
                    title = "You"
                    setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
                }
                map.overlays.add(me)
            }

            if (onTap != null) {
                map.overlays.add(
                    MapEventsOverlay(object : MapEventsReceiver {
                        override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                            onTap(p.latitude, p.longitude)
                            return true
                        }

                        override fun longPressHelper(p: GeoPoint): Boolean = false
                    })
                )
            }

            if (focus != null && map.tag != focusToken) {
                if (map.tag == null) map.controller.setCenter(focus) else map.controller.animateTo(focus)
                map.tag = focusToken
            }
            map.invalidate()
        }
    )
}
