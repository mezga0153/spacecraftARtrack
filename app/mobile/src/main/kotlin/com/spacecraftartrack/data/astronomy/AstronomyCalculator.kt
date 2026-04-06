package com.spacecraftartrack.data.astronomy

import kotlin.math.*

/**
 * Celestial object position in the local horizontal coordinate system.
 * @param azimuth degrees from North clockwise (0=N, 90=E, 180=S, 270=W)
 * @param altitude degrees above horizon (negative = below horizon)
 * @param distanceKm approximate distance in kilometres (0 if not applicable)
 */
data class HorizontalPosition(
    val azimuth: Double,
    val altitude: Double,
    val distanceKm: Double = 0.0,
)

/**
 * Astronomical position calculations.
 *
 * Moon position algorithm: Jean Meeus "Astronomical Algorithms" Chapter 47 (low-precision).
 * Accurate to ~0.3°, sufficient for AR sky-pointing.
 */
object AstronomyCalculator {

    // ── Julian Date ──────────────────────────────────────────────────────────

    /** Unix epoch millis → Julian Date */
    fun toJulianDate(epochMillis: Long): Double {
        return epochMillis / 86_400_000.0 + 2_440_587.5
    }

    // ── Moon position ────────────────────────────────────────────────────────

    /**
     * Compute the Moon's azimuth and altitude as seen from [latDeg]/[lonDeg]
     * at the given [epochMillis].
     */
    fun moonPosition(latDeg: Double, lonDeg: Double, epochMillis: Long): HorizontalPosition {
        val jd = toJulianDate(epochMillis)
        val (ra, dec, dist) = moonRaDec(jd)
        val (az, alt) = raDecToAzAlt(ra, dec, latDeg, lonDeg, jd)
        return HorizontalPosition(az, alt, dist)
    }

    /**
     * Returns (rightAscension hours, declination degrees, distance km)
     * using the simplified Meeus algorithm.
     */
    private fun moonRaDec(jd: Double): Triple<Double, Double, Double> {
        val T = (jd - 2_451_545.0) / 36_525.0   // Julian centuries since J2000.0

        // Moon's mean longitude
        val L0 = norm360(218.3164477 + 481_267.88123421 * T)
        // Moon's mean anomaly
        val M = toRad(norm360(134.9633964 + 477_198.8675055 * T))
        // Sun's mean anomaly
        val Ms = toRad(norm360(357.5291092 + 35_999.0502909 * T))
        // Moon's argument of latitude
        val F = toRad(norm360(93.2720950 + 483_202.0175233 * T))
        // Elongation
        val D = toRad(norm360(297.8501921 + 445_267.1114034 * T))

        // Longitude perturbations (degrees)
        val dLon = (6.288750 * sin(M)
                + 1.274018 * sin(2 * D - M)
                + 0.658309 * sin(2 * D)
                + 0.213616 * sin(2 * M)
                - 0.185596 * sin(Ms)
                - 0.114336 * sin(2 * F)
                + 0.058793 * sin(2 * D - 2 * M)
                + 0.057212 * sin(2 * D - Ms - M)
                + 0.053320 * sin(2 * D + M)
                + 0.045874 * sin(2 * D - Ms)
                + 0.041024 * sin(M - Ms)
                - 0.034718 * sin(D)
                - 0.030465 * sin(Ms + M)
                + 0.015326 * sin(2 * D - 2 * F)
                - 0.012528 * sin(2 * F + M)
                - 0.010980 * sin(2 * F - M)
                + 0.010674 * sin(4 * D - M)
                + 0.010034 * sin(3 * M)
                + 0.008548 * sin(4 * D - 2 * M))

        // Latitude perturbations (degrees)
        val dLat = (5.128122 * sin(F)
                + 0.280602 * sin(M + F)
                + 0.277693 * sin(M - F)
                + 0.173237 * sin(2 * D - F)
                + 0.055413 * sin(2 * D + F - M)
                + 0.046272 * sin(2 * D - F - M)
                + 0.032573 * sin(2 * D + F)
                + 0.017198 * sin(2 * M + F)
                + 0.009266 * sin(2 * D + M - F)
                + 0.008823 * sin(2 * M - F)
                + 0.008247 * sin(2 * D - Ms - F)
                + 0.004323 * sin(2 * D - F - 2 * M)
                + 0.004200 * sin(2 * D + F + M))

        // Distance (km)
        val dDist = (385_001.0
                - 20_905.355 * cos(M)
                - 3_699.111 * cos(2 * D - M)
                - 2_955.968 * cos(2 * D)
                - 569.925 * cos(2 * M)
                + 48.888 * cos(Ms))

        val lambda = toRad(L0 + dLon)               // ecliptic longitude
        val beta = toRad(dLat)                        // ecliptic latitude
        val epsilon = toRad(obliquity(T))             // obliquity of ecliptic

        // Convert ecliptic → equatorial
        val sinDec = sin(beta) * cos(epsilon) + cos(beta) * sin(epsilon) * sin(lambda)
        val dec = toDeg(asin(sinDec))

        val y = sin(lambda) * cos(epsilon) - tan(beta) * sin(epsilon)
        val x = cos(lambda)
        val ra = norm24(toDeg(atan2(y, x)) / 15.0)   // hours

        return Triple(ra, dec, dDist)
    }

    // ── Coordinate conversion ────────────────────────────────────────────────

    /**
     * Convert equatorial (RA h, Dec °) to horizontal (Az °, Alt °) for a given
     * observer location and Julian Date.
     */
    fun raDecToAzAlt(
        raHours: Double,
        decDeg: Double,
        latDeg: Double,
        lonDeg: Double,
        jd: Double,
    ): Pair<Double, Double> {
        val gst = greenwichSiderealTime(jd)       // hours
        val lst = norm24(gst + lonDeg / 15.0)     // local sidereal time (hours)
        val ha = toRad((lst - raHours) * 15.0)    // hour angle → radians

        val dec = toRad(decDeg)
        val lat = toRad(latDeg)

        val sinAlt = sin(lat) * sin(dec) + cos(lat) * cos(dec) * cos(ha)
        val alt = toDeg(asin(sinAlt))

        val cosAz = (sin(dec) - sin(lat) * sin(toRad(alt))) / (cos(lat) * cos(toRad(alt)))
        val az0 = toDeg(acos(cosAz.coerceIn(-1.0, 1.0)))
        val az = if (sin(ha) > 0) 360.0 - az0 else az0

        return Pair(az, alt)
    }

    // ── Greenwich Sidereal Time ──────────────────────────────────────────────

    /** Greenwich Mean Sidereal Time in hours for a given Julian Date. */
    fun greenwichSiderealTime(jd: Double): Double {
        val T = (jd - 2_451_545.0) / 36_525.0
        val theta = 280.46061837 + 360.98564736629 * (jd - 2_451_545.0) +
                0.000387933 * T * T - T * T * T / 38_710_000.0
        return norm24(theta / 15.0)
    }

    // ── Obliquity ────────────────────────────────────────────────────────────

    private fun obliquity(T: Double): Double =
        23.439291111 - 0.013004167 * T - 0.000000164 * T * T + 0.000000504 * T * T * T

    // ── Helpers ──────────────────────────────────────────────────────────────

    private fun toRad(deg: Double) = deg * PI / 180.0
    private fun toDeg(rad: Double) = rad * 180.0 / PI
    private fun norm360(d: Double) = ((d % 360.0) + 360.0) % 360.0
    private fun norm24(h: Double) = ((h % 24.0) + 24.0) % 24.0

    // ── Sun position ───────────────────────────────────────────────────────────────

    /**
     * Low-precision Sun position. Accurate to ~1°.
     * Distance returned is 1 AU in km.
     */
    fun sunPosition(latDeg: Double, lonDeg: Double, epochMillis: Long): HorizontalPosition {
        val jd = toJulianDate(epochMillis)
        val T = (jd - 2_451_545.0) / 36_525.0
        // Geometric mean longitude & anomaly
        val L0 = norm360(280.46646 + 36_000.76983 * T + 0.0003032 * T * T)
        val M = toRad(norm360(357.52911 + 35_999.05029 * T - 0.0001537 * T * T))
        // Equation of centre
        val C = (1.914602 - 0.004817 * T) * sin(M) +
                (0.019993 - 0.000101 * T) * sin(2 * M) +
                0.000289 * sin(3 * M)
        val sunLon = toRad(L0 + C)   // ecliptic longitude
        val epsilon = toRad(obliquity(T))
        // Equatorial coordinates
        val sinDec = sin(epsilon) * sin(sunLon)
        val dec = toDeg(asin(sinDec))
        val ra = norm24(toDeg(atan2(sin(sunLon) * cos(epsilon), cos(sunLon))) / 15.0)
        val (az, alt) = raDecToAzAlt(ra, dec, latDeg, lonDeg, jd)
        return HorizontalPosition(az, alt, 149_597_870.7) // 1 AU
    }
}
