/**
 * Follow-up suggestions, authorized overrides, explicit confirmations and care-team tasks for an encounter.
 *
 * <p>Protected commands re-derive active membership and roles and book only through the scheduling boundary on explicit confirmation. It must not auto-book a suggestion, accept a tenant from the caller, or weaken the appointment booking invariants.</p>
 */
package com.aurevia.scheduling.followup;
