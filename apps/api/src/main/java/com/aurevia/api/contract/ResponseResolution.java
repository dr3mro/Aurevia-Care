package com.aurevia.api.contract;

/**
 * Decision taken for one keyed write request, with the committed response when replayable.
 *
 * @param decision proceed, replay or conflict, using the existing contract vocabulary
 * @param response the frozen first response for {@code REPLAY}, or {@code null}
 */
public record ResponseResolution(IdempotencyLedger.Decision decision, StoredResponse response) {
}
