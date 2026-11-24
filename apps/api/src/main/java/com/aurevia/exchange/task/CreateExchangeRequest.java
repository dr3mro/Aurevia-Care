package com.aurevia.exchange.task;

/**
 * Command to open an exchange work item. Tenant, author, version and every
 * clinical field are derived server-side and are deliberately absent.
 *
 * @param orderRef tenant-local order reference; must be bounded, printable text
 * @return this record
 */
public record CreateExchangeRequest(String orderRef) {
}
