/**
 * Instant staff-conversation values, commands, durable store, monotonic read cursors and the conversation HTTP surface.
 *
 * <p>Messages append a gap-free sequence inside one conversation and a read cursor never moves backwards. It must not edit message content in place, let a repeated mark-read regress the cursor, or expose a conversation outside the captured facility.</p>
 */
package com.aurevia.staffmsg.instant;
