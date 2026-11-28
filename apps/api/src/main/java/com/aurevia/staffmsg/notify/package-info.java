/**
 * Content-free notification drafts, per-subject settings, deduplicated automatic replies and identifier-only outbox publication.
 *
 * <p>The outbox digest covers fixed phrases rather than a message body, and a reply is recorded at most once per sender and UTC day. It must not publish patient content, recipient names or a client-derived organization, or widen the outbox key across messages.</p>
 */
package com.aurevia.staffmsg.notify;
