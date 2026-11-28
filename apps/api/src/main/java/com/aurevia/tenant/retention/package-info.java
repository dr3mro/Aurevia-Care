/**
 * Retention policies, legal holds, offboarding commands and the pure retention decision kernel.
 *
 * <p>Active records check a hold before the retention window; backups check tenant, approval and due date before the hold. It must not delete held data, treat a cross-tenant policy as applicable, or embed a scheduler or durable store in the kernel.</p>
 */
package com.aurevia.tenant.retention;
