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
}
