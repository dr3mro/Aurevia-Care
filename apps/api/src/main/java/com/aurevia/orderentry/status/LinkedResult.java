package com.aurevia.orderentry.status;

import java.time.Instant;

import com.aurevia.exchange.result.ResultModel.ResultKind;

/**
 * The result projected onto an order's status (LAB-02, RAD-01, XCH-03, XCH-04).
 *
 * <p>The result keeps its own identity and disposition and links back to the
 * tenant-local order identifier through the enclosing status view. Reusing the
 * exchange result disposition avoids a second vocabulary: a critical link only
 * marks that the dedicated XCH-05 critical-result workflow still owns
 * verification, escalation, read-back and acknowledgement. Linking here never
 * advances that workflow.</p>
 *
 * @param resultId                 opaque tenant-local result identifier
 * @param kind                     result disposition reused from the exchange result model
 * @param critical                 whether the result is a critical finding
 * @param criticalWorkflowReference reference used by the separate critical workflow, or {@code null}
 * @param linkedAt                 server instant the result was linked
 */
public record LinkedResult(String resultId, ResultKind kind, boolean critical,
                           String criticalWorkflowReference, Instant linkedAt) {
}
