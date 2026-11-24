package com.aurevia.it.messagingconversations;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

import com.aurevia.api.ApiException;
import com.aurevia.messaging.conversations.ConversationContract.UnitKey;
import com.aurevia.messaging.conversations.ConversationUnitResolver;

/**
 * Fast tests for parsing the tenant-directory unit category of a verified
 * connection into the bounded conversation unit vocabulary.
 */
class ConversationUnitResolverTest {

    @Test
    void everyDirectoryUnitKeyIsAccepted() {
        for (UnitKey key : UnitKey.values()) {
            assertEquals(key, ConversationUnitResolver.fromDirectory(key.wire()));
        }
    }

    @Test
    void anUnknownOrMissingDirectoryUnitKeyIsRejected() {
        ApiException unknown = assertThrows(ApiException.class,
                () -> ConversationUnitResolver.fromDirectory("bogus"));
        assertEquals(403, unknown.status());
        assertEquals("FORBIDDEN", unknown.code());
        assertEquals(403, assertThrows(ApiException.class,
                () -> ConversationUnitResolver.fromDirectory(null)).status());
    }
}
