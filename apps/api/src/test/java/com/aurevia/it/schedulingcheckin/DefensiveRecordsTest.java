package com.aurevia.it.schedulingcheckin;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.aurevia.scheduling.checkin.CheckInCommands.VerifyIdentity;
import com.aurevia.scheduling.checkin.Verification;

/** S-G1-02 value objects copy list state on store and on read. */
class DefensiveRecordsTest {

    @Test
    void verificationCopiesIdentifiersAndRejectsMutation() {
        List<String> source = new ArrayList<>(List.of("MRN", "DOB"));
        Verification verification = new Verification("id", "appointment", "patient", "MAIN",
                "two_identifiers", source, null, null, "reception-a", Instant.now());
        source.add("GOV_ID");
        assertEquals(List.of("MRN", "DOB"), verification.identifiers());
        assertThrows(UnsupportedOperationException.class, () -> verification.identifiers().add("X"));
    }

    @Test
    void verificationToleratesAnAbsentIdentifierList() {
        Verification verification = new Verification("id", "appointment", "patient", "MAIN",
                "documented_alternate", null, "SITE_BACKUP", "reason", "reception-a", Instant.now());
        assertEquals(List.of(), verification.identifiers());
    }

    @Test
    void verifyIdentityCommandCopiesIdentifiersAndRejectsMutation() {
        List<String> source = new ArrayList<>(List.of("MRN", "GOV_ID"));
        VerifyIdentity command = new VerifyIdentity("appointment", source, "two_identifiers", null, null);
        source.clear();
        assertEquals(List.of("MRN", "GOV_ID"), command.identifiers());
        assertThrows(UnsupportedOperationException.class, () -> command.identifiers().add("X"));
    }

    @Test
    void verifyIdentityCommandToleratesAnAbsentIdentifierList() {
        VerifyIdentity command = new VerifyIdentity("appointment", null, "documented_alternate",
                "SITE_BACKUP", "reason");
        assertEquals(List.of(), command.identifiers());
    }
}
