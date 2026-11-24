package com.aurevia.exchange.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Tests that the FHIR and HL7 conformance wire adapters round-trip every
 * synthetic fixture event without losing identity, ordering or clinical payload.
 */
class PartnerEventAdapterTest {

    private static final List<PartnerEventAdapter> ADAPTERS = List.of(
            new FhirPartnerEventAdapter(), new Hl7PartnerEventAdapter());

    private static List<ConformanceFixture> everyFixture() {
        List<ConformanceFixture> fixtures = new ArrayList<>(ConformanceFixtures.all());
        fixtures.add(ConformanceFixtures.replayWithReason());
        return fixtures;
    }

    @Test
    void everyFixtureEventRoundTripsThroughBothAdapters() {
        for (ConformanceFixture fixture : everyFixture()) {
            for (PartnerEventAdapter adapter : ADAPTERS) {
                for (PartnerEvent event : fixture.events()) {
                    PartnerEvent decoded = adapter.roundTrip(event);
                    assertEquals(event, decoded, adapter.name() + " " + fixture.id());
                    assertEquals(event.eventId(), decoded.eventId());
                    assertEquals(event.sender(), decoded.sender());
                    assertEquals(event.kind(), decoded.kind());
                    assertEquals(event.aggregateKey(), decoded.aggregateKey());
                    assertEquals(event.revision(), decoded.revision());
                    assertEquals(event.identifier(), decoded.identifier());
                    assertEquals(event.unit(), decoded.unit());
                    assertEquals(event.payload(), decoded.payload());
                }
            }
        }
    }

    @Test
    void adaptersPreserveDelimitersAndOptionalUnits() {
        PartnerEvent withDelimiters = new PartnerEvent("evt|1", "partner^lab", EventKind.OBSERVATION,
                "obs", 7L, "TEST~A", "mg/dL", "7.1 & <5");
        PartnerEvent withoutUnit = PartnerEvent.of("evt-2", EventKind.RESULT, "order-1", 2L,
                "ACC-1001", "7.4 mg/dL");
        for (PartnerEventAdapter adapter : ADAPTERS) {
            assertEquals(withDelimiters, adapter.roundTrip(withDelimiters), adapter.name());
            assertNull(adapter.roundTrip(withoutUnit).unit(), adapter.name());
            assertFalse(adapter.encode(withDelimiters).isBlank());
        }
    }

    @Test
    void adaptersPreserveANullIdentifier() {
        PartnerEvent event = new PartnerEvent("evt-null", "partner-lab", EventKind.RESULT,
                "order-1", 1L, null, null, "7.1 mg/dL");
        for (PartnerEventAdapter adapter : ADAPTERS) {
            PartnerEvent decoded = adapter.roundTrip(event);
            assertEquals(event, decoded, adapter.name());
            assertNull(decoded.identifier(), adapter.name());
        }
    }

    @Test
    void adaptersExposeTheirWireName() {
        assertEquals("fhir", new FhirPartnerEventAdapter().name());
        assertEquals("hl7", new Hl7PartnerEventAdapter().name());
    }

    @Test
    void fhirAdapterRejectsForeignResourceAndHl7AdapterRejectsMissingSegment() {
        PartnerEventAdapter fhir = new FhirPartnerEventAdapter();
        PartnerEventAdapter hl7 = new Hl7PartnerEventAdapter();
        assertThrows(IllegalArgumentException.class,
                () -> fhir.decode("{\"resourceType\":\"Patient\"}"));
        assertThrows(IllegalArgumentException.class,
                () -> hl7.decode("MSH|^~\\&|PARTNER\r"));

        List<PartnerEvent> observed = new ArrayList<>();
        PartnerEvent event = PartnerEvent.of("e", EventKind.RESULT, "order-1", 1L, "ACC-1001", "7.1");
        for (PartnerEventAdapter adapter : ADAPTERS) {
            observed.add(adapter.roundTrip(event));
        }
        assertTrue(observed.stream().allMatch(event::equals));
    }
}
