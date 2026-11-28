/**
 * Email-like staff mail values, validation rules, commands, versioned durable state, replay by client key and the mail HTTP surface.
 *
 * <p>A message is immutable after send, corrections append a later message, blind copy and confirmation errors surface through the route, and retries replay by client key. It must not append a duplicate on retry, mutate a stored message, or derive the sender from request input.</p>
 */
package com.aurevia.staffmsg.mail;
