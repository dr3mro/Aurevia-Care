/**
 * Reception worklist, identity evidence, check-in transitions and handoff records for arriving patients.
 *
 * <p>The local role matrix selects worklist actions, while the tenant-guarded fold enforces facility scope and re-derives roles for every command. It must not grant encounter or vitals privileges from this matrix, trust a client-supplied role or facility, or record identity evidence outside the append-only ledgers.</p>
 */
package com.aurevia.scheduling.checkin;
