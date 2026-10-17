package io.haifa.agent.superharness.server.schedule;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.zone.ZoneRules;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Five-field cron expression evaluated in a caller-supplied time zone.
 *
 * <p>This is a narrow product-layer port of the frozen DeerFlow scheduler's local-time evaluation
 * (which delegates to {@code croniter}). Fields are interpreted as wall-clock time in the task's
 * declared zone and the resulting instant is normalized to UTC. Gap, overlap and DST-offset
 * changes resolve with the same algorithm as the frozen {@code croniter}: a nonexistent wall-clock
 * time is nudged forward to the first existing minute, an ambiguous time picks the occurrence that
 * is a successor of {@code now} (the second occurrence once the first has passed), and when an
 * offset change occurs the alternative-offset occurrence is compared and the nearer successor is
 * chosen.
 *
 * <p>Supported field syntax: {@code *}, {@code ?} (day fields), single values, names
 * ({@code JAN}..{@code DEC}, {@code SUN}..{@code SAT}), {@code a-b} ranges, comma lists and
 * {@code /step} increments. Day-of-month additionally supports {@code L}, {@code a-L} and
 * {@code nW}/{@code Wn}; day-of-week supports {@code L<dow>} (last weekday of the month) and
 * {@code <dow>#n} (n-th weekday of the month). Day of month and day of week follow the standard
 * cron rule: when both are restricted the day matches if either matches, otherwise both must
 * match.
 */
final class CronExpression {
    private static final int MAX_DAYS = 366 * 5 + 1;

    private static final Map<String, Integer> MONTH_NAMES = nameMap(
            "JAN", 1, "FEB", 2, "MAR", 3, "APR", 4, "MAY", 5, "JUN", 6,
            "JUL", 7, "AUG", 8, "SEP", 9, "OCT", 10, "NOV", 11, "DEC", 12);
    private static final Map<String, Integer> DAY_NAMES = nameMap(
            "SUN", 0, "MON", 1, "TUE", 2, "WED", 3, "THU", 4, "FRI", 5, "SAT", 6);

    private final boolean[] minutes;
    private final boolean[] hours;
    private final boolean[] monthDays;
    private final boolean[] months;
    private final boolean[] weekDays;
    private final List<Predicate<LocalDate>> monthDaySpecials;
    private final List<Predicate<LocalDate>> weekDaySpecials;
    private final boolean dayOfMonthRestricted;
    private final boolean dayOfWeekRestricted;
    private final boolean hourWildcard;
    private final int[] hourValues;
    private final int[] minuteValues;

    private CronExpression(SimpleField minute, SimpleField hour, DayOfMonth dayOfMonth,
            SimpleField month, DayOfWeek dayOfWeek) {
        this.minutes = minute.present;
        this.hours = hour.present;
        this.monthDays = dayOfMonth.present;
        this.months = month.present;
        this.weekDays = dayOfWeek.present;
        this.monthDaySpecials = dayOfMonth.specials;
        this.weekDaySpecials = dayOfWeek.specials;
        this.dayOfMonthRestricted = !dayOfMonth.wildcard;
        this.dayOfWeekRestricted = !dayOfWeek.wildcard;
        this.hourWildcard = hour.wildcard;
        this.hourValues = valuesOf(this.hours);
        this.minuteValues = valuesOf(this.minutes);
    }

    static CronExpression parse(String expression) {
        List<String> parts = List.of(expression.trim().split("\\s+"));
        if (parts.size() != 5 || parts.stream().anyMatch(String::isEmpty)) {
            throw new ScheduleValidationException("Cron expression must contain exactly 5 fields");
        }
        SimpleField minute = parseSimpleField(parts.get(0), 0, 59, Map.of());
        SimpleField hour = parseSimpleField(parts.get(1), 0, 23, Map.of());
        DayOfMonth dayOfMonth = parseDayOfMonth(parts.get(2));
        SimpleField month = parseSimpleField(parts.get(3), 1, 12, MONTH_NAMES);
        DayOfWeek dayOfWeek = parseDayOfWeek(parts.get(4));
        return new CronExpression(minute, hour, dayOfMonth, month, dayOfWeek);
    }

    Instant next(Instant now, ZoneId zone) {
        LocalDateTime cursor = LocalDateTime.ofInstant(now, zone).withSecond(0).withNano(0);
        LocalDateTime candidate = nextMatch(cursor);
        return reconcile(candidate, now, zone);
    }

    /** The first matching wall-clock time strictly after {@code after}, searched up to 5 years. */
    private LocalDateTime nextMatch(LocalDateTime after) {
        LocalDate startDate = after.toLocalDate();
        for (int dayOffset = 0; dayOffset < MAX_DAYS; dayOffset++) {
            LocalDate date = startDate.plusDays(dayOffset);
            if (!months[date.getMonthValue()] || !matchesDay(date)) {
                continue;
            }
            for (int hour : hourValues) {
                for (int minute : minuteValues) {
                    LocalDateTime candidate = date.atTime(hour, minute);
                    if (candidate.isAfter(after)) {
                        return candidate;
                    }
                }
            }
        }
        throw new ScheduleValidationException("Cron expression has no occurrence in the next 5 years");
    }

    /**
     * Ports the frozen {@code croniter} DST reconciliation: resolve the wall-clock candidate, then
     * look for a nearer occurrence under the alternative UTC offset when the offset changed.
     */
    private Instant reconcile(LocalDateTime candidate, Instant now, ZoneId zone) {
        ZoneRules rules = zone.getRules();
        Added added = addZoneInfo(candidate, now, rules);
        while (!added.exists && (!added.instant.isAfter(now) || hourWildcard)) {
            candidate = nextMatch(candidate);
            added = addZoneInfo(candidate, now, rules);
        }
        long deltaSeconds = rules.getOffset(added.instant).getTotalSeconds()
                - rules.getOffset(now).getTotalSeconds();
        if (deltaSeconds == 0) {
            return added.instant;
        }
        LocalDateTime altCursor = LocalDateTime.ofInstant(now, zone).withSecond(0).withNano(0)
                .plusSeconds(deltaSeconds);
        Instant altAware = addZoneInfo(nextMatch(altCursor), now, rules).instant;
        if (!altAware.isAfter(now)) {
            return added.instant;
        }
        return added.instant.isAfter(altAware) ? altAware : added.instant;
    }

    private record Added(Instant instant, boolean exists) {
    }

    private static Added addZoneInfo(LocalDateTime local, Instant now, ZoneRules rules) {
        List<ZoneOffset> offsets = rules.getValidOffsets(local);
        if (offsets.isEmpty()) {
            LocalDateTime probe = local;
            while (rules.getValidOffsets(probe).isEmpty()) {
                probe = probe.plusMinutes(1);
            }
            List<ZoneOffset> resolved = rules.getValidOffsets(probe);
            return new Added(probe.atOffset(resolved.get(0)).toInstant(), false);
        }
        if (offsets.size() == 1) {
            return new Added(local.atOffset(offsets.get(0)).toInstant(), true);
        }
        Instant earlier = local.atOffset(offsets.get(0)).toInstant();
        Instant later = local.atOffset(offsets.get(1)).toInstant();
        if (earlier.isAfter(later)) {
            Instant swap = earlier;
            earlier = later;
            later = swap;
        }
        return new Added(earlier.isAfter(now) ? earlier : later, true);
    }

    private boolean matchesDay(LocalDate date) {
        boolean dayOfMonthMatches = dayOfMonthRestricted && matchesMonthDay(date);
        boolean dayOfWeekMatches = dayOfWeekRestricted && matchesWeekDay(date);
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

    private boolean matchesMonthDay(LocalDate date) {
        if (monthDays[date.getDayOfMonth()]) {
            return true;
        }
        for (Predicate<LocalDate> special : monthDaySpecials) {
            if (special.test(date)) {
                return true;
            }
        }
        return false;
    }

    private boolean matchesWeekDay(LocalDate date) {
        if (weekDays[date.getDayOfWeek().getValue() % 7]) {
            return true;
        }
        for (Predicate<LocalDate> special : weekDaySpecials) {
            if (special.test(date)) {
                return true;
            }
        }
        return false;
    }

    // ------------------------------------------------------------ day parsing

    private static DayOfMonth parseDayOfMonth(String token) {
        String value = token.trim();
        if (value.isEmpty()) {
            throw new ScheduleValidationException("Cron field is empty");
        }
        if (isWildcard(value)) {
            return new DayOfMonth(true, new boolean[32], List.of());
        }
        String[] items = value.split(",", -1);
        boolean hasWeekday = false;
        boolean hasOther = false;
        for (String item : items) {
            if (item.isEmpty()) {
                throw new ScheduleValidationException("Cron field is empty");
            }
            if (isWildcard(item)) {
                return new DayOfMonth(true, new boolean[32], List.of());
            }
            if (isNearestWeekday(item)) {
                hasWeekday = true;
            } else {
                hasOther = true;
            }
        }
        if (hasWeekday && hasOther) {
            throw new ScheduleValidationException(
                    "Cron day-of-month cannot mix a nearest-weekday expression with other terms: " + token);
        }
        boolean[] present = new boolean[32];
        List<Predicate<LocalDate>> specials = new ArrayList<>();
        boolean any = false;
        for (String item : items) {
            if (item.equalsIgnoreCase("L")) {
                specials.add(date -> date.getDayOfMonth() == date.lengthOfMonth());
                any = true;
                continue;
            }
            if (isNearestWeekday(item)) {
                specials.add(nearestWeekday(weekdayTarget(item)));
                any = true;
                continue;
            }
            int dash = item.indexOf('-');
            if (dash > 0 && item.substring(dash + 1).equalsIgnoreCase("L")) {
                int low = parseNumber(item.substring(0, dash));
                if (low < 1 || low > 31) {
                    throw new ScheduleValidationException("Cron field value out of range: " + item);
                }
                specials.add(date -> date.getDayOfMonth() >= low);
                any = true;
                continue;
            }
            parseItem(item, 1, 31, Map.of(), present);
            any = true;
        }
        if (!any) {
            throw new ScheduleValidationException("Cron field is empty: " + token);
        }
        return new DayOfMonth(false, present, specials);
    }

    private static DayOfWeek parseDayOfWeek(String token) {
        String value = token.trim();
        if (value.isEmpty()) {
            throw new ScheduleValidationException("Cron field is empty");
        }
        if (isWildcard(value)) {
            return new DayOfWeek(true, new boolean[7], List.of());
        }
        String[] items = value.split(",", -1);
        boolean[] raw = new boolean[8];
        List<Predicate<LocalDate>> specials = new ArrayList<>();
        boolean hasSpecial = false;
        boolean hasPlain = false;
        for (String item : items) {
            if (item.isEmpty()) {
                throw new ScheduleValidationException("Cron field is empty");
            }
            if (isWildcard(item)) {
                continue;
            }
            int hash = item.indexOf('#');
            if (hash > 0) {
                int weekday = resolveWeekday(item.substring(0, hash));
                int nth = parseNumber(item.substring(hash + 1));
                if (nth < 1 || nth > 5) {
                    throw new ScheduleValidationException("Cron day-of-week occurrence is out of range: " + item);
                }
                specials.add(nthWeekday(weekday, nth));
                hasSpecial = true;
                continue;
            }
            if (item.length() > 1 && (item.charAt(0) == 'L' || item.charAt(0) == 'l')) {
                specials.add(lastWeekday(resolveWeekday(item.substring(1))));
                hasSpecial = true;
                continue;
            }
            parseItem(item, 0, 7, DAY_NAMES, raw);
            hasPlain = true;
        }
        if (hasSpecial && hasPlain) {
            throw new ScheduleValidationException(
                    "Cron day-of-week cannot mix a positional expression with plain values: " + token);
        }
        if (hasSpecial) {
            return new DayOfWeek(false, new boolean[7], specials);
        }
        boolean[] present = new boolean[7];
        boolean any = false;
        for (int index = 0; index <= 7; index++) {
            if (raw[index]) {
                present[index % 7] = true;
                any = true;
            }
        }
        if (!any) {
            throw new ScheduleValidationException("Cron field is empty: " + token);
        }
        return new DayOfWeek(false, present, List.of());
    }

    private static int resolveWeekday(String token) {
        Integer named = DAY_NAMES.get(token.toUpperCase(java.util.Locale.ROOT));
        int weekday = named == null ? parseNumber(token) : named;
        if (weekday < 0 || weekday > 7) {
            throw new ScheduleValidationException("Cron field value out of range: " + token);
        }
        return weekday % 7;
    }

    private static Predicate<LocalDate> nthWeekday(int weekday, int nth) {
        return date -> date.getDayOfWeek().getValue() % 7 == weekday
                && (date.getDayOfMonth() - 1) / 7 + 1 == nth;
    }

    private static Predicate<LocalDate> lastWeekday(int weekday) {
        return date -> date.getDayOfWeek().getValue() % 7 == weekday
                && date.getDayOfMonth() + 7 > date.lengthOfMonth();
    }

    private static Predicate<LocalDate> nearestWeekday(int targetDay) {
        return date -> {
            int target = Math.min(targetDay, date.lengthOfMonth());
            LocalDate candidate = date.withDayOfMonth(target);
            if (candidate.getDayOfWeek().getValue() == 6) {
                candidate = candidate.minusDays(1);
                if (candidate.getMonthValue() != date.getMonthValue()
                        || candidate.getYear() != date.getYear()) {
                    candidate = date.withDayOfMonth(Math.min(target + 2, date.lengthOfMonth()));
                }
            } else if (candidate.getDayOfWeek().getValue() == 7) {
                candidate = candidate.plusDays(1);
                if (candidate.getMonthValue() != date.getMonthValue()
                        || candidate.getYear() != date.getYear()) {
                    candidate = date.withDayOfMonth(target - 2);
                }
            }
            return date.equals(candidate);
        };
    }

    private static boolean isNearestWeekday(String item) {
        String upper = item.toUpperCase(java.util.Locale.ROOT);
        if (upper.length() < 2 || !upper.endsWith("W") && !upper.startsWith("W")) {
            return false;
        }
        try {
            weekdayTarget(item);
            return true;
        } catch (ScheduleValidationException failure) {
            return false;
        }
    }

    private static int weekdayTarget(String item) {
        String upper = item.toUpperCase(java.util.Locale.ROOT);
        String digits = upper.endsWith("W") ? upper.substring(0, upper.length() - 1) : upper.substring(1);
        int target = parseNumber(digits);
        if (target < 1 || target > 31) {
            throw new ScheduleValidationException(
                    "Cron nearest-weekday target is out of range: " + item);
        }
        return target;
    }

    private static boolean isWildcard(String token) {
        return "*".equals(token) || "?".equals(token);
    }

    // -------------------------------------------------------- simple fields

    private static SimpleField parseSimpleField(String token, int min, int max, Map<String, Integer> names) {
        boolean wildcard = "*".equals(token.trim());
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
        return new SimpleField(wildcard, present);
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
        for (long value = low; value <= high; value += step) {
            present[(int) value] = true;
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

    private record SimpleField(boolean wildcard, boolean[] present) {
    }

    private record DayOfMonth(boolean wildcard, boolean[] present, List<Predicate<LocalDate>> specials) {
    }

    private record DayOfWeek(boolean wildcard, boolean[] present, List<Predicate<LocalDate>> specials) {
    }
}
