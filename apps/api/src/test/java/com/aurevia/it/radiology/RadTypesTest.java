package com.aurevia.it.radiology;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.aurevia.radiology.RadTypes.InterpretationKind;
import com.aurevia.radiology.RadTypes.Modality;
import com.aurevia.radiology.RadTypes.OrderState;
import com.aurevia.radiology.RadTypes.RadEventType;
import com.aurevia.radiology.RadTypes.Urgency;

/** RAD-01 bounded vocabularies and the imaging order state machine. */
class RadTypesTest {

    @Test
    void urgencyWireFormsAndNamedOwnerRule() {
        assertTrue(Urgency.ROUTINE.requiresNamedOwner() == false);
        assertTrue(Urgency.URGENT.requiresNamedOwner() == false);
        assertTrue(Urgency.STAT.requiresNamedOwner());
        org.junit.jupiter.api.Assertions.assertEquals("routine", Urgency.ROUTINE.wire());
        org.junit.jupiter.api.Assertions.assertEquals("urgent", Urgency.URGENT.wire());
        org.junit.jupiter.api.Assertions.assertEquals("stat", Urgency.STAT.wire());
    }

    @Test
    void modalityWireForms() {
        org.junit.jupiter.api.Assertions.assertEquals("ct", Modality.CT.wire());
        org.junit.jupiter.api.Assertions.assertEquals("mri", Modality.MRI.wire());
        org.junit.jupiter.api.Assertions.assertEquals("xray", Modality.XRAY.wire());
        org.junit.jupiter.api.Assertions.assertEquals("ultrasound", Modality.ULTRASOUND.wire());
        org.junit.jupiter.api.Assertions.assertEquals("pet", Modality.PET.wire());
    }

    @Test
    void orderStateTransitionsAreExactlyTheImagingLifecycle() {
        Map<OrderState, Set<OrderState>> legal = new EnumMap<>(OrderState.class);
        legal.put(OrderState.INTAKE, Set.of(OrderState.SCHEDULED));
        legal.put(OrderState.SCHEDULED, Set.of(OrderState.ACCESSIONED));
        legal.put(OrderState.ACCESSIONED, Set.of(OrderState.STUDY_ASSOCIATED));
        legal.put(OrderState.STUDY_ASSOCIATED, Set.of(OrderState.SIGNED));
        legal.put(OrderState.SIGNED, Set.of(OrderState.ADDENDED));
        legal.put(OrderState.ADDENDED, Set.of(OrderState.ADDENDED));

        for (OrderState source : OrderState.values()) {
            Set<OrderState> expected = legal.get(source);
            for (OrderState target : OrderState.values()) {
                boolean allowed = source.canTransitionTo(target);
                org.junit.jupiter.api.Assertions.assertEquals(expected.contains(target), allowed,
                        source + " -> " + target);
            }
            org.junit.jupiter.api.Assertions.assertEquals(source.name().toLowerCase(java.util.Locale.ROOT),
                    source.wire());
        }
    }

    @Test
    void interpretationKindAndEventWireForms() {
        org.junit.jupiter.api.Assertions.assertEquals("initial", InterpretationKind.INITIAL.wire());
        org.junit.jupiter.api.Assertions.assertEquals("addendum", InterpretationKind.ADDENDUM.wire());
        for (RadEventType type : RadEventType.values()) {
            org.junit.jupiter.api.Assertions.assertEquals(
                    type.name().toLowerCase(java.util.Locale.ROOT), type.wire());
        }
        assertFalse(RadEventType.values().length == 0);
        assertTrue(RadEventType.values().length > 0);
    }
}
