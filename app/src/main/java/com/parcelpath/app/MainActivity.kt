package com.parcelpath.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Criteria
import android.location.Geocoder
import android.location.LocationListener
import android.location.LocationManager
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.*

data class ParcelStop(
    val id: Int, var phone: String = "", var pin: String = "", var tracking: String = "",
    var address: String = "", var verifiedAddress: String = "", var lat: Double? = null,
    var lon: Double? = null, var rawText: String = ""
)

class MainActivity : AppCompatActivity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val stops = mutableListOf<ParcelStop>()
    private val addressFields = mutableMapOf<Int, EditText>()
    private var nextId = 1
    private var startLat: Double? = null
    private var startLon: Double? = null
    private var ordered = listOf<ParcelStop>()
    private var navIndex = 0
    private lateinit var status: TextView
    private lateinit var parcelList: LinearLayout
    private lateinit var startLabel: TextView
    private lateinit var summary: TextView
    private lateinit var verifyButton: Button
    private lateinit var optimizeButton: Button
    private lateinit var navButton: Button

    private val picker = registerForActivityResult(ActivityResultContracts.GetMultipleContents()) { uris ->
        if (uris.isNullOrEmpty()) return@registerForActivityResult
        busy(true)
        status.text = "Reading ${uris.size} label image(s)…"
        executor.execute {
            val recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
            val extracted = mutableListOf<ParcelStop>()
            try {
                uris.forEachIndexed { index, uri ->
                    val text = try {
                        recognizer.process(InputImage.fromFilePath(this, uri)).result.text
                    } catch (_: Exception) { "" }
                    extracted.add(parseLabel(text))
                    runOnUiThread { status.text = "OCR: ${index + 1}/${uris.size}" }
                }
            } finally { recognizer.close() }
            runOnUiThread {
                stops.addAll(extracted)
                renderStops()
                status.text = "OCR complete. Check and correct all addresses before verifying."
                busy(false)
                toast("${extracted.size} labels processed")
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
    }

    private fun parseLabel(text: String): ParcelStop {
        val phone = Regex("""(?:\+91[\s-]?)?[6-9]\d{9}""").find(text)?.value.orEmpty()
        val pin = Regex("""\b[1-9]\d{5}\b""").find(text)?.value.orEmpty()
        val tracking = Regex("""(?i)(?:AWB|TRACKING|ORDER|SHIPMENT|WAYBILL)[\s:#-]*([A-Z0-9-]{7,})""")
            .find(text)?.groupValues?.getOrNull(1).orEmpty()
        val ignored = Regex("""(?i)^(ship to|deliver to|delivery address|invoice|tax invoice|order details|customer care|phone|mobile|email|www\.|amazon|flipkart|meesho|delhivery|ekart|ecom express|shadowfax|awb|tracking id)""")
        val address = text.lines().map { it.trim().replace("|", "I") }
            .filter { it.length > 2 && !ignored.containsMatchIn(it) && !it.matches(Regex("""[A-Z0-9-]{9,}""")) }
            .joinToString(", ").take(500)
        return ParcelStop(nextId++, phone = phone, pin = pin, tracking = tracking,
            address = if (pin.isNotBlank() && !address.contains(pin)) "$address, $pin" else address, rawText = text)
    }

    private fun buildUi() {
        val outer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(18), dp(18), dp(16))
            setBackgroundColor(0xFFF4F6F2.toInt())
        }
        val scroll = ScrollView(this)
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(body)
        outer.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        body.addView(label("PARCELPATH", 12, 0xFF5C7448.toInt(), true))
        body.addView(label("Parcel route planner", 27, 0xFF17352C.toInt(), true))
        body.addView(label("Scan labels · verify addresses · plan your delivery run", 14, 0xFF637069.toInt()))
        body.addView(space(14))
        body.addView(button("＋  Upload parcel screenshots") { picker.launch("image/*") })
        body.addView(button("＋  Add address manually") {
            stops.add(ParcelStop(nextId++)); renderStops()
        })
        status = label("Ready. Select one or more parcel label images.", 13, 0xFF53645B.toInt())
        body.addView(status)
        body.addView(space(8))
        body.addView(label("PARCELS", 12, 0xFF5C7448.toInt(), true))
        parcelList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        body.addView(parcelList)
        verifyButton = button("Verify delivery addresses") { verifyAddresses() }
        body.addView(verifyButton)
        body.addView(space(18))
        body.addView(label("STARTING LOCATION", 12, 0xFF5C7448.toInt(), true))
        startLabel = label("Not set — use GPS or enter a starting address", 14, 0xFF53645B.toInt())
        body.addView(startLabel)
        body.addView(button("◎  Use current GPS location") { requestGps() })
        val startInput = EditText(this).apply {
            hint = "Starting address (e.g. Thammanam, Kochi)"
            setSingleLine(true)
        }
        body.addView(startInput)
        body.addView(button("Set starting address") { setStartAddress(startInput.text.toString()) })
        body.addView(space(12))
        optimizeButton = button("Optimize delivery route") { optimizeRoute() }
        body.addView(optimizeButton)
        summary = label("Verified stops and route order will appear here.", 14, 0xFF53645B.toInt())
        body.addView(summary)
        navButton = button("Navigate to next stop in Google Maps") { navigateNext() }
        navButton.isEnabled = false
        body.addView(navButton)
        body.addView(label("Open one stop at a time. Return here and tap again for the next stop.", 12, 0xFF637069.toInt()))
        setContentView(outer)
    }

    private fun renderStops() {
        parcelList.removeAllViews()
        addressFields.clear()
        if (stops.isEmpty()) {
            parcelList.addView(label("No parcels added yet.", 13, 0xFF637069.toInt()))
            return
        }
        stops.forEachIndexed { index, stop ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(8))
                setBackgroundColor(0xFFFFFFFF.toInt())
            }
            card.addView(label("PARCEL ${index + 1}", 11, 0xFF5C7448.toInt(), true))
            val address = EditText(this).apply {
                hint = "Full delivery address, locality, city and PIN"
                minLines = 2
                textSize = 14f
                setText(stop.address)
            }
            addressFields[stop.id] = address
            card.addView(address)
            if (stop.phone.isNotBlank()) card.addView(label("Phone: ${stop.phone}", 12, 0xFF637069.toInt()))
            if (stop.pin.isNotBlank()) card.addView(label("PIN: ${stop.pin}", 12, 0xFF637069.toInt()))
            if (stop.tracking.isNotBlank()) card.addView(label("Reference: ${stop.tracking}", 12, 0xFF637069.toInt()))
            if (stop.verifiedAddress.isNotBlank()) card.addView(label("✓ ${stop.verifiedAddress}", 12, 0xFF386A43.toInt()))
            card.addView(button("Remove parcel ${index + 1}") { stops.remove(stop); renderStops() })
            parcelList.addView(card, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(8) })
        }
    }

    private fun syncAddresses() {
        stops.forEach { it.address = addressFields[it.id]?.text?.toString()?.trim().orEmpty() }
    }

    private fun verifyAddresses() {
        syncAddresses()
        val candidates = stops.filter { it.address.isNotBlank() }
        if (candidates.isEmpty()) { toast("Add at least one address first."); return }
        busy(true)
        status.text = "Verifying addresses…"
        executor.execute {
            var matched = 0
            candidates.forEachIndexed { index, stop ->
                val result = geocode(stop.address)
                if (result != null) {
                    stop.lat = result.first; stop.lon = result.second
                    stop.verifiedAddress = result.third
                    matched++
                } else {
                    stop.lat = null; stop.lon = null
                    stop.verifiedAddress = "No match — edit the address and retry"
                }
                runOnUiThread { status.text = "Address check ${index + 1}/${candidates.size}" }
                if (index < candidates.lastIndex) Thread.sleep(1100)
            }
            runOnUiThread {
                renderStops()
                status.text = "$matched/${candidates.size} addresses matched. Confirm each result is the correct delivery location."
                busy(false)
            }
        }
    }

    private fun geocode(query: String): Triple<Double, Double, String>? {
        return try {
            if (!Geocoder.isPresent()) null
            else {
                @Suppress("DEPRECATION")
                val results = Geocoder(this, Locale("en", "IN")).getFromLocationName("$query, India", 1)
                val place = results?.firstOrNull()
                if (place == null) null
                else Triple(place.latitude, place.longitude, place.getAddressLine(0) ?: query)
            }
        } catch (_: Exception) { null }
    }

    private fun setStartAddress(address: String) {
        if (address.isBlank()) { toast("Enter a starting address."); return }
        busy(true)
        executor.execute {
            val place = geocode(address)
            runOnUiThread {
                if (place == null) toast("Starting address not found.")
                else {
                    startLat = place.first; startLon = place.second
                    startLabel.text = place.third
                    toast("Starting point set.")
                }
                busy(false)
            }
        }
    }

    private fun requestGps() {
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 701)
            return
        }
        val manager = getSystemService(LOCATION_SERVICE) as LocationManager
        val provider = manager.getBestProvider(Criteria().apply { accuracy = Criteria.ACCURACY_FINE }, true)
        if (provider == null) { toast("Enable device location or enter a starting address."); return }
        busy(true); status.text = "Acquiring GPS location…"
        try {
            manager.requestSingleUpdate(provider, object : LocationListener {
                override fun onLocationChanged(location: android.location.Location) {
                    startLat = location.latitude; startLon = location.longitude
                    startLabel.text = "GPS %.5f, %.5f".format(location.latitude, location.longitude)
                    status.text = "GPS starting point acquired."
                    busy(false)
                }
                @Deprecated("Deprecated")
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
            }, Looper.getMainLooper())
        } catch (_: SecurityException) { busy(false); toast("Location permission not granted.") }
    }

    private fun optimizeRoute() {
        syncAddresses()
        val lat = startLat; val lon = startLon
        if (lat == null || lon == null) { toast("Set your starting point first."); return }
        val mapped = stops.filter { it.lat != null && it.lon != null }
        if (mapped.isEmpty()) { toast("Verify at least one delivery address first."); return }
        busy(true); status.text = "Optimizing stop order…"
        executor.execute {
            val roadOrder = if (mapped.size <= 90) osrmTrip(lat, lon, mapped) else null
            val route = roadOrder ?: nearestNeighbour(lat, lon, mapped)
            runOnUiThread {
                ordered = route
                navIndex = 0
                summary.text = buildString {
                    append("${route.size} stops\n")
                    append(if (roadOrder != null) "OSRM road-network order" else "Approximate nearest-stop order")
                    route.forEachIndexed { i, p -> append("\n${i + 1}. ${p.verifiedAddress.ifBlank { p.address }}") }
                }
                navButton.isEnabled = route.isNotEmpty()
                navButton.text = "Navigate to stop 1 of ${route.size}"
                status.text = "Route ready. Review the order before navigation."
                busy(false)
            }
        }
    }

    private fun osrmTrip(lat: Double, lon: Double, points: List<ParcelStop>): List<ParcelStop>? = try {
        val coords = (listOf("$lon,$lat") + points.map { "${it.lon},${it.lat}" }).joinToString(";")
        val connection = URL("https://router.project-osrm.org/trip/v1/driving/$coords?roundtrip=false&source=first&destination=last&overview=false")
            .openConnection() as HttpURLConnection
        connection.connectTimeout = 15000; connection.readTimeout = 20000
        val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
        connection.disconnect()
        if (json.optString("code") != "Ok") return null
        val waypoints = json.getJSONArray("waypoints")
        val sorted = (1 until waypoints.length()).map { i ->
            val wp = waypoints.getJSONObject(i)
            wp.getInt("waypoint_index") to (i - 1)
        }.sortedBy { it.first }
        if (sorted.size != points.size) null else sorted.map { points[it.second] }
    } catch (_: Exception) { null }

    private fun nearestNeighbour(lat: Double, lon: Double, points: List<ParcelStop>): List<ParcelStop> {
        val remaining = points.toMutableList()
        val route = mutableListOf<ParcelStop>()
        var a = lat; var b = lon
        while (remaining.isNotEmpty()) {
            val next = remaining.minByOrNull { distance(a, b, it.lat!!, it.lon!!) }!!
            remaining.remove(next); route.add(next); a = next.lat!!; b = next.lon!!
        }
        return route
    }

    private fun distance(a: Double, b: Double, c: Double, d: Double): Double {
        val r = 6371.0
        val x = sin(Math.toRadians(c - a) / 2).pow(2.0) +
            cos(Math.toRadians(a)) * cos(Math.toRadians(c)) * sin(Math.toRadians(d - b) / 2).pow(2.0)
        return 2 * r * asin(sqrt(x))
    }

    private fun navigateNext() {
        if (ordered.isEmpty()) return
        if (navIndex >= ordered.size) {
            toast("All stops have been opened.")
            navIndex = 0
            navButton.text = "Navigate to stop 1 of ${ordered.size}"
            return
        }
        val stop = ordered[navIndex]
        val uri = Uri.parse("google.navigation:q=${stop.lat},${stop.lon}&mode=d")
        val mapsIntent = Intent(Intent.ACTION_VIEW, uri).setPackage("com.google.android.apps.maps")
        try { startActivity(mapsIntent) }
        catch (_: Exception) {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(
                "https://www.google.com/maps/dir/?api=1&destination=${stop.lat},${stop.lon}&travelmode=driving"
            )))
        }
        navIndex++
        navButton.text = if (navIndex < ordered.size) "Navigate to stop ${navIndex + 1} of ${ordered.size}" else "All stops opened — reset"
    }

    private fun busy(value: Boolean) {
        if (::verifyButton.isInitialized) verifyButton.isEnabled = !value
        if (::optimizeButton.isInitialized) optimizeButton.isEnabled = !value
    }

    private fun button(text: String, action: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; textSize = 14f
        setOnClickListener { action() }
    }
    private fun label(text: String, size: Int, color: Int, bold: Boolean = false) = TextView(this).apply {
        this.text = text; textSize = size.toFloat(); setTextColor(color)
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        setPadding(0, dp(4), 0, dp(4))
    }
    private fun space(height: Int) = View(this).apply { layoutParams = LinearLayout.LayoutParams(1, dp(height)) }
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_LONG).show()

    override fun onDestroy() { executor.shutdown(); super.onDestroy() }
}
