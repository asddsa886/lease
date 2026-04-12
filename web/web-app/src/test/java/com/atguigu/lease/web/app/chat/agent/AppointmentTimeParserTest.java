package com.atguigu.lease.web.app.chat.agent;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class AppointmentTimeParserTest {

    @Test
    void parse_shouldSupportDayOfMonthWithExplicitHour() {
        AppointmentTimeParser.ParsedAppointmentTime parsed =
                AppointmentTimeParser.parse("11号的11点", ZoneId.systemDefault());

        assertNotNull(parsed);
        assertEquals("11:00", parsed.displayText().substring(11));
    }

    @Test
    void parse_shouldSupportRelativeDateWithDeBeforeExplicitHour() {
        AppointmentTimeParser.ParsedAppointmentTime parsed =
                AppointmentTimeParser.parse("明天的12点", ZoneId.systemDefault());

        assertNotNull(parsed);
        assertEquals("12:00", parsed.displayText().substring(11));
    }
}
