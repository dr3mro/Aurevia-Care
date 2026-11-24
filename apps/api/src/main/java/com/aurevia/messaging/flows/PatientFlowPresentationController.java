package com.aurevia.messaging.flows;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.messaging.flows.PatientFlowContract.FlowPresentation;
import com.aurevia.messaging.flows.PatientFlowContract.PresentationQuery;

/**
 * Thin HTTP entry point for server-authored patient-flow presentation (PAT-01–03,
 * MSG-01–07, UX-01).
 *
 * <p>All tenant derivation, active-membership checks, locale/platform validation
 * and approved-notice policy live in
 * {@link com.aurevia.messaging.flows.PatientFlowPresentationService}.
 * This adapter only binds request parameters and returns the resulting value;
 * failures are rendered by the shared API error handler.</p>
 */
@RestController
@RequestMapping("/api/v1/patient-flow")
public class PatientFlowPresentationController {

    private final PatientFlowPresentationService service;

    /**
     * Create the controller with the injected presentation service.
     *
     * @param service server-authoritative patient-flow presentation policy
     */
    public PatientFlowPresentationController(PatientFlowPresentationService service) {
        this.service = service;
    }

    /**
     * Resolve the presentation policy for the authenticated patient.
     *
     * @param locale   client locale wire key
     * @param platform client platform wire key
     * @param timeZone client IANA time-zone identifier
     * @return the server-authored presentation policy
     */
    @GetMapping("/presentation")
    public FlowPresentation presentation(@RequestParam(required = false) String locale,
                                         @RequestParam(required = false) String platform,
                                         @RequestParam(required = false) String timeZone) {
        // Let the service reject missing values with the shared INVALID_INPUT
        // contract instead of Spring's generic 400 for a missing parameter.
        return service.presentation(new PresentationQuery(locale, platform, timeZone));
    }

    /**
     * Resolve the approved, clinical-detail-free text for one notice kind.
     *
     * @param kind   notice kind wire key
     * @param locale client locale wire key
     * @return the approved generic text
     */
    @GetMapping("/notices/{kind}")
    public NoticeTextView notificationText(@PathVariable String kind,
                                           @RequestParam String locale) {
        return new NoticeTextView(service.notificationText(kind, locale));
    }

    /**
     * The generic notification text returned to the patient client.
     *
     * @param text approved clinical-detail-free text
     * @return the immutable text view
     */
    public record NoticeTextView(String text) {
    }
}
