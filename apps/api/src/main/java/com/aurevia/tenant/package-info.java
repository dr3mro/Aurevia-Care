/**
 * Thread-bound tenant context, the guarded transaction boundary that sets transaction-local database settings, and active-tenant enumeration.
 *
 * <p>An absent context stops work before a transaction opens, and the enumerated values exist only for scheduler selection. It must not log bound values, fall back to a default organization, or let a pooled connection carry settings into the next transaction.</p>
 */
package com.aurevia.tenant;
