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
        mvc = newMvc(store);
        mapper = new ObjectMapper();
    }

    /** Build a fresh store over the same tenant-guarded physical connection. */
    private JdbcInstantConversationStore newStore() {
        return new JdbcInstantConversationStore(new NamedParameterJdbcTemplate(dataSource),
                new TransactionTemplate(new DataSourceTransactionManager(dataSource)));
    }

    /** Mount the real controller and service over a chosen store instance. */
    private MockMvc newMvc(InstantConversationStore conversationStore) {
        return newMvc(conversationStore, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    /** Mount the real controller over a store and an explicit application clock. */
    private MockMvc newMvc(InstantConversationStore conversationStore, Clock clock) {
        InstantMessagingService mounted = new InstantMessagingService(conversationStore, clock);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        return MockMvcBuilders.standaloneSetup(new InstantMessagingController(mounted))
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
    }

    private static MockHttpServletRequestBuilder headers(MockHttpServletRequestBuilder request) {
        return headersFor(request, ACTOR);
    }

    /** Bind a different synthetic subject to a request while keeping the active organization. */
    private static MockHttpServletRequestBuilder headersFor(MockHttpServletRequestBuilder request, String subject) {
        return request.header("X-Synthetic-Subject", subject)
                .header("X-Active-Organization", ORG_A);
    }

    /** Rebind the physical connection's tenant GUCs; callers restore them in a finally block. */
    private void setOrg(String org, String actor) throws Exception {
        try (Connection connection = dataSource.getConnection();
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + org + "'");
            statement.execute("SET app.actor = '" + actor + "'");
        }
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
        var first = service.send(conversation, new SendRequest(FACILITY, conversation, "one"), "retry-key");
        var retry = service.send(conversation, new SendRequest(FACILITY, conversation, "different body"), "retry-key");

        assertEquals(first.id(), retry.id());
        assertEquals(first.sequence(), retry.sequence());
        assertEquals("one", retry.body());
        assertEquals(1, store.messages(ORG_A, conversation, 0L, null).size());
        assertEquals(1L, first.sequence());
    }

    /**
     * The URL conversation is the authorization and write target. A body that names a
     * different, equally accessible conversation must be refused before either thread
     * is touched; two permitted conversations stop a permission denial masking the bug.
     */
    @Test
    void anHttpBodyConversationMismatchWritesToNeitherConversation() throws Exception {
        String direct = mvc.perform(headers(post(BASE + "/direct"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new OpenDirectRequest(FACILITY, PEER))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String directId = mapper.readTree(direct).get("id").asText();
        String group = mvc.perform(headers(post(BASE + "/groups"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new CreateGroupRequest(FACILITY, "handover", java.util.List.of(PEER)))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String groupId = mapper.readTree(group).get("id").asText();
        // Seed both threads through the matching URL so a later mismatch cannot
        // hide behind an empty history.
        mvc.perform(headers(post(BASE + "/{id}/messages", directId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "seed-direct")
                        .content(mapper.writeValueAsString(new SendRequest(FACILITY, directId, "direct seed"))))
                .andExpect(status().isOk());
        mvc.perform(headers(post(BASE + "/{id}/messages", groupId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "seed-group")
                        .content(mapper.writeValueAsString(new SendRequest(FACILITY, groupId, "group seed"))))
                .andExpect(status().isOk());

        mvc.perform(headers(post(BASE + "/{id}/messages", directId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "mismatch-direct")
                        .content(mapper.writeValueAsString(new SendRequest(FACILITY, groupId, "to the group"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));
        mvc.perform(headers(post(BASE + "/{id}/messages", groupId))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "mismatch-group")
                        .content(mapper.writeValueAsString(new SendRequest(FACILITY, directId, "to the direct"))))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("INVALID_INPUT"));

        assertEquals(1, store.messages(ORG_A, directId, 0L, null).size());
        assertEquals(1, store.messages(ORG_A, groupId, 0L, null).size());
        assertEquals("direct seed", store.messages(ORG_A, directId, 0L, null).get(0).body());
        assertEquals("group seed", store.messages(ORG_A, groupId, 0L, null).get(0).body());
        assertTrue(store.messageByClientId(ORG_A, directId, "mismatch-direct").isEmpty());
        assertTrue(store.messageByClientId(ORG_A, groupId, "mismatch-direct").isEmpty());
        assertTrue(store.messageByClientId(ORG_A, directId, "mismatch-group").isEmpty());
        assertTrue(store.messageByClientId(ORG_A, groupId, "mismatch-group").isEmpty());
    }

    /** The same key replayed through the matching URL returns the original message. */
    @Test
    void aSameKeyRetryThroughTheMatchingUrlPublishesOneMessage() throws Exception {
        String conversation = direct();
        String first = mvc.perform(headers(post(BASE + "/{id}/messages", conversation))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "http-retry-key")
                        .content(mapper.writeValueAsString(new SendRequest(FACILITY, conversation, "one"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String retry = mvc.perform(headers(post(BASE + "/{id}/messages", conversation))
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "http-retry-key")
                        .content(mapper.writeValueAsString(new SendRequest(FACILITY, conversation, "different body"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        assertEquals(mapper.readTree(first).get("id").asText(), mapper.readTree(retry).get("id").asText());
        assertEquals(mapper.readTree(first).get("sequence").asLong(), mapper.readTree(retry).get("sequence").asLong());
        assertEquals(1L, mapper.readTree(first).get("sequence").asLong());
        assertEquals("one", mapper.readTree(retry).get("body").asText());
        assertEquals(1, store.messages(ORG_A, conversation, 0L, null).size());
    }

    /** Deduplication is per conversation, so one key cannot suppress another thread's message. */
    @Test
    void oneKeyInTwoMatchingUrlsPublishesOneMessagePerConversation() throws Exception {
        String first = direct();
        String second = service.createGroup(
                new CreateGroupRequest(FACILITY, "handover", java.util.List.of(PEER))).id();
        for (String conversation : java.util.List.of(first, second)) {
            mvc.perform(headers(post(BASE + "/{id}/messages", conversation))
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Idempotency-Key", "shared-key")
                            .content(mapper.writeValueAsString(new SendRequest(FACILITY, conversation, "body"))))
                    .andExpect(status().isOk());
        }
        assertEquals(1, store.messages(ORG_A, first, 0L, null).size());
        assertEquals(1, store.messages(ORG_A, second, 0L, null).size());
    }

    @Test
    void aRemovedMemberKeepsHistoryButCannotReadLaterMessages() throws Exception {
        String conversation = service.createGroup(
                new CreateGroupRequest(FACILITY, "handover", java.util.List.of(PEER))).id();
        service.send(conversation, new SendRequest(FACILITY, conversation, "before leave"), "before");
        service = new InstantMessagingService(store, Clock.fixed(NOW.plusSeconds(5), ZoneOffset.UTC));
        service.removeParticipant(FACILITY, conversation, PEER);
        service.send(conversation, new SendRequest(FACILITY, conversation, "after leave"), "after");

        assertEquals(2, store.messages(ORG_A, conversation, 0L, null).size());
        context(PEER);
        var visible = service.messages(FACILITY, conversation);
        assertEquals(1, visible.size());
        assertEquals("before leave", visible.get(0).body());
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.send(conversation, new SendRequest(FACILITY, conversation, "reply"), "blocked")).status());
    }

    @Test
    void unreadCountsArePerPersonAndASeenMessageIsNoLongerUnread() throws Exception {
        String conversation = direct();
        service.send(conversation, new SendRequest(FACILITY, conversation, "first"), "m1");
        service.send(conversation, new SendRequest(FACILITY, conversation, "second"), "m2");

        context(PEER);
        assertEquals(2L, service.unreadCounts(FACILITY).get(0).unreadCount());
        service.markRead(new MarkReadRequest(FACILITY, conversation, 2L));
        assertEquals(0L, service.unreadCounts(FACILITY).get(0).unreadCount());
        context(ACTOR);
        assertEquals(0L, service.unreadCounts(FACILITY).get(0).unreadCount());
    }

    /**
     * A stale or repeated read acknowledgement must not move a cursor backwards:
     * otherwise messages already marked read would reappear as unread. The fresh
     * store proves the cursor is durable state, not a process-local maximum.
     */
    @Test
    void staleAndRepeatedMarkReadDoNotMoveTheCursorBackwards() {
        String conversation = direct();
        service.send(conversation, new SendRequest(FACILITY, conversation, "first"), "m1");
        service.send(conversation, new SendRequest(FACILITY, conversation, "second"), "m2");

        context(PEER);
        service.markRead(new MarkReadRequest(FACILITY, conversation, 2L));
        service.markRead(new MarkReadRequest(FACILITY, conversation, 1L));
        service.markRead(new MarkReadRequest(FACILITY, conversation, 2L));
        assertEquals(2L, store.readSequence(ORG_A, conversation, PEER));
        assertEquals(2L, newStore().readSequence(ORG_A, conversation, PEER));
        assertEquals(0L, service.unreadCounts(FACILITY).get(0).unreadCount());
    }

    /** A later, higher acknowledgement raises the persisted cursor. */
    @Test
    void aHigherReadAcknowledgementRaisesTheCursor() {
        String conversation = direct();
        service.send(conversation, new SendRequest(FACILITY, conversation, "first"), "raise-1");
        service.send(conversation, new SendRequest(FACILITY, conversation, "second"), "raise-2");

        context(PEER);
        service.markRead(new MarkReadRequest(FACILITY, conversation, 1L));
        assertEquals(1L, store.readSequence(ORG_A, conversation, PEER));
        assertEquals(1L, service.unreadCounts(FACILITY).get(0).unreadCount());

        service.markRead(new MarkReadRequest(FACILITY, conversation, 2L));
        assertEquals(2L, newStore().readSequence(ORG_A, conversation, PEER));
        assertEquals(0L, service.unreadCounts(FACILITY).get(0).unreadCount());
    }

    /** A cursor written by one store instance must be readable by a new one. */
    @Test
    void readCursorSurvivesANewStoreInstance() {
        String conversation = direct();
        service.send(conversation, new SendRequest(FACILITY, conversation, "first"), "persist-1");
        service.send(conversation, new SendRequest(FACILITY, conversation, "second"), "persist-2");

        context(PEER);
        service.markRead(new MarkReadRequest(FACILITY, conversation, 2L));

        assertEquals(2L, newStore().readSequence(ORG_A, conversation, PEER));
    }

    /**
     * A future acknowledgement must be clamped to the last appended sequence;
     * a plain maximum would silently mark not-yet-sent messages as read.
     */
    @Test
    void futureCursorIsClampedSoTheNextMessageBecomesUnread() {
        String conversation = direct();
        service.send(conversation, new SendRequest(FACILITY, conversation, "first"), "clamp-1");

        context(PEER);
        service.markRead(new MarkReadRequest(FACILITY, conversation, Long.MAX_VALUE));
        assertEquals(1L, store.readSequence(ORG_A, conversation, PEER));

        context(ACTOR);
        service.send(conversation, new SendRequest(FACILITY, conversation, "second"), "clamp-2");
        context(PEER);
        assertEquals(1L, service.unreadCounts(FACILITY).get(0).unreadCount());
    }

    /** With no message yet, even a future acknowledgement clamps to zero. */
    @Test
    void markReadOnAnEmptyConversationIsClampedToZero() {
        String conversation = direct();
        context(PEER);
        service.markRead(new MarkReadRequest(FACILITY, conversation, Long.MAX_VALUE));
        assertEquals(0L, store.readSequence(ORG_A, conversation, PEER));

        context(ACTOR);
        service.send(conversation, new SendRequest(FACILITY, conversation, "later"), "clamp-empty");
        context(PEER);
        assertEquals(1L, service.unreadCounts(FACILITY).get(0).unreadCount());
    }

    /**
     * A cursor is keyed by participant and tenant: another participant's read,
     * and a foreign tenant's write, cannot change this person's cursor.
     */
    @Test
    void cursorIsIsolatedByTenantAndParticipant() throws Exception {
        String conversation = direct();
        service.send(conversation, new SendRequest(FACILITY, conversation, "first"), "iso-1");
        service.send(conversation, new SendRequest(FACILITY, conversation, "second"), "iso-2");

        context(PEER);
        service.markRead(new MarkReadRequest(FACILITY, conversation, 2L));
        assertEquals(2L, store.readSequence(ORG_A, conversation, PEER));
        assertEquals(0L, store.readSequence(ORG_A, conversation, ACTOR));

        try {
            setOrg(ORG_B, "foreign-b");
            // The other tenant has no row under ORG_A's conversation key.
            assertEquals(0L, store.readSequence(ORG_B, conversation, PEER));
            // Seed a valid ORG_B conversation so a foreign write has a real key.
            String foreign = store.createConversation(ORG_B, OTHER_FACILITY,
                    com.aurevia.smartclinic.staffmsg.model.StaffMessagingContract.ConversationKind.DIRECT,
                    null, "foreign-b").id();
            store.markRead(ORG_B, foreign, "foreign-b", 1L);
            assertEquals(0L, store.readSequence(ORG_B, foreign, PEER));
        } finally {
            setOrg(ORG_A, ACTOR);
        }
        assertEquals(2L, newStore().readSequence(ORG_A, conversation, PEER));
    }

    /** Denied mark-read attempts leave the durable cursor untouched. */
    @Test
    void markReadDenialsDoNotMoveTheCursor() throws Exception {
        String conversation = direct();
        service.send(conversation, new SendRequest(FACILITY, conversation, "first"), "deny-1");
        service.send(conversation, new SendRequest(FACILITY, conversation, "second"), "deny-2");

        mvc.perform(headersFor(post(BASE + "/read"), PEER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new MarkReadRequest(FACILITY, conversation, -1L))))
                .andExpect(status().isUnprocessableEntity());
        mvc.perform(headersFor(post(BASE + "/read"), PEER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(
                                new MarkReadRequest(OTHER_FACILITY, conversation, 2L))))
                .andExpect(status().isForbidden());
        mvc.perform(post(BASE + "/read")
                        .header("X-Synthetic-Subject", PEER)
                        .header("X-Active-Organization", ORG_B)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new MarkReadRequest(FACILITY, conversation, 2L))))
                .andExpect(status().is4xxClientError());
        assertEquals(0L, store.readSequence(ORG_A, conversation, PEER));

        // The interceptor clears the thread-local context after each request.
        context(ACTOR);
        Clock later = Clock.fixed(NOW.plusSeconds(5), ZoneOffset.UTC);
        service = new InstantMessagingService(store, later);
        service.removeParticipant(FACILITY, conversation, PEER);
        mvc = newMvc(store, later);
        mvc.perform(headersFor(post(BASE + "/read"), PEER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new MarkReadRequest(FACILITY, conversation, 2L))))
                .andExpect(status().isForbidden());
        assertEquals(0L, newStore().readSequence(ORG_A, conversation, PEER));
    }

    /** The HTTP mark-read path persists the cursor and the unread projection. */
    @Test
    void httpMarkReadUpdatesUnreadCounts() throws Exception {
        String created = mvc.perform(headers(post(BASE + "/direct"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new OpenDirectRequest(FACILITY, PEER))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String conversation = mapper.readTree(created).get("id").asText();
        for (int i = 1; i <= 2; i++) {
            mvc.perform(headers(post(BASE + "/{id}/messages", conversation))
                            .contentType(MediaType.APPLICATION_JSON)
                            .header("Idempotency-Key", "http-read-" + i)
                            .content(mapper.writeValueAsString(
                                    new SendRequest(FACILITY, conversation, "message " + i))))
                    .andExpect(status().isOk());
        }

        mvc.perform(headersFor(get(BASE + "/unread"), PEER).param("facilityId", FACILITY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].unreadCount").value(2));
        mvc.perform(headersFor(post(BASE + "/read"), PEER)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new MarkReadRequest(FACILITY, conversation, 2L))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unreadCount").value(0));

        // A fresh store behind a fresh controller must read the durable cursor.
        mvc = newMvc(newStore());
        mvc.perform(headersFor(get(BASE + "/unread"), PEER).param("facilityId", FACILITY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].unreadCount").value(0));
    }

    @Test
    void aWrongFacilityOrTenantIsRefused() throws Exception {
        String conversation = direct();
        assertEquals(403, assertThrows(ApiException.class,
                () -> service.send(conversation, new SendRequest(OTHER_FACILITY, conversation, "body"), "wrong-facility"))
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
