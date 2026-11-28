/**
 * Immutable value types shared by staff messaging: conversation and participant kinds, folders, scan state and gap-free sequencing rules.
 *
 * <p>The sequence rule is the authority for ordering a conversation. This package owns values only; it must not own persistence, authorization or a client-facing transport.</p>
 */
package com.aurevia.staffmsg.model;
