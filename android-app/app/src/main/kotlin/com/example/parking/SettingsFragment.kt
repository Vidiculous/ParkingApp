package com.example.parking

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.example.parking.Prefs.backendUrl
import com.example.parking.Prefs.dongleId
import com.example.parking.Prefs.dongleMinor
import com.example.parking.Prefs.geoLat
import com.example.parking.Prefs.geoLng
import com.example.parking.Prefs.geoRadius
import com.example.parking.Prefs.userName
import com.example.parking.databinding.FragmentSettingsBinding
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    private var geofenceCentre: GeoPoint? = null
    private var markerOverlay: Marker? = null
    private var circleOverlay: Polygon? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // Populate fields from prefs
        val ctx = requireContext()
        binding.editName.setText(ctx.userName)
        binding.editDongleId.setText(ctx.dongleId)
        binding.editBackendUrl.setText(ctx.backendUrl)
        binding.sliderMinor.value = ctx.dongleMinor.toFloat()
        binding.sliderRadius.value = ctx.geoRadius.toFloat().coerceIn(20f, 500f)
        updateRadiusLabel()

        // Map setup
        val map = binding.mapView
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)

        val savedLat = ctx.geoLat
        val savedLng = ctx.geoLng
        if (!savedLat.isNaN() && !savedLng.isNaN()) {
            geofenceCentre = GeoPoint(savedLat, savedLng)
            map.controller.setZoom(17.0)
            map.controller.setCenter(geofenceCentre)
            refreshMapOverlays()
        } else {
            map.controller.setZoom(14.0)
            map.controller.setCenter(GeoPoint(51.5, -0.12))
        }

        // Tap on map to set geofence centre
        map.overlays.add(MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                geofenceCentre = p
                refreshMapOverlays()
                return true
            }
            override fun longPressHelper(p: GeoPoint) = false
        }))

        // Slider listeners
        binding.sliderMinor.addOnChangeListener { _, _, _ ->
            binding.labelMinor.text = "Dongle minor: ${binding.sliderMinor.value.toInt()}"
        }
        binding.sliderRadius.addOnChangeListener { _, _, _ ->
            updateRadiusLabel()
            refreshMapOverlays()
        }

        binding.btnMyLocation.setOnClickListener { fetchMyLocation() }
        binding.btnSave.setOnClickListener { saveSettings() }
    }

    override fun onResume() {
        super.onResume()
        binding.mapView.onResume()
    }

    override fun onPause() {
        binding.mapView.onPause()
        super.onPause()
    }

    override fun onDestroyView() {
        binding.mapView.onDetach()
        _binding = null
        super.onDestroyView()
    }

    private fun updateRadiusLabel() {
        val r = binding.sliderRadius.value.toInt()
        binding.labelRadius.text = "Geofence radius: ${r}m"
    }

    private fun refreshMapOverlays() {
        val map = binding.mapView
        val centre = geofenceCentre ?: return
        val radius = binding.sliderRadius.value.toDouble()

        // Remove old overlays
        circleOverlay?.let { map.overlays.remove(it) }
        markerOverlay?.let { map.overlays.remove(it) }

        // Draw circle
        circleOverlay = Polygon().apply {
            points = Polygon.pointsAsCircle(centre, radius)
            fillPaint.color = Color.argb(40, 0, 120, 255)
            outlinePaint.color = Color.argb(200, 0, 120, 255)
            outlinePaint.strokeWidth = 3f
        }
        map.overlays.add(circleOverlay)

        // Draw centre marker
        markerOverlay = Marker(map).apply {
            position = centre
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            title = "Parking lot"
        }
        map.overlays.add(markerOverlay)
        map.invalidate()
    }

    private fun fetchMyLocation() {
        if (ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.ACCESS_FINE_LOCATION
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            Toast.makeText(requireContext(), "Location permission required", Toast.LENGTH_SHORT).show()
            return
        }

        lifecycleScope.launch {
            try {
                val loc = LocationServices.getFusedLocationProviderClient(requireContext())
                    .lastLocation.await()
                if (loc != null) {
                    launch(Dispatchers.Main) {
                        geofenceCentre = GeoPoint(loc.latitude, loc.longitude)
                        binding.mapView.controller.animateTo(geofenceCentre)
                        refreshMapOverlays()
                    }
                } else {
                    launch(Dispatchers.Main) {
                        Toast.makeText(requireContext(), "Location not available yet", Toast.LENGTH_SHORT).show()
                    }
                }
            } catch (e: Exception) {
                launch(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Failed to get location: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun saveSettings() {
        val ctx = requireContext()
        ctx.userName = binding.editName.text.toString().trim()
        ctx.dongleId = binding.editDongleId.text.toString().trim()
        ctx.dongleMinor = binding.sliderMinor.value.toInt()
        ctx.backendUrl = binding.editBackendUrl.text.toString().trim()
        ctx.geoRadius = binding.sliderRadius.value.toDouble()

        val centre = geofenceCentre
        if (centre != null) {
            ctx.geoLat = centre.latitude
            ctx.geoLng = centre.longitude
        }

        // Re-register geofence with new coordinates
        GeofenceManager.register(ctx)

        Toast.makeText(ctx, "Settings saved", Toast.LENGTH_SHORT).show()
    }
}
