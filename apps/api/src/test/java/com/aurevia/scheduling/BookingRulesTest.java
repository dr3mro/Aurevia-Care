package com.aurevia.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

/** ADM-01 booking rules: approved defaults and bounded overrides. */
class BookingRulesTest {

    private static Map<String, String> settings(String... pairs) {
        Map<String, String> map = new HashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            map.put(pairs[index], pairs[index + 1]);
        }
        return map;
    }

    private static BookingRules rules(Map<String, String> settings) {
        return BookingRules.from(key -> Optional.ofNullable(settings.get(key)));
    }

    @Test
    void defaultsApplyWhenNoSettingIsApproved() {
        BookingRules defaults = BookingRules.defaults();
        assertEquals(0, defaults.minNoticeMinutes());
        assertEquals(365, defaults.maxAdvanceDays());
        assertEquals(15, defaults.holdMinutes());
        assertEquals(defaults, rules(Map.of()));
    }

    @Test
    void approvedSettingsOverrideTheDefaults() {
        BookingRules configured = rules(settings(
                BookingRules.MIN_NOTICE_KEY, "120",
                BookingRules.MAX_ADVANCE_KEY, "30",
                BookingRules.HOLD_MINUTES_KEY, "5"));
        assertEquals(120, configured.minNoticeMinutes());
        assertEquals(30, configured.maxAdvanceDays());
        assertEquals(5, configured.holdMinutes());
    }

    @Test
    void outOfRangeAndNonNumericValuesFallBackInsteadOfBlockingBooking() {
        assertEquals(0, rules(settings(BookingRules.MIN_NOTICE_KEY, "-5")).minNoticeMinutes());
        assertEquals(0, rules(settings(BookingRules.MIN_NOTICE_KEY, "10081")).minNoticeMinutes());
        assertEquals(365, rules(settings(BookingRules.MAX_ADVANCE_KEY, "0")).maxAdvanceDays());
        assertEquals(365, rules(settings(BookingRules.MAX_ADVANCE_KEY, "3651")).maxAdvanceDays());
        assertEquals(15, rules(settings(BookingRules.HOLD_MINUTES_KEY, "0")).holdMinutes());
        assertEquals(15, rules(settings(BookingRules.HOLD_MINUTES_KEY, "121")).holdMinutes());
        assertEquals(0, rules(settings(BookingRules.MIN_NOTICE_KEY, "later")).minNoticeMinutes());
        assertEquals(30, rules(settings(BookingRules.MAX_ADVANCE_KEY, " 30 ")).maxAdvanceDays());
    }

    @Test
    void boundaryValuesAreAccepted() {
        assertEquals(10080, rules(settings(BookingRules.MIN_NOTICE_KEY, "10080")).minNoticeMinutes());
        assertEquals(1, rules(settings(BookingRules.MAX_ADVANCE_KEY, "1")).maxAdvanceDays());
        assertEquals(120, rules(settings(BookingRules.HOLD_MINUTES_KEY, "120")).holdMinutes());
    }
}
