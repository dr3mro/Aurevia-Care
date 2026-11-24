package com.aurevia.acceptance.specialty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.radiology.ImagingOrder;
import com.aurevia.radiology.ImagingService;
import com.aurevia.radiology.Interpretation;
import com.aurevia.radiology.RadCommands.AccessionCommand;
import com.aurevia.radiology.RadCommands.AddendumCommand;
import com.aurevia.radiology.RadCommands.AssociateStudyCommand;
import com.aurevia.radiology.RadCommands.IntakeCommand;
import com.aurevia.radiology.RadCommands.ScheduleCommand;
import com.aurevia.radiology.RadCommands.SignCommand;
import com.aurevia.radiology.RadEvent;
import com.aurevia.radiology.RadPolicyCatalog;
import com.aurevia.radiology.RadPolicyCatalog.InterpreterPrivilege;
import com.aurevia.radiology.RadTypes.InterpretationKind;
import com.aurevia.radiology.RadTypes.Modality;
import com.aurevia.radiology.RadTypes.OrderState;
import com.aurevia.radiology.RadTypes.RadEventType;
import com.aurevia.radiology.RadTypes.Urgency;

/**
 * Gate G4 acceptance for the radiology addendum notification.
 *
 * <p>Requirements proved: RAD-01 (an addendum links to the prior signed
 * interpretation and a critical communication reaches the referrer) and G4 (a
 * radiology addendum reaches the referrer). "Reaches the referrer" is proven as
 * a distinct {@code REFERRER_NOTIFIED} domain fact addressed to the verified
 * referrer and following the {@code ADDENDUM_LINKED} fact; delivery itself is a
 * later outbox concern. All identifiers and content are synthetic.</p>
 */
class RadiologyAddendumReachesReferrerAcceptanceTest {

    private static final String ORG = "55555555-5555-5555-5555-555555555555";
    private static final String ORDER = "order-g4-addendum";
    private static final String PATIENT = "patient-g4-addendum";
    private static final String OTHER_PATIENT = "patient-g4-other";
    private static final String ACC = "ACC-G4-2";
    private static final String ISSUER = "ISSUER-G4";
    private static final String UID = "1.2.840.113619.2.55.3.g4.addendum";
    private static final String WORKSTATION = "pacs-workstation-g4-addendum";
    private static final String ACTOR = "tech-g4-addendum";
    private static final String INTERPRETER = "dr-g4";
    private static final String OWNER = "owner-g4-addendum";
    private static final String REFERRER = "referrer-g4-addendum";
    private static final Instant FROM = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant AT = Instant.parse("2026-11-21T10:00:00Z");
    private static final Instant SCHEDULED = AT.plus(Duration.ofDays(1));
    private static final Set<String> STAFF = Set.of("radiology_staff");
    private static final Set<String> INTERPRETER_ROLE = Set.of("radiology_interpreter");

    private ImagingService service;

    @BeforeEach
    void setUp() {
        RadPolicyCatalog policies = new RadPolicyCatalog(List.of(Modality.CT),
                List.of(new InterpreterPrivilege(INTERPRETER, Modality.CT, true, FROM, null)));
        service = new ImagingService(policies, Clock.fixed(AT, ZoneOffset.UTC));
    }

    @Test
    void addendumReachesTheReferrerAsASeparateDomainFact() {
        // RAD-01 / G4: a signed addendum supersedes the prior interpretation and
        // produces its own ADDENDUM_LINKED and REFERRER_NOTIFIED facts.
        ImagingOrder signed = signed();
        assertEquals(OrderState.SIGNED, signed.state());
        assertEquals(5, signed.version());

        Interpretation addendum = service.addAddendum(new AddendumCommand(ORG, INTERPRETER,
                INTERPRETER_ROLE, ORDER, signed.version(), PATIENT, UID, "late findings",
                "late impression", "late result", "I attest to the addendum"));

        assertEquals(InterpretationKind.ADDENDUM, addendum.kind());
        assertEquals(2, addendum.version());
        assertEquals(1, addendum.supersedesVersion());
        assertEquals("late result", addendum.reason());
        assertEquals(addendum, service.interpretation(ORG, ORDER));
        assertEquals(OrderState.ADDENDED, service.read(ORG, ORDER).state());
        assertEquals(6, service.read(ORG, ORDER).version());

        List<RadEvent> events = service.events(ORG);
        RadEvent linked = events.stream()
                .filter(event -> event.type() == RadEventType.ADDENDUM_LINKED)
                .findFirst().orElseThrow();
        assertEquals(REFERRER, linked.destination());
        assertEquals(addendum.checksum(), linked.payloadDigest());

        // One REFERRER_NOTIFIED fact for the interpretation and one for the
        // addendum; the addendum's notice follows its link fact and is addressed
        // to the same verified referrer.
        List<RadEvent> notifications = events.stream()
                .filter(event -> event.type() == RadEventType.REFERRER_NOTIFIED).toList();
        assertEquals(2, notifications.size());
        RadEvent last = notifications.get(1);
        assertEquals(REFERRER, last.destination());
        assertNull(last.payloadDigest());
        assertTrue(events.indexOf(linked) < events.indexOf(last));
    }

    @Test
    void anInvalidAddendumNeverNotifiesTheReferrerAgain() {
        // RAD-01 failure paths: a wrong role, an unprivileged interpreter, an
        // empty reason, a wrong patient or a stale version must leave the signed
        // interpretation immutable and emit no extra notification.
        ImagingOrder signed = signed();

        assertEquals(403, status(() -> service.addAddendum(new AddendumCommand(ORG, INTERPRETER,
                STAFF, ORDER, signed.version(), PATIENT, UID, "f", "i", "r", "a"))));
        assertEquals(403, status(() -> service.addAddendum(new AddendumCommand(ORG, "dr-no-priv",
                INTERPRETER_ROLE, ORDER, signed.version(), PATIENT, UID, "f", "i", "r", "a"))));
        assertEquals(422, status(() -> service.addAddendum(new AddendumCommand(ORG, INTERPRETER,
                INTERPRETER_ROLE, ORDER, signed.version(), PATIENT, UID, "f", "i", " ", "a"))));
        assertEquals(422, status(() -> service.addAddendum(new AddendumCommand(ORG, INTERPRETER,
                INTERPRETER_ROLE, ORDER, signed.version(), OTHER_PATIENT, UID, "f", "i", "r", "a"))));
        assertEquals(412, status(() -> service.addAddendum(new AddendumCommand(ORG, INTERPRETER,
                INTERPRETER_ROLE, ORDER, signed.version() + 1, PATIENT, UID, "f", "i", "r", "a"))));

        assertEquals(OrderState.SIGNED, service.read(ORG, ORDER).state());
        assertEquals(5, service.read(ORG, ORDER).version());
        assertEquals(1, service.interpretations(ORG, ORDER).size());
        assertTrue(service.events(ORG).stream()
                .noneMatch(event -> event.type() == RadEventType.ADDENDUM_LINKED));
        assertEquals(1, service.events(ORG).stream()
                .filter(event -> event.type() == RadEventType.REFERRER_NOTIFIED).count());
    }

    private ImagingOrder signed() {
        ImagingOrder order = service.intake(new IntakeCommand(ORG, ACTOR, STAFF, ORDER, PATIENT,
                "enc-g4", "CT-CHEST", Urgency.ROUTINE, "rule out pulmonary embolism", "clinic",
                "radiology", OWNER, "rad-queue", REFERRER, Modality.CT));
        order = service.schedule(new ScheduleCommand(ORG, ACTOR, STAFF, ORDER, order.version(),
                SCHEDULED, Modality.CT));
        order = service.accession(new AccessionCommand(ORG, ACTOR, STAFF, ORDER, order.version(),
                ACC, ISSUER));
        order = service.associateStudy(new AssociateStudyCommand(ORG, ACTOR, STAFF, ORDER,
                order.version(), UID, PATIENT, ACC, WORKSTATION));
        service.sign(new SignCommand(ORG, INTERPRETER, INTERPRETER_ROLE, ORDER, order.version(),
                PATIENT, ACC, UID, "technique", "comparison", "findings", "impression",
                "follow-up", "tpl-g4", "I attest"));
        return service.read(ORG, ORDER);
    }

    private static int status(Runnable action) {
        return assertThrows(ApiException.class, action::run).status();
    }
}
