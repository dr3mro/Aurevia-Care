package com.aurevia.chart.record;

import java.time.Instant;

/**
 * Command to capture one chart entry (CLN-03).
 *
 * <p>Organization, data-entry actor, identifier, version, recorded time and
 * verification are never accepted from the client: they are derived by the
 * service. A new entry always starts unverified and must be promoted through
 * the explicit verify command.</p>
 *
 * @param patientId      owning patient identifier in the active organization
 * @param encounterId    optional encounter context
 * @param type           chart family
 * @param originalText   submitted text, retained verbatim
 * @param originalCode   submitted code, or {@code null}
 * @param codeSystem     local or market code system, paired with the code
 * @param source         origin of the information
 * @param presence       clinical assertion
 * @param unknownReason  coded reason required when presence is {@code UNKNOWN}
 * @param effectiveFrom  clinical effective start, or {@code null}
 * @param effectiveTo    clinical effective end, or {@code null}
 * @param measuredAt     observation time for a vital, or {@code null}
 * @param value          qualitative or quantitative value, or {@code null}
 * @param unit           measurement unit, or {@code null}
 * @param clinicalAuthor clinically responsible author, or {@code null} at intake
 * @return this record
 */
public record RecordEntryCommand(
        String patientId,
        String encounterId,
        EntryType type,
        String originalText,
        String originalCode,
        String codeSystem,
        EntrySource source,
        PresenceState presence,
        String unknownReason,
        Instant effectiveFrom,
        Instant effectiveTo,
        Instant measuredAt,
        String value,
        String unit,
        String clinicalAuthor) {
}
