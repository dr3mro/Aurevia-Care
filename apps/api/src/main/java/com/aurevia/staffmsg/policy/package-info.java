/**
 * Effective-dated messaging policy values, append-only revision guards, the actor boundary for authorization and the fail-closed default.
 *
 * <p>With no approved revision the default closes cross-facility messaging and opens only the documented audience and media. It must not accept a role from client input, claim that an administrative gate is wired here, or replace the fail-closed substitute with a guessed approval.</p>
 */
package com.aurevia.staffmsg.policy;
