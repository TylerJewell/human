package io.akka.human.engine;

import java.time.Duration;
import java.util.regex.Pattern;

/**
 * ISO 8601 durations as Verify and the contract write them. {@link Duration#parse} reads days and
 * times only; weeks, months and years are read here as 7, 30 and 365 days, which is what a deadline
 * written that way means in practice.
 */
public final class Durations {

  private static final Pattern ISO = Pattern.compile(
      "^P(?!$)(?:(\\d+)Y)?(?:(\\d+)M)?(?:(\\d+)W)?(?:(\\d+)D)?(?:T(?=\\d)(?:(\\d+)H)?(?:(\\d+)M)?(?:(\\d+(?:\\.\\d+)?)S)?)?$");

  private Durations() {}

  public static Duration parse(String iso) {
    if (iso == null) throw new IllegalArgumentException("a duration is required");
    var m = ISO.matcher(iso.trim());
    if (!m.matches()) throw new IllegalArgumentException("not an ISO 8601 duration: " + iso);
    long days = number(m.group(1)) * 365 + number(m.group(2)) * 30 + number(m.group(3)) * 7 + number(m.group(4));
    var duration = Duration.ofDays(days).plusHours(number(m.group(5))).plusMinutes(number(m.group(6)));
    if (m.group(7) != null) duration = duration.plusMillis(Math.round(Double.parseDouble(m.group(7)) * 1000));
    return duration;
  }

  public static boolean valid(String iso) {
    return iso != null && ISO.matcher(iso.trim()).matches();
  }

  private static long number(String digits) {
    return digits == null ? 0 : Long.parseLong(digits);
  }
}
