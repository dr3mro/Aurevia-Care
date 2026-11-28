/**
 * Tenant-scoped facility and staff directory, active-staff guard, same-organization policy and cross-facility crossing guard.
 *
 * <p>Cross-organization messaging is refused regardless of configuration, and a cross-facility crossing needs an approved tenant flag. It must not read a role or organization from client input, assume every subject has exactly one facility assignment, or widen oversight exemptions.</p>
 */
package com.aurevia.staffmsg.directory;
