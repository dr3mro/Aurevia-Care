package com.aurevia.it.patient.identity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/**
 * Fast-tier schema-contract guard for the identity link projection (IAM-05, ARC-01).
 *
 * <p>The V10 {@code current_chart_links()} fold deliberately exposes an account
 * id and a database-owned {@code seq}, not the {@code account_chart_link} event
 * id. A projection that reads a bare {@code id} from the fold fails at runtime
 * with SQLSTATE 42703 on every claim, unlink and links command. That failure is
 * invisible to the database-less unit tier because the repository is mocked
 * there, so this check keeps the repository/fold boundary honest in the fast
 * suite. All fixtures are derived from repository source, not patient data.</p>
 */
class IdentityLinkProjectionContractTest {

    private static final Path MIGRATION =
            Path.of("../../db/migrations/V10__patient_identity_proofing.sql");
    private static final Path REPOSITORY =
            Path.of("src/main/java/com/aurevia/patient/identity/IdentityProofingRepository.java");

    private static final Pattern FOLD_SIGNATURE = Pattern.compile(
            "CREATE\\s+OR\\s+REPLACE\\s+FUNCTION\\s+public\\.current_chart_links\\(\\).*?"
                    + "RETURNS\\s+TABLE\\s*\\((.*?)\\)",
            Pattern.DOTALL);
    private static final Pattern FOLD_PROJECTION = Pattern.compile(
            "\"\"\"\\s*SELECT\\s+([^\"]*?)\\s+FROM\\s+public\\.current_chart_links\\(\\)",
            Pattern.DOTALL);
    private static final Pattern UNQUALIFIED_EVENT_ID = Pattern.compile("(?<![.\\w])id\\s*::\\s*text");

    @Test
    void theFoldExposesAnAccountIdAndSequenceButNoEventId() throws Exception {
        Set<String> columns = foldColumns();

        assertTrue(columns.contains("account_id"), "the fold must expose the account it links");
        assertTrue(columns.contains("seq"), "the fold must expose the ordering sequence");
        assertFalse(columns.contains("id"),
                "the fold does not expose the link event id; callers must join the append-only table");
    }

    @Test
    void linkProjectionsQualifyTheEventIdFromTheAppendOnlyTable() throws Exception {
        String source = Files.readString(REPOSITORY);
        Matcher projections = FOLD_PROJECTION.matcher(source);
        int checked = 0;

        while (projections.find()) {
            checked++;
            String projection = projections.group(1);
            assertFalse(UNQUALIFIED_EVENT_ID.matcher(projection).find(),
                    "a bare id::text over the fold fails with SQLSTATE 42703: " + projection);
        }
        assertTrue(checked >= 2, "both currentLink and links must read the current-link fold");
    }

    private static Set<String> foldColumns() throws Exception {
        Matcher signature = FOLD_SIGNATURE.matcher(Files.readString(MIGRATION));
        assertTrue(signature.find(), "V10 must define the current_chart_links() fold");
        return Arrays.stream(signature.group(1).split(","))
                .map(String::trim)
                .map(column -> column.split("\\s+")[0])
                .collect(Collectors.toSet());
    }
}
