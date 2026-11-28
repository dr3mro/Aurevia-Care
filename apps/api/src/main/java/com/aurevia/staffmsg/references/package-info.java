/**
 * Typed staff-message reference values, validation, append-only audit and identifier-only outbox events.
 *
 * <p>The audit key uses the reference identifier and outcome without patient data, and the outbox digest covers a fixed phrase. It must not create a clinical attestation, carry a patient identifier, or authenticate an actor from request input.</p>
 */
package com.aurevia.staffmsg.references;
