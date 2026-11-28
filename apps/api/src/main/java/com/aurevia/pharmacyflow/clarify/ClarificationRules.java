package com.aurevia.pharmacyflow.clarify;

import java.time.Duration;
import java.time.Instant;
import java.util.Set;

import com.aurevia.authz.specialty.SpecialtyRoles;
import com.aurevia.pharmacyflow.clarify.ClarificationContract.State;
import com.aurevia.pharmacyflow.clarify.ClarificationContract.Thread;

/**
 * Bounded permission and overdue policy for clarification threads.
 *
 * <p>The checks reuse the shared specialty role vocabulary so the clarification
 * API cannot drift into a second permission model. Roles are supplied by the
 * caller from the authoritative active-membership fold of the current
 * organization and are re-derived on every command.</p>
 */
public final class ClarificationRules {

    private ClarificationRules() {
    }

    /**
     * Decide whether the active roles permit opening a request.
     *
     * @param roles role keys active in the current organization
     * @return {@code true} only for pharmacy staff
     */
    public static boolean mayOpen(Set<String> roles) {
        return roles.contains(SpecialtyRoles.PHARMACY_STAFF);
    }

    /**
     * Decide whether the active roles permit answering a request.
     *
     * @param roles role keys active in the current organization
     * @return {@code true} only for a clinician
     */
    public static boolean mayAnswer(Set<String> roles) {
        return roles.contains(SpecialtyRoles.CLINICIAN);
    }

    /**
     * Decide whether the active roles may read a clarification queue.
     *
     * @param roles role keys active in the current organization
     * @return {@code true} for either thread party role
     */
    public static boolean mayView(Set<String> roles) {
        return roles.contains(SpecialtyRoles.PHARMACY_STAFF) || roles.contains(SpecialtyRoles.CLINICIAN);
    }

    /**
     * Decide whether a subject is one of the two thread parties.
     *
     * @param subject authenticated subject
     * @param thread  thread being read
     * @return {@code true} for the requester or the addressee
     */
    public static boolean isParty(String subject, Thread thread) {
        return subject.equals(thread.requesterSubject()) || subject.equals(thread.prescriberSubject());
    }

    /**
     * Decide whether an unanswered thread is older than the configured limit.
     *
     * <p>The comparison is strict: a thread at exactly the limit is not yet
     * overdue, and an answered thread is never overdue however old it becomes.</p>
     *
     * @param thread       thread to evaluate
     * @param now          current instant from the injected clock
     * @param overdueAfter configured unanswered age limit
     * @return {@code true} only for a request older than the limit
     */
    public static boolean isOverdue(Thread thread, Instant now, Duration overdueAfter) {
        return thread.state() == State.OPEN && thread.openedAt().isBefore(now.minus(overdueAfter));
    }
}
