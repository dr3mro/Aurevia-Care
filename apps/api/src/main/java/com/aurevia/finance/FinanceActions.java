package com.aurevia.finance;

import org.springframework.stereotype.Component;

/**
 * Bounded action matrix for the FIN-01 slice (PRD-04).
 *
 * <p>Finance data is not clinical data: finance staff, tenant administrators
 * and privacy auditors may read invoices or reconciliation totals for audit and
 * disclosure review, and only finance staff may post or void entries. Approvals
 * are checked separately as a
 * distinct action so the approver must be a different active member. The matrix
 * is paired with a fresh membership and role check on every protected request
 * in the domain service.</p>
 */
@Component
public class FinanceActions {

    /** Create an itemized draft invoice. */
    public static final String CREATE = "FINANCE_INVOICE_CREATE";
    /** Issue a draft invoice, making its balance payable. */
    public static final String ISSUE = "FINANCE_INVOICE_ISSUE";
    /** Record a payment against an issued invoice. */
    public static final String PAYMENT = "FINANCE_PAYMENT_RECORD";
    /** Record an approved credit against an issued invoice. */
    public static final String CREDIT = "FINANCE_CREDIT_RECORD";
    /** Record an approved refund against an issued invoice. */
    public static final String REFUND = "FINANCE_REFUND_RECORD";
    /** Void an invoice with a recorded reason. */
    public static final String VOID = "FINANCE_INVOICE_VOID";
    /** Approve an adjustment or void as a separate authorized member. */
    public static final String APPROVE = "FINANCE_APPROVE";
    /** Read an invoice, its lines and its ledger trail. */
    public static final String READ = "FINANCE_INVOICE_READ";
    /** Read the finance-only daily reconciliation report. */
    public static final String RECONCILE = "FINANCE_RECONCILE";

    /**
     * Decide whether a role may perform an action.
     *
     * @param action action key from this class
     * @param role   role key active in the current organization
     * @return {@code true} when the role is permitted
     */
    public boolean allows(String action, String role) {
        return switch (action) {
            case CREATE, ISSUE, PAYMENT, CREDIT, REFUND, VOID -> "finance".equals(role);
            case APPROVE -> "finance".equals(role) || "tenant_admin".equals(role);
            case READ, RECONCILE -> "finance".equals(role) || "tenant_admin".equals(role)
                    || "privacy_auditor".equals(role);
            default -> false;
        };
    }
}
