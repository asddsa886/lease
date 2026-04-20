package com.atguigu.lease.web.app.assistant.service.tool;

import com.atguigu.lease.common.exception.LeaseException;
import com.atguigu.lease.common.result.ResultCodeEnum;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAdjusters;
import java.util.Date;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class AssistantDateTimeParser {

    static final ZoneId DEFAULT_ZONE = ZoneId.of("Asia/Shanghai");

    private static final Clock DEFAULT_CLOCK = Clock.system(DEFAULT_ZONE);

    private static final List<DateTimeFormatter> DATE_TIME_FORMATTERS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm:ss"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd HH:mm"),
            DateTimeFormatter.ISO_LOCAL_DATE_TIME
    );

    private static final List<DateTimeFormatter> DATE_FORMATTERS = List.of(
            DateTimeFormatter.ofPattern("yyyy-MM-dd"),
            DateTimeFormatter.ofPattern("yyyy/MM/dd"),
            DateTimeFormatter.ISO_LOCAL_DATE
    );

    private static final Pattern ABSOLUTE_DATE_PATTERN = Pattern.compile(
            "(?:(\\d{4})[-/.年])?(\\d{1,2})[-/.月](\\d{1,2})(?:日)?"
    );
    private static final Pattern WEEKDAY_PATTERN = Pattern.compile(
            "(下下周|下周|这周|本周|周)([一二三四五六日天])"
    );
    private static final Pattern COLON_TIME_PATTERN = Pattern.compile(
            "(凌晨|早上|上午|中午|下午|晚上)?\\s*(\\d{1,2}):(\\d{1,2})(?::(\\d{1,2}))?"
    );
    private static final Pattern CLOCK_TIME_PATTERN = Pattern.compile(
            "(凌晨|早上|上午|中午|下午|晚上)?\\s*([零〇一二两三四五六七八九十\\d]{1,3})点(?:\\s*(半|一刻|三刻|[零〇一二两三四五六七八九十\\d]{1,3}分?))?"
    );

    private AssistantDateTimeParser() {
    }

    static Date parseDateTime(String value) {
        return parseDateTime(value, DEFAULT_CLOCK);
    }

    static Date parseDateTime(String value, Clock clock) {
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            throw new LeaseException(ResultCodeEnum.PARAM_ERROR);
        }

        Date instantDate = tryParseInstant(normalized);
        if (instantDate != null) {
            return instantDate;
        }

        LocalDateTime explicitDateTime = tryParseExplicitDateTime(normalized);
        if (explicitDateTime != null) {
            return toDate(explicitDateTime, clock);
        }

        LocalDate date = resolveDate(normalized, clock);
        LocalTime time = resolveTime(normalized);
        if (date == null && time != null) {
            date = defaultDateForTime(time, clock);
        }
        if (date != null && time != null) {
            return toDate(LocalDateTime.of(date, time), clock);
        }

        throw invalidDateTime();
    }

    static Date parseDate(String value) {
        return parseDate(value, DEFAULT_CLOCK);
    }

    static Date parseDate(String value, Clock clock) {
        String normalized = normalize(value);
        if (normalized.isBlank()) {
            throw new LeaseException(ResultCodeEnum.PARAM_ERROR);
        }

        LocalDate explicitDate = tryParseExplicitDate(normalized);
        if (explicitDate != null) {
            return toDate(explicitDate.atStartOfDay(), clock);
        }

        LocalDate date = resolveDate(normalized, clock);
        if (date != null) {
            return toDate(date.atStartOfDay(), clock);
        }

        throw invalidDate();
    }

    private static Date tryParseInstant(String value) {
        try {
            return Date.from(Instant.parse(value));
        } catch (DateTimeParseException ignored) {
        }
        try {
            return Date.from(OffsetDateTime.parse(value).toInstant());
        } catch (DateTimeParseException ignored) {
        }
        return null;
    }

    private static LocalDateTime tryParseExplicitDateTime(String value) {
        for (DateTimeFormatter formatter : DATE_TIME_FORMATTERS) {
            try {
                return LocalDateTime.parse(value, formatter);
            } catch (DateTimeParseException ignored) {
            }
        }
        return null;
    }

    private static LocalDate tryParseExplicitDate(String value) {
        for (DateTimeFormatter formatter : DATE_FORMATTERS) {
            try {
                return LocalDate.parse(value, formatter);
            } catch (DateTimeParseException ignored) {
            }
        }
        return null;
    }

    private static LocalDate resolveDate(String value, Clock clock) {
        LocalDate today = LocalDate.now(clock);

        if (value.contains("大后天")) {
            return today.plusDays(3);
        }
        if (value.contains("后天")) {
            return today.plusDays(2);
        }
        if (value.contains("明天")) {
            return today.plusDays(1);
        }
        if (value.contains("今天")) {
            return today;
        }

        Matcher weekdayMatcher = WEEKDAY_PATTERN.matcher(value);
        if (weekdayMatcher.find()) {
            DayOfWeek dayOfWeek = toDayOfWeek(weekdayMatcher.group(2));
            LocalDate startOfWeek = today.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            int weeksToAdd = switch (weekdayMatcher.group(1)) {
                case "下周" -> 1;
                case "下下周" -> 2;
                default -> 0;
            };
            LocalDate candidate = startOfWeek.plusWeeks(weeksToAdd).with(TemporalAdjusters.nextOrSame(dayOfWeek));
            return weeksToAdd == 0 && candidate.isBefore(today) ? candidate.plusWeeks(1) : candidate;
        }

        Matcher absoluteDateMatcher = ABSOLUTE_DATE_PATTERN.matcher(value);
        if (!absoluteDateMatcher.find()) {
            return null;
        }

        Integer year = absoluteDateMatcher.group(1) == null ? null : Integer.parseInt(absoluteDateMatcher.group(1));
        int month = Integer.parseInt(absoluteDateMatcher.group(2));
        int day = Integer.parseInt(absoluteDateMatcher.group(3));
        LocalDate candidate = year == null
                ? LocalDate.of(today.getYear(), month, day)
                : LocalDate.of(year, month, day);
        return year == null && candidate.isBefore(today) ? candidate.plusYears(1) : candidate;
    }

    private static LocalTime resolveTime(String value) {
        Matcher colonTimeMatcher = COLON_TIME_PATTERN.matcher(value);
        if (colonTimeMatcher.find()) {
            return buildTime(
                    colonTimeMatcher.group(1),
                    Integer.parseInt(colonTimeMatcher.group(2)),
                    Integer.parseInt(colonTimeMatcher.group(3)),
                    colonTimeMatcher.group(4) == null ? 0 : Integer.parseInt(colonTimeMatcher.group(4))
            );
        }

        Matcher clockTimeMatcher = CLOCK_TIME_PATTERN.matcher(value);
        if (clockTimeMatcher.find()) {
            return buildTime(
                    clockTimeMatcher.group(1),
                    parseNumber(clockTimeMatcher.group(2)),
                    parseMinute(clockTimeMatcher.group(3)),
                    0
            );
        }

        return value.contains("中午") ? LocalTime.NOON : null;
    }

    private static LocalDate defaultDateForTime(LocalTime time, Clock clock) {
        LocalDate today = LocalDate.now(clock);
        LocalDateTime now = LocalDateTime.now(clock);
        return LocalDateTime.of(today, time).isAfter(now) ? today : today.plusDays(1);
    }

    private static LocalTime buildTime(String period, int hour, int minute, int second) {
        int normalizedHour = normalizeHour(period, hour);
        if (normalizedHour < 0 || normalizedHour > 23 || minute < 0 || minute > 59 || second < 0 || second > 59) {
            throw new LeaseException(ResultCodeEnum.PARAM_ERROR);
        }
        return LocalTime.of(normalizedHour, minute, second);
    }

    private static int normalizeHour(String period, int hour) {
        if (hour == 24) {
            return 0;
        }
        if (period == null || period.isBlank()) {
            return hour;
        }
        return switch (period) {
            case "凌晨", "早上", "上午" -> hour == 12 ? 0 : hour;
            case "中午" -> hour >= 1 && hour <= 10 ? hour + 12 : hour;
            case "下午", "晚上" -> hour < 12 ? hour + 12 : hour;
            default -> hour;
        };
    }

    private static int parseMinute(String value) {
        if (value == null || value.isBlank()) {
            return 0;
        }
        return switch (value) {
            case "半" -> 30;
            case "一刻" -> 15;
            case "三刻" -> 45;
            default -> parseNumber(value.replace("分", ""));
        };
    }

    private static int parseNumber(String value) {
        if (value == null || value.isBlank()) {
            throw new LeaseException(ResultCodeEnum.PARAM_ERROR);
        }

        String normalized = value.replace("两", "二").replace("〇", "零");
        if (normalized.chars().allMatch(Character::isDigit)) {
            return Integer.parseInt(normalized);
        }
        if ("十".equals(normalized)) {
            return 10;
        }
        if (normalized.contains("十")) {
            int index = normalized.indexOf('十');
            int tens = index == 0 ? 1 : parseDigit(normalized.charAt(0));
            int ones = index == normalized.length() - 1 ? 0 : parseDigit(normalized.charAt(index + 1));
            return tens * 10 + ones;
        }
        if (normalized.length() == 1) {
            return parseDigit(normalized.charAt(0));
        }
        throw new LeaseException(ResultCodeEnum.PARAM_ERROR);
    }

    private static int parseDigit(char value) {
        return switch (value) {
            case '零', '〇' -> 0;
            case '一' -> 1;
            case '二' -> 2;
            case '三' -> 3;
            case '四' -> 4;
            case '五' -> 5;
            case '六' -> 6;
            case '七' -> 7;
            case '八' -> 8;
            case '九' -> 9;
            default -> throw new LeaseException(ResultCodeEnum.PARAM_ERROR);
        };
    }

    private static DayOfWeek toDayOfWeek(String value) {
        return switch (value) {
            case "一" -> DayOfWeek.MONDAY;
            case "二" -> DayOfWeek.TUESDAY;
            case "三" -> DayOfWeek.WEDNESDAY;
            case "四" -> DayOfWeek.THURSDAY;
            case "五" -> DayOfWeek.FRIDAY;
            case "六" -> DayOfWeek.SATURDAY;
            case "日", "天" -> DayOfWeek.SUNDAY;
            default -> throw new LeaseException(ResultCodeEnum.PARAM_ERROR);
        };
    }

    private static Date toDate(LocalDateTime dateTime, Clock clock) {
        return Date.from(dateTime.atZone(clock.getZone()).toInstant());
    }

    private static LeaseException invalidDateTime() {
        return new LeaseException(
                ResultCodeEnum.PARAM_ERROR.getCode(),
                "时间格式不正确，请使用 yyyy-MM-dd HH:mm:ss 或常见中文时间，例如明天下午3点"
        );
    }

    private static LeaseException invalidDate() {
        return new LeaseException(
                ResultCodeEnum.PARAM_ERROR.getCode(),
                "日期格式不正确，请使用 yyyy-MM-dd 或常见中文日期，例如明天、下周一"
        );
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim()
                .replace('：', ':')
                .replace('／', '/')
                .replace('－', '-')
                .replace('—', '-')
                .replace('\u3000', ' ')
                .replace("礼拜", "周")
                .replace("星期", "周")
                .replace("周天", "周日")
                .replace("下个周", "下周")
                .replace("这个周", "这周")
                .replace("今日", "今天")
                .replace("号", "日")
                .replaceAll("\\s+", " ")
                .trim();
    }
}
