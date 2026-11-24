package com.aurevia.exchange.trend;

import org.springframework.stereotype.Component;

/**
 * Action policy for XCH-07 clinical trend data.
 *
 * <p>Clinical reads and observation writes require the clinical role: tenant
 * administrators and privacy auditors do not gain clinical access by virtue of
 * their administrative or audit role (IAM-01, IAM-06). Defining the
 * comparability policy is an administrative configuration act and is separated
 * from reading or writing clinical values (ADM-01).</p>
 */
@Component
public class TrendActionPolicy {

    /**
     * Whether a role may read a patient's trend.
     *
     * @param role role key active in the tenant
     * @return {@code true} for a clinician
     */
    public boolean allowsRead(String role) {
        return "clinician".equals(role);
    }

    /**
     * Whether a role may record an observation.
     *
     * @param role role key active in the tenant
     * @return {@code true} for a clinician
     */
    public boolean allowsWrite(String role) {
        return "clinician".equals(role);
    }

    /**
     * Whether a role may define comparability policy.
     *
     * @param role role key active in the tenant
     * @return {@code true} for a tenant administrator
     */
    public boolean allowsPolicyWrite(String role) {
        return "tenant_admin".equals(role);
    }
}
