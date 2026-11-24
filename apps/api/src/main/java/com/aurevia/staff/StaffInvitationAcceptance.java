package com.aurevia.staff;

/**
 * The durable outcome of accepting a staff invitation (IAM-02).
 *
 * <p>The membership and role assignments are written atomically by the database
 * before this projection is returned. The inviting administrator is recorded as
 * the grantor of each role and the acceptance instant is its effective date.</p>
 *
 * @param subject  subject whose membership was created
 * @param status   effective membership status
 * @return this record
 */
public record StaffInvitationAcceptance(String subject, String status) {
}
