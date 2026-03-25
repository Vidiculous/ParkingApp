package com.example.parking

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DividerItemDecoration
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.example.parking.Prefs.backendUrl
import com.example.parking.Prefs.debugBeaconNearby
import com.example.parking.Prefs.debugInsideGeofence
import com.example.parking.Prefs.dongleMinor
import com.example.parking.Prefs.geoLat
import com.example.parking.Prefs.geoLng
import com.example.parking.Prefs.geoRadius
import com.example.parking.Prefs.hasGeofence
import com.example.parking.Prefs.userName
import com.example.parking.databinding.FragmentStatusBinding
import com.example.parking.databinding.ItemParkingBinding
import com.google.android.gms.location.LocationServices
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import okhttp3.WebSocket
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polygon
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.*

class StatusFragment : Fragment() {

    private var _binding: FragmentStatusBinding? = null
    private val binding get() = _binding!!

    private val adapter = ParkingAdapter()
    private var ws: WebSocket? = null
    private var currentOccupancy: Occupancy? = null
    private var currentMyEntry: ParkingEntry? = null

    // Debug map overlays
    private var debugUserMarker: Marker? = null
    private var debugGeofenceCircle: Polygon? = null

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentStatusBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerView.addItemDecoration(DividerItemDecoration(requireContext(), DividerItemDecoration.VERTICAL))
        binding.recyclerView.adapter = adapter

        binding.btnToggle.setOnClickListener { onToggleClick() }
        binding.btnRefresh.setOnClickListener { reconnect() }

        setupDebugMap()
        reconnect()
        startDebugPolling()
    }

    override fun onResume() {
        super.onResume()
        binding.debugMap.onResume()
    }

    override fun onPause() {
        binding.debugMap.onPause()
        super.onPause()
    }

    override fun onDestroyView() {
        ws?.close(1000, "Fragment destroyed")
        ws = null
        binding.debugMap.onDetach()
        _binding = null
        super.onDestroyView()
    }

    // ── WebSocket ──────────────────────────────────────────────────────────

    private fun reconnect() {
        ws?.close(1000, "Reconnecting")
        ws = ParkingRepository.connectWebSocket(
            context = requireContext(),
            onUpdate = { state ->
                activity?.runOnUiThread { updateUi(state) }
            },
            onConnectionChange = { connected ->
                activity?.runOnUiThread { updateConnectionDot(connected) }
            }
        )
    }

    private fun updateUi(state: ParkingState) {
        currentOccupancy = state.occupancy
        val myName = requireContext().userName
        currentMyEntry = state.users.find { it.name == myName }

        val occ = state.occupancy
        val pct = if (occ.total > 0) (occ.parked * 100 / occ.total) else 0
        binding.occupancyBar.progress = pct
        binding.occupancyText.text = if (occ.full)
            "Parking lot full (${occ.parked} / ${occ.total})"
        else
            "${occ.parked} / ${occ.total} spots occupied"

        val bannerColor = if (occ.full) Color.parseColor("#FFF1F2") else Color.parseColor("#F0FDF4")
        val textColor   = if (occ.full) Color.parseColor("#BE123C") else Color.parseColor("#15803D")
        binding.occupancyCard.setCardBackgroundColor(bannerColor)
        binding.occupancyText.setTextColor(textColor)

        val myEntry = currentMyEntry
        val isParked = myEntry?.status == "parked"
        if (myName.isNotEmpty()) {
            binding.myStatusCard.visibility = View.VISIBLE
            binding.myStatusText.text = if (isParked) "You are parked" else "You are away"
            binding.myStatusText.setTextColor(if (isParked) Color.parseColor("#15803D") else Color.parseColor("#6B7280"))
            binding.btnToggle.text = if (isParked) "Mark Away" else "Mark Parked"
            binding.btnToggle.isEnabled = myEntry?.status != "unknown"
        } else {
            binding.myStatusCard.visibility = View.GONE
        }

        adapter.submitList(state.users)
    }

    private fun updateConnectionDot(connected: Boolean) {
        binding.connectionDot.setBackgroundResource(
            if (connected) R.drawable.dot_green else R.drawable.dot_grey
        )
    }

    // ── Manual toggle ──────────────────────────────────────────────────────

    private fun onToggleClick() {
        val currentStatus = currentMyEntry?.status ?: "unknown"
        val nextStatus = if (currentStatus == "parked") "left" else "parked"

        if (nextStatus == "parked" && currentOccupancy?.full == true) {
            AlertDialog.Builder(requireContext())
                .setTitle("Lot is full")
                .setMessage("All ${currentOccupancy?.total ?: 7} spots are taken. Park anyway?")
                .setPositiveButton("Park anyway") { _, _ -> postStatus(nextStatus) }
                .setNegativeButton("Cancel", null)
                .show()
        } else {
            postStatus(nextStatus)
        }
    }

    private fun postStatus(status: String) {
        lifecycleScope.launch {
            val result = ParkingRepository.postStatus(requireContext(), status, manual = true)
            launch(Dispatchers.Main) {
                when (result) {
                    is PostResult.Ok -> {}
                    is PostResult.OkWithWarning -> Toast.makeText(
                        requireContext(), "Lot is full — status recorded anyway", Toast.LENGTH_SHORT
                    ).show()
                    is PostResult.Error -> Toast.makeText(
                        requireContext(), "Error: ${result.message}", Toast.LENGTH_SHORT
                    ).show()
                }
            }
        }
    }

    // ── Debug panel ────────────────────────────────────────────────────────

    private fun setupDebugMap() {
        val map = binding.debugMap
        map.setTileSource(TileSourceFactory.MAPNIK)
        map.setMultiTouchControls(true)
        map.controller.setZoom(17.0)

        val ctx = requireContext()
        if (ctx.hasGeofence()) {
            val centre = GeoPoint(ctx.geoLat, ctx.geoLng)
            map.controller.setCenter(centre)
            drawGeofenceCircle(ctx.geoLat, ctx.geoLng, ctx.geoRadius)
        }
    }

    private fun startDebugPolling() {
        // GPS every 10s
        viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                pollDebugGps()
                delay(10_000)
            }
        }
        // BLE every 30s
        viewLifecycleOwner.lifecycleScope.launch {
            while (true) {
                pollDebugBle()
                delay(30_000)
            }
        }
        // Geofence state is derived from GPS distance — updated alongside GPS poll
    }

    private suspend fun pollDebugGps() {
        val ctx = context ?: return
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) return

        try {
            val loc = LocationServices.getFusedLocationProviderClient(ctx)
                .lastLocation.await() ?: return

            val geoLat = ctx.geoLat
            val geoLng = ctx.geoLng
            val distM = if (!geoLat.isNaN() && !geoLng.isNaN())
                haversineMetres(loc.latitude, loc.longitude, geoLat, geoLng)
            else null

            val inside = distM != null && distM < ctx.geoRadius
            val b = _binding ?: return
            activity?.runOnUiThread {
                b.debugGps.text = "%.5f, %.5f".format(loc.latitude, loc.longitude)
                b.debugDistance.text = if (distM != null)
                    "%.0f m  (r=%.0f m)".format(distM, ctx.geoRadius)
                else "—"
                b.debugGeofence.text = if (inside) "✓ inside" else "✗ outside"
                b.debugGeofence.setTextColor(
                    if (inside) Color.parseColor("#69DB7C") else Color.parseColor("#FF6B6B")
                )
                updateDebugUserMarker(loc.latitude, loc.longitude, inside)
            }
        } catch (_: Exception) {}
    }

    private suspend fun pollDebugBle() {
        val ctx = context ?: return
        val minor = ctx.dongleMinor
        val found = BleScanner.scan(ctx, minor, 8_000)
        with(Prefs) { ctx.debugBeaconNearby = found }
        val b = _binding ?: return
        activity?.runOnUiThread {
            b.debugBeacon.text = if (found) "✓ nearby" else "✗ not detected"
            b.debugBeacon.setTextColor(
                if (found) Color.parseColor("#69DB7C") else Color.parseColor("#FFA94D")
            )
        }
    }

    private fun updateDebugUserMarker(lat: Double, lng: Double, inside: Boolean) {
        val map = _binding?.debugMap ?: return
        debugUserMarker?.let { map.overlays.remove(it) }

        val dotColor = if (inside) Color.parseColor("#22C55E") else Color.parseColor("#EF4444")
        debugUserMarker = Marker(map).apply {
            position = GeoPoint(lat, lng)
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            icon = createColoredDot(dotColor)
            title = "You"
        }
        map.overlays.add(debugUserMarker)
        map.controller.animateTo(GeoPoint(lat, lng))
        map.invalidate()
    }

    private fun drawGeofenceCircle(lat: Double, lng: Double, radius: Double) {
        val map = _binding?.debugMap ?: return
        debugGeofenceCircle?.let { map.overlays.remove(it) }
        debugGeofenceCircle = Polygon().apply {
            points = Polygon.pointsAsCircle(GeoPoint(lat, lng), radius)
            fillPaint.color = Color.argb(40, 0, 120, 255)
            outlinePaint.color = Color.argb(200, 0, 120, 255)
            outlinePaint.strokeWidth = 3f
        }
        map.overlays.add(0, debugGeofenceCircle)
        map.invalidate()
    }

    private fun createColoredDot(color: Int): android.graphics.drawable.Drawable {
        val size = 24
        val bmp = android.graphics.Bitmap.createBitmap(size, size, android.graphics.Bitmap.Config.ARGB_8888)
        val canvas = android.graphics.Canvas(bmp)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 2, paint)
        val border = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = Color.WHITE
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 3f
        }
        canvas.drawCircle(size / 2f, size / 2f, size / 2f - 2, border)
        return android.graphics.drawable.BitmapDrawable(resources, bmp)
    }
}

private fun haversineMetres(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val r = 6_371_000.0
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).pow(2)
    return r * 2 * atan2(sqrt(a), sqrt(1 - a))
}

// ── RecyclerView Adapter ────────────────────────────────────────────────────

class ParkingAdapter : RecyclerView.Adapter<ParkingAdapter.ViewHolder>() {

    private val items = mutableListOf<ParkingEntry>()
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault()).apply {
        timeZone = TimeZone.getDefault()
    }
    private val isoFmt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.getDefault()).apply {
        timeZone = TimeZone.getTimeZone("UTC")
    }

    fun submitList(list: List<ParkingEntry>) {
        items.clear()
        items.addAll(list)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemParkingBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])
    override fun getItemCount() = items.size

    inner class ViewHolder(private val b: ItemParkingBinding) : RecyclerView.ViewHolder(b.root) {
        fun bind(entry: ParkingEntry) {
            val isParked = entry.status == "parked"
            b.root.setBackgroundColor(if (isParked) Color.parseColor("#F0FDF4") else Color.WHITE)
            b.textInitial.text = entry.name.firstOrNull()?.uppercaseChar()?.toString() ?: "?"
            b.textInitial.backgroundTintList = android.content.res.ColorStateList.valueOf(
                if (isParked) Color.parseColor("#22C55E") else Color.parseColor("#D1D5DB")
            )
            b.textName.text = entry.name
            b.textDongle.text = entry.dongleId
            b.badgeStatus.text = if (isParked) "Parked" else "Away"
            b.badgeStatus.setBackgroundResource(if (isParked) R.drawable.badge_parked else R.drawable.badge_away)
            b.badgeStatus.setTextColor(
                if (isParked) Color.parseColor("#15803D") else Color.parseColor("#6B7280")
            )
            b.textManual.visibility = if (entry.manual) View.VISIBLE else View.GONE
            b.textSince.text = entry.since?.let { formatTime(it) } ?: ""
        }

        private fun formatTime(iso: String): String = try {
            val date = isoFmt.parse(iso.substringBefore("+").substringBefore("Z").take(19)) ?: return ""
            timeFmt.format(date)
        } catch (_: Exception) { "" }
    }
}
