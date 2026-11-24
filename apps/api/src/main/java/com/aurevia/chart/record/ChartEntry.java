package com.aurevia.chart.record;

import java.time.Instant;

/**
 * One immutable longitudinal chart entry with full provenance (CLN-03, ARC-01).
 *
 * <p>The submitted {@code originalCode}/{@code originalText} are retained
 * verbatim on every version. Any diagnosis-catalog mapping is a write-time
 * snapshot held in {@code mappedCode}/{@code mappedText}/{@code catalogVersion};
 * it is never recomputed on read. Clinical authorship is stored separately
 * from the {@code dataEntryActor} who entered the row.</p>
 *
 * @param id              tenant-local opaque identifier
 * @param patientId       owning patient identifier
 * @param encounterId     optional encounter context
 * @param type            chart family
 * @param originalText    submitted text, retained verbatim
 * @param originalCode    submitted code, retained verbatim, or {@code null}
 * @param codeSystem      local or market code system for the submitted code
 * @param mappedCode      catalog-mapped code snapshot, or {@code null}
 * @param mappedText      catalog-mapped text snapshot, or {@code null}
 * @param catalogVersion  effective catalog version used for the mapping
 * @param source          origin of the information
 * @param presence        clinical assertion, independent of verification
 * @param unknownReason   coded reason when presence is {@code UNKNOWN}
 * @param verification    verification state of this version
 * @param verifiedBy      subject who verified, or {@code null}
 * @param verifiedAt      verification instant, or {@code null}
 * @param effectiveFrom   clinical effective start, or {@code null}
 * @param effectiveTo     clinical effective end, or {@code null}
 * @param measuredAt      observation time for a vital, or {@code null}
 * @param value           qualitative or quantitative value, or {@code null}
 * @param unit            measurement unit, or {@code null}
 * @param clinicalAuthor  clinically responsible author, or {@code null} at intake
 * @param dataEntryActor  subject that entered the row, from the tenant context
 * @param version         optimistic-concurrency version, starting at one
 * @param recordedAt      ingestion instant, distinct from clinical time
 * @return this record
 */
public record ChartEntry(
        String id,
        String patientId,
        String encounterId,
        EntryType type,
        String originalText,
        String originalCode,
        String codeSystem,
        String mappedCode,
        String mappedText,
        Integer catalogVersion,
        EntrySource source,
        PresenceState presence,
        String unknownReason,
        VerificationState verification,
        String verifiedBy,
        Instant verifiedAt,
        Instant effectiveFrom,
        Instant effectiveTo,
        Instant measuredAt,
        String value,
        String unit,
        String clinicalAuthor,
        String dataEntryActor,
        int version,
        Instant recordedAt) {
}
