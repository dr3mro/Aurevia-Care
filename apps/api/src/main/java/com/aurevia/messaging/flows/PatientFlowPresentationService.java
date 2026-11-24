package com.aurevia.messaging.flows;

import java.time.Clock;
import java.time.ZoneId;

import org.springframework.stereotype.Service;

import com.aurevia.api.ApiException;
import com.aurevia.messaging.flows.PatientFlowContract.AppLocale;
import com.aurevia.messaging.flows.PatientFlowContract.AppPlatform;
import com.aurevia.messaging.flows.PatientFlowContract.FlowAudit;
import com.aurevia.messaging.flows.PatientFlowContract.FlowPresentation;
import com.aurevia.messaging.flows.PatientFlowContract.NoticeKind;
import com.aurevia.messaging.flows.PatientFlowContract.PresentationQuery;
import com.aurevia.tenant.TenantContext;

/**
 * Server-authoritative presentation policy for accessible patient flows on
 * iOS and Android (PAT-01–03, MSG-01–07, UX-01).
 *
 * <p>Every protected method derives the organization and subject from the
 * request context and re-checks active membership, so a deactivation denies
 * the next request. Locale, platform and time zone are validated on the
 * server; the resulting direction, platform wording, approved policy version
 * and generic-notice catalog are all derived here and never trusted from the
 * client. Notifications carry fixed, clinical-detail-free text.</p>
 */
@Service
public class PatientFlowPresentationService {

    /** Accessibility conformance target published to every patient client. */
    public static final String ACCESSIBILITY_PROFILE = "WCAG-2.2-AA";

    private final FlowPolicyStore store;
    private final Clock clock;

    /**
     * Create the service.
     *
     * @param store tenant-scoped policy store
     * @param clock application clock for audit timestamps
     */
    public PatientFlowPresentationService(FlowPolicyStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /**
     * Resolve the presentation policy for the authenticated patient.
     *
     * @param query client-supplied locale, platform and time-zone context
     * @return the server-authored presentation policy
     * @throws ApiException on missing context, denial or invalid input
     */
    public FlowPresentation presentation(PresentationQuery query) {
        TenantContext context = requireContext();
        if (query == null) {
            throw invalid(context, "FLOW_PRESENTATION");
        }
        requireMembership(context, "FLOW_PRESENTATION");
        AppLocale locale = parseLocale(query.locale(), context);
        AppPlatform platform = parsePlatform(query.platform(), context);
        if (!isValidTimeZone(query.timeZone())) {
            throw invalid(context, "FLOW_PRESENTATION");
        }
        Integer version = store.policyVersion(context.organizationId(), locale, platform);
        FlowPresentation presentation = new FlowPresentation(
                context.organizationId(), locale, platform, locale.rtl(), locale.direction(),
                platform.settingsLabel(locale), platform.secureStorageLabel(locale),
                version == null ? 0 : version, clock.instant(), query.timeZone(),
                ACCESSIBILITY_PROFILE, store.approvedNotices(context.organizationId()));
        audit(context, "FLOW_PRESENTATION", "SUCCESS");
        return presentation;
    }

    /**
     * Resolve the approved generic text for a notice kind and locale.
     *
     * @param kindValue notice kind wire key
     * @param localeValue locale wire key
     * @return clinical-detail-free notification text
     * @throws ApiException on missing context, denial or invalid input
     */
    public String notificationText(String kindValue, String localeValue) {
        TenantContext context = requireContext();
        requireMembership(context, "FLOW_NOTICE");
        NoticeKind kind;
        AppLocale locale;
        try {
            kind = NoticeKind.from(kindValue);
            locale = AppLocale.from(localeValue);
        } catch (IllegalArgumentException exception) {
            throw invalid(context, "FLOW_NOTICE");
        }
        // The published approved set and the notification resolver must agree:
        // a kind the tenant has not approved cannot be sent as generic text.
        if (!store.approvedNotices(context.organizationId()).contains(kind)) {
            throw invalid(context, "FLOW_NOTICE");
        }
        audit(context, "FLOW_NOTICE", "SUCCESS");
        return kind.text(locale);
    }

    private TenantContext requireContext() {
        TenantContext context = TenantContext.current();
        if (context == null) {
            // No organization or subject exists to key a tenant-scoped audit
            // row; the authentication interceptor records unauthenticated
            // attempts upstream (OPS-02).
            throw new ApiException(401, "UNAUTHENTICATED", "authentication required");
        }
        return context;
    }

    private void requireMembership(TenantContext context, String action) {
        if (!store.hasActiveMembership(context.organizationId(), context.subject())) {
            throw deny(context, action, 403, "FORBIDDEN", "no active membership");
        }
    }

    private AppLocale parseLocale(String value, TenantContext context) {
        try {
            return AppLocale.from(value);
        } catch (IllegalArgumentException exception) {
            throw invalid(context, "FLOW_PRESENTATION");
        }
    }

    private AppPlatform parsePlatform(String value, TenantContext context) {
        try {
            return AppPlatform.from(value);
        } catch (IllegalArgumentException exception) {
            throw invalid(context, "FLOW_PRESENTATION");
        }
    }

    private boolean isValidTimeZone(String value) {
        if (value == null || value.isEmpty()) {
            return false;
        }
        // Accept only identifiers from the platform's authoritative IANA set, so
        // an invented zone or a generic GMT offset cannot masquerade as a region.
        return ZoneId.getAvailableZoneIds().contains(value);
    }

    private ApiException invalid(TenantContext context, String action) {
        audit(context, action, "INVALID_INPUT");
        return new ApiException(422, "INVALID_INPUT", "invalid presentation input");
    }

    private ApiException deny(TenantContext context, String action, int status, String code, String message) {
        audit(context, action, code);
        return new ApiException(status, code, message);
    }

    private void audit(TenantContext context, String action, String outcome) {
        store.appendAudit(new FlowAudit(context.organizationId(), context.subject(),
                context.purpose(), action, context.correlationId(), outcome, clock.instant()));
    }
}
