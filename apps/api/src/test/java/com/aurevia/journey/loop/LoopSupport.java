package com.aurevia.journey.loop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Tag;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import com.aurevia.journey.JourneyContract.ActorType;
import com.aurevia.journey.JourneyContract.JourneyEvent;
import com.aurevia.journey.JourneyContract.JourneyItemType;
import com.aurevia.journey.JourneyContract.JourneyStep;
import com.aurevia.journey.JourneyContract.JourneyView;
import com.aurevia.journey.JourneyContract.StepStatus;
import com.aurevia.journey.JourneyContract.StepView;
import com.aurevia.journey.JourneyContract.Viewer;
import com.aurevia.journey.JourneyTimeline;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Synthetic fixtures shared by the two care-loop integration tests.
 *
 * <p>Each test class owns a dedicated PostgreSQL database so the loops can run in
 * one Maven invocation without sharing rows. Both tenants receive identical local
 * subjects, patients and identifiers; only forced row-level security and the
 * injected tenant context keep them apart. All narratives, subjects and
 * identifiers are synthetic.</p>
 *
 * <p>The journey projector is a pure read model with no HTTP entry point, so each
 * loop test converts the facts observed in real HTTP responses into
 * {@code JourneyEvent} records and projects them with {@code JourneyTimeline}.
 * Two disjoint time bands are assigned to the two tenants: tenant A facts occur
 * after tenant B facts. A tenant-filter regression would therefore make the
 * earlier tenant-B event win the earliest-wins projection, which the isolation
 * assertion detects.</p>
 */
@Tag("integration")
final class LoopSupport {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String GRANT_A = "55555555-5555-5555-5555-555555555551";
    static final String GRANT_B = "55555555-5555-5555-5555-555555555552";
    static final String MIGRATE = "aurevia_migrate";
    static final String ADMIN = System.getenv().getOrDefault("AUREVIA_PG_ADMIN", "postgres");

    static final Instant EVALUATED_AT = Instant.parse("2026-11-21T12:00:00Z");
    static final Instant ORG_A_BASE = EVALUATED_AT.minusSeconds(600);
    static final Instant ORG_B_BASE = EVALUATED_AT.minusSeconds(1800);
    static final Instant ORG_B_LATEST = ORG_B_BASE.plusSeconds(600);
    static final JourneyTimeline TIMELINE = new JourneyTimeline(Clock.fixed(EVALUATED_AT, ZoneOffset.UTC));

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private LoopSupport() {
    }

    /** Recreate, migrate and seed one dedicated database for a journey loop. */
    static void prepare(String databaseUrl, String databaseName) throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DO $$ BEGIN "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='aurevia_migrate') THEN "
                    + "CREATE ROLE aurevia_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='aurevia_app') THEN "
                    + "CREATE ROLE aurevia_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; END $$;");
            statement.execute("DROP DATABASE IF EXISTS " + databaseName + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + databaseName + " OWNER aurevia_migrate");
        }
        Flyway.configure().dataSource(databaseUrl, MIGRATE, "")
                .locations("filesystem:../../db/migrations").load().migrate();
        try (Connection connection = DriverManager.getConnection(databaseUrl, MIGRATE, "");
             Statement statement = connection.createStatement()) {
            seedTenant(statement, ORG_A, "1111");
            seedTenant(statement, ORG_B, "2222");
            seedGrants(statement);
        }
    }

    private static void seedGrants(Statement statement) throws Exception {
        statement.execute("SET app.current_org = '" + ORG_A + "'");
        statement.execute("SET app.actor = 'admin-1111'");
        statement.execute("INSERT INTO public.exchange_authorization "
                + "(organization_id, id, order_code, destination_org, purpose, max_duration_minutes, "
                + "effective_from, expires_at, active) VALUES ('" + ORG_A + "', '" + GRANT_A
                + "', 'CBC', '" + ORG_B + "', 'lab review', 60, now() - interval '1 day', "
                + "now() + interval '1 day', true)");
        statement.execute("SET app.current_org = '" + ORG_B + "'");
        statement.execute("SET app.actor = 'admin-2222'");
        statement.execute("INSERT INTO public.exchange_authorization "
                + "(organization_id, id, order_code, destination_org, purpose, max_duration_minutes, "
                + "effective_from, expires_at, active) VALUES ('" + ORG_B + "', '" + GRANT_B
                + "', 'CBC', '" + ORG_A + "', 'lab review', 60, now() - interval '1 day', "
                + "now() + interval '1 day', true)");
    }

    /** Drop one dedicated journey-loop database. */
    static void drop(String databaseName) throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + databaseName + " WITH (FORCE)");
        }
    }

    private static void seedTenant(Statement statement, String org, String suffix) throws Exception {
        String bootstrap = "bootstrap-" + suffix;
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + bootstrap + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) VALUES ('"
                + org + "', 'Synthetic " + suffix + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) VALUES ('"
                + org + "', '" + bootstrap + "', 'Bootstrap')");
        statement.execute("INSERT INTO public.patient_record "
                + "(organization_id, version, mrn, mrn_issuer, display_name, normalized_name, dob) "
                + "VALUES ('" + org + "', 1, 'MRN-1', 'CLINIC', 'Synthetic Patient " + suffix + "', "
                + "'synthetic patient " + suffix + "', '1990-05-20')");

        member(statement, org, "clinician-" + suffix, "clinician", bootstrap);
        member(statement, org, "prescriber-" + suffix, "clinician", bootstrap);
        member(statement, org, "pharmacy-" + suffix, "pharmacy_staff", bootstrap);
        member(statement, org, "reception-" + suffix, "reception", bootstrap);
        member(statement, org, "collector-" + suffix, "lab_collector", bootstrap);
        member(statement, org, "verifier-" + suffix, "lab_verifier", bootstrap);
        member(statement, org, "rad-tech-" + suffix, "radiology_staff", bootstrap);
        member(statement, org, "rad-interpreter-" + suffix, "radiology_interpreter", bootstrap);
        member(statement, org, "admin-" + suffix, "tenant_admin", bootstrap);

        policy(statement, org, "admin-" + suffix, "rx02.market", "US");
        policy(statement, org, "admin-" + suffix, "rx02.entitlement.prescriber-" + suffix, "enabled");
        policy(statement, org, "admin-" + suffix, "rx02.approved_channels", "E_PRESCRIBE");
    }

    private static void member(Statement statement, String org, String subject, String role,
                               String bootstrap) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + bootstrap + "'");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) VALUES ('"
                + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    private static void policy(Statement statement, String org, String actor, String key, String value)
            throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + actor + "'");
        statement.execute("INSERT INTO public.config_entry (organization_id, key, version, value) VALUES ('"
                + org + "', '" + key + "', public.next_config_version('" + key + "'), '" + value + "')");
        statement.execute("INSERT INTO public.config_approval (organization_id, entry_id, note) "
                + "SELECT '" + org + "', id, 'synthetic approval' FROM public.config_entry "
                + "WHERE key = '" + key + "' AND version = 1");
    }

    /** Read the synthetic patient identifier for one tenant. */
    static String patientId(String databaseUrl, String org) {
        try (Connection connection = DriverManager.getConnection(databaseUrl, MIGRATE, "")) {
            connection.setAutoCommit(false);
            try (PreparedStatement bind = connection.prepareStatement(
                    "SELECT set_config('app.current_org', CAST(? AS text), true), "
                            + "set_config('app.actor', CAST(? AS text), true)")) {
                bind.setString(1, org);
                bind.setString(2, "clinician-" + suffix(org));
                bind.execute();
            }
            try (Statement statement = connection.createStatement();
                 ResultSet rows = statement.executeQuery(
                         "SELECT id::text FROM public.patient_record WHERE mrn = 'MRN-1'")) {
                assertTrue(rows.next(), "synthetic patient must exist for " + org);
                return rows.getString(1);
            } finally {
                connection.rollback();
            }
        } catch (Exception exception) {
            throw new IllegalStateException("cannot read the synthetic patient", exception);
        }
    }

    private static String suffix(String org) {
        return org.substring(0, 4);
    }

    /** Build one normalized fact for the journey projector. */
    static JourneyEvent event(String id, String org, String item, JourneyItemType type, String source,
                              JourneyStep step, ActorType actor, Instant at, boolean releasedToPatient) {
        return new JourneyEvent(id, org, item, type, source, step, actor, at, releasedToPatient);
    }

    /** Assert the exact complete/pending pattern and the next expected step. */
    static void assertSteps(JourneyView view, Set<JourneyStep> complete, JourneyStep nextExpected) {
        for (JourneyStep step : JourneyStep.values()) {
            StepStatus expected = complete.contains(step) ? StepStatus.COMPLETE : StepStatus.PENDING;
            assertEquals(expected, view.steps().get(step.ordinal()).status(),
                    step + " of " + view.itemId() + " for " + view.organizationId());
        }
        assertEquals(nextExpected, view.nextExpectedStep(), "next step in " + view.itemId());
    }

    /**
     * Assert that a mixed fact collection still projects two isolated timelines.
     *
     * <p>Tenant A facts are later than tenant B facts, so a missing tenant filter
     * would make a tenant B fact win a shared step and fail the time-band check.</p>
     */
    static void assertIsolated(List<JourneyEvent> mixed, String itemA, String itemB,
                               JourneyItemType type) {
        JourneyView viewA = TIMELINE.view(Viewer.CLINICIAN, ORG_A, itemA, type, mixed);
        JourneyView viewB = TIMELINE.view(Viewer.CLINICIAN, ORG_B, itemB, type, mixed);
        assertEquals(ORG_A, viewA.organizationId());
        assertEquals(ORG_B, viewB.organizationId());
        for (StepView step : viewA.steps()) {
            if (step.occurredAt() != null) {
                assertTrue(!step.occurredAt().isBefore(ORG_A_BASE),
                        "tenant A timeline must not contain an earlier tenant B fact: " + step);
            }
        }
        for (StepView step : viewB.steps()) {
            if (step.occurredAt() != null) {
                assertTrue(!step.occurredAt().isAfter(ORG_B_LATEST),
                        "tenant B timeline must not contain a later tenant A fact: " + step);
            }
        }
        // Cross-projecting with the other tenant's item id yields an all-pending journey.
        JourneyView foreign = TIMELINE.view(Viewer.CLINICIAN, ORG_A, itemB, type, mixed);
        assertEquals(Set.of(JourneyStep.values()), pendingSteps(foreign),
                "another item's facts must not appear in this item's timeline");
    }

    private static Set<JourneyStep> pendingSteps(JourneyView view) {
        Set<JourneyStep> pending = new java.util.HashSet<>();
        for (StepView step : view.steps()) {
            if (step.status() == StepStatus.PENDING) {
                pending.add(step.step());
            }
        }
        return pending;
    }

    /** HTTP client that adds the synthetic tenant headers to every request. */
    static final class Client {
        private final RestClient rest;

        Client(int port) {
            this.rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
        }

        ResponseEntity<String> send(HttpMethod method, String uri, String body, String subject, String org) {
            return send(method, uri, body, subject, org, Map.of());
        }

        ResponseEntity<String> send(HttpMethod method, String uri, String body, String subject, String org,
                                    Map<String, String> extra) {
            HttpHeaders headers = new HttpHeaders();
            if (subject != null) {
                headers.set("X-Synthetic-Subject", subject);
            }
            if (org != null) {
                headers.set("X-Active-Organization", org);
            }
            headers.setContentType(MediaType.APPLICATION_JSON);
            extra.forEach(headers::set);
            RestClient.RequestBodySpec spec = rest.method(method).uri(uri)
                    .headers(httpHeaders -> httpHeaders.addAll(headers));
            RestClient.RequestHeadersSpec<?> headerSpec = body == null ? spec : spec.body(body);
            return headerSpec.retrieve()
                    .onStatus(status -> true, (request, response) -> { })
                    .toEntity(String.class);
        }

        JsonNode json(ResponseEntity<String> response) {
            try {
                return MAPPER.readTree(response.getBody());
            } catch (Exception exception) {
                throw new IllegalStateException("cannot parse response: " + response.getBody(), exception);
            }
        }

        String field(ResponseEntity<String> response, String pointer) {
            JsonNode value = json(response).at(pointer);
            assertTrue(!value.isMissingNode(), "missing " + pointer + " in " + response.getBody());
            return value.isNull() ? null : value.asText();
        }
    }

    /** A fresh opaque identifier for a synthetic local item. */
    static String newId() {
        return UUID.randomUUID().toString();
    }
}
