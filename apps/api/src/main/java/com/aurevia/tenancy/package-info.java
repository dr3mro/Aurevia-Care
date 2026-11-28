/**
 * Facility projections, their tenant-scoped lookup and the service that re-authorizes facility-aware commands.
 *
 * <p>All reads pass through the forced-row-level-security repository in the active organization. It must not derive the organization from caller input, cache a role across requests, or add a second persistence path beside the repository.</p>
 */
package com.aurevia.tenancy;
