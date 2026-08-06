package dev.heyari.ari.ui.settings.pages

import android.Manifest
import android.location.Location
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.google.android.gms.location.LocationServices
import dev.heyari.ari.R
import dev.heyari.ari.listening.ListeningPlace
import dev.heyari.ari.ui.settings.SettingsViewModel
import dev.heyari.ari.ui.settings.components.SettingsScaffold
import org.ramani.compose.CameraPosition
import org.ramani.compose.CenterState
import org.ramani.compose.Circle
import org.ramani.compose.MapLibre
import org.ramani.compose.MapStyle
import org.ramani.compose.rememberCameraPositionState
import java.util.UUID
import kotlin.math.cos

/**
 * Style URL kept as one constant, not hard-coded inline, per the design spec's
 * caution about OpenFreeMap: donation-funded, no SLA. Swapping providers later
 * (VersaTiles is the fallback) is then a one-line change.
 */
private const val MAP_STYLE_URL = "https://tiles.openfreemap.org/styles/liberty"

@Composable
fun ListeningPlacesPage(
    onBack: () -> Unit,
    onOpenEditor: (String?) -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshPermissions()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val fineLocationLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { viewModel.refreshPermissions() }

    SettingsScaffold(
        title = stringResource(R.string.settings_listening_places_title),
        onBack = onBack,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.settings_listening_places_blurb),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            if (!state.geofencingAvailable) {
                NoticeCard(text = stringResource(R.string.settings_listening_places_no_play_services))
            } else if (!state.hasFineLocation) {
                PermissionCard(
                    body = stringResource(R.string.settings_listening_places_need_fine_location),
                    buttonLabel = stringResource(R.string.action_grant),
                    onClick = { fineLocationLauncher.launch(Manifest.permission.ACCESS_FINE_LOCATION) },
                )
            } else if (!state.hasBackgroundLocation) {
                PermissionCard(
                    body = stringResource(
                        R.string.settings_listening_places_need_background_location,
                        viewModel.backgroundLocationOptionLabel(),
                    ),
                    buttonLabel = stringResource(R.string.action_open_settings),
                    onClick = { viewModel.openAppSettings() },
                )
            }

            val canAdd = state.geofencingAvailable && state.hasFineLocation && state.hasBackgroundLocation

            if (canAdd) {
                if (state.listeningPlaces.isEmpty()) {
                    Text(
                        text = stringResource(R.string.settings_listening_places_empty),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                state.listeningPlaces.forEach { place ->
                    PlaceRow(
                        place = place,
                        onEdit = { onOpenEditor(place.id) },
                        onDelete = { viewModel.deleteListeningPlace(place.id) },
                    )
                }

                Button(
                    onClick = { onOpenEditor(null) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.settings_listening_place_add))
                }
            }
        }
    }
}

@Composable
private fun PlaceRow(place: ListeningPlace, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
        onClick = onEdit,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(text = place.name, style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.settings_listening_place_radius, place.radiusMetres.toInt()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.action_delete))
            }
        }
    }
}

@Composable
private fun PermissionCard(body: String, buttonLabel: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer,
            )
            Spacer(Modifier.height(8.dp))
            TextButton(onClick = onClick) { Text(buttonLabel) }
        }
    }
}

/**
 * Full screen rather than a dialog: a name field, a hint, a map, and a radius
 * slider don't fit a dialog's fixed-height content area — the map in
 * particular needs real room to be usable for actually placing a pin. A
 * dedicated destination lets the map take most of the screen via `weight(1f)`.
 */
@Composable
fun PlaceEditorScreen(
    placeId: String?,
    onBack: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val initial = remember(placeId, state.listeningPlaces) {
        state.listeningPlaces.firstOrNull { it.id == placeId }
    }
    val context = LocalContext.current

    var name by remember(initial) { mutableStateOf(initial?.name ?: "") }
    var radius by remember(initial) {
        mutableFloatStateOf(initial?.radiusMetres ?: ListeningPlace.DEFAULT_RADIUS_METRES)
    }

    val startTarget = remember(initial) {
        org.ramani.compose.LatLng(
            initial?.latitude ?: DEFAULT_LATITUDE,
            initial?.longitude ?: DEFAULT_LONGITUDE,
        )
    }
    val cameraState = rememberCameraPositionState(
        CameraPosition(target = startTarget, zoom = if (initial != null) 15.0 else 12.0)
    )
    val centerState = remember(initial) { CenterState(startTarget) }

    // No last-known fix if editing an existing place — moving the pin to "here"
    // on open would silently relocate a place the user already placed on
    // purpose.
    if (initial == null) {
        DisposableEffect(Unit) {
            val hasFine = ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (hasFine) {
                @Suppress("MissingPermission")
                LocationServices.getFusedLocationProviderClient(context).lastLocation
                    .addOnSuccessListener { loc: Location? ->
                        if (loc != null) {
                            val here = org.ramani.compose.LatLng(loc.latitude, loc.longitude)
                            centerState.center = here
                            cameraState.position = CameraPosition(target = here, zoom = 15.0)
                        }
                    }
            }
            onDispose {}
        }
    }

    SettingsScaffold(
        title = stringResource(
            if (placeId == null) R.string.settings_listening_place_add
            else R.string.settings_listening_place_edit
        ),
        onBack = onBack,
        actions = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    viewModel.saveListeningPlace(
                        ListeningPlace(
                            id = placeId ?: UUID.randomUUID().toString(),
                            name = name.trim(),
                            latitude = centerState.center.latitude,
                            longitude = centerState.center.longitude,
                            radiusMetres = radius,
                        )
                    )
                    onBack()
                },
            ) { Text(stringResource(R.string.action_save)) }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.settings_listening_place_name_label)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text(
                    text = stringResource(R.string.settings_listening_place_map_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            MapLibre(
                modifier = Modifier.fillMaxWidth().weight(1f),
                style = MapStyle.Uri(MAP_STYLE_URL),
                cameraPositionState = cameraState,
                onMapClick = { latLng ->
                    centerState.center = latLng
                },
            ) {
                // Radius circle: honestly represents the geofence's ground
                // area (see metresToPixelRadius), but a translucent fill that
                // may span most of the screen at a close zoom reads poorly as
                // "here is the exact centre" — the dot below is that anchor.
                Circle(
                    centerState = centerState,
                    radius = metresToPixelRadius(
                        radiusMetres = radius,
                        latitude = centerState.center.latitude,
                        zoom = cameraState.position.zoom ?: 15.0,
                    ),
                    isDraggable = true,
                    color = "#3B82F6",
                    opacity = 0.35f,
                    borderColor = "#3B82F6",
                    borderWidth = 2f,
                    onCenterDragged = { centerState.center = it },
                )
                // The centre dot. Fixed pixel size regardless of zoom or
                // radius — its job is to mark the exact point, not the area —
                // drawn after the translucent circle so it sits visibly on
                // top of it rather than blending in.
                Circle(
                    centerState = centerState,
                    radius = CENTER_DOT_RADIUS_PX,
                    isDraggable = true,
                    color = "#3B82F6",
                    opacity = 1f,
                    borderColor = "#FFFFFF",
                    borderWidth = 2f,
                    onCenterDragged = { centerState.center = it },
                )
            }

            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text(
                    text = stringResource(R.string.settings_listening_place_radius, radius.toInt()),
                    style = MaterialTheme.typography.labelLarge,
                )
                Slider(
                    value = radius,
                    onValueChange = { radius = it },
                    valueRange = ListeningPlace.MIN_RADIUS_METRES..ListeningPlace.MAX_RADIUS_METRES,
                )
            }
        }
    }
}

/**
 * Screen pixels a circle of [radiusMetres] should be drawn at, at [zoom] and
 * [latitude], on the standard 512px-tile Web Mercator projection MapLibre uses.
 * Web Mercator distorts by latitude (`cos(lat)`), which is why this can't be a
 * single constant-per-zoom lookup.
 */
private fun metresToPixelRadius(radiusMetres: Float, latitude: Double, zoom: Double): Float {
    val metresPerPixel = EARTH_CIRCUMFERENCE_METRES * cos(Math.toRadians(latitude)) /
        (TILE_SIZE_PX * Math.pow(2.0, zoom))
    return (radiusMetres / metresPerPixel).toFloat()
}

private const val EARTH_CIRCUMFERENCE_METRES = 40_075_016.686
private const val TILE_SIZE_PX = 512.0
private const val CENTER_DOT_RADIUS_PX = 6f

// A harmless default centre for a first-ever place when no location fix is
// available yet (permission not granted, or no fix cached).
private const val DEFAULT_LATITUDE = 35.8997
private const val DEFAULT_LONGITUDE = 14.5147
