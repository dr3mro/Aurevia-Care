package com.aurevia.patient.identity;

/**
 * A site-verified patient account (IAM-05).
 *
 * <p>The account is the login identity, distinct from any patient chart. One
 * account may hold several independently verified chart links. The subject is
 * unique inside the tenant and never inferred from demographics.</p>
 *
 * @param id          opaque account identifier
 * @param subject     verified login subject, unique within the tenant
 * @param displayName human-readable account label (synthetic in tests)
 * @return this record
 */
public record PatientAccount(String id, String subject, String displayName) {
}
