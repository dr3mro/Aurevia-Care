package com.aurevia.messaging.conversations;

import com.aurevia.api.ApiException;
import com.aurevia.messaging.conversations.ConversationContract.UnitKey;

/**
 * Parses the bounded conversation unit category declared by a tenant's care-unit
 * directory entry.
 *
 * <p>The unit category is authoritative directory data resolved from a verified
 * connection's recipient unit; this class only converts that value into the
 * bounded {@link com.aurevia.messaging.conversations.ConversationContract.UnitKey}
 * vocabulary used by conversations and rejects anything outside it. It never
 * chooses a unit from client input.</p>
 */
public final class ConversationUnitResolver {

    private ConversationUnitResolver() {
    }

    /**
     * Parse a tenant-directory unit key into the conversation vocabulary.
     *
     * @param unitKey unit category declared by the recipient care unit
     * @return the matching bounded unit key
     * @throws ApiException when the directory value is absent or not a conversation unit
     */
    public static UnitKey fromDirectory(String unitKey) {
        if (unitKey == null) {
            throw new ApiException(403, "FORBIDDEN", "the connection's unit has no conversation category");
        }
        try {
            return UnitKey.from(unitKey);
        } catch (IllegalArgumentException exception) {
            throw new ApiException(403, "FORBIDDEN", "the connection's unit has an unknown conversation category");
        }
    }
}
