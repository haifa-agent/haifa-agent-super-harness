package io.haifa.agent.superharness.server.schedule;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Five-field cron expression evaluated in a caller-supplied time zone.
 *
 * <p>This is a narrow product-layer port of the frozen DeerFlow scheduler's local-time evaluation
 * (which delegates to {@code croniter}): fields are always interpreted as wall-clock time in the
 * task's declared zone and the resulting instant is normalized to UTC. Java's default gap/fold
 * resolution ({@code LocalDateTime.atZone}) matches the pre-transition offset choice of an aware
 * local construction, so spring-forward gaps and fall-back folds are deterministic.
 *
 * <p>Supported field syntax: {@code *}, single values, names ({@code JAN}..{@code DEC},
 * {@code SUN}..{@code SAT}), {@code a-b} ranges, comma lists and {@code /step} increments. Day of
 * month and day of week follow the standard cron rule: when both are restricted the day matches if
 * either matches, otherwise both must match.
 */
final class CronExpression {
    private static final int MAX_DAYS = 366 * 5 + 1;

    private static final Map<String, Integer> MONTH_NAMES = nameMap(
            "JAN", 1, "FEB", 2, "MAR", 3, "APR", 4, "MAY", 5, "JUN", 6,
            "JUL", 7, "AUG", 8, "SEP", 9, "OCT", 10, "NOV", 11, "DEC", 12);
    private static final Map<String, Integer> DAY_NAMES = nameMap(
            "SUN", 0, "MON", 1, "TUE", 2, "WED", 3, "THU", 4, "FRI", 5, "SAT", 6);

    private final boolean[] minutes = new boolean[60];
    private final boolean[] hours = new boolean[24];
    private final boolean[] monthDays = new boolean[32];
    private final boolean[] months = new boolean[13];
    private final boolean[] weekDays = new boolean[7];
    private final boolean dayOfMonthRestricted;
    private final boolean dayOfWeekRestricted;
    private final int[] hourValues;
    private final int[] minuteValues;

    private CronExpression(boolean[] minutes, boolean[] hours, boolean[] monthDays, boolean[] months,
            boolean[] weekDays, boolean dayOfMonthRestricted, boolean dayOfWeekRestricted) {
        System.arraycopy(minutes, 0, this.minutes, 0, 60);
        System.arraycopy(hours, 0, this.hours, 0, 24);
        System.arraycopy(monthDays, 0, this.monthDays, 0, 32);
        System.arraycopy(months, 0, this.months, 0, 13);
        System.arraycopy(weekDays, 0, this.weekDays, 0, 7);
        this.dayOfMonthRestricted = dayOfMonthRestricted;
        this.dayOfWeekRestricted = dayOfWeekRestricted;
        this.hourValues = valuesOf(this.hours);
        this.minuteValues = valuesOf(this.minutes);
    }

    static CronExpression parse(String expression) {
        List<String> parts = List.of(expression.trim().split("\\s+"));
        if (parts.size() != 5 || parts.stream().anyMatch(String::isEmpty)) {
            throw new ScheduleValidationException("Cron expression must contain exactly 5 fields");
        }
        Field minute = parseField(parts.get(0), 0, 59, Map.of());
        Field hour = parseField(parts.get(1), 0, 23, Map.of());
        Field dayOfMonth = parseField(parts.get(2), 1, 31, Map.of());
        Field month = parseField(parts.get(3), 1, 12, MONTH_NAMES);
        Field dayOfWeek = parseField(parts.get(4), 0, 7, DAY_NAMES);
        boolean[] weekDays = new boolean[7];
        for (int value = 0; value <= 7; value++) {
            if (dayOfWeek.present[value]) {
                weekDays[value % 7] = true;
            }
        }
        return new CronExpression(minute.present, hour.present, dayOfMonth.present, month.present,
                weekDays, !dayOfMonth.wildcard, !dayOfWeek.wildcard);
    }

    Instant next(Instant now, ZoneId zone) {
        LocalDateTime cursor = LocalDateTime.ofInstant(now, zone).withSecond(0).withNano(0);
        LocalDate startDate = cursor.toLocalDate();
        for (int dayOffset = 0; dayOffset < MAX_DAYS; dayOffset++) {
            LocalDate date = startDate.plusDays(dayOffset);
            if (!months[date.getMonthValue()] || !matchesDay(date)) {
                continue;
            }
            for (int hour : hourValues) {
                for (int minute : minuteValues) {
                    LocalDateTime candidate = date.atTime(hour, minute);
                    if (dayOffset == 0 && !candidate.isAfter(cursor)) {
                        continue;
                    }
                    Instant instant = candidate.atZone(zone).toInstant();
                    if (instant.isAfter(now)) {
                        return instant;
                    }
                }
            }
        }
        throw new ScheduleValidationException("Cron expression has no occurrence in the next 5 years");
    }

    private boolean matchesDay(LocalDate date) {
        boolean dayOfMonthMatches = dayOfMonthRestricted && monthDays[date.getDayOfMonth()];
        boolean dayOfWeekMatches = dayOfWeekRestricted && weekDays[date.getDayOfWeek().getValue() % 7];
        if (dayOfMonthRestricted && dayOfWeekRestricted) {
            return dayOfMonthMatches || dayOfWeekMatches;
        }
        if (dayOfMonthRestricted) {
            return dayOfMonthMatches;
        }
        if (dayOfWeekRestricted) {
            return dayOfWeekMatches;
        }
        return true;
    }

    private static Field parseField(String token, int min, int max, Map<String, Integer> names) {
        boolean wildcard = "*".equals(token);
        boolean[] present = new boolean[max + 1];
        for (String item : token.split(",", -1)) {
            parseItem(item, min, max, names, present);
        }
        boolean any = false;
        for (boolean value : present) {
            any |= value;
        }
        if (!any) {
            throw new ScheduleValidationException("Cron field is empty: " + token);
        }
        return new Field(wildcard, present);
    }

    private static void parseItem(String item, int min, int max, Map<String, Integer> names,
            boolean[] present) {
        if (item.isEmpty()) {
            throw new ScheduleValidationException("Cron field is empty");
        }
        String base = item;
        int step = 1;
        int slash = item.indexOf('/');
        if (slash >= 0) {
            base = item.substring(0, slash);
            String rawStep = item.substring(slash + 1);
            step = parseNumber(rawStep);
            if (step < 1) {
                throw new ScheduleValidationException("Cron step must be a positive integer: " + item);
            }
        }
        int low;
        int high;
        if (base.equals("*")) {
            low = min;
            high = max;
        } else {
            int dash = base.indexOf('-');
            if (dash >= 0) {
                low = resolve(base.substring(0, dash), min, max, names);
                high = resolve(base.substring(dash + 1), min, max, names);
                if (low > high) {
                    throw new ScheduleValidationException("Cron range is inverted: " + item);
                }
            } else {
                low = resolve(base, min, max, names);
                // A bare "a/n" increments from a to the field maximum, matching croniter.
                high = slash >= 0 ? max : low;
            }
        }
        for (int value = low; value <= high; value += step) {
            present[value] = true;
        }
    }

    private static int resolve(String token, int min, int max, Map<String, Integer> names) {
        if (token.isEmpty()) {
            throw new ScheduleValidationException("Cron field is empty");
        }
        Integer named = names.get(token.toUpperCase(java.util.Locale.ROOT));
        int value;
        if (named != null) {
            value = named;
        } else {
            value = parseNumber(token);
        }
        if (value < min || value > max) {
            throw new ScheduleValidationException("Cron field value out of range: " + token);
        }
        return value;
    }

    private static int parseNumber(String token) {
        try {
            return Integer.parseInt(token);
        } catch (NumberFormatException failure) {
            throw new ScheduleValidationException("Cron field is not a number: " + token);
        }
    }

    private static int[] valuesOf(boolean[] present) {
        List<Integer> values = new ArrayList<>();
        for (int value = 0; value < present.length; value++) {
            if (present[value]) {
                values.add(value);
            }
        }
        if (values.isEmpty()) {
            throw new ScheduleValidationException("Cron field has no values");
        }
        int[] result = new int[values.size()];
        for (int index = 0; index < result.length; index++) {
            result[index] = values.get(index);
        }
        return result;
    }

    private static Map<String, Integer> nameMap(Object... pairs) {
        Map<String, Integer> names = new HashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            names.put((String) pairs[index], (Integer) pairs[index + 1]);
        }
        return Map.copyOf(names);
    }

    private record Field(boolean wildcard, boolean[] present) {
    }
}
