package com.aurevia.smartclinic.staffmsg.instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.smartclinic.api.ApiErrorHandler;
import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.staffmsg.instant.InstantMessagingContract.CreateGroupRequest;
import com.aurevia.smartclinic.staffmsg.instant.InstantMessagingContract.MarkReadRequest;
import com.aurevia.smartclinic.staffmsg.instant.InstantMessagingContract.OpenDirectRequest;
import com.aurevia.smartclinic.staffmsg.instant.InstantMessagingContract.SendRequest;
import com.aurevia.smartclinic.tenant.TenantContext;
import com.aurevia.smartclinic.web.TenantContextInterceptor;

import tools.jackson.databind.ObjectMapper;

/**
 * SMSG-03 acceptance on real PostgreSQL: a retry publishes one message, a removed
 * member loses only later messages, unread counts are per person, and a foreign
 * facility or tenant is refused. All fixtures are synthetic.
 */
@Tag("integration")
class InstantMessagingIntegrationTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String FACILITY = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String OTHER_FACILITY = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String ACTOR = "nurse-a";
    private static final String PEER = "nurse-b";
    private static final String BASE = "/api/v1/staff/instant";
    private static final String DATABASE = "smartclinic_instant_it";
    private static final String PREFIX = "jdbc:postgresql://127.0.0.1:5432/";
    private static final String MIGRATE = "smartclinic_migrate";
    private static final String APP = "smartclinic_app";
    private static final String ADMIN = System.getenv().getOrDefault(
            "SMARTCLINIC_PG_ADMIN", System.getProperty("user.name"));
    private static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");

    private SingleConnectionDataSource dataSource;
    private JdbcInstantConversationStore store;
    private InstantMessagingService service;
    private MockMvc mvc;
    private ObjectMapper mapper;

    @BeforeAll
    static void createRoles() throws Exception {
        try (Connection root = DriverManager.getConnection(PREFIX + "postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DO $$ BEGIN "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='smartclinic_migrate') THEN "
                    + "CREATE ROLE smartclinic_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='smartclinic_app') THEN "
                    + "CREATE ROLE smartclinic_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; END $$;");
        }
    }

    @BeforeEach
    void setUp() throws Exception {
        try (Connection root = DriverManager.getConnection(PREFIX + "postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + DATABASE + " OWNER " + MIGRATE);
        }
        Flyway.configure()
                .dataSource(PREFIX + DATABASE, MIGRATE, "")
                .locations("filesystem:../../db/migrations")
                .load().migrate();
        try (Connection connection = DriverManager.getConnection(PREFIX + DATABASE, MIGRATE, "");
             Statement statement = connection.createStatement()) {
            seedStaff(statement, ORG_A, ACTOR, FACILITY);
            seedStaff(statement, ORG_A, PEER, FACILITY);
            statement.execute("SET app.current_org = '" + ORG_B + "'");
            statement.execute("SET app.actor = 'foreign-b'");
            statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                    + "VALUES ('" + ORG_B + "', 'Org B')");
            statement.execute("INSERT INTO public.user_identity "
                    + "(organization_id, subject, display_name) VALUES ('" + ORG_B + "', 'foreign-b', 'Foreign')");
            statement.execute("INSERT INTO public.facility (organization_id, id, code, name) "
                    + "VALUES ('" + ORG_B + "', '" + OTHER_FACILITY + "', 'fb', 'Facility B')");
        }

        Connection connection = DriverManager.getConnection(PREFIX + DATABASE, APP, "");
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("SET app.actor = '" + ACTOR + "'");
        }
        dataSource = new SingleConnectionDataSource(connection, true);
        TransactionTemplate transactions = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        store = new JdbcInstantConversationStore(new NamedParameterJdbcTemplate(dataSource), transactions);
        service = new InstantMessagingService(store, Clock.fixed(NOW, ZoneOffset.UTC));
        context(ACTOR);
        // Drive the real controller and real service against the same real database
        // so a request is proven to reach the durable store through every layer.
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(new InstantMessagingController(service))
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
        mapper = new ObjectMapper();
    }

    private static MockHttpServletRequestBuilder headers(MockHttpServletRequestBuilder request) {
        return request.header("X-Synthetic-Subject", ACTOR)
                .header("X-Active-Organization", ORG_A);
    }

    @AfterEach
    void tearDown() throws Exception {
        TenantContext.clear();
        if (dataSource != null) {
            dataSource.destroy();
        }
        try (Connection root = DriverManager.getConnection(PREFIX + "postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
        }
    }

    private static void seedStaff(Statement statement, String org, String subject, String facility) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "SELECT '" + org + "', 'Org " + subject + "' "
                + "WHERE NOT EXISTS (SELECT 1 FROM public.organization WHERE organization_id = '" + org + "')");
        statement.execute("INSERT INTO public.user_identity "
                + "(organization_id, subject, display_name) VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("INSERT INTO public.facility (organization_id, id, code, name) "
                + "SELECT '" + org + "', '" + facility + "', 'fa', 'Facility A' "
                + "WHERE NOT EXISTS (SELECT 1 FROM public.facility WHERE id = '" + facility + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, 'active' FROM public.user_identity "
                + "WHERE organization_id = '" + org + "' AND subject = '" + subject + "'");
        statement.execute("INSERT INTO public.role_assignment (organization_id, membership_id, role_key, granted_by) "
                + "SELECT '" + org + "', m.id, 'nurse', u.id FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
        statement.execute("INSERT INTO public.membership_facility (organization_id, membership_id, facility_id) "
                + "SELECT '" + org + "', m.id, '" + facility + "' FROM public.membership m "
                + "JOIN public.user_identity u ON u.organization_id = m.organization_id AND u.id = m.user_id "
                + "WHERE u.subject = '" + subject + "'");
    }

    private static void context(String subject) {
        TenantContext.set(new TenantContext(ORG_A, subject, "STAFF_MESSAGING", "correlation-it"));
    }

    private String direct() {
        return service.openDirect(new OpenDirectRequest(FACILITY, PEER)).id();
    }

    @Test
    void aRetriedSendPublishesExactlyOneMessage() {
        String conversation = direct();
        var first = service.send(new SendRequest(FACILITY, conversation, "one"), "retry-key");
        var retry = service.send(new SendRequest(FACILITY, conversation, "different body"), "retry-key");

        assertEquals(first.id(), retry.id());
        assertEquals(first.sequence(), retry.sequence());
        assertEquals("one", retry.body());
        assertEquals(1, store.messages(ORG_A, conversation, 0L, null).size());
        assertEquals(1L, first.sequence());
    }

    @Test
    void aRemovedMemberKeepsHistoryButCannotReadLaterMessages() throws Exception {
        String conversation = service.createGroup(
                new CreateGroupRequest(FACILITY, "handover", java.util.List.of(PEER))).id();
        service.send(new SendRequest(FACILITY, conversation, "before leave"), "before");
        service = new InstantMessagingService(store, Clock.fixed(NOW.plusSeconds(5), ZoneOffset.UTC));
        service.removeParticipant(FACILITY, conversation, PEER);
        service.send(new SendRequest(FACILITY, conversation, "after leave"), "after");

        assertEquals(2, store.messages(ORG_A, conversation, 0L, null).size());
        context(PEER);
        var visible = service.messages(FACILITY, conversation);
        assertEquals(1, visible.size());
        assertEquals("before leave", visible.get(0).body());
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.send(new SendRequest(FACILITY, conversation, "reply"), "blocked")).status());
    }

    @Test
    void unreadCountsArePerPersonAndASeenMessageIsNoLongerUnread() throws Exception {
        String conversation = direct();
        service.send(new SendRequest(FACILITY, conversation, "first"), "m1");
        service.send(new SendRequest(FACILITY, conversation, "second"), "m2");

        context(PEER);
        assertEquals(2L, service.unreadCounts(FACILITY).get(0).unreadCount());
        service.markRead(new MarkReadRequest(FACILITY, conversation, 2L));
        assertEquals(0L, service.unreadCounts(FACILITY).get(0).unreadCount());
        context(ACTOR);
        assertEquals(0L, service.unreadCounts(FACILITY).get(0).unreadCount());
    }

    /**
     * A stale read acknowledgement must not move a cursor backwards: otherwise
     * messages already marked read would reappear as unread for that person.
     */
    @Test
    void aLowerReadAcknowledgementDoesNotMoveTheCursorBackwards() {
        String conversation = direct();
        service.send(new SendRequest(FACILITY, conversation, "first"), "m1");
        service.send(new SendRequest(FACILITY, conversation, "second"), "m2");

        context(PEER);
        service.markRead(new MarkReadRequest(FACILITY, conversation, 2L));
        assertEquals(0L, service.unreadCounts(FACILITY).get(0).unreadCount());
        service.markRead(new MarkReadRequest(FACILITY, conversation, 1L));
        assertEquals(0L, service.unreadCounts(FACILITY).get(0).unreadCount());
    }

    @Test
    void aWrongFacilityOrTenantIsRefused() throws Exception {
        String conversation = direct();
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.send(new SendRequest(OTHER_FACILITY, conversation, "body"), "wrong-facility"))
                .status());

        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_B + "'");
            assertTrue(store.conversation(ORG_A, conversation).isEmpty());
            statement.execute("SET app.current_org = '" + ORG_A + "'");
        }
    }

    /**
     * The tenant's membership-facility fold confines an assigned subject to the
     * facilities they were invited to, even when they have an active staff role.
     */
    @Test
    void aStaffMemberAssignedElsewhereCannotOpenAConversationInAnotherFacility() {
        assertTrue(store.facilityWithinScope(ORG_A, ACTOR, FACILITY));
        assertTrue(!store.facilityWithinScope(ORG_A, ACTOR, OTHER_FACILITY));
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.openDirect(new OpenDirectRequest(OTHER_FACILITY, PEER))).status());
    }

    @Test
    void activeStaffRequiresBothMembershipAndAnActiveRole() {
        assertTrue(store.activeStaff(ORG_A, ACTOR));
        assertTrue(!store.activeStaff(ORG_A, "ghost"));
    }

    @Test
    void closingAMembershipKeepsTheRowForHistory() {
        String conversation = service.createGroup(
                new CreateGroupRequest(FACILITY, "handover", java.util.List.of(PEER))).id();
        service.removeParticipant(FACILITY, conversation, PEER);
        assertEquals(2, store.participants(ORG_A, conversation).size());
        assertTrue(store.participants(ORG_A, conversation).stream().anyMatch(
                participant -> participant.subject().equals(PEER) && participant.leftAt() != null));
    }

    /**
     * A request travels through the thin controller, the real domain service and
     * the JDBC store into the ledger: opening, sending, reading and the unread
     * projection all succeed over HTTP, and the published row is durable.
     */
    @Test
    void anHttpRequestReachesTheRealServiceAndDurableStore() throws Exception {
        String created = mvc.perform(headers(post(BASE + "/direct"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new OpenDirectRequest(FACILITY, PEER))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String conversation = mapper.readTree(created).get("id").asText();

        mvc.perform(headers(post(BASE + "/{id}/messages", conversation))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "http-key")
                        .content(mapper.writeValueAsString(new SendRequest(FACILITY, conversation, "over-http"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sequence").value(1))
                .andExpect(jsonPath("$.body").value("over-http"));

        mvc.perform(headers(get(BASE + "/{id}/messages", conversation)).param("facilityId", FACILITY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].body").value("over-http"));
        mvc.perform(headers(get(BASE + "/unread")).param("facilityId", FACILITY))
                .andExpect(status().isOk());
        mvc.perform(headers(get(BASE + "/conversations")).param("facilityId", FACILITY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(conversation));

        assertEquals(1, store.messages(ORG_A, conversation, 0L, null).size());
    }

    /**
     * The same HTTP entry point refuses a facility the caller is assigned
     * elsewhere to, proving the facility fold runs on the request path.
     */
    @Test
    void anHttpRequestOutsideTheFacilityIsRefused() throws Exception {
        mvc.perform(headers(post(BASE + "/direct"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new OpenDirectRequest(OTHER_FACILITY, PEER))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
}
