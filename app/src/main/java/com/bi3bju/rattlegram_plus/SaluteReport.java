package com.bi3bju.rattlegram_plus;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * Builds the compact plain-text SALUTE report that goes into the message box,
 * and the time strings used to fill the Time field.
 */
final class SaluteReport {
    private SaluteReport() {}

    /**
     * Compact report: one "X value" line per filled field, e.g. "S 3 dismounted".
     * No header line and empty fields are left out, so a report fits in an encrypted message.
     * Returns "" if every field is empty.
     */
    static String build(String size, String activity, String location,
                        String unit, String time, String equipment) {
        StringBuilder sb = new StringBuilder();
        append(sb, 'S', size);
        append(sb, 'A', activity);
        append(sb, 'L', location);
        append(sb, 'U', unit);
        append(sb, 'T', time);
        append(sb, 'E', equipment);
        return sb.toString();
    }

    private static void append(StringBuilder sb, char label, String value) {
        if (value == null) return;
        String v = value.replaceAll("\\s+", " ").trim();
        if (v.isEmpty()) return;
        if (sb.length() > 0)
            sb.append('\n');
        sb.append(label).append(' ').append(v);
    }

    /** UTC time, e.g. "12:54UTC". */
    static String utcTime(Date when) {
        SimpleDateFormat f = new SimpleDateFormat("HH:mm", Locale.US);
        f.setTimeZone(TimeZone.getTimeZone("UTC"));
        return f.format(when) + "UTC";
    }

    /** Local 12-hour time with zone, e.g. "8:54 am EDT". */
    static String localTime(Date when, TimeZone zone) {
        SimpleDateFormat f = new SimpleDateFormat("h:mm a", Locale.US);
        f.setTimeZone(zone);
        String zoneName = zone.getDisplayName(zone.inDaylightTime(when), TimeZone.SHORT, Locale.US);
        return f.format(when).toLowerCase(Locale.US) + " " + zoneName;
    }
}
