package com.aurevia.it;

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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

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

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import org.flywaydb.core.Flyway;

/**
 * CW-003 acceptance for S-G0-04 and ARC-02: pooled connections never carry a
 * tenant context between requests or transactions, and every statement without
 * a binding fails closed.
 *
 * <p>The suite drives the real HTTP entry point through the application's Hikari
 * pool (two connections) and also exercises dedicated pools with one and two
 * connections so that physical connection reuse is deterministic. Fixtures are
 * synthetic: two organizations sharing the facility code {@code MAIN} and one
 * denied role.</p>
 */
@Tag("integration")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:postgresql://127.0.0.1:5432/orevia_concurrent_it")
@ActiveProfiles("synthetic-auth")
@Timeout(120)
class ConcurrentTenantIsolationTest {

    static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    static final String URL = "jdbc:postgresql://127.0.0.1:5432/orevia_concurrent_it";
    static final String ADMIN = System.getenv().getOrDefault(
            "OREVIA_PG_ADMIN", System.getProperty("user.name"));

    /** One successful request, kept so its audit row can be traced back to the caller. */
    private record Observed(String correlationId, String organizationId) {
    }

    private RestClient rest;

    @Autowired
    private DataSource applicationDataSource;

    @BeforeEach
    void initRestClient(@LocalServerPort int port) {
        rest = RestClient.builder().baseUrl("http://localhost:" + port).build();
    }

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
            statement.execute("DROP DATABASE IF EXISTS orevia_concurrent_it WITH (FORCE)");
            statement.execute("CREATE DATABASE orevia_concurrent_it OWNER orevia_migrate");
        }
        Flyway.configure()
                .dataSource(URL, "orevia_migrate", "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();

        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "");
             Statement statement = connection.createStatement()) {
            seed(statement, ORG_A, "admin-a", "tenant_admin", "MAIN", "Alpha Main");
            seed(statement, ORG_B, "admin-b", "tenant_admin", "MAIN", "Beta Main");
            seed(statement, ORG_A, "reception-a", "reception", null, null);
        }
    }

    @AfterAll
    static void cleanUp() throws Exception {
        try (Connection root = DriverManager.getConnection(
                "jdbc:postgresql://127.0.0.1:5432/postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS orevia_concurrent_it WITH (FORCE)");
        }
    }

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
     * Run one worker body per thread, collect every failure and fail once all
     * workers finish. A worker exception is recorded rather than swallowed so a
     * failed task cannot deadlock the others behind a barrier.
     *
     * @param workers number of worker threads
     * @param body    work executed with the worker index
     * @throws Exception when a worker future cannot be awaited
     */
    private static void runConcurrently(int workers, Worker body) throws Exception {
        Queue<Throwable> failures = new ConcurrentLinkedQueue<>();
        ExecutorService executor = Executors.newFixedThreadPool(workers);
        try {
            List<Future<?>> futures = new ArrayList<>();
            for (int index = 0; index < workers; index++) {
                int worker = index;
                futures.add(executor.submit(() -> {
                    try {
                        body.run(worker);
                    } catch (Throwable failure) {
                        failures.add(failure);
                    }
                }));
            }
            for (Future<?> future : futures) {
                future.get(120, TimeUnit.SECONDS);
            }
        } finally {
            executor.shutdownNow();
        }
        assertTrue(failures.isEmpty(), () -> "concurrent failures: " + failures);
    }

    /**
     * Worker body used by {@link #runConcurrently(int, Worker)}.
     */
    @FunctionalInterface
    private interface Worker {

        /**
         * Execute the worker body.
         *
         * @param worker zero-based worker index
         * @throws Exception when the body cannot complete
         */
        void run(int worker) throws Exception;
    }

    /**
     * Many request threads alternate tenants over the two-connection application
     * pool: every response and every audit row must belong to the caller.
     *
     * @throws Exception when a worker fails or the pool does not drain in time
     */
    @Test
    void concurrentRequestsNeverObserveAnotherTenant() throws Exception {
        int workers = 6;
        int iterations = 12;
        List<Observed> observed = new CopyOnWriteArrayList<>();

        runConcurrently(workers, worker -> {
            for (int iteration = 0; iteration < iterations; iteration++) {
                boolean alpha = (worker + iteration) % 2 == 0;
                String organization = alpha ? ORG_A : ORG_B;
                String subject = alpha ? "admin-a" : "admin-b";
                String otherOrganization = alpha ? ORG_B : ORG_A;
                String correlation = "conc-" + worker + "-" + iteration;

                ResponseEntity<String> response = get(subject, organization, correlation);
                String body = response.getBody();
                assertEquals(HttpStatus.OK, response.getStatusCode(),
                        "request " + correlation + " must succeed");
                assertTrue(body != null && body.contains(organization),
                        "request " + correlation + " must return its own tenant");
                assertFalse(body != null && body.contains(otherOrganization),
                        "request " + correlation + " must never return the other tenant");
                observed.add(new Observed(correlation, organization));
            }
        });

        assertEquals(workers * iterations, observed.size());

        Map<String, Integer> auditA = auditCorrelationCounts(ORG_A);
        Map<String, Integer> auditB = auditCorrelationCounts(ORG_B);
        for (Observed entry : observed) {
            Map<String, Integer> own = ORG_A.equals(entry.organizationId()) ? auditA : auditB;
            Map<String, Integer> other = ORG_A.equals(entry.organizationId()) ? auditB : auditA;
            assertEquals(1, own.getOrDefault(entry.correlationId(), 0),
                    "audit row must be written for " + entry.correlationId());
            assertEquals(0, other.getOrDefault(entry.correlationId(), 0),
                    "audit row must not leak into another tenant: " + entry.correlationId());
        }
    }

    /**
     * Two barrier-synchronized creates of the same code by different tenants
     * must both succeed and each read back its own row.
     *
     * @throws Exception when a worker fails
     */
    @Test
    void concurrentCreatesOfTheSameCodeStayTenantScoped() throws Exception {
        CyclicBarrier barrier = new CyclicBarrier(2);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<ResponseEntity<String>> alpha = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return post("SHARED", "admin-a", ORG_A, "shared-a");
            });
            Future<ResponseEntity<String>> beta = executor.submit(() -> {
                barrier.await(10, TimeUnit.SECONDS);
                return post("SHARED", "admin-b", ORG_B, "shared-b");
            });
            assertEquals(HttpStatus.CREATED, alpha.get(30, TimeUnit.SECONDS).getStatusCode());
            assertEquals(HttpStatus.CREATED, beta.get(30, TimeUnit.SECONDS).getStatusCode());
        } finally {
            executor.shutdownNow();
        }

        ResponseEntity<String> alphaRead = get("admin-a", ORG_A, "shared-read-a");
        assertEquals(HttpStatus.OK, alphaRead.getStatusCode());
        assertTrue(alphaRead.getBody().contains(ORG_A));
        assertFalse(alphaRead.getBody().contains(ORG_B));

        ResponseEntity<String> betaRead = get("admin-b", ORG_B, "shared-read-b");
        assertEquals(HttpStatus.OK, betaRead.getStatusCode());
        assertTrue(betaRead.getBody().contains(ORG_B));
        assertFalse(betaRead.getBody().contains(ORG_A));
    }

    /**
     * Denied requests (403) interleaved with successful ones must not leave a
     * tenant binding on a pooled connection that the next request observes.
     *
     * @throws Exception when a worker fails
     */
    @Test
    void deniedRequestsDoNotPoisonPooledConnections() throws Exception {
        int workers = 4;
        int iterations = 15;

        runConcurrently(workers, worker -> {
            for (int iteration = 0; iteration < iterations; iteration++) {
                if ((worker + iteration) % 3 == 0) {
                    assertEquals(HttpStatus.FORBIDDEN,
                            get("reception-a", ORG_A, "deny-" + worker + "-" + iteration)
                                    .getStatusCode());
                    continue;
                }
                boolean alpha = (worker + iteration) % 2 == 0;
                String organization = alpha ? ORG_A : ORG_B;
                String subject = alpha ? "admin-a" : "admin-b";
                String otherOrganization = alpha ? ORG_B : ORG_A;
                ResponseEntity<String> response =
                        get(subject, organization, "mix-" + worker + "-" + iteration);
                String body = response.getBody();
                assertEquals(HttpStatus.OK, response.getStatusCode());
                assertTrue(body != null && body.contains(organization));
                assertFalse(body != null && body.contains(otherOrganization));
            }
        });
    }

    /**
     * A single-connection pool must reuse the same physical connection for
     * sequential transactions, and that connection must drop the previous
     * tenant's context at commit and at rollback.
     *
     * <p>A session-level binding is planted as a positive control: it survives
     * the pool round trip, so the clean readings around it are real evidence
     * that the application never writes session-scoped tenant state and not an
     * artefact of connection scrubbing by the pool.</p>
     *
     * @throws Exception when the pool or the database rejects a statement
     */
    @Test
    void reusedPooledConnectionDropsTheCommittedTenantContext() throws Exception {
        try (HikariDataSource pool = pool(1)) {
            long pid;
            try (Connection connection = pool.getConnection()) {
                connection.setAutoCommit(false);
                bind(connection, ORG_A, "admin-a");
                assertBoundTenant(connection, ORG_A);
                assertEquals(ORG_A, facilityOrganization(connection, "MAIN"));
                assertEquals(Set.of(ORG_A), visibleOrganizations(connection));
                pid = backendPid(connection);
                connection.commit();

                assertNoTenantContext(connection);
                connection.rollback();
            }

            try (Connection planted = pool.getConnection()) {
                assertEquals(pid, backendPid(planted),
                        "maximumPoolSize=1 must hand out the same physical connection");
                assertNoTenantContext(planted);
                bindForSession(planted, ORG_A);
                assertEquals(ORG_A, currentSetting(planted, "app.current_org"));
            }

            try (Connection reused = pool.getConnection()) {
                assertEquals(pid, backendPid(reused),
                        "maximumPoolSize=1 must hand out the same physical connection");
                assertEquals(ORG_A, currentSetting(reused, "app.current_org"),
                        "a session-level binding must survive the pool, otherwise the assertions below are vacuous");
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
     * Without a binding every tenant table is invisible and inserts violate RLS,
     * including a bound tenant attempting to write another tenant's row.
     *
     * @throws Exception when the pool cannot be created
     */
    @Test
    void missingOrForeignTenantContextFailsClosed() throws Exception {
        try (HikariDataSource pool = pool(1);
             Connection connection = pool.getConnection()) {
            assertNoTenantContext(connection);

            SQLException unbound = assertThrows(SQLException.class,
                    () -> insertFacility(connection, ORG_A, "UNBOUND", "Unbound"));
            assertEquals("42501", unbound.getSQLState(), "unbound insert must be denied by RLS");

            connection.setAutoCommit(false);
            bind(connection, ORG_A, "admin-a");
            assertBoundTenant(connection, ORG_A);
            SQLException foreign = assertThrows(SQLException.class,
                    () -> insertFacility(connection, ORG_B, "FOREIGN", "Foreign"));
            assertEquals("42501", foreign.getSQLState(), "cross-tenant insert must be denied by RLS");
            connection.rollback();
            assertNoTenantContext(connection);
            connection.rollback();
        }
    }

    /**
     * Two simultaneous transactions on a two-connection pool must each keep
     * their own context and their own physical connection.
     *
     * @throws Exception when a worker fails
     */
    @Test
    void simultaneousTransactionsOnSeparateConnectionsKeepTheirOwnTenant() throws Exception {
        try (HikariDataSource pool = pool(2)) {
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                for (int round = 0; round < 25; round++) {
                    CyclicBarrier barrier = new CyclicBarrier(2);
                    Queue<Long> pids = new ConcurrentLinkedQueue<>();
                    Future<?> alpha = executor.submit(() -> {
                        holdTenantTransaction(pool, ORG_A, "admin-a", barrier, pids);
                        return null;
                    });
                    Future<?> beta = executor.submit(() -> {
                        holdTenantTransaction(pool, ORG_B, "admin-b", barrier, pids);
                        return null;
                    });
                    alpha.get(30, TimeUnit.SECONDS);
                    beta.get(30, TimeUnit.SECONDS);
                    assertEquals(2, new HashSet<>(pids).size(),
                            "simultaneous transactions must hold distinct physical connections");
                }
            } finally {
                executor.shutdownNow();
            }
        }
    }

    /**
     * After concurrent HTTP traffic the application pool's physical connections
     * must be free of every request-scoped setting, so the next borrower cannot
     * inherit a tenant.
     *
     * @throws Exception when the pool or the database rejects a statement
     */
    @Test
    void applicationPoolReturnsConnectionsWithoutTenantContext() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 10; round++) {
                CyclicBarrier barrier = new CyclicBarrier(2);
                final String correlationA = "pool-a-" + round;
                final String correlationB = "pool-b-" + round;
                Future<ResponseEntity<String>> alpha = executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return get("admin-a", ORG_A, correlationA);
                });
                Future<ResponseEntity<String>> beta = executor.submit(() -> {
                    barrier.await(10, TimeUnit.SECONDS);
                    return get("admin-b", ORG_B, correlationB);
                });
                assertEquals(HttpStatus.OK, alpha.get(30, TimeUnit.SECONDS).getStatusCode());
                assertEquals(HttpStatus.OK, beta.get(30, TimeUnit.SECONDS).getStatusCode());
            }
        } finally {
            executor.shutdownNow();
        }

        List<Connection> borrowed = new ArrayList<>();
        Set<Long> pids = new HashSet<>();
        try {
            for (int index = 0; index < 2; index++) {
                Connection connection = applicationDataSource.getConnection();
                borrowed.add(connection);
                pids.add(backendPid(connection));
            }
            assertEquals(2, pids.size(),
                    "borrowing both application pool connections must yield two physical connections");
            for (Connection connection : borrowed) {
                assertNoTenantContext(connection);
            }
        } finally {
            for (Connection connection : borrowed) {
                connection.close();
            }
        }
    }

    private static void holdTenantTransaction(HikariDataSource pool, String organization, String subject,
                                              CyclicBarrier barrier, Queue<Long> pids) throws Exception {
        try (Connection connection = pool.getConnection()) {
            connection.setAutoCommit(false);
            try {
                bind(connection, organization, subject);
                pids.add(backendPid(connection));
                barrier.await(10, TimeUnit.SECONDS);

                assertEquals(organization, currentSetting(connection, "app.current_org"));
                assertEquals(Set.of(organization), visibleOrganizations(connection));
                assertEquals(organization, facilityOrganization(connection, "MAIN"));
            } finally {
                connection.rollback();
            }
        }
    }

    private static HikariDataSource pool(int maximumPoolSize) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(URL);
        config.setUsername("orevia_app");
        config.setPassword("");
        config.setMaximumPoolSize(maximumPoolSize);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(10_000);
        config.setPoolName("isolation-" + maximumPoolSize);
        return new HikariDataSource(config);
    }

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
     * Plant a session-scoped binding. Only a positive control uses this: the
     * production code paths must never bind tenant state outside a transaction.
     *
     * @param connection    pooled connection
     * @param organization  synthetic organization
     * @throws SQLException when the setting cannot be written
     */
    private static void bindForSession(Connection connection, String organization) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT set_config('app.current_org', CAST(? AS text), false)")) {
            statement.setString(1, organization);
            statement.execute();
        }
    }

    private static void assertBoundTenant(Connection connection, String organization) throws SQLException {
        assertEquals(organization, currentSetting(connection, "app.current_org"));
        assertEquals(organization, scalar(connection, "SELECT public.app_org()::text"));
    }

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

    private static long backendPid(Connection connection) throws SQLException {
        return Long.parseLong(scalar(connection, "SELECT pg_backend_pid()::text"));
    }

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

    private static void assertNoTenantContext(Connection connection) throws SQLException {
        for (String setting : List.of("app.current_org", "app.actor", "app.purpose", "app.correlation_id")) {
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

    private static long count(Connection connection, String sql) throws SQLException {
        return Long.parseLong(scalar(connection, sql));
    }

    private static String scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            assertTrue(result.next(), "query must return one row: " + sql);
            return result.getString(1);
        }
    }

    private static Map<String, Integer> auditCorrelationCounts(String organization) throws SQLException {
        try (Connection connection = DriverManager.getConnection(URL, "orevia_migrate", "")) {
            connection.setAutoCommit(false);
            try (PreparedStatement binding = connection.prepareStatement(
                    "SELECT set_config('app.current_org', CAST(? AS text), true)")) {
                binding.setString(1, organization);
                binding.execute();
            }
            Map<String, Integer> counts = new HashMap<>();
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery(
                         "SELECT correlation_id, count(*) FROM public.audit_event "
                                 + "WHERE correlation_id LIKE 'conc-%' GROUP BY correlation_id")) {
                while (result.next()) {
                    counts.put(result.getString(1), result.getInt(2));
                }
            }
            connection.commit();
            return counts;
        }
    }

    private HttpHeaders headers(String subject, String organization, String correlation) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Synthetic-Subject", subject);
        headers.set("X-Active-Organization", organization);
        headers.set("X-Correlation-Id", correlation);
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    private ResponseEntity<String> get(String subject, String organization, String correlation) {
        return rest.method(HttpMethod.GET).uri("/api/v1/facilities/MAIN")
                .headers(httpHeaders -> httpHeaders.addAll(headers(subject, organization, correlation)))
                .retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }

    private ResponseEntity<String> post(String code, String subject, String organization, String correlation) {
        String body = "{\"code\":\"" + code + "\",\"name\":\"Concurrent " + code + "\"}";
        return rest.method(HttpMethod.POST).uri("/api/v1/facilities")
                .headers(httpHeaders -> httpHeaders.addAll(headers(subject, organization, correlation)))
                .body(body)
                .retrieve()
                .onStatus(status -> true, (request, response) -> { })
                .toEntity(String.class);
    }
}
