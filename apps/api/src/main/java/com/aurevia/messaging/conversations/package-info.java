/**
 * Conversation lifecycle: creation, inbox and work-queue views, reply,
 * assignment, transfer, resolution, close and read state.
 *
 * <p>This package owns the conversation contract, action and unit matrix, SQL
 * repository, domain service and HTTP routes. A command on an existing thread
 * requires an If-Match version precondition; a patient must address a unit through
 * a verified connection, while staff replies are gated by the patient link and
 * the staff unit matrix. Authorization runs before existence checks so a caller
 * cannot probe a resource it is not allowed to see.</p>
 *
 * <p>It must not depend on the acknowledgment or attachment stores, on a
 * notification outbox row, or on an unverified unit identity: a closed thread
 * refuses mutations except marking read.</p>
 */
package com.aurevia.messaging.conversations;
