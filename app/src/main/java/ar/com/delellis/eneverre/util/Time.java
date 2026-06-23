package ar.com.delellis.eneverre.util;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

public class Time {
    // RFC3339 with and without sub-second precision: recordings carry millis
    // (".SSS"), the events endpoint reports whole seconds ("…T13:18:17Z").
    private static final String[] RFC3339_PATTERNS = {
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ssXXX"
    };

    public static long RFC3339toMS(String dateTime) {
        for (String pattern : RFC3339_PATTERNS) {
            SimpleDateFormat formatter = new SimpleDateFormat(pattern, Locale.US);
            try {
                return formatter.parse(dateTime).getTime();
            } catch (ParseException ignored) {
                // Try the next pattern.
            }
        }
        return -1;
    }

    public static String MStoRFC3339(long time) {
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX" , Locale.US);
        return simpleDateFormat.format(new Date(time));
    }

    public static String MStoFriendlyURL(long time) {
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
        return simpleDateFormat.format(new Date(time));
    }

    public static boolean isSameDay(Date d1, Date d2) {
        Calendar c1 = Calendar.getInstance();
        Calendar c2 = Calendar.getInstance();

        c1.setTime(d1);
        c2.setTime(d2);

        return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR) &&
                c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR);
    }

    public static Date dateAddDays(Date date, int days) {
        Calendar cal = Calendar.getInstance();
        cal.setTime(date);
        cal.add(Calendar.DAY_OF_YEAR, days);
        return cal.getTime();
    }
}
