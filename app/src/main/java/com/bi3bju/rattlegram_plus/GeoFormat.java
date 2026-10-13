package com.bi3bju.rattlegram_plus;

import java.util.Locale;

/**
 * Coordinate formatting helpers: decimal lat/long, Maidenhead grid locator and MGRS.
 * Pure functions with no Android dependencies.
 */
final class GeoFormat {
    static final int LAT_LONG = 0;
    static final int MAIDENHEAD = 1;
    static final int MGRS = 2;

    private GeoFormat() {}

    /** Formats a position in the given format. Returns null when MGRS is not defined there (poles). */
    static String format(int format, double lat, double lon) {
        switch (format) {
            case MAIDENHEAD:
                return maidenhead(lat, lon);
            case MGRS:
                return mgrs(lat, lon);
            default:
                return latLong(lat, lon);
        }
    }

    /** Decimal degrees, 5 places (about 1 m). */
    static String latLong(double lat, double lon) {
        return String.format(Locale.US, "%.5f,%.5f", lat, lon);
    }

    /** 8-character Maidenhead locator, e.g. FN31pr21. */
    static String maidenhead(double lat, double lon) {
        double lo = Math.min(Math.max(lon, -180.0), 179.999999) + 180.0;
        double la = Math.min(Math.max(lat, -90.0), 89.999999) + 90.0;

        int fieldLon = (int) (lo / 20);
        int fieldLat = (int) (la / 10);
        lo -= fieldLon * 20;
        la -= fieldLat * 10;

        int squareLon = (int) (lo / 2);
        int squareLat = (int) la;
        lo -= squareLon * 2;
        la -= squareLat;

        int subLon = Math.min((int) (lo * 12), 23);
        int subLat = Math.min((int) (la * 24), 23);
        lo -= subLon / 12.0;
        la -= subLat / 24.0;

        int extLon = Math.min((int) (lo * 120), 9);
        int extLat = Math.min((int) (la * 240), 9);

        return "" + (char) ('A' + fieldLon) + (char) ('A' + fieldLat)
                + squareLon + squareLat
                + (char) ('a' + subLon) + (char) ('a' + subLat)
                + extLon + extLat;
    }

    private static final String LAT_BANDS = "CDEFGHJKLMNPQRSTUVWX";
    private static final String COLUMN_LETTERS = "ABCDEFGHJKLMNPQRSTUVWXYZ";
    private static final String ROW_LETTERS = "ABCDEFGHJKLMNPQRSTUV";

    // WGS84
    private static final double A = 6378137.0;
    private static final double F = 1 / 298.257223563;
    private static final double K0 = 0.9996;

    /**
     * MGRS at 10 m precision, e.g. "18S UJ 2337 0651".
     * Returns null outside 80S..84N, where MGRS uses the polar (UPS) grid, which is not implemented.
     */
    static String mgrs(double lat, double lon) {
        if (lat < -80.0 || lat > 84.0) return null;
        lon = ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;

        int zone = (int) Math.floor((lon + 180.0) / 6.0) + 1;
        // Norway and Svalbard zone exceptions
        if (lat >= 56.0 && lat < 64.0 && lon >= 3.0 && lon < 12.0) zone = 32;
        if (lat >= 72.0 && lat < 84.0) {
            if (lon >= 0.0 && lon < 9.0) zone = 31;
            else if (lon >= 9.0 && lon < 21.0) zone = 33;
            else if (lon >= 21.0 && lon < 33.0) zone = 35;
            else if (lon >= 33.0 && lon < 42.0) zone = 37;
        }
        char band = LAT_BANDS.charAt(Math.min((int) Math.floor((lat + 80.0) / 8.0), 19));

        // UTM forward projection (Krueger series, 4th order)
        double lon0 = Math.toRadians((zone - 1) * 6 - 180 + 3);
        double phi = Math.toRadians(lat);
        double lam = Math.toRadians(lon) - lon0;

        double n = F / (2 - F);
        double n2 = n * n, n3 = n2 * n, n4 = n3 * n;
        double aa = A / (1 + n) * (1 + n2 / 4 + n4 / 64);
        double[] alpha = {
                n / 2 - 2 * n2 / 3 + 5 * n3 / 16 + 41 * n4 / 180,
                13 * n2 / 48 - 3 * n3 / 5 + 557 * n4 / 1440,
                61 * n3 / 240 - 103 * n4 / 140,
                49561 * n4 / 161280
        };

        double c = 2 * Math.sqrt(n) / (1 + n);
        double sinPhi = Math.sin(phi);
        double t = Math.sinh(atanh(sinPhi) - c * atanh(c * sinPhi));
        double xiP = Math.atan2(t, Math.cos(lam));
        double etaP = atanh(Math.sin(lam) / Math.sqrt(1 + t * t));

        double xi = xiP;
        double eta = etaP;
        for (int j = 1; j <= 4; j++) {
            xi += alpha[j - 1] * Math.sin(2 * j * xiP) * Math.cosh(2 * j * etaP);
            eta += alpha[j - 1] * Math.cos(2 * j * xiP) * Math.sinh(2 * j * etaP);
        }

        double easting = 500000.0 + K0 * aa * eta;
        double northing = K0 * aa * xi;
        if (northing < 0) northing += 10000000.0;

        // 100 km square letters
        int columnSet = zone % 3;
        int columnBase = columnSet == 1 ? 0 : (columnSet == 2 ? 8 : 16);
        int column = (int) (easting / 100000.0) - 1;
        int row = ((int) (northing / 100000.0) + (zone % 2 == 0 ? 5 : 0)) % 20;

        int e10 = (int) ((easting % 100000.0) / 10.0);
        int n10 = (int) ((northing % 100000.0) / 10.0);

        return String.format(Locale.US, "%02d%c %c%c %04d %04d", zone, band,
                COLUMN_LETTERS.charAt(columnBase + column), ROW_LETTERS.charAt(row), e10, n10);
    }

    private static double atanh(double x) {
        return 0.5 * Math.log((1 + x) / (1 - x));
    }
}
