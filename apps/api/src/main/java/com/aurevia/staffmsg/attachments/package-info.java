/**
 * Staff-message attachment values, configured limits, scan verdicts, private object storage, publication rules and HTTP upload and download.
 *
 * <p>Only clean, checksum-verified bytes bound to a sent message may be delivered, and download requires recipient status plus an audited role check. It must not log client-supplied file names or payload bytes, publish a quarantined file, or grant access from role alone.</p>
 */
package com.aurevia.staffmsg.attachments;
