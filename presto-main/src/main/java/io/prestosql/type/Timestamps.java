/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.prestosql.type;

import io.prestosql.spi.block.Block;
import io.prestosql.spi.connector.ConnectorSession;
import io.prestosql.spi.type.LongTimestamp;
import io.prestosql.spi.type.TimeZoneKey;
import io.prestosql.spi.type.TimestampType;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.base.Preconditions.checkArgument;
import static io.prestosql.spi.type.TimestampType.MAX_PRECISION;
import static io.prestosql.spi.type.TimestampType.MAX_SHORT_PRECISION;
import static java.lang.Math.floorMod;
import static java.lang.Math.multiplyExact;
import static java.lang.String.format;
import static java.time.temporal.ChronoField.MICRO_OF_SECOND;

public final class Timestamps
{
    public static final Pattern DATETIME_PATTERN = Pattern.compile("" +
            "(?<year>\\d\\d\\d\\d)-(?<month>\\d{1,2})-(?<day>\\d{1,2})" +
            "(?: (?<hour>\\d{1,2}):(?<minute>\\d{1,2})(?::(?<second>\\d{1,2})(?:\\.(?<fraction>\\d+))?)?)?" +
            "\\s*(?<timezone>.+)?");
    private static final DateTimeFormatter TIMESTAMP_FORMATTER = DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss");

    private static final long[] POWERS_OF_TEN = {
            1L,
            10L,
            100L,
            1000L,
            10_000L,
            100_000L,
            1_000_000L,
            10_000_000L,
            100_000_000L,
            1_000_000_000L,
            10_000_000_000L,
            100_000_000_000L,
            1000_000_000_000L
    };

    public static final int MILLISECONDS_PER_SECOND = 1000;
    public static final int MICROSECONDS_PER_SECOND = 1_000_000;
    public static final int MICROSECONDS_PER_MILLISECOND = 1000;
    public static final long PICOSECONDS_PER_SECOND = 1_000_000_000_000L;
    public static final int NANOSECONDS_PER_MICROSECOND = 1_000;
    public static final int PICOSECONDS_PER_MICROSECOND = 1_000_000;
    public static final int PICOSECONDS_PER_NANOSECOND = 1000;

    private Timestamps() {}

    private static long roundDiv(long value, long factor)
    {
        checkArgument(factor > 0, "factor must be positive");

        if (value >= 0) {
            return (value + (factor / 2)) / factor;
        }

        return (value + 1 - (factor / 2)) / factor;
    }

    public static long scaleEpochMicrosToMillis(long value)
    {
        return Math.floorDiv(value, MICROSECONDS_PER_MILLISECOND);
    }

    private static long scaleEpochMicrosToSeconds(long epochMicros)
    {
        return Math.floorDiv(epochMicros, MICROSECONDS_PER_SECOND);
    }

    public static long scaleEpochMillisToMicros(long epochMillis)
    {
        return multiplyExact(epochMillis, MICROSECONDS_PER_MILLISECOND);
    }

    public static long epochSecondToMicrosWithRounding(long epochSecond, long picoOfSecond)
    {
        return epochSecond * MICROSECONDS_PER_SECOND + roundDiv(picoOfSecond, PICOSECONDS_PER_MICROSECOND);
    }

    public static int getMicrosOfSecond(long epochMicros)
    {
        return floorMod(epochMicros, MICROSECONDS_PER_SECOND);
    }

    public static int getMicrosOfMilli(long epochMicros)
    {
        return floorMod(epochMicros, MICROSECONDS_PER_MILLISECOND);
    }

    public static long round(long value, int magnitude)
    {
        return roundToNearest(value, POWERS_OF_TEN[magnitude]);
    }

    public static long roundToNearest(long value, long bound)
    {
        return roundDiv(value, bound) * bound;
    }

    private static long scaleFactor(int fromPrecision, int toPrecision)
    {
        if (fromPrecision > toPrecision) {
            throw new IllegalArgumentException("fromPrecision must be <= toPrecision");
        }

        return POWERS_OF_TEN[toPrecision - fromPrecision];
    }

    /**
     * Rescales a value of the given precision to another precision by adding 0s or truncating.
     */
    public static long rescale(long value, int fromPrecision, int toPrecision)
    {
        if (value < 0) {
            throw new IllegalArgumentException("value must be >= 0");
        }

        if (fromPrecision <= toPrecision) {
            value *= scaleFactor(fromPrecision, toPrecision);
        }
        else {
            value /= scaleFactor(toPrecision, fromPrecision);
        }

        return value;
    }

    public static boolean timestampHasTimeZone(String value)
    {
        Matcher matcher = DATETIME_PATTERN.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(format("Invalid timestamp '%s'", value));
        }

        return matcher.group("timezone") != null;
    }

    public static int extractTimestampPrecision(String value)
    {
        Matcher matcher = DATETIME_PATTERN.matcher(value);
        if (!matcher.matches()) {
            throw new IllegalArgumentException(format("Invalid timestamp '%s'", value));
        }

        String fraction = matcher.group("fraction");
        if (fraction == null) {
            return 0;
        }

        return fraction.length();
    }

    public static LocalDateTime toLocalDateTime(TimestampType type, ConnectorSession session, Block block, int position)
    {
        int precision = type.getPrecision();

        long epochMicros;
        int picosOfMicro = 0;
        if (precision <= 3) {
            epochMicros = scaleEpochMillisToMicros(type.getLong(block, position));
        }
        else if (precision <= MAX_SHORT_PRECISION) {
            epochMicros = type.getLong(block, position);
        }
        else {
            LongTimestamp timestamp = (LongTimestamp) type.getObject(block, position);
            epochMicros = timestamp.getEpochMicros();
            picosOfMicro = timestamp.getPicosOfMicro();
        }

        long epochSecond = scaleEpochMicrosToSeconds(epochMicros);
        int nanoFraction = getMicrosOfSecond(epochMicros) * NANOSECONDS_PER_MICROSECOND + (int) (roundToNearest(picosOfMicro, PICOSECONDS_PER_NANOSECOND) / PICOSECONDS_PER_NANOSECOND);

        Instant instant = Instant.ofEpochSecond(epochSecond, nanoFraction);
        if (session.isLegacyTimestamp()) {
            return LocalDateTime.ofInstant(instant, session.getTimeZoneKey().getZoneId());
        }

        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    /**
     * Formats a timestamp of the given precision. This method doesn't do any rounding, so it's expected that the
     * combination of [epochMicros, picosSecond] is already rounded to the provided precision if necessary
     */
    public static String formatTimestamp(int precision, long epochMicros, int picosOfMicro, ZoneId zoneId)
    {
        return formatTimestamp(precision, epochMicros, picosOfMicro, zoneId, TIMESTAMP_FORMATTER);
    }

    /**
     * Formats a timestamp of the given precision. This method doesn't do any rounding, so it's expected that the
     * combination of [epochMicros, picosSecond] is already rounded to the provided precision if necessary
     */
    public static String formatTimestamp(int precision, long epochMicros, int picosOfMicro, ZoneId zoneId, DateTimeFormatter yearToSecondFormatter)
    {
        checkArgument(picosOfMicro >= 0 && picosOfMicro < PICOSECONDS_PER_MICROSECOND, "picosOfMicro is out of range [0, 1_000_000]");

        Instant instant = Instant.ofEpochSecond(scaleEpochMicrosToSeconds(epochMicros));
        LocalDateTime dateTime = LocalDateTime.ofInstant(instant, zoneId);

        StringBuilder builder = new StringBuilder();
        builder.append(yearToSecondFormatter.format(dateTime));
        if (precision > 0) {
            long picoFraction = ((long) getMicrosOfSecond(epochMicros)) * PICOSECONDS_PER_MICROSECOND + picosOfMicro;
            builder.append(".");
            builder.append(format("%0" + precision + "d", rescale(picoFraction, 12, precision)));
        }

        return builder.toString();
    }

    public static Object parseTimestamp(int precision, String value)
    {
        if (precision <= MAX_SHORT_PRECISION) {
            return parseShortTimestamp(value, ZoneOffset.UTC);
        }

        return parseLongTimestamp(value, ZoneOffset.UTC);
    }

    public static Object parseLegacyTimestamp(int precision, TimeZoneKey timeZoneKey, String value)
    {
        if (precision <= MAX_SHORT_PRECISION) {
            return parseShortTimestamp(value, timeZoneKey.getZoneId());
        }

        return parseLongTimestamp(value, timeZoneKey.getZoneId());
    }

    private static long parseShortTimestamp(String value, ZoneId zoneId)
    {
        Matcher matcher = DATETIME_PATTERN.matcher(value);
        if (!matcher.matches() || matcher.group("timezone") != null) {
            throw new IllegalArgumentException("Invalid timestamp: " + value);
        }

        String year = matcher.group("year");
        String month = matcher.group("month");
        String day = matcher.group("day");
        String hour = matcher.group("hour");
        String minute = matcher.group("minute");
        String second = matcher.group("second");
        String fraction = matcher.group("fraction");

        long epochSecond = toEpochSecond(year, month, day, hour, minute, second, zoneId);

        int precision = 0;
        long fractionValue = 0;
        if (fraction != null) {
            precision = fraction.length();
            fractionValue = Long.parseLong(fraction);
        }

        if (precision <= 3) {
            // scale to millis
            return epochSecond * MILLISECONDS_PER_SECOND + rescale(fractionValue, precision, 3);
        }
        else if (precision <= MAX_SHORT_PRECISION) {
            // scale to micros
            return epochSecond * MICROSECONDS_PER_SECOND + rescale(fractionValue, precision, 6);
        }

        throw new IllegalArgumentException(format("Cannot parse '%s' as short timestamp. Max allowed precision = %s", value, MAX_SHORT_PRECISION));
    }

    private static LongTimestamp parseLongTimestamp(String value, ZoneId zoneId)
    {
        Matcher matcher = DATETIME_PATTERN.matcher(value);
        if (!matcher.matches() || matcher.group("timezone") != null) {
            throw new IllegalArgumentException("Invalid timestamp: " + value);
        }

        String year = matcher.group("year");
        String month = matcher.group("month");
        String day = matcher.group("day");
        String hour = matcher.group("hour");
        String minute = matcher.group("minute");
        String second = matcher.group("second");
        String fraction = matcher.group("fraction");

        if (fraction == null || fraction.length() <= MAX_SHORT_PRECISION) {
            throw new IllegalArgumentException(format("Cannot parse '%s' as long timestamp. Precision must be in the range [%s, %s]", value, MAX_SHORT_PRECISION + 1, MAX_PRECISION));
        }

        int precision = fraction.length();
        long epochSecond = toEpochSecond(year, month, day, hour, minute, second, zoneId);
        long picoFraction = rescale(Long.parseLong(fraction), precision, 12);

        return longTimestamp(epochSecond, picoFraction);
    }

    private static long toEpochSecond(String year, String month, String day, String hour, String minute, String second, ZoneId zoneId)
    {
        LocalDateTime timestamp = LocalDateTime.of(
                Integer.parseInt(year),
                Integer.parseInt(month),
                Integer.parseInt(day),
                hour == null ? 0 : Integer.parseInt(hour),
                minute == null ? 0 : Integer.parseInt(minute),
                second == null ? 0 : Integer.parseInt(second),
                0);

        // Only relevant for legacy timestamps. New timestamps are parsed using UTC, which doesn't
        // have daylight savings transitions. TODO: remove once legacy timestamps are gone
        List<ZoneOffset> offsets = zoneId.getRules().getValidOffsets(timestamp);
        if (offsets.isEmpty()) {
            throw new IllegalArgumentException("Invalid timestamp due to daylight savings transition");
        }

        return timestamp.toEpochSecond(offsets.get(0));
    }

    public static LongTimestamp longTimestamp(long precision, Instant start)
    {
        checkArgument(precision > MAX_SHORT_PRECISION && precision <= MAX_PRECISION, "Precision is out of range");
        return new LongTimestamp(
                start.getEpochSecond() * MICROSECONDS_PER_SECOND + start.getLong(MICRO_OF_SECOND),
                (int) round((start.getNano() % PICOSECONDS_PER_NANOSECOND) * PICOSECONDS_PER_NANOSECOND, (int) (MAX_PRECISION - precision)));
    }

    public static LongTimestamp longTimestamp(long epochSecond, long fractionInPicos)
    {
        return new LongTimestamp(
                multiplyExact(epochSecond, MICROSECONDS_PER_SECOND) + fractionInPicos / PICOSECONDS_PER_MICROSECOND,
                (int) (fractionInPicos % PICOSECONDS_PER_MICROSECOND));
    }
}
