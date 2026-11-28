/**
 * Message-attachment upload, quarantine, scan, publication, mediated download
 * and removal, together with their append-only fact trail.
 *
 * <p>This package owns the attachment contract, role matrix, tenant-scoped store
 * and HTTP routes. Upload binding comes from a server-side verified connection;
 * declared type, magic bytes, extension, size and checksum are checked before
 * quarantine, and explicit publication with a validated message identifier is
 * required before another participant can download the file.</p>
 *
 * <p>It must not depend on public object URLs, clinical record stores or direct
 * payload exposure: a download returns a mediated handle only. Message existence
 * is not verified in this package.</p>
 */
package com.aurevia.messaging.attachments;
