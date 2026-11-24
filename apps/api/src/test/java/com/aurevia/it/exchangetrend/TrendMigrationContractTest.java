package com.aurevia.it.exchangetrend;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import org.junit.jupiter.api.Test;

/**
 * Fast-tier guard for the SQL contract of the effective-dated comparability
 * fold (XCH-07). The behavioural proof runs against PostgreSQL in
 * {@code TrendingTest}; this test keeps the ordering rule visible in the
 * database-less tier so a backdated policy version cannot silently win.
 */
class TrendMigrationContractTest {

    private static final String MIGRATION = "V25__observation_trending.sql";

    @Test
    void effectiveComparabilityOrdersByEffectiveDateBeforeVersion() throws IOException {
        String sql = migration();
        int fold = sql.indexOf("FUNCTION public.current_trend_comparability");
        assertTrue(fold > 0, "V25 must define the current comparability fold");

        int orderBy = sql.indexOf("ORDER BY", fold);
        assertTrue(orderBy > 0, "the fold must order candidate policy rows");
        int end = sql.indexOf("$$", orderBy);
        assertTrue(end > orderBy, "the fold body must close after its ORDER BY");

        String clause = sql.substring(orderBy, end).toLowerCase(Locale.ROOT);
        assertTrue(clause.contains("effective_from desc"),
                "the latest effective policy must win: " + clause);
        assertTrue(clause.indexOf("effective_from desc") < clause.indexOf("version desc"),
                "version may only break an effective-date tie: " + clause);
    }

    private static String migration() throws IOException {
        for (Path candidate : new Path[] {
                Path.of("..", "..", "db", "migrations", MIGRATION),
                Path.of("..", "db", "migrations", MIGRATION),
                Path.of("db", "migrations", MIGRATION)}) {
            if (Files.isRegularFile(candidate)) {
                return Files.readString(candidate);
            }
        }
        throw new IOException("cannot locate " + MIGRATION + " from " + Path.of("").toAbsolutePath());
    }
}
