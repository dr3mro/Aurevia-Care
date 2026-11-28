package com.aurevia.staffmsg.mail;

import java.util.List;

/**
 * Resolves a role recipient to the people who currently hold it.
 *
 * <p>The lookup is performed at send time, never when a draft is composed, so a
 * role always reaches its present holders. The resolved list is then frozen on
 * the sent message and does not change when roles later change.</p>
 */
public interface RecipientDirectory {

    /**
     * List the active holders of a role in a facility of the active tenant.
     *
     * @param organizationId active tenant identifier
     * @param facilityId     facility the role is scoped to
     * @param role           role key
     * @return the distinct current holder subjects in a stable order
     */
    List<String> holders(String organizationId, String facilityId, String role);
}
