package com.atguigu.lease.web.app.chat.agent;

import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class AppointmentTimeParser {

    private static final Pattern RELATIVE_PATTERN = Pattern.compile("(今天|明天|后天)(上午|中午|下午|晚上)?(?:(\\d{1,2})(?:点|:|：)(\\d{1,2})?)?");
    private static final Pattern ABSOLUTE_DATE_TIME_PATTERN = Pattern.compile("(\\d{4})[-/年](\\d{1,2})[-/月](\\d{1,2})(?:日)?(?:\\s*(上午|中午|下午|晚上))?(?:\\s*(\\d{1,2})(?:点|:|：)(\\d{1,2})?)?");
    private static final Pattern MONTH_DAY_PATTERN = Pattern.compile("(\\d{1,2})月(\\d{1,2})(?:日|号)?(?:\\s*(上午|中午|下午|晚上))?(?:\\s*(\\d{1,2})(?:点|:|：)(\\d{1,2})?)?");
    private static final DateTimeFormatter DISPLAY_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private AppointmentTimeParser() {
    }

    public static ParsedAppointmentTime parse(String text, ZoneId zoneId) {
        if (!StringUtils.hasText(text)) {
            return null;
        }

        String normalized = normalize(text);
        LocalDateTime now = LocalDateTime.now(zoneId);

        ParsedAppointmentTime parsed = parseRelative(normalized, now, zoneId);
        if (parsed != null) {
            return parsed;
        }

        parsed = parseAbsolute(normalized, now, zoneId);
        if (parsed != null) {
            return parsed;
        }

        return parseMonthDay(normalized, now, zoneId);
    }

    private static ParsedAppointmentTime parseRelative(String normalized, LocalDateTime now, ZoneId zoneId) {
        Matcher matcher = RELATIVE_PATTERN.matcher(normalized);
        if (!matcher.find()) {
            return null;
        }

        LocalDate baseDate = switch (matcher.group(1)) {
            case "今天" -> now.toLocalDate();
            case "明天" -> now.toLocalDate().plusDays(1);
            case "后天" -> now.toLocalDate().plusDays(2);
            default -> now.toLocalDate();
        };

        Integer explicitHour = parseInteger(matcher.group(3));
        Integer explicitMinute = parseInteger(matcher.group(4));
        int hour = resolveHour(matcher.group(2), explicitHour);
        int minute = explicitMinute == null ? 0 : explicitMinute;
        return toParsed(baseDate.atTime(hour, minute), now, zoneId);
    }

    private static ParsedAppointmentTime parseAbsolute(String normalized, LocalDateTime now, ZoneId zoneId) {
        Matcher matcher = ABSOLUTE_DATE_TIME_PATTERN.matcher(normalized);
        if (!matcher.find()) {
            return null;
        }

        int year = Integer.parseInt(matcher.group(1));
        int month = Integer.parseInt(matcher.group(2));
        int day = Integer.parseInt(matcher.group(3));
        Integer explicitHour = parseInteger(matcher.group(5));
        Integer explicitMinute = parseInteger(matcher.group(6));
        int hour = resolveHour(matcher.group(4), explicitHour);
        int minute = explicitMinute == null ? 0 : explicitMinute;
        return toParsed(LocalDate.of(year, month, day).atTime(hour, minute), now, zoneId);
    }

    private static ParsedAppointmentTime parseMonthDay(String normalized, LocalDateTime now, ZoneId zoneId) {
        Matcher matcher = MONTH_DAY_PATTERN.matcher(normalized);
        if (!matcher.find()) {
            return null;
        }

        int month = Integer.parseInt(matcher.group(1));
        int day = Integer.parseInt(matcher.group(2));
        Integer explicitHour = parseInteger(matcher.group(4));
        Integer explicitMinute = parseInteger(matcher.group(5));
        int hour = resolveHour(matcher.group(3), explicitHour);
        int minute = explicitMinute == null ? 0 : explicitMinute;

        LocalDate candidateDate = LocalDate.of(now.getYear(), month, day);
        if (candidateDate.atTime(hour, minute).isBefore(now.plusMinutes(5))) {
            candidateDate = candidateDate.plusYears(1);
        }
        return toParsed(candidateDate.atTime(hour, minute), now, zoneId);
    }

    private static ParsedAppointmentTime toParsed(LocalDateTime dateTime, LocalDateTime now, ZoneId zoneId) {
        if (dateTime.isBefore(now.plusMinutes(5))) {
            return null;
        }
        return new ParsedAppointmentTime(
                Date.from(dateTime.atZone(zoneId).toInstant()),
                dateTime.format(DISPLAY_FORMATTER)
        );
    }

    private static Integer parseInteger(String value) {
        if (!StringUtils.hasText(value)) {
            return null;
        }
        return Integer.parseInt(value);
    }

    private static int resolveHour(String period, Integer explicitHour) {
        if (explicitHour != null) {
            int hour = explicitHour;
            if (StringUtils.hasText(period)) {
                String normalizedPeriod = period.trim().toLowerCase(Locale.ROOT);
                if (("下午".equals(normalizedPeriod) || "晚上".equals(normalizedPeriod)) && hour < 12) {
                    hour += 12;
                }
                if ("中午".equals(normalizedPeriod) && hour < 11) {
                    hour += 12;
                }
            }
            return hour;
        }
        if (!StringUtils.hasText(period)) {
            return 10;
        }
        return switch (period) {
            case "上午" -> 10;
            case "中午" -> 12;
            case "下午" -> 15;
            case "晚上" -> 19;
            default -> 10;
        };
    }

    private static String normalize(String text) {
        return text.trim()
                .replace("预约", "")
                .replace("看房", "")
                .replace("这个房源", "")
                .replace("这个", "")
                .replace("房源", "")
                .replace("安排", "")
                .replace("一下", "")
                .replaceAll("\\s+", "");
    }

    public record ParsedAppointmentTime(Date date, String displayText) {
    }
}
