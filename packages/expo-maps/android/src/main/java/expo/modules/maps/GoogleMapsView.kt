@file:OptIn(EitherType::class)

package expo.modules.maps

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import android.view.MotionEvent
import android.view.ViewGroup
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.LocationSource
import com.google.android.gms.maps.model.BitmapDescriptor
import com.google.android.gms.maps.model.BitmapDescriptorFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.CameraMoveStartedReason
import com.google.maps.android.compose.CameraPositionState
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.Marker
import com.google.maps.android.compose.MarkerState
import com.google.maps.android.compose.Polygon
import com.google.maps.android.compose.Circle
import com.google.maps.android.compose.Polyline
import expo.modules.kotlin.AppContext
import expo.modules.kotlin.apifeatures.EitherType
import expo.modules.kotlin.sharedobjects.SharedRef
import expo.modules.kotlin.types.toKClass
import expo.modules.kotlin.viewevent.EventDispatcher
import expo.modules.kotlin.viewevent.ViewEventCallback
import expo.modules.kotlin.views.ComposeProps
import expo.modules.kotlin.views.ExpoComposeView
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.ui.unit.dp
import com.google.android.gms.maps.GoogleMapOptions
import expo.modules.kotlin.views.ComposableScope

data class GoogleMapsViewProps(
  val userLocation: MutableState<UserLocationRecord> = mutableStateOf(UserLocationRecord()),
  val cameraPosition: MutableState<CameraPositionRecord> = mutableStateOf(CameraPositionRecord()),
  val markers: MutableState<List<MarkerRecord>> = mutableStateOf(listOf()),
  val polylines: MutableState<List<PolylineRecord>> = mutableStateOf(listOf()),
  val polygons: MutableState<List<PolygonRecord>> = mutableStateOf(listOf()),
  val circles: MutableState<List<CircleRecord>> = mutableStateOf(listOf()),
  val uiSettings: MutableState<MapUiSettingsRecord> = mutableStateOf(MapUiSettingsRecord()),
  val properties: MutableState<MapPropertiesRecord> = mutableStateOf(MapPropertiesRecord()),
  val colorScheme: MutableState<MapColorSchemeEnum> = mutableStateOf(MapColorSchemeEnum.FOLLOW_SYSTEM),
  val contentPadding: MutableState<MapContentPaddingRecord> = mutableStateOf(MapContentPaddingRecord()),
  val mapOptions: MutableState<MapOptionsRecord> = mutableStateOf(MapOptionsRecord())
) : ComposeProps

@SuppressLint("ViewConstructor")
class GoogleMapsView(context: Context, appContext: AppContext) :
  ExpoComposeView<GoogleMapsViewProps>(context, appContext, withHostingView = true) {
  override val props = GoogleMapsViewProps()

  private val onMapLoaded by EventDispatcher<Unit>()
  
  // Track gesture state to manage parent scroll blocking
  private var isMapInteracting = false

  init {
    // More aggressive touch handling to prevent parent scroll interference
    isClickable = true
    isFocusable = true
  }

  override fun onInterceptTouchEvent(ev: MotionEvent?): Boolean {
    // Block parent from intercepting any touch events on the map
    ev?.let { event ->
      when (event.action and MotionEvent.ACTION_MASK) {
        MotionEvent.ACTION_DOWN -> {
          // Aggressively block parent interception
          requestDisallowInterceptTouchEvent(true)
          findScrollableParent()?.requestDisallowInterceptTouchEvent(true)
          isMapInteracting = true
        }
        MotionEvent.ACTION_POINTER_DOWN -> {
          // Multi-touch (pinch) - definitely block parent
          requestDisallowInterceptTouchEvent(true)
          findScrollableParent()?.requestDisallowInterceptTouchEvent(true)
          isMapInteracting = true
        }
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
          // Re-enable parent scrolling
          requestDisallowInterceptTouchEvent(false)
          findScrollableParent()?.requestDisallowInterceptTouchEvent(false)
          isMapInteracting = false
        }
      }
    }
    return false // Don't intercept, let map handle it
  }

  override fun onTouchEvent(event: MotionEvent?): Boolean {
    // Consume touch events and ensure parent doesn't interfere
    event?.let { ev ->
      when (ev.action and MotionEvent.ACTION_MASK) {
        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
          // Block all parent views from intercepting
          var currentParent = parent
          while (currentParent != null) {
            currentParent.requestDisallowInterceptTouchEvent(true)
            currentParent = currentParent.parent
          }
        }
        MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
          // Re-enable parent interception
          var currentParent = parent
          while (currentParent != null) {
            currentParent.requestDisallowInterceptTouchEvent(false)
            currentParent = currentParent.parent
          }
        }
      }
    }
    
    return super.onTouchEvent(event)
  }

  private fun findScrollableParent(): android.view.ViewGroup? {
    var currentParent = parent
    while (currentParent != null) {
      if (currentParent.javaClass.name.contains("ScrollView") || 
          currentParent.javaClass.name.contains("Scroll")) {
        return currentParent as? android.view.ViewGroup
      }
      currentParent = currentParent.parent
    }
    return null
  }

  override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
    // Intercept at the dispatch level to prevent parent scrollview interference
    ev?.let { event ->
      when (event.action and MotionEvent.ACTION_MASK) {
        MotionEvent.ACTION_DOWN -> {
          // Block parent interception immediately at dispatch level
          var p = parent
          while (p != null) {
            p.requestDisallowInterceptTouchEvent(true)
            p = p.parent
          }
        }
        MotionEvent.ACTION_POINTER_DOWN -> {
          // Pinch detected - aggressive blocking
          var p = parent
          while (p != null) {
            p.requestDisallowInterceptTouchEvent(true)
            p = p.parent
          }
        }
      }
    }
    return super.dispatchTouchEvent(ev)
  }

  private val onMapClick by EventDispatcher<MapClickEvent>()
  private val onMapLongClick by EventDispatcher<MapClickEvent>()
  private val onPOIClick by EventDispatcher<POIRecord>()
  private val onMarkerClick by EventDispatcher<MarkerRecord>()
  private val onPolylineClick by EventDispatcher<PolylineRecord>()
  private val onPolygonClick by EventDispatcher<PolygonRecord>()
  private val onCircleClick by EventDispatcher<CircleRecord>()

  private val onCameraMove by EventDispatcher<CameraMoveEvent>()

  private var wasLoaded = mutableStateOf(false)

  private lateinit var cameraState: CameraPositionState
  private var manualCameraControl = false
  private var selectedMarkerId = mutableStateOf<String?>(null)

  @Composable
  override fun ComposableScope.Content() {
    cameraState = updateCameraState()
    val markerState = markerStateFromProps()
    val locationSource = locationSourceFromProps()
    val polylineState by polylineStateFromProps()
    val polygonState by polygonStateFromProps()
    val circleState by circleStateFromProps()
    val mapOptions = props.mapOptions.value.mapId?.let { GoogleMapOptions().mapId(it) } ?: GoogleMapOptions()

    GoogleMap(
      googleMapOptionsFactory = { mapOptions },
      modifier = Modifier.fillMaxSize(),
      cameraPositionState = cameraState,
      uiSettings = props.uiSettings.value.toMapUiSettings(),
      properties = props.properties.value.toMapProperties(),
      contentPadding = props.contentPadding.value.let {
        PaddingValues(start = it.start.dp, end = it.end.dp, top = it.top.dp, bottom = it.bottom.dp)
      },
      onMapLoaded = {
        onMapLoaded(Unit)
        wasLoaded.value = true
      },
      onMapClick = { latLng ->
        // Clear marker selection when tapping on the map background
        selectedMarkerId.value = null
        onMapClick(
          MapClickEvent(
            Coordinates(latLng.latitude, latLng.longitude)
          )
        )
      },
      onMapLongClick = { latLng ->
        onMapLongClick(
          MapClickEvent(
            Coordinates(latLng.latitude, latLng.longitude)
          )
        )
      },
      onPOIClick = { poi ->
        onPOIClick(
          POIRecord(
            poi.name,
            Coordinates(poi.latLng.latitude, poi.latLng.longitude)
          )
        )
      },
      onMyLocationButtonClick = props.userLocation.value.coordinates?.let { coordinates ->
        {
          // Override onMyLocationButtonClick with default behavior to update manualCameraControl
          appContext.mainQueue.launch {
            cameraState.animate(CameraUpdateFactory.newLatLng(coordinates.toLatLng()))
            manualCameraControl = false
          }
          true
        }
      },
      mapColorScheme = props.colorScheme.value.toComposeMapColorScheme(),
      locationSource = locationSource
    ) {
      polylineState.forEach { (polyline, coordinates) ->
        Polyline(
          points = coordinates,
          color = Color(polyline.color),
          geodesic = polyline.geodesic,
          width = polyline.width,
          clickable = true,
          onClick = {
            onPolylineClick(
              PolylineRecord(
                id = polyline.id,
                coordinates.map { Coordinates(it.latitude, it.longitude) },
                polyline.geodesic,
                polyline.color,
                polyline.width
              )
            )
          }
        )
      }

      MapPolygons(
        polygonState = polygonState,
        onPolygonClick = onPolygonClick
      )

      MapCircles(
        circleState = circleState,
        onCircleClick = onCircleClick
      )

      for ((marker, state) in markerState.value) {
        val isSelected = selectedMarkerId.value == marker.id
        val icon = getIconDescriptor(marker, isSelected)

        Marker(
          state = state,
          title = marker.title,
          snippet = marker.snippet,
          draggable = marker.draggable,
          anchor = marker.anchor.toOffset(),
          zIndex = if (isSelected) marker.zIndex + 1f else marker.zIndex,
          icon = icon,
          onClick = {
            // Update selected marker visual state
            selectedMarkerId.value = marker.id
            onMarkerClick(
              // We can't send icon to js, because it's not serializable
              // So we need to remove it from the marker record
              MarkerRecord(
                id = marker.id,
                title = marker.title,
                snippet = marker.snippet,
                coordinates = marker.coordinates
              )
            )
            !marker.showCallout
          }
        )
      }
    }
  }

  @Composable
  private fun updateCameraState(): CameraPositionState {
    val cameraPosition = props.cameraPosition.value
    cameraState = remember(cameraPosition) {
      CameraPositionState(
        position = CameraPosition.fromLatLngZoom(
          cameraPosition.coordinates.toLatLng(),
          cameraPosition.zoom
        )
      )
    }

    LaunchedEffect(cameraState.cameraMoveStartedReason) {
      // We should stop following the user's location when camera is moved manually.
      if (cameraState.cameraMoveStartedReason == CameraMoveStartedReason.GESTURE || cameraState.cameraMoveStartedReason == CameraMoveStartedReason.API_ANIMATION) {
        manualCameraControl = true
      }
    }

    LaunchedEffect(cameraState.isMoving) {
      // We don't want to send the event when the map is not loaded yet
      if (!wasLoaded.value) {
        return@LaunchedEffect
      }

      if (!cameraState.isMoving && cameraState.cameraMoveStartedReason == CameraMoveStartedReason.GESTURE || 
          cameraState.cameraMoveStartedReason == CameraMoveStartedReason.DEVELOPER_ANIMATION) {

        if (cameraState.cameraMoveStartedReason == CameraMoveStartedReason.GESTURE || 
            cameraState.cameraMoveStartedReason == CameraMoveStartedReason.DEVELOPER_ANIMATION) {
          val position = cameraState.position
          onCameraMove(
            CameraMoveEvent(
              Coordinates(position.target.latitude, position.target.longitude),
              position.zoom,
              position.tilt,
              position.bearing
            )
          )
        }
      }
    }
    return cameraState
  }

  @Composable
  private fun locationSourceFromProps(): LocationSource? {
    val coordinates = props.userLocation.value.coordinates
    val followUserLocation = props.userLocation.value.followUserLocation

    val locationSource = remember(coordinates) {
      CustomLocationSource()
    }
    LaunchedEffect(coordinates) {
      if (coordinates == null) {
        return@LaunchedEffect
      }
      locationSource.onLocationChanged(coordinates.toLocation())
      if (followUserLocation && !manualCameraControl) {
        // Update camera position when location changes and manualCameraControl is disabled.
        cameraState.animate(CameraUpdateFactory.newLatLng(coordinates.toLatLng()))
      }
    }
    return coordinates?.let {
      locationSource.apply {
        onLocationChanged(coordinates.toLocation())
      }
    }
  }

  @Composable
  private fun markerStateFromProps() =
    remember {
      derivedStateOf {
        props.markers.value.map { marker ->
          marker to MarkerState(position = marker.coordinates.toLatLng())
        }
      }
    }

  @Composable
  private fun circleStateFromProps() =
    remember {
      derivedStateOf {
        props.circles.value.map { circle ->
          circle to circle.center.toLatLng()
        }
      }
    }

  @Composable
  private fun polylineStateFromProps() =
    remember {
      derivedStateOf {
        props.polylines.value.map { polyline ->
          polyline to polyline.coordinates.map { it.toLatLng() }
        }
      }
    }

  @Composable
  private fun polygonStateFromProps() =
    remember {
      derivedStateOf {
        props.polygons.value.map { polygon ->
          polygon to polygon.coordinates.map { it.toLatLng() }
        }
      }
    }

  @Composable
  private fun MapPolygons(
    polygonState: List<Pair<PolygonRecord, List<LatLng>>>,
    onPolygonClick: ViewEventCallback<PolygonRecord>
  ) {
    polygonState.forEach { (polygon, coordinates) ->
      Polygon(
        points = coordinates,
        fillColor = Color(polygon.color),
        strokeColor = Color(polygon.lineColor),
        strokeWidth = polygon.lineWidth,
        clickable = true,
        onClick = {
          onPolygonClick(
            PolygonRecord(
              id = polygon.id,
              coordinates.map { Coordinates(it.latitude, it.longitude) },
              color = polygon.color,
              lineColor = polygon.lineColor,
              lineWidth = polygon.lineWidth
            )
          )
        }
      )
    }
  }

  suspend fun setCameraPosition(config: SetCameraPositionConfig?) {
    // Stop updating the camera position based on user location.
    manualCameraControl = true
    // If no coordinates are provided, the camera will be centered on the user's location.
    val coordinates: LatLng = config?.coordinates?.toLatLng()
      ?: props.userLocation.value.coordinates?.toLatLng()
      ?: return

    val cameraUpdate = config?.zoom?.let { CameraUpdateFactory.newLatLngZoom(coordinates, it) }
      ?: CameraUpdateFactory.newLatLng(coordinates)

    // When Int.MAX_VALUE is provided as durationMs, the default animation duration will be used.
    cameraState.animate(cameraUpdate, config?.duration ?: Int.MAX_VALUE)

    // If centering on the user's location, stop manual camera control.
    if (config?.coordinates == null) {
      manualCameraControl = false
    }
  }

  private fun getIconDescriptor(
    marker: MarkerRecord,
    isSelected: Boolean = false
  ): BitmapDescriptor? {
    // Prefer custom text-based icon when `text` is provided and no explicit icon is set
    if (marker.text != null && marker.icon == null) {
      val bitmap = createTextMarkerBitmap(marker, isSelected)
      return bitmap?.let { BitmapDescriptorFactory.fromBitmap(it) }
    }

    return marker.icon?.let { icon ->
      val baseBitmap = if (icon.`is`(toKClass<SharedRef<Drawable>>())) {
        (icon.get(toKClass<SharedRef<Drawable>>()).ref as? BitmapDrawable)?.bitmap
      } else {
        icon.get(toKClass<SharedRef<Bitmap>>()).ref
      }
      baseBitmap?.let { BitmapDescriptorFactory.fromBitmap(it) }
    }
  }

  private fun createTextMarkerBitmap(marker: MarkerRecord, isSelected: Boolean): Bitmap? {
    val text = marker.text ?: return null

    val density = context.resources.displayMetrics.density
    fun dp(value: Float) = (value * density)

    val horizontalPadding = dp(14f)
    val verticalPadding = dp(10f)
    val cornerRadius = dp(18f)
    val borderWidth = dp(1f)

    // Paints
    val selectedBg = 0xFF277DA0.toInt()
    val selectedText = 0xFFFFFFFF.toInt()
    val selectedBorder = selectedBg

    val normalBg = marker.backgroundColor ?: 0xFFFFFFFF.toInt()
    val normalText = marker.textColor ?: 0xFF000000.toInt()
    val normalBorder = 0xFFD1D1D1.toInt()

    val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = if (isSelected) selectedText else normalText
      textSize = dp(14f)
      typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = if (isSelected) selectedBg else normalBg
      style = Paint.Style.FILL
    }
    val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
      color = if (isSelected) selectedBorder else normalBorder
      style = Paint.Style.STROKE
      strokeWidth = borderWidth
    }

    // Measure text
    val bounds = Rect()
    textPaint.getTextBounds(text, 0, text.length, bounds)
    val textWidth = bounds.width().toFloat()
    val textHeight = bounds.height().toFloat()

    // Bitmap size
    val width = (textWidth + horizontalPadding * 2f + borderWidth * 2f).toInt()
    val height = (textHeight + verticalPadding * 2f + borderWidth * 2f).toInt()

    val bitmap = Bitmap.createBitmap(maxOf(1, width), maxOf(1, height), Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)

    // Draw rounded rect background
    val rect = RectF(
      borderWidth / 2f,
      borderWidth / 2f,
      bitmap.width - borderWidth / 2f,
      bitmap.height - borderWidth / 2f
    )
    canvas.drawRoundRect(rect, cornerRadius, cornerRadius, bgPaint)
    canvas.drawRoundRect(rect, cornerRadius, cornerRadius, borderPaint)

    // Draw text centered vertically, left with padding
    val x = horizontalPadding
    val y = (bitmap.height / 2f) + (textHeight / 2f) - bounds.bottom
    canvas.drawText(text, x, y, textPaint)

    return bitmap
  }
}

@Composable
private fun MapCircles(
  circleState: List<Pair<CircleRecord, LatLng>>,
  onCircleClick: ViewEventCallback<CircleRecord>
) {
  circleState.forEach { (circle, center) ->
    Circle(
      center = center,
      radius = circle.radius,
      fillColor = Color(circle.color),
      strokeColor = circle.lineColor?.let { Color(it) } ?: Color.Transparent,
      strokeWidth = circle.lineWidth ?: 0f,
      clickable = true,
      onClick = {
        onCircleClick(
          CircleRecord(
            id = circle.id,
            center = Coordinates(center.latitude, center.longitude),
            radius = circle.radius,
            color = circle.color,
            lineColor = circle.lineColor,
            lineWidth = circle.lineWidth
          )
        )
      }
    )
  }
}
