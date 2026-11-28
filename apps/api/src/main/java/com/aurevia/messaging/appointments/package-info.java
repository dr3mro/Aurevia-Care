/**
 * Patient-facing appointment projections, confirmation, generic status pushes
 * and device registration and revocation.
 *
 * <p>This package owns the appointment and device ports, their synthetic local
 * adapters and the HTTP routes. A client-supplied patient identifier is used only
 * after a fresh verified-link check; confirmation takes a version precondition
 * in the command body and records a server timestamp. The service has no durable
 * adapter yet, so it is intentionally not registered as a Spring bean.</p>
 *
 * <p>It must not depend on staff scheduling writes, clinical record stores or a
 * provider push SDK: notices come from one fixed generic template and carry no
 * clinical detail.</p>
 */
package com.aurevia.messaging.appointments;
