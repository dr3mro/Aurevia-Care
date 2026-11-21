package com.aurevia.smartclinic.exchange.fhir;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import com.aurevia.smartclinic.exchange.fhir.FhirModel.FhirReferenceRange;
import com.aurevia.smartclinic.exchange.fhir.FhirModel.ObservationData;
import com.aurevia.smartclinic.exchange.order.OrderView;
import com.aurevia.smartclinic.lab.accession.AccessionState;
import com.aurevia.smartclinic.lab.accession.LabAccession;
import com.aurevia.smartclinic.lab.result.LabObservation;
import com.aurevia.smartclinic.lab.result.LabTypes.AbnormalFlag;
import com.aurevia.smartclinic.lab.result.LabTypes.ObservationKind;
import com.aurevia.smartclinic.lab.result.LabTypes.ObservationPresence;
import com.aurevia.smartclinic.lab.result.LabTypes.Qualifier;
import com.aurevia.smartclinic.lab.result.LabTypes.VerificationState;
import com.aurevia.smartclinic.lab.result.ReportRevision;

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
}
