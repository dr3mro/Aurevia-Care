package com.aurevia.messaging.appointments;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** S-G3-01: push text is generic and never carries appointment detail (PAT-01, OPS-02). */
class GenericPushNoticeTest {

    @Test
    void statusChangeUsesTheSingleGenericTemplate() {
        GenericPushNotice notice = GenericPushNotice.appointmentUpdate();
        assertEquals(GenericPushNotice.GENERIC_TITLE, notice.title());
        assertEquals(GenericPushNotice.GENERIC_BODY, notice.body());
        assertEquals(GenericPushNotice.GENERIC_LINK, notice.deepLink());
        assertEquals(GenericPushNotice.appointmentUpdate(), notice);
    }

    @Test
    void genericBodyContainsNoDigitsOrPatientDetail() {
        GenericPushNotice notice = GenericPushNotice.appointmentUpdate();
        assertFalse(notice.body().matches(".*\\d.*"));
        assertFalse(notice.reveals("MAIN"));
        assertFalse(notice.reveals("patient-1"));
        assertFalse(notice.reveals("Dr A"));
        assertFalse(notice.reveals("booked"));
    }

    @Test
    void revealsDetectsTitleBodyAndDeepLinkLeaks() {
        GenericPushNotice notice = GenericPushNotice.appointmentUpdate();
        assertTrue(notice.reveals(GenericPushNotice.GENERIC_TITLE));
        assertTrue(notice.reveals(GenericPushNotice.GENERIC_BODY));
        assertTrue(notice.reveals("orevia"));
    }

    @Test
    void blankAndNullDetailsAreNotLeaks() {
        GenericPushNotice notice = GenericPushNotice.appointmentUpdate();
        assertFalse(notice.reveals(null));
        assertFalse(notice.reveals(""));
        assertFalse(notice.reveals("   "));
    }
}
