package com.aurevia.staffmsg.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

/** Pure thread and recipient rules (MSG-02, ARC-07). */
class MailRulesTest {

    @Test
    void normalizeSubjectStripsRepeatedPrefixesAndFoldsCase() {
        assertEquals("handover", MailRules.normalizeSubject("Handover"));
        assertEquals("handover", MailRules.normalizeSubject("  Re: Handover  "));
        assertEquals("handover", MailRules.normalizeSubject("Fwd: Re: FWD: HANDOVER"));
        assertEquals("", MailRules.normalizeSubject("Re:"));
    }

    @Test
    void replyAllDeduplicatesAndRemovesTheActor() {
        List<String> all = List.of("sender", "nurse-a", "nurse-b", "doctor-a", "nurse-b");
        assertEquals(List.of("sender", "nurse-b", "doctor-a"),
                MailRules.replyAll(all, "nurse-a"));
        assertEquals(List.of("sender", "nurse-a", "nurse-b", "doctor-a"),
                MailRules.replyAll(all, "nobody"));
    }

    @Test
    void confirmationMatchesIgnoresOrderButNotMissingOrExtraRecipients() {
        assertTrue(MailRules.confirmationMatches(List.of("nurse-b", "nurse-a"),
                List.of("nurse-a", "nurse-b")));
        assertTrue(MailRules.confirmationMatches(List.of("nurse-a", "nurse-a", "nurse-b"),
                List.of("nurse-a", "nurse-b")));
        assertFalse(MailRules.confirmationMatches(null, List.of("nurse-a")));
        assertFalse(MailRules.confirmationMatches(List.of("nurse-a"), List.of("nurse-a", "nurse-b")));
        assertFalse(MailRules.confirmationMatches(List.of("nurse-a", "nurse-c"), List.of("nurse-a", "nurse-b")));
    }
}
