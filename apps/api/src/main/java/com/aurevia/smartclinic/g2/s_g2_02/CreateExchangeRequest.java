package com.aurevia.smartclinic.g2.s_g2_02;

/**
 * Command to open an exchange work item. Tenant, author, version and every
 * clinical field are derived server-side and are deliberately absent.
 *
 * @param orderRef tenant-local order reference; must be bounded, printable text
 */
public record CreateExchangeRequest(String orderRef) {
}
