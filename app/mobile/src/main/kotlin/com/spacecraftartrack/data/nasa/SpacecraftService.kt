package com.spacecraftartrack.data.nasa

import com.spacecraftartrack.data.astronomy.AstronomyCalculator
import com.spacecraftartrack.data.astronomy.HorizontalPosition
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton

/**
 * State of Artemis spacecraft tracking.
 */
sealed class SpacecraftState {
    data object Loading : SpacecraftState()
    data object NoActiveMission : SpacecraftState()
    data class Tracking(val position: HorizontalPosition, val missionName: String) : SpacecraftState()
    data class Error(val message: String) : SpacecraftState()
}

/**
 * Queries the NASA/JPL Horizons API for the current position of the Artemis/Orion spacecraft.
 *
 * The Orion capsule is tracked in Horizons under ID -1047 (Artemis I / EFT-1).
 * When no active Artemis mission is in progress the API returns no data, which
 * is handled gracefully as [SpacecraftState.NoActiveMission].
 *
 * API docs: https://ssd-api.jpl.nasa.gov/doc/horizons.html
 */
@Singleton
class SpacecraftService @Inject constructor(
    private val httpClient: HttpClient,
) {
    companion object {
        private const val HORIZONS_URL = "https://ssd.jpl.nasa.gov/api/horizons.api"
        // JPL Horizons NAIF ID for Artemis II / Orion "Integrity" (2026-069A)
        // Artemis I was -1023; Artemis II is -1024
        private const val ORION_ID = "-1024"
        private val FMT = DateTimeFormatter.ofPattern("yyyy-MMM-dd HH:mm").withZone(ZoneOffset.UTC)
    }

    /**
     * Fetch the Artemis spacecraft position as seen from [latDeg]/[lonDeg].
     * Returns [SpacecraftState.NoActiveMission] when Horizons has no current ephemeris.
     */
    suspend fun fetchArtemisPosition(
        latDeg: Double,
        lonDeg: Double,
        altMeters: Double = 0.0,
    ): SpacecraftState {
        return try {
            val now = Instant.now()
            val stop = Instant.ofEpochMilli(now.toEpochMilli() + 60_000L)
            val startStr = FMT.format(now)
            val stopStr = FMT.format(stop)

            val response = httpClient.get(HORIZONS_URL) {
                // Azimuth + elevation (quantities=4) and range (quantities=20)
                parameter("format", "json")
                parameter("COMMAND", "'$ORION_ID'")
                parameter("CENTER", "'coord@399'")
                parameter("COORD_TYPE", "'GEODETIC'")
                parameter("SITE_COORD", "'$lonDeg,$latDeg,$altMeters'")
                parameter("START_TIME", "'$startStr'")
                parameter("STOP_TIME", "'$stopStr'")
                parameter("STEP_SIZE", "'1 m'")
                parameter("QUANTITIES", "'4,20'")
                parameter("MAKE_EPHEM", "YES")
                parameter("OBJ_DATA", "NO")
                parameter("CAL_FORMAT", "CAL")
                parameter("ANG_FORMAT", "DEG")
            }

            val body = response.bodyAsText()
            parseHorizonsAzAlt(body)
        } catch (e: Exception) {
            SpacecraftState.Error(e.message ?: "Network error")
        }
    }

    /**
     * Parses azimuth/elevation and range from a Horizons plain-text ephemeris section.
     *
     * The $$SOE / $$EOE markers delimit the ephemeris table; each data row contains:
     *   date/time   Az(deg)   El(deg)   ...  delta(AU)  ...
     */
    private fun parseHorizonsAzAlt(body: String): SpacecraftState {
        val soeIndex = body.indexOf("\$\$SOE")
        val eoeIndex = body.indexOf("\$\$EOE")
        if (soeIndex < 0 || eoeIndex < 0) {
            // Horizons returned no ephemeris — treat as no active mission
            if (body.contains("No ephemeris", ignoreCase = true) ||
                body.contains("no data", ignoreCase = true) ||
                body.contains("target body not found", ignoreCase = true)
            ) {
                return SpacecraftState.NoActiveMission
            }
            return SpacecraftState.Error("Unexpected response format")
        }

        val tableSection = body.substring(soeIndex + 5, eoeIndex).trim()
        val firstLine = tableSection.lines().firstOrNull { it.isNotBlank() }
            ?: return SpacecraftState.NoActiveMission

        // Data row: "date  time  [presence-code]  Az  El  delta  deldot"
        // The presence-code column (e.g. "*", "C") may appear as a separate token,
        // so we skip non-numeric tokens and take the first parseable doubles as Az/El/delta.
        val parts = firstLine.trim().split(Regex("\\s+"))
        if (parts.size < 5) return SpacecraftState.NoActiveMission

        return try {
            val numbers = parts.mapNotNull { it.toDoubleOrNull() }
            if (numbers.size < 3) return SpacecraftState.NoActiveMission

            val az = numbers[0]
            val el = numbers[1]
            val distanceKm = numbers[2] * 149_597_870.7   // AU → km

            SpacecraftState.Tracking(
                position = HorizontalPosition(az, el, distanceKm),
                missionName = "Artemis II (Integrity)"
            )
        } catch (e: NumberFormatException) {
            SpacecraftState.NoActiveMission
        }
    }
}
