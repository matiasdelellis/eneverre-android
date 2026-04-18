package ar.com.delellis.eneverre.util;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class Time {
    public static long RFC3339toMS(String dateTime) {
        SimpleDateFormat timeServerFormatter = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX", Locale.US);
        try {
            timeServerFormatter.parse(dateTime);
        } catch (ParseException e) {
            return -1;
        }

        return timeServerFormatter.getCalendar().getTimeInMillis();
    }

    public static String MStoRFC3339(long time) {
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX" , Locale.US);
        return simpleDateFormat.format(new Date(time));
    }

    public static String MStoFriendlyURL(long time) {
        SimpleDateFormat simpleDateFormat = new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US);
        return simpleDateFormat.format(new Date(time));
    }
}
