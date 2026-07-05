package com.druk.lmplayground.tools

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import androidx.core.content.ContextCompat
import org.json.JSONObject
import java.util.Locale

/**
 * Reports the device's current APPROXIMATE location (coarse / network accuracy)
 * plus a best-effort nearby place name. Sensitive: guarded by the runtime
 * ACCESS_COARSE_LOCATION permission (requested when the user turns the tool on in
 * Settings), self-checks it at call time, and carries a transparency note in the
 * Settings row. Uses only last-known fixes, so it's fast and never blocks on GPS.
 */
class LocationTool(private val context: Context) : Tool {
    override val name = "location"
    override val description = "Get the device's current approximate location: latitude, longitude, accuracy in meters, and, when available, the nearby place (city, region, country). Requires location permission; if it isn't granted, tell the user to enable the Location tool in Settings."
    override val parametersSchema = """{"type":"object","properties":{}}"""

    override fun execute(arguments: String): String {
        return try {
            if (!hasPermission()) {
                return """{"error":"Location permission is not granted. Enable the Location tool in Settings so the app can ask for permission."}"""
            }
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                ?: return """{"error":"Location service is unavailable on this device."}"""
            val location = bestLastKnown(lm)
                ?: return """{"error":"No recent location fix is available. Make sure location is turned on, then try again."}"""

            val out = JSONObject()
                .put("latitude", location.latitude)
                .put("longitude", location.longitude)
                .put("accuracy_m", if (location.hasAccuracy()) location.accuracy.toDouble() else JSONObject.NULL)
                .put("provider", location.provider ?: "")
                .put("timestamp_ms", location.time)
            reverseGeocode(location.latitude, location.longitude)?.let { out.put("place", it) }
            out.toString()
        } catch (e: SecurityException) {
            """{"error":"Location permission is not granted."}"""
        } catch (e: Exception) {
            """{"error":"${(e.message ?: "Could not read the location").replace("\"", "'")}"}"""
        }
    }

    private fun hasPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    /** Most recent last-known fix across the providers coarse permission allows. */
    @Suppress("MissingPermission") // permission is verified in execute() before this runs
    private fun bestLastKnown(lm: LocationManager): Location? {
        var best: Location? = null
        for (provider in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.PASSIVE_PROVIDER, LocationManager.GPS_PROVIDER)) {
            val loc = try {
                if (lm.isProviderEnabled(provider)) lm.getLastKnownLocation(provider) else null
            } catch (_: SecurityException) {
                null // provider needs finer permission than we hold; skip it
            } catch (_: Exception) {
                null
            }
            if (loc != null && (best == null || loc.time > best.time)) best = loc
        }
        return best
    }

    private fun reverseGeocode(lat: Double, lon: Double): String? {
        return try {
            @Suppress("DEPRECATION")
            val addresses = Geocoder(context, Locale.getDefault()).getFromLocation(lat, lon, 1)
            val a = addresses?.firstOrNull() ?: return null
            listOfNotNull(a.locality ?: a.subAdminArea, a.adminArea, a.countryName)
                .distinct()
                .joinToString(", ")
                .ifEmpty { null }
        } catch (_: Exception) {
            null // Geocoder needs network / a backend; best-effort only
        }
    }
}
