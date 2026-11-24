package com.aurevia.messaging.flows;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.messaging.flows.PatientFlowContract.AppLocale;
import com.aurevia.messaging.flows.PatientFlowContract.AppPlatform;
import com.aurevia.messaging.flows.PatientFlowContract.FlowPresentation;
import com.aurevia.messaging.flows.PatientFlowContract.NoticeKind;
import com.aurevia.messaging.flows.PatientFlowContract.PresentationQuery;
import com.aurevia.tenant.TenantContext;

/**
 * Unit acceptance for S-G3-11 at the domain boundary.
 *
 * <p>Every subject, organization and policy version below is synthetic. The
 * tests prove the server is authoritative for locale, direction, platform
 * wording, generic-notice text and tenant scoping, and that the app cannot
 * obtain a policy without an active membership or with invalid input.</p>
 */
class PatientFlowPresentationTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String SUBJECT = "patient-user";
    private static final String OTHER_SUBJECT = "other-user";
    private static final Instant NOW = Instant.parse("2026-11-19T12:00:00Z");

    private InMemoryFlowPolicyStore store;
    private PatientFlowPresentationService service;

    /** Set up a synthetic tenant policy and an active patient member. */
    @BeforeEach
    void setUp() {
        store = new InMemoryFlowPolicyStore();
        service = new PatientFlowPresentationService(store, Clock.fixed(NOW, ZoneOffset.UTC));
        TenantContext.set(new TenantContext(ORG, SUBJECT, "PATIENT_FLOW_PRESENTATION", "corr-1"));
        store.addMember(ORG, SUBJECT, true);
        store.addMember(OTHER_ORG, OTHER_SUBJECT, true);
        store.setPolicyVersion(ORG, AppLocale.EN, AppPlatform.ANDROID, 7);
        store.setPolicyVersion(ORG, AppLocale.AR, AppPlatform.IOS, 9);
        store.setPolicyVersion(OTHER_ORG, AppLocale.EN, AppPlatform.ANDROID, 99);
        store.setApprovedNotices(ORG, List.of(NoticeKind.NEW_MESSAGE, NoticeKind.REAUTH_REQUIRED));
    }

    /** Clear the thread-bound context after each test. */
    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void contractParsesWireKeysAndFailsOnUnknownValues() {
        assertEquals(AppLocale.AR, AppLocale.from("ar"));
        assertEquals(AppPlatform.IOS, AppPlatform.from("ios"));
        assertEquals(NoticeKind.NEW_MESSAGE, NoticeKind.from("new_message"));
        assertThrows(IllegalArgumentException.class, () -> AppLocale.from("fr"));
        assertThrows(IllegalArgumentException.class, () -> AppPlatform.from("web"));
        assertThrows(IllegalArgumentException.class, () -> NoticeKind.from("diagnosis_result"));
    }

    @Test
    void contractMapsDirectionAndGenericTextWithoutClinicalDetail() {
        assertTrue(AppLocale.AR.rtl());
        assertFalse(AppLocale.EN.rtl());
        assertEquals("rtl", AppLocale.AR.direction());
        assertEquals("ltr", AppLocale.EN.direction());
        assertEquals("Protected by iOS Keychain", AppPlatform.IOS.secureStorageLabel(AppLocale.EN));
        assertEquals("محمي بواسطة Keychain", AppPlatform.IOS.secureStorageLabel(AppLocale.AR));
        assertEquals("Protected by Android Keystore", AppPlatform.ANDROID.secureStorageLabel(AppLocale.EN));
        assertEquals("محمي بواسطة Android Keystore", AppPlatform.ANDROID.secureStorageLabel(AppLocale.AR));
        assertEquals("Open iOS Settings", AppPlatform.IOS.settingsLabel(AppLocale.EN));
        assertEquals("افتح إعدادات iOS", AppPlatform.IOS.settingsLabel(AppLocale.AR));
        assertEquals("Open Android system settings", AppPlatform.ANDROID.settingsLabel(AppLocale.EN));
        assertEquals("افتح إعدادات Android", AppPlatform.ANDROID.settingsLabel(AppLocale.AR));
        assertEquals("You have a new message.", NoticeKind.NEW_MESSAGE.text(AppLocale.EN));
        assertEquals("لديك تعليمات جديدة.", NoticeKind.NEW_INSTRUCTION.text(AppLocale.AR));
    }

    @Test
    void everyNoticeKindHasApprovedTextInBothLocalesAndParsesFromWire() {
        for (NoticeKind kind : NoticeKind.values()) {
            assertEquals(kind, NoticeKind.from(kind.wire()));
            for (AppLocale locale : AppLocale.values()) {
                String text = kind.text(locale);
                assertNotNull(text);
                assertFalse(text.isBlank());
                assertFalse(text.toLowerCase(java.util.Locale.ROOT).contains("patient"));
            }
        }
        assertEquals(AppLocale.EN, AppLocale.from("en"));
        assertEquals(AppPlatform.ANDROID, AppPlatform.from("android"));
    }

    @Test
    void presentationIsTenantScopedLocalizedAndAudited() {
        FlowPresentation result = service.presentation(new PresentationQuery("en", "android", "Europe/London"));
        assertEquals(ORG, result.organizationId());
        assertEquals(AppLocale.EN, result.locale());
        assertEquals(AppPlatform.ANDROID, result.platform());
        assertFalse(result.rtl());
        assertEquals("ltr", result.direction());
        assertEquals("Protected by Android Keystore", result.secureStorageLabel());
        assertEquals("Open Android system settings", result.settingsLabel());
        assertEquals(7, result.policyVersion());
        assertEquals(NOW, result.issuedAt());
        assertEquals("Europe/London", result.timeZoneContext());
        assertEquals("WCAG-2.2-AA", result.accessibilityProfile());
        assertEquals(List.of(NoticeKind.NEW_MESSAGE, NoticeKind.REAUTH_REQUIRED), result.noticeKinds());
        assertThrows(UnsupportedOperationException.class, () -> result.noticeKinds().add(NoticeKind.SUPPORT_REPLY));
        assertEquals("SUCCESS", store.auditEvents(ORG).get(0).outcome());
        assertEquals("PATIENT_FLOW_PRESENTATION", store.auditEvents(ORG).get(0).purpose());
        assertEquals("corr-1", store.auditEvents(ORG).get(0).correlationId());
        assertEquals("FLOW_PRESENTATION", store.auditEvents(ORG).get(0).action());
    }

    @Test
    void arabicIosPresentationMirrorsDirectionAndUsesIosWording() {
        FlowPresentation result = service.presentation(new PresentationQuery("ar", "ios", "Asia/Riyadh"));
        assertTrue(result.rtl());
        assertEquals("rtl", result.direction());
        assertEquals("افتح إعدادات iOS", result.settingsLabel());
        assertEquals("محمي بواسطة Keychain", result.secureStorageLabel());
        assertEquals(9, result.policyVersion());
        assertEquals(NOW, result.issuedAt());
    }

    @Test
    void missingPolicyVersionDefaultsToZeroAndNeverUsesAnotherTenantVersion() {
        FlowPresentation fallback = service.presentation(new PresentationQuery("en", "ios", "UTC"));
        assertEquals(0, fallback.policyVersion());
        // OTHER_ORG has version 99 for en/android; the active tenant has none.
        TenantContext.set(new TenantContext(ORG, OTHER_SUBJECT, "PATIENT_FLOW_PRESENTATION", "corr-2"));
        ApiException denied = assertThrows(ApiException.class,
                () -> service.presentation(new PresentationQuery("en", "android", "UTC")));
        assertEquals(403, denied.status());
    }

    @Test
    void presentationFailsClosedForMissingMembership() {
        store.addMember(ORG, SUBJECT, false);
        ApiException denied = assertThrows(ApiException.class,
                () -> service.presentation(new PresentationQuery("en", "ios", "UTC")));
        assertEquals(403, denied.status());
        assertEquals("FORBIDDEN", denied.code());
    }

    @Test
    void presentationRequiresAnAuthenticatedContext() {
        TenantContext.clear();
        ApiException denied = assertThrows(ApiException.class,
                () -> service.presentation(new PresentationQuery("en", "ios", "UTC")));
        assertEquals(401, denied.status());
        assertEquals("UNAUTHENTICATED", denied.code());
    }

    @Test
    void presentationRejectsInvalidLocalePlatformAndTimeZone() {
        assertInvalid(new PresentationQuery("fr", "ios", "UTC"));
        assertInvalid(new PresentationQuery("en", "web", "UTC"));
        assertInvalid(new PresentationQuery("en", "ios", "Mars/Phobos"));
        assertInvalid(new PresentationQuery("en", "ios", ""));
        assertInvalid(new PresentationQuery("en", "ios", null));
    }

    @Test
    void notificationTextIsGenericValidatedAndTenantApproved() {
        assertEquals("You have a new message.", service.notificationText("new_message", "en"));
        assertEquals("يرجى تسجيل الدخول مرة أخرى للمتابعة.", service.notificationText("reauth_required", "ar"));
        assertEquals("INVALID_INPUT", assertThrows(ApiException.class,
                () -> service.notificationText("new_instruction", "en")).code());
        assertEquals("INVALID_INPUT", assertThrows(ApiException.class,
                () -> service.notificationText("diagnosis_result", "en")).code());
        assertEquals("INVALID_INPUT", assertThrows(ApiException.class,
                () -> service.notificationText("new_message", "fr")).code());
    }

    @Test
    void nullQueryIsInvalidInput() {
        ApiException exception = assertThrows(ApiException.class, () -> service.presentation(null));
        assertEquals(422, exception.status());
        assertEquals("INVALID_INPUT", exception.code());
    }

    @Test
    void notificationTextFailsClosedWithoutMembershipOrContext() {
        store.addMember(ORG, SUBJECT, false);
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.notificationText("new_message", "en")).status());
        TenantContext.clear();
        assertEquals(401, assertThrows(ApiException.class,
                () -> service.notificationText("new_message", "en")).status());
    }

    @Test
    void auditRecordsDenialsAndInvalidInput() {
        ApiException denial = assertThrows(ApiException.class,
                () -> service.presentation(new PresentationQuery("fr", "ios", "UTC")));
        assertEquals(422, denial.status());
        assertNotNull(store.auditEvents(ORG));
        assertEquals("INVALID_INPUT", store.auditEvents(ORG).get(0).outcome());
    }

    private void assertInvalid(PresentationQuery query) {
        ApiException exception = assertThrows(ApiException.class, () -> service.presentation(query));
        assertEquals(422, exception.status());
        assertEquals("INVALID_INPUT", exception.code());
    }
}
