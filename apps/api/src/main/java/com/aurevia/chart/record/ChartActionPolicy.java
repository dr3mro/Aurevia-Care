package com.aurevia.chart.record;

import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * The CLN-03 chart role matrix, local to this slice.
 *
 * <p>The shared {@code ActionPolicy} has no clinical chart actions and is out
 * of this story's scope. Tenant administration and privacy audit roles are
 * deliberately excluded: the specification keeps clinical access separate.</p>
 */
@Component
public class ChartActionPolicy {

    /** Read a patient's chart or one chart entry. */
    public static final String CHART_READ = "CHART_READ";
    /** Record a problem, allergy, medication or vital. */
    public static final String CHART_ENTRY_CREATE = "CHART_ENTRY_CREATE";
    /** Verify a chart entry; reserved for clinicians. */
    public static final String CHART_ENTRY_VERIFY = "CHART_ENTRY_VERIFY";

    private static final Set<String> READ_AND_CREATE =
            Set.of("clinician", "clinical_assistant");
    private static final Set<String> VERIFY = Set.of("clinician");

    /**
     * Decide whether a role may perform a chart action.
     *
     * @param action action key such as {@code CHART_ENTRY_CREATE}
     * @param role   role key assigned in the active organization
     * @return {@code true} only for an explicitly permitted pair
     */
    public boolean allows(String action, String role) {
        if (action == null || role == null) {
            return false;
        }
        return switch (action) {
            case CHART_READ, CHART_ENTRY_CREATE -> READ_AND_CREATE.contains(role);
            case CHART_ENTRY_VERIFY -> VERIFY.contains(role);
            default -> false;
        };
    }
}
