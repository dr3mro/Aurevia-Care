package com.aurevia.radflow.report;

import java.util.List;

import com.aurevia.radiology.Interpretation;

/**
 * One immutable delivered-report aggregate.
 *
 * <p>The aggregate holds the append-only interpretation revisions and one
 * acknowledgment receipt per addendum. A signed interpretation and every later
 * addendum are delivered to the referring clinician named on the aggregate; an
 * addendum never edits a signed revision. Lists are frozen at construction and
 * returned defensively so a projection cannot mutate the kernel's state.</p>
 *
 * @return this record
 */
public record ReportDelivery(String organizationId, String orderId, String patientId, String referrer,
                             int version, List<Interpretation> revisions, List<AddendumReceipt> addenda) {

    /** Freeze the caller's lists so later mutation cannot alter this aggregate. */
    public ReportDelivery {
        revisions = List.copyOf(revisions);
        addenda = List.copyOf(addenda);
    }

    /** @return an immutable snapshot of the interpretation revisions */
    @Override
    public List<Interpretation> revisions() {
        return List.copyOf(revisions);
    }

    /** @return an immutable snapshot of the addendum receipts */
    @Override
    public List<AddendumReceipt> addenda() {
        return List.copyOf(addenda);
    }

    /**
     * Return the newest interpretation revision.
     *
     * @return the current signed revision
     */
    public Interpretation current() {
        return revisions.get(revisions.size() - 1);
    }

    /**
     * Whether at least one addendum still awaits acknowledgment.
     *
     * @return {@code true} when an open receipt exists
     */
    public boolean hasOpenAddendum() {
        return addenda.stream().anyMatch(AddendumReceipt::isOpen);
    }
}
