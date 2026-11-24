package com.aurevia.exchange.order;

/**
 * Command that records the sending organization's approval for a bounded exchange (XCH-01).
 *
 * @param orderCode          order code the authorization covers
 * @param destinationOrg     the only receiving organization the authorization covers
 * @param purpose            the only business purpose the authorization covers
 * @param maxDurationMinutes maximum validity of any release under this authorization
 * @param validForDays       number of days from creation until the authorization expires
 * @return this record
 */
public record AuthorizationRequest(String orderCode, String destinationOrg, String purpose,
                                   Integer maxDurationMinutes, Integer validForDays) {
}
