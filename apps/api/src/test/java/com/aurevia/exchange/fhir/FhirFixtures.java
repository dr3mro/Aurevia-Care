package com.aurevia.exchange.fhir;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.aurevia.documents.DocumentModel.DocumentAssoc;
import com.aurevia.exchange.fhir.FhirModel.FhirReferenceRange;
import com.aurevia.exchange.fhir.FhirModel.ObservationData;
import com.aurevia.exchange.order.OrderView;
import com.aurevia.lab.accession.AccessionState;
import com.aurevia.lab.accession.LabAccession;
import com.aurevia.lab.result.LabObservation;
import com.aurevia.lab.result.LabTypes.AbnormalFlag;
import com.aurevia.lab.result.LabTypes.ObservationKind;
import com.aurevia.lab.result.LabTypes.ObservationPresence;
import com.aurevia.lab.result.LabTypes.Qualifier;
import com.aurevia.lab.result.LabTypes.VerificationState;
import com.aurevia.lab.result.ReportRevision;
import com.aurevia.prescription.PrescriptionContract.Draft;
import com.aurevia.radiology.ImagingOrder;
import com.aurevia.radiology.RadTypes.Modality;
import com.aurevia.radiology.RadTypes.OrderState;
import com.aurevia.radiology.RadTypes.Urgency;

/**
 * Synthetic FHIR fixtures shared by the exchange and laboratory mapping tests.
 *
 * <p>Every identifier is invented; no fixture carries a real patient or credential.</p>
 */
final class FhirFixtures {

    static final String PROFILE = "synthetic-partner-lab-r4";
    static final String PATIENT = "pt-synthetic-001";
    static final String ENCOUNTER = "enc-synthetic-001";
    static final String ORDER = "ord-synthetic-001";
    static final String ACCESSION = "acc-synthetic-001";
    static final String REPORT = "rpt-synthetic-001";
    static final String STUDY_UID = "1.2.840.113619.2.55.3.1234.5678.2026";
    static final String IMAGING_ACCESSION = "IMG-2026-0001";
    static final String CHECKSUM = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    static final Instant EFFECTIVE = Instant.parse("2026-11-21T09:30:00Z");

    private FhirFixtures() {
    }

    static OrderView order() {
        return new OrderView(ORDER, 1, PATIENT, ENCOUNTER, "LAB-GLU", "urgent",
                "Assess glycaemic control", "org-receiving-synthetic", "subject-synthetic",
                "clinician-synthetic", "lab-queue-synthetic", "auth-synthetic-001",
                "snapshot-synthetic-001", "fingerprint-synthetic", "disclosure-synthetic-001",
                "workitem-synthetic-001", EFFECTIVE);
    }

    static LabAccession accession(AccessionState state) {
        return new LabAccession(ACCESSION, 1, "ACC-2026-0001", "synthetic-lab", PATIENT, ORDER, state,
                "SERUM", "ARM-L", "collector-synthetic", "2026-11-21T08:00:00Z",
                "2026-11-21T08:30:00Z", "ACCEPTABLE", null, null, null);
    }

    static LabObservation numeric() {
        return new LabObservation("GLU", ObservationKind.NUMERIC, ObservationPresence.PRESENT,
                new BigDecimal("5.4"), Qualifier.NONE, "mmol/L", null, null, "HEXOKINASE",
                AbnormalFlag.NONE, "analyzer-synthetic-01", "verifier-synthetic");
    }

    static LabObservation qualified() {
        return new LabObservation("GLU", ObservationKind.QUALIFIED_NUMERIC, ObservationPresence.PRESENT,
                new BigDecimal("3.0"), Qualifier.LT, "mmol/L", null, null, "ASSAY",
                AbnormalFlag.LOW, "analyzer-synthetic-02", "verifier-synthetic");
    }

    static LabObservation text() {
        return new LabObservation("URINALYSIS", ObservationKind.TEXT, ObservationPresence.PRESENT,
                null, Qualifier.NONE, null, "clear", null, "DIPSTICK",
                AbnormalFlag.NONE, "strip-reader-synthetic", "verifier-synthetic");
    }

    static LabObservation coded() {
        return new LabObservation("CULTURE", ObservationKind.CODED, ObservationPresence.PRESENT,
                null, Qualifier.NONE, null, null, "NEGATIVE", "CULTURE-METHOD",
                AbnormalFlag.NONE, "incubator-synthetic", "verifier-synthetic");
    }

    static LabObservation unknown() {
        return new LabObservation("GLU", ObservationKind.NUMERIC, ObservationPresence.UNKNOWN,
                null, Qualifier.NONE, null, null, null, "HEXOKINASE",
                AbnormalFlag.NONE, "analyzer-synthetic-01", "verifier-synthetic");
    }

    static LabObservation absent() {
        return new LabObservation("GLU", ObservationKind.NUMERIC, ObservationPresence.ABSENT,
                null, Qualifier.NONE, null, null, null, "HEXOKINASE",
                AbnormalFlag.NONE, "analyzer-synthetic-01", "verifier-synthetic");
    }

    static FhirReferenceRange range() {
        return new FhirReferenceRange(new BigDecimal("3.9"), new BigDecimal("7.1"), "mmol/L");
    }

    static ObservationData observation(String id, LabObservation source, FhirReferenceRange range) {
        return ObservationMapper.fromObservation(source, id, PATIENT, ACCESSION, "final", PROFILE, range);
    }

    static ReportRevision revision(int version, Integer supersedes, VerificationState state,
                                   List<LabObservation> sources) {
        List<LabObservation> observations = sources.isEmpty() ? List.of() : List.copyOf(sources);
        return new ReportRevision("org-synthetic-001", REPORT, version, supersedes, PATIENT, ACCESSION,
                ORDER, state, observations, "actor-synthetic", "verifier-synthetic", EFFECTIVE,
                supersedes == null ? null : "corrected value", 1, 1);
    }

    static ImagingOrder imagingOrder() {
        return imagingOrder(PATIENT, IMAGING_ACCESSION);
    }

    static ImagingOrder imagingOrder(String patientId, String accession) {
        return new ImagingOrder("org-synthetic-001", ORDER, patientId, ENCOUNTER, "CT-CHEST",
                Urgency.URGENT, "Assess chest pain", "source-synthetic", "workstation-synthetic",
                "clinician-synthetic", "rad-queue-synthetic", "referrer-synthetic",
                OrderState.STUDY_ASSOCIATED, 5, EFFECTIVE, Modality.CT, accession,
                "synthetic-imaging-issuer", STUDY_UID, "ws-synthetic-01", patientId, accession);
    }

    static DocumentAssoc activeDocument() {
        return document("active", null, null);
    }

    static DocumentAssoc voidedDocument() {
        return document("voided", "duplicate copy", "2026-11-21T09:00:00Z");
    }

    private static DocumentAssoc document(String status, String voidReason, String voidedAt) {
        return new DocumentAssoc("doc-synthetic-001", "active".equals(status) ? 3 : 4,
                "chart", PATIENT, "report.pdf", "application/pdf", 2048L, CHECKSUM, 0, status,
                voidReason, "creator-synthetic", "2026-11-21T08:00:00Z", voidedAt);
    }

    static Draft draft() {
        return new Draft("draft-synthetic-001", 1, PATIENT, "draft", "DRUG-CODE-001", 1,
                "Amoxicillin", "500", "mg", "ORAL", "BID", "20", "capsule", 2,
                "Take with water", "pharmacy-synthetic", ENCOUNTER, "prescriber-synthetic",
                EFFECTIVE);
    }
}
