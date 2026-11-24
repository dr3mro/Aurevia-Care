package com.aurevia.tenancy.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;
import java.util.function.Supplier;

import javax.sql.DataSource;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.web.client.RestClient;

import com.aurevia.tenant.TenantContext;
import com.aurevia.tenancy.FacilityService;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

/**
 * ARC-02 evidence that transaction-local tenant bindings never survive on a
 * pooled connection and that every surface refuses work without a context.
 *
 * <p>A dedicated one-connection pool makes physical reuse deterministic: the
 * backend process identifier is compared across borrows, and a session-scoped
 * binding is planted as a positive control so a clean reading cannot be an
 * artefact of connection scrubbing. Two synthetic organizations reuse the same
 * local facility code, while a third code exists only in organization A so a
 * foreign read has an observable empty result. No production code is added.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {ValidatePooledConnectionReuseFailClosedContextTest.DATASOURCE_PROPERTY,
                "spring.datasource.hikari.maximum-pool-size=1"})
@ActiveProfiles("synthetic-auth")
@Timeout(120)
class ValidatePooledConnectionReuseFailClosedContextTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String ONLY_A = "ONLY-A";
    static final String DATABASE = "orevia_arc02_pool_it";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/" + DATABASE;
    static final String DATASOURCE_PROPERTY = "spring.datasource.url=" + URL;
    static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));

    @Autowired
    FacilityService facilityService;

    @Autowired
    DataSource applicationDataSource;

    RestClient rest;

    /**
     * Build the HTTP client for the randomly assigned server port.
     *
     * @param port the port chosen by Spring Boot
     */
    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

    /**
     * Create a fresh forced-RLS database with two synthetic organizations.
     *
     * @throws Exception when the database or a fixture statement fails
     */
    @BeforeAll
    static void prepare() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DO $$ BEGIN "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='orevia_migrate') THEN "
                    + "CREATE ROLE orevia_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='orevia_app') THEN "
                    + "CREATE ROLE orevia_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; END $$;");
            // IF NOT EXISTS cannot correct a pre-existing bypass role, so verify the
            // security precondition instead of assuming it from the creation clause.
            assertRuntimeRolesDoNotBypassRls(statement);
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + DATABASE + " OWNER orevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();

        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seed(statement, ORG_A, "admin-a", "tenant_admin", "MAIN", "Alpha Main");
            seed(statement, ORG_B, "admin-b", "tenant_admin", "MAIN", "Beta Main");
            // A code present only in A makes a foreign tenant's empty result observable.
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("INSERT INTO public.facility (organization_id, code, name) "
                    + "VALUES ('" + ORG_A + "', '" + ONLY_A + "', 'Alpha Only')");
        }
    }

    /**
     * Drop the temporary database so repeated runs start from a clean state.
     *
     * @throws Exception when the database cannot be dropped
     */
    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
        }
    }

    /**
     * Verify that both runtime roles can log in and cannot bypass forced RLS.
     *
     * @param statement statement on the administrative connection
     * @throws SQLException when the role catalogue cannot be read
     */
    private static void assertRuntimeRolesDoNotBypassRls(Statement statement) throws SQLException {
        Set<String> checked = new HashSet<>();
        try (ResultSet result = statement.executeQuery(
                "SELECT rolname, rolbypassrls, rolcanlogin FROM pg_roles "
                        + "WHERE rolname IN ('orevia_migrate', 'orevia_app')")) {
            while (result.next()) {
                checked.add(result.getString(1));
                assertFalse(result.getBoolean(2), result.getString(1) + " must not bypass RLS");
                assertTrue(result.getBoolean(3), result.getString(1) + " must be able to log in");
            }
        }
        assertEquals(Set.of("orevia_migrate", "orevia_app"), checked);
    }

    /**
     * Seed one organization with an administrator and an optional facility.
     *
     * @param statement      migration-role statement with a bound organization
     * @param org            synthetic organization identifier
     * @param subject        synthetic authenticated subject
     * @param role           bounded role key
     * @param facilityCode   tenant-local facility code, or null for no facility
     * @param facilityName   display name of the facility, or null for no facility
     * @throws Exception when a fixture statement fails
     */
    private static void seed(Statement statement, String org, String subject, String role,
                             String facilityCode, String facilityName) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "SELECT '" + org + "', 'Org " + subject + "' "
                + "WHERE NOT EXISTS (SELECT 1 FROM public.organization WHERE organization_id = '" + org + "')");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity WHERE subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, '" + role + "', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
        if (facilityCode != null) {
            statement.execute("INSERT INTO public.facility (organization_id, code, name) "
                    + "VALUES ('" + org + "', '" + facilityCode + "', '" + facilityName + "')");
        }
    }

    /**
     * The rule being met: a transaction-local binding is dropped at commit and
     * the same physical connection can safely carry a different tenant next.
     *
     * @throws Exception when the pool or a statement fails
     */
    @Test
    void reusedPooledConnectionDropsTenantContextAcrossTransactions() throws Exception {
        try (HikariDataSource pool = pool(1)) {
            long pid;
            try (Connection first = pool.getConnection()) {
                first.setAutoCommit(false);
                bind(first, ORG_A, "admin-a");
                assertBoundTenant(first, ORG_A);
                assertEquals(ORG_A, facilityOrganization(first, "MAIN"));
                assertEquals(Set.of(ORG_A), visibleOrganizations(first));
                pid = backendPid(first);
                first.commit();
                // set_config(..., true) leaves no context after the transaction ends.
                assertNoTenantContext(first);
                first.rollback();
            }

            try (Connection control = pool.getConnection()) {
                assertEquals(pid, backendPid(control),
                        "maximumPoolSize=1 must hand out the same physical connection");
                assertNoTenantContext(control);
                // Positive control: session state survives the pool round trip,
                // so the clean readings around it are not a scrubbing artefact.
                bindForSession(control, ORG_A);
                assertEquals(ORG_A, currentSetting(control, "app.current_org"));
            }

            try (Connection reused = pool.getConnection()) {
                assertEquals(pid, backendPid(reused),
                        "maximumPoolSize=1 must hand out the same physical connection");
                assertEquals(ORG_A, currentSetting(reused, "app.current_org"),
                        "the planted session binding must survive, otherwise the assertions are vacuous");
                try (Statement statement = reused.createStatement()) {
                    statement.execute("RESET app.current_org");
                }
                assertNoTenantContext(reused);

                reused.setAutoCommit(false);
                bind(reused, ORG_B, "admin-b");
                assertBoundTenant(reused, ORG_B);
                assertEquals(ORG_B, facilityOrganization(reused, "MAIN"));
                assertEquals(Set.of(ORG_B), visibleOrganizations(reused));
                reused.rollback();
                assertNoTenantContext(reused);
                reused.rollback();
            }
        }
    }

    /**
     * Production path: the guarded transaction manager leaves the application's
     * single pooled connection clean after each tenant's transaction, proven by
     * inspecting the reused physical connection rather than a side pool.
     *
     * @throws Exception when a pooled connection or probe fails
     */
    @Test
    void applicationPoolReusesACleanConnectionAfterGuardedTransactions() throws Exception {
        long pid;
        try (Connection connection = applicationDataSource.getConnection()) {
            pid = backendPid(connection);
            assertNoTenantContext(connection);
        }

        as(ORG_A, "admin-a", () -> facilityService.get("MAIN"));
        try (Connection connection = applicationDataSource.getConnection()) {
            assertEquals(pid, backendPid(connection),
                    "the application pool must reuse the same physical connection");
            assertNoTenantContext(connection);
        }

        as(ORG_B, "admin-b", () -> facilityService.get("MAIN"));
        try (Connection connection = applicationDataSource.getConnection()) {
            assertEquals(pid, backendPid(connection),
                    "the application pool must reuse the same physical connection");
            assertNoTenantContext(connection);
        }
    }

    /**
     * Negative: every layer refuses work when subject, organization or thread
     * context is missing, and forced RLS denies an unbound write.
     *
     * @throws Exception when the pool or a statement fails
     */
    @Test
    void missingTenantContextFailsClosed() throws Exception {
        // Application layer: the guarded transaction manager refuses to open.
        TenantContext.clear();
        assertThrows(IllegalStateException.class, () -> facilityService.get("MAIN"));

        // Request layer: no subject is 401, no active organization is 422.
        assertEquals(HttpStatus.UNAUTHORIZED,
                get("/api/v1/facilities/MAIN", null, ORG_A).getStatusCode());
        assertEquals(422,
                get("/api/v1/facilities/MAIN", "admin-a", null).getStatusCode().value());

        // Database layer: without a binding everything is invisible and a write
        // is rejected with insufficient_privilege (SQLState 42501).
        try (HikariDataSource pool = pool(1);
             Connection connection = pool.getConnection()) {
            assertNoTenantContext(connection);
            SQLException denied = assertThrows(SQLException.class,
                    () -> insertFacility(connection, ORG_A, "UNBOUND", "Unbound"));
            assertEquals("42501", denied.getSQLState(), "an unbound insert must be denied by RLS");
        }
    }

    /**
     * Negative: a foreign tenant reading another tenant's facility gets no row
     * through both the HTTP entry point and the forced-RLS query path.
     *
     * @throws Exception when the pool or a statement fails
     */
    @Test
    void foreignTenantContextSeesNoRows() throws Exception {
        ResponseEntity<String> own = get("/api/v1/facilities/" + ONLY_A, "admin-a", ORG_A);
        assertEquals(HttpStatus.OK, own.getStatusCode());
        assertTrue(own.getBody() != null && own.getBody().contains(ORG_A));
        assertFalse(own.getBody() != null && own.getBody().contains(ORG_B));
        assertEquals(HttpStatus.NOT_FOUND,
                get("/api/v1/facilities/" + ONLY_A, "admin-b", ORG_B).getStatusCode());

        try (HikariDataSource pool = pool(1);
             Connection connection = pool.getConnection()) {
            connection.setAutoCommit(false);
            bind(connection, ORG_B, "admin-b");
            assertEquals(0, count(connection,
                    "SELECT count(*) FROM public.facility WHERE code = '" + ONLY_A + "'"),
                    "a foreign transaction-local binding must see no row");
            connection.rollback();
            assertNoTenantContext(connection);

            connection.setAutoCommit(false);
            bind(connection, ORG_A, "admin-a");
            assertEquals(1, count(connection,
                    "SELECT count(*) FROM public.facility WHERE code = '" + ONLY_A + "'"),
                    "the owning tenant must still see its own row");
            connection.rollback();
            assertNoTenantContext(connection);
        }
    }

    /**
     * Create a dedicated pool whose single connection is reused deterministically.
     *
     * @param maximumPoolSize maximum number of physical connections
     * @return a new Hikari data source using the non-bypass runtime role
     */
    private static HikariDataSource pool(int maximumPoolSize) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(URL);
        config.setUsername("orevia_app");
        config.setPassword("");
        config.setMaximumPoolSize(maximumPoolSize);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(10_000);
        config.setPoolName("pool-reuse-" + maximumPoolSize);
        return new HikariDataSource(config);
    }

    /**
     * Bind a transaction-local tenant on a pooled connection.
     *
     * @param connection   pooled connection inside a transaction
     * @param organization synthetic organization identifier
     * @param subject      synthetic authenticated subject
     * @throws SQLException when the binding cannot be written
     */
    private static void bind(Connection connection, String organization, String subject) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('app.current_org', CAST(? AS text), true), "
                        + "set_config('app.actor', CAST(? AS text), true)")) {
            statement.setString(1, organization);
            statement.setString(2, subject);
            statement.execute();
        }
    }

    /**
     * Plant a session-scoped binding, used only as a positive control.
     *
     * @param connection   pooled connection
     * @param organization synthetic organization identifier
     * @throws SQLException when the setting cannot be written
     */
    private static void bindForSession(Connection connection, String organization) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('app.current_org', CAST(? AS text), false)")) {
            statement.setString(1, organization);
            statement.execute();
        }
    }

    /**
     * Assert that both the GUC and the RLS resolver see the bound tenant.
     *
     * @param connection   pooled connection
     * @param organization expected synthetic organization identifier
     * @throws SQLException when a probe fails
     */
    private static void assertBoundTenant(Connection connection, String organization) throws SQLException {
        assertEquals(organization, currentSetting(connection, "app.current_org"));
        assertEquals(organization, scalar(connection, "SELECT public.app_org()::text"));
    }

    /**
     * Assert that a borrowed connection carries no request-scoped tenant state.
     *
     * @param connection pooled connection
     * @throws SQLException when a probe fails
     */
    private static void assertNoTenantContext(Connection connection) throws SQLException {
        for (String setting : Set.of("app.current_org", "app.actor", "app.purpose", "app.correlation_id")) {
            String value = currentSetting(connection, setting);
            assertTrue(value == null || value.isBlank(),
                    "pooled connection retained " + setting + "=" + value);
        }
        assertNull(scalar(connection, "SELECT public.app_org()::text"),
                "app_org() must be null without a tenant binding");
        assertEquals(0, count(connection, "SELECT count(*) FROM public.facility"));
        assertEquals(0, count(connection, "SELECT count(*) FROM public.organization"));
        assertEquals(0, count(connection, "SELECT count(*) FROM public.audit_event"));
    }

    /**
     * Insert a facility with an explicit organization value to probe RLS WITH CHECK.
     *
     * @param connection   pooled connection
     * @param organization organization identifier to attempt to write
     * @param code         facility code
     * @param name         facility name
     * @throws SQLException when RLS denies or the insert fails for another reason
     */
    private static void insertFacility(Connection connection, String organization, String code, String name)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO public.facility (organization_id, code, name) "
                        + "VALUES (CAST(? AS uuid), ?, ?)")) {
            statement.setString(1, organization);
            statement.setString(2, code);
            statement.setString(3, name);
            statement.executeUpdate();
        }
    }

    /**
     * Return the physical backend identifier of a connection.
     *
     * @param connection pooled connection
     * @return PostgreSQL backend process identifier
     * @throws SQLException when the probe fails
     */
    private static long backendPid(Connection connection) throws SQLException {
        return Long.parseLong(scalar(connection, "SELECT pg_backend_pid()::text"));
    }

    /**
     * Return the owning organization of a facility visible to the current binding.
     *
     * @param connection pooled connection
     * @param code       tenant-local facility code
     * @return owning organization identifier as text
     * @throws SQLException when the facility is not visible
     */
    private static String facilityOrganization(Connection connection, String code) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT organization_id::text FROM public.facility WHERE code = ?")) {
            statement.setString(1, code);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next(), "facility " + code + " must be visible to the bound tenant");
                return result.getString(1);
            }
        }
    }

    /**
     * Return the organizations visible to the current binding.
     *
     * @param connection pooled connection
     * @return set of visible organization identifiers
     * @throws SQLException when the probe fails
     */
    private static Set<String> visibleOrganizations(Connection connection) throws SQLException {
        Set<String> organizations = new HashSet<>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(
                     "SELECT DISTINCT organization_id::text FROM public.facility")) {
            while (result.next()) {
                organizations.add(result.getString(1));
            }
        }
        assertFalse(organizations.isEmpty(), "a bound tenant must see its own facilities");
        return organizations;
    }

    /**
     * Read one custom setting, or null when it is not set.
     *
     * @param connection pooled connection
     * @param setting    custom GUC name
     * @return current value, or null when unset
     * @throws SQLException when the probe fails
     */
    private static String currentSetting(Connection connection, String setting) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT current_setting(CAST(? AS text), true)::text")) {
            statement.setString(1, setting);
            try (ResultSet result = statement.executeQuery()) {
                assertTrue(result.next());
                return result.getString(1);
            }
        }
    }

    /**
     * Return the single long value of a query.
     *
     * @param connection pooled connection
     * @param sql        query returning exactly one row and one column
     * @return value as text
     * @throws SQLException when the probe fails
     */
    private static String scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next(), "query must return one row: " + sql);
            return result.getString(1);
        }
    }

    /**
     * Return the single count value of a query.
     *
     * @param connection pooled connection
     * @param sql        count query
     * @return count as a long
     * @throws SQLException when the probe fails
     */
    private static long count(Connection connection, String sql) throws SQLException {
        return Long.parseLong(scalar(connection, sql));
    }

    /**
     * Bind the request-style tenant context on the calling thread for one action.
     *
     * @param organization active organization
     * @param subject      synthetic authenticated subject
     * @param action       action executed under the bound context
     * @param <T>          result type of the action
     * @return the action result
     */
    private <T> T as(String organization, String subject, Supplier<T> action) {
        TenantContext.set(new TenantContext(organization, subject, TenantContext.PURPOSE, "pool-reuse-it"));
        try {
            return action.get();
        } finally {
            TenantContext.clear();
        }
    }

    /**
     * Build the synthetic authentication headers for a request.
     *
     * @param subject      synthetic subject, or null to omit the header
     * @param organization active organization, or null to omit the header
     * @return HTTP headers carrying only synthetic values
     */
    private HttpHeaders headers(String subject, String organization) {
        HttpHeaders headers = new HttpHeaders();
        if (subject != null) {
            headers.set("X-Synthetic-Subject", subject);
        }
        if (organization != null) {
            headers.set("X-Active-Organization", organization);
        }
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    /**
     * Issue a GET against the facilities entry point.
     *
     * @param uri          request URI
     * @param subject      synthetic subject, or null to omit the header
     * @param organization active organization, or null to omit the header
     * @return HTTP response including the status and body
     */
    private ResponseEntity<String> get(String uri, String subject, String organization) {
        return rest.method(HttpMethod.GET).uri(uri)
                .headers(httpHeaders -> httpHeaders.addAll(headers(subject, organization)))
                .retrieve().onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }
}
