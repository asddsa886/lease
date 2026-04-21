package com.atguigu.lease.web.app.assistant.service.tool;

import com.atguigu.lease.common.exception.LeaseException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AssistantDateTimeParserTest {

    private static final ZoneId ZONE_ID = AssistantDateTimeParser.DEFAULT_ZONE;
    private static final Clock FIXED_CLOCK = Clock.fixed(
            Instant.parse("2026-04-20T02:00:00Z"),
            ZONE_ID
    );
    private static final DateTimeFormatter DATE_TIME_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final DateTimeFormatter DATE_FORMATTER =
            DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Test
    void shouldParseNaturalLanguageAppointmentTimeInLocalTimezone() {
        Date parsed = AssistantDateTimeParser.parseDateTime("明天下午12点", FIXED_CLOCK);

        assertThat(formatDateTime(parsed)).isEqualTo("2026-04-21 12:00:00");
    }

    @Test
    void shouldParseWeekdayNaturalLanguageTime() {
        Date parsed = AssistantDateTimeParser.parseDateTime("周六下午3点", FIXED_CLOCK);

        assertThat(formatDateTime(parsed)).isEqualTo("2026-04-25 15:00:00");
    }

    @Test
    void shouldParseStandardSlashDateTimeFormat() {
        Date parsed = AssistantDateTimeParser.parseDateTime("2026/04/21 15:30", FIXED_CLOCK);

        assertThat(formatDateTime(parsed)).isEqualTo("2026-04-21 15:30:00");
    }

    @Test
    void shouldParseTimeOnlyWithLocalTodaySemantics() {
        Date parsed = AssistantDateTimeParser.parseDateTime("15:30", FIXED_CLOCK);

        assertThat(formatDateTime(parsed)).isEqualTo("2026-04-20 15:30:00");
    }

    @Test
    void shouldRejectIsoInstantInputOutsideSupportedRange() {
        assertThatThrownBy(() -> AssistantDateTimeParser.parseDateTime("2026-04-21T04:00:00Z", FIXED_CLOCK))
                .isInstanceOf(LeaseException.class);
    }

    @Test
    void shouldParseNaturalLanguageDateForLeaseOrder() {
        Date parsed = AssistantDateTimeParser.parseDate("下周一", FIXED_CLOCK);

        assertThat(formatDate(parsed)).isEqualTo("2026-04-27");
    }

    private String formatDateTime(Date value) {
        return LocalDateTime.ofInstant(value.toInstant(), ZONE_ID).format(DATE_TIME_FORMATTER);
    }

    private String formatDate(Date value) {
        return LocalDateTime.ofInstant(value.toInstant(), ZONE_ID).toLocalDate().format(DATE_FORMATTER);
    }
}
