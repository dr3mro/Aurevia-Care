package com.aurevia.labflow.rejection;

import com.aurevia.lab.accession.AccessionState;

/**
 * Result of recollecting a rejected specimen (LAB-01).
 *
 * @param specimenId         opaque identifier of the new recollection specimen
 * @param replacesAccessionId identifier of the rejected specimen this one replaces
 * @param sourceOrderRef     source order shared with the rejected specimen
 * @param state              initial lifecycle state of the recollection
 * @param version            initial optimistic-concurrency version
 * @return this record
 */
public record RecollectionOutcome(String specimenId, String replacesAccessionId, String sourceOrderRef,
                                  AccessionState state, int version) {
}
