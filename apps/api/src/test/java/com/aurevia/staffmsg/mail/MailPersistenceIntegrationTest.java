package com.aurevia.staffmsg.mail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.authz.access.AccessRepository;
import com.aurevia.staff.StaffRepository;
import com.aurevia.staffmsg.directory.ActiveStaffGuard;
import com.aurevia.staffmsg.directory.DirectoryPolicy;
import com.aurevia.staffmsg.directory.StaffDirectoryService;
import com.aurevia.staffmsg.mail.MailContract.DraftView;
import com.aurevia.staffmsg.mail.MailContract.MailView;
import com.aurevia.staffmsg.mail.MailContract.Recipient;
import com.aurevia.staffmsg.mail.MailContract.SentMail;
import com.aurevia.staffmsg.model.StaffMessagingContract.MailFolder;
import com.aurevia.tenancy.FacilityRepository;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.TenantContextInterceptor;

import tools.jackson.databind.ObjectMapper;

/**
 * MSG-02, ARC-02 and ARC-07 acceptance over real PostgreSQL. The mail HTTP entry
 * point stores drafts, messages, folders and retry keys durably, a fresh service
 * instance reads the same state, a retry is idempotent, a failed send rolls back
 * completely, and tenants and viewers cannot see one another's state.
 */
@Tag("integration")
class MailPersistenceIntegrationTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String FACILITY_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String FACILITY_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String FACILITY_C = "cccccccc-cccc-cccc-cccc-cccccccccccc";
    private static final String SENDER = "nurse-a";
    private static final String READER = "nurse-b";
    private static final String OTHER_FACILITY_READER = "nurse-d";
    private static final String INACTIVE_READER = "former-nurse";
    private static final String OTHER_ORG_READER = "nurse-x";
    private static final String GHOST = "ghost";
    private static final String DATABASE = "aurevia_mail_it";
    private static final String PREFIX = "jdbc:postgresql://127.0.0.1:5432/";
    private static final String MIGRATE = "aurevia_migrate";
    private static final String APP = "aurevia_app";
    private static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final String BASE = "/api/v1/staff/mail";
    private static final Instant NOW = Instant.parse("2026-11-21T10:00:00Z");

    private SingleConnectionDataSource dataSource;
    private Connection connection;
    private JdbcMailStore store;
    private MockMvc mvc;
    private ObjectMapper mapper;

    @BeforeEach
    void setUp() throws Exception {
        createRoles();
        try (Connection root = DriverManager.getConnection(PREFIX + "postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + DATABASE + " OWNER " + MIGRATE);
        }
        Flyway.configure().dataSource(PREFIX + DATABASE, MIGRATE, "")
                .locations("filesystem:../../db/migrations").load().migrate();
        seed();

        connection = DriverManager.getConnection(PREFIX + DATABASE, APP, "");
        dataSource = new SingleConnectionDataSource(connection, true);
        as(SENDER, ORG_A);
        store = new JdbcMailStore(new NamedParameterJdbcTemplate(dataSource), transactions(dataSource));
        mapper = new ObjectMapper();
        mvc = MockMvcBuilders.standaloneSetup(new MailController(service(store, dataSource)))
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(mockEnvironment()))
                .build();
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

    @Test
    void sentAndFolderStateSurviveANewServiceInstance() throws Exception {
        String draftId = idOf(compose(SENDER, ORG_A, "Handover", List.of(Recipient.person(READER))));
        String messageId = idOf(send(draftId, SENDER, ORG_A, List.of(READER), "survive-1")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viewerFolder").value("sent")));

        MailService restarted = service(new JdbcMailStore(new NamedParameterJdbcTemplate(dataSource),
                transactions(dataSource)), dataSource);
        MailViewAfterRestart sent = new MailViewAfterRestart(
                restarted.view(ORG_A, SENDER, messageId),
                restarted.listFolder(ORG_A, SENDER, MailFolder.SENT),
                restarted.listFolder(ORG_A, READER, MailFolder.INBOX));
        assertEquals("sent", sent.senderFolder.viewerFolder().wire());
        assertEquals(messageId, sent.sentFolder.get(0).message().id());
        assertEquals(messageId, sent.readerInbox.get(0).message().id());
        assertEquals(List.of(READER), sent.readerInbox.get(0).message().resolvedRecipients());
    }

    @Test
    void staleDraftRetryAndFailedSendBehaveAtomicallyOverHttp() throws Exception {
        String draftId = idOf(compose(SENDER, ORG_A, "Handover", List.of(Recipient.person(READER))));
        mvc.perform(withHeaders(put(BASE + "/drafts/{id}", draftId), SENDER, ORG_A)
                        .header("If-Match", "9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(composeBody("stale", List.of(Recipient.person(READER))))))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("STALE_VERSION"));

        String messageId = idOf(send(draftId, SENDER, ORG_A, List.of(READER), "retry-key")
                .andExpect(status().isOk()));
        send(draftId, SENDER, ORG_A, List.of(READER), "retry-key")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message.id").value(messageId));
        assertEquals(1, count("SELECT count(*) FROM public.staff_message"));
        assertEquals(1, count("SELECT count(*) FROM public.staff_mail_idempotency"));

        String failingDraft = idOf(compose(SENDER, ORG_A, "Unsendable", List.of(Recipient.person(GHOST))));
        assertThrows(Exception.class,
                () -> send(failingDraft, SENDER, ORG_A, List.of(GHOST), "rollback-key"));
        assertEquals(1, count("SELECT count(*) FROM public.staff_message"));
        assertEquals(1, count("SELECT count(*) FROM public.staff_mail_idempotency"));
        assertTrue(store.draft(ORG_A, failingDraft, SENDER).isPresent());
        assertTrue(store.replay(ORG_A, SENDER, "rollback-key").isEmpty());
    }

    @Test
    void tenantsAndViewersStaySeparateThroughHttp() throws Exception {
        String draftId = idOf(compose(SENDER, ORG_A, "Handover", List.of(Recipient.person(READER))));
        String messageId = idOf(send(draftId, SENDER, ORG_A, List.of(READER), "tenant-key")
                .andExpect(status().isOk()));

        as(SENDER, ORG_B);
        mvc.perform(withHeaders(get(BASE + "/messages/{id}", messageId), SENDER, ORG_B))
                .andExpect(status().isNotFound());
        mvc.perform(withHeaders(get(BASE + "/folders/{folder}", "inbox"), READER, ORG_B))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").doesNotExist());

        as(SENDER, ORG_A);
        mvc.perform(withHeaders(post(BASE + "/messages/{id}/move", messageId), SENDER, ORG_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new MailController.FolderRequest("archive"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.viewerFolder").value("archive"));
        mvc.perform(withHeaders(get(BASE + "/folders/{folder}", "inbox"), READER, ORG_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].message.id").value(messageId));
        mvc.perform(withHeaders(get(BASE + "/folders/{folder}", "sent"), SENDER, ORG_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0]").doesNotExist());

        as(READER, ORG_A);
        mvc.perform(withHeaders(post(BASE + "/messages/{id}/flag", messageId), READER, ORG_A)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new MailController.FlagRequest("flagged", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message.flags[0]").value("flagged"));
        as(SENDER, ORG_A);
        mvc.perform(withHeaders(get(BASE + "/messages/{id}", messageId), SENDER, ORG_A))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message.flags[0]").value("flagged"));
    }

    @Test
    void roleRecipientsAreScopedToTheSelectedFacilityThroughHttp() throws Exception {
        // Real production wiring over forced RLS: nurse-b is assigned to facility A and
        // nurse-d to facility C, so the same role resolves to different holders.
        String draftA = idOf(compose(SENDER, ORG_A, "Handover A",
                List.of(Recipient.role("nurse")), FACILITY_A));
        send(draftA, SENDER, ORG_A, List.of(READER), "facility-a-role")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message.resolvedRecipients.length()").value(1))
                .andExpect(jsonPath("$.message.resolvedRecipients[0]").value(READER));

        String draftC = idOf(compose(SENDER, ORG_A, "Handover C",
                List.of(Recipient.role("nurse")), FACILITY_C));
        send(draftC, SENDER, ORG_A, List.of(OTHER_FACILITY_READER), "facility-c-role")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message.resolvedRecipients.length()").value(1))
                .andExpect(jsonPath("$.message.resolvedRecipients[0]").value(OTHER_FACILITY_READER));

        // A revoked holder and a holder in another organization are never resolved.
        String stale = idOf(compose(SENDER, ORG_A, "Stale",
                List.of(Recipient.role("nurse")), FACILITY_A));
        send(stale, SENDER, ORG_A, List.of(READER, INACTIVE_READER, OTHER_ORG_READER), "facility-stale")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("RECIPIENT_CONFIRMATION_MISMATCH"));
    }

    @Test
    void aLostRetryClaimStillResolvesToTheWinningMessage() {
        DraftView draft = service(store, dataSource).createDraft(new MailCommands.CreateDraftCommand(
                ORG_A, FACILITY_A, SENDER, "Handover", "body", List.of(Recipient.person(READER)),
                List.of(), List.of(), List.of()));
        String firstId = "33333333-3333-3333-3333-333333333333";
        String secondId = "44444444-4444-4444-4444-444444444444";
        SentMail message = new SentMail(ORG_A, firstId, FACILITY_A, firstId, "Handover",
                "body", SENDER, "staff", NOW, List.of(Recipient.person(READER)), List.of(),
                List.of(READER), List.of(), List.of());
        String first = store.publish(ORG_A, SENDER, "lost-claim", draft.id(), message, List.of(READER));

        SentMail secondMessage = new SentMail(ORG_A, secondId, FACILITY_A, secondId,
                "Other", "other", SENDER, "staff", NOW, List.of(Recipient.person(READER)), List.of(),
                List.of(READER), List.of(), List.of());
        String second = store.publish(ORG_A, SENDER, "lost-claim", draft.id(), secondMessage, List.of(READER));
        assertEquals(first, second);
        assertEquals(firstId, second);
        assertNotEquals(first, secondId);
    }

    private static MockEnvironment mockEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        return environment;
    }

    private static TransactionTemplate transactions(SingleConnectionDataSource source) {
        return new TransactionTemplate(new DataSourceTransactionManager(source));
    }

    /** Production wiring of the mail kernel, including the real facility-scoped directory. */
    private static MailService service(MailStore store, SingleConnectionDataSource source) {
        NamedParameterJdbcTemplate jdbc = new NamedParameterJdbcTemplate(source);
        TransactionTemplate transactions = transactions(source);
        StaffRepository staff = new StaffRepository(jdbc);
        StaffDirectoryService directory = new StaffDirectoryService(staff, new FacilityRepository(jdbc),
                transactions, new ActiveStaffGuard(staff));
        return new MailService(Clock.fixed(NOW, ZoneOffset.UTC), staff, transactions, directory,
                new AccessRepository(jdbc), new DirectoryPolicy(), store);
    }

    private void as(String subject, String organization) throws Exception {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + organization + "'");
            statement.execute("SET app.actor = '" + subject + "'");
        }
    }

    private static void createRoles() throws Exception {
        try (Connection root = DriverManager.getConnection(PREFIX + "postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DO $$ BEGIN "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='aurevia_migrate') THEN "
                    + "CREATE ROLE aurevia_migrate LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; "
                    + "IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='aurevia_app') THEN "
                    + "CREATE ROLE aurevia_app LOGIN NOBYPASSRLS NOCREATEDB NOCREATEROLE; END IF; END $$;");
        }
    }

    private static void seed() throws Exception {
        try (Connection connection = DriverManager.getConnection(PREFIX + DATABASE, MIGRATE, "");
             Statement statement = connection.createStatement()) {
            seedOrg(statement, ORG_A, FACILITY_A);
            seedOrg(statement, ORG_B, FACILITY_B);
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("INSERT INTO public.facility (organization_id, id, code, name) "
                    + "VALUES ('" + ORG_A + "', '" + FACILITY_C + "', 'f-c', 'Facility C')");
            statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                    + "VALUES ('" + ORG_A + "', '" + SENDER + "', '" + SENDER + "')");
            seedRoleHolder(statement, ORG_A, READER, FACILITY_A, "active");
            seedRoleHolder(statement, ORG_A, OTHER_FACILITY_READER, FACILITY_C, "active");
            seedRoleHolder(statement, ORG_A, INACTIVE_READER, FACILITY_A, "revoked");
            seedRoleHolder(statement, ORG_B, OTHER_ORG_READER, FACILITY_B, "active");
        }
    }

    /** Seed one active-role holder with an assigned facility; status may be revoked. */
    private static void seedRoleHolder(Statement statement, String org, String subject, String facility,
                                       String status) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("SET app.actor = '" + subject + "'");
        statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                + "VALUES ('" + org + "', '" + subject + "', '" + subject + "')");
        statement.execute("INSERT INTO public.membership (organization_id, user_id, status) "
                + "SELECT '" + org + "', id, '" + status + "' FROM public.user_identity "
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

    private static void seedOrg(Statement statement, String org, String facility) throws Exception {
        statement.execute("SET app.current_org = '" + org + "'");
        statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                + "VALUES ('" + org + "', 'Org " + org + "')");
        statement.execute("INSERT INTO public.facility (organization_id, id, code, name) "
                + "VALUES ('" + org + "', '" + facility + "', 'f', 'Facility')");
    }

    private int count(String sql) throws Exception {
        try (Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery(sql)) {
            row.next();
            return row.getInt(1);
        }
    }

    private ResultActions compose(String actor, String org, String subject, List<Recipient> to) throws Exception {
        return compose(actor, org, subject, to, FACILITY_A);
    }

    private ResultActions compose(String actor, String org, String subject, List<Recipient> to,
                                  String facilityId) throws Exception {
        as(actor, org);
        return mvc.perform(withHeaders(post(BASE + "/drafts"), actor, org)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(composeBody(subject, to, facilityId))));
    }

    private static MailController.ComposeRequest composeBody(String subject, List<Recipient> to) {
        return composeBody(subject, to, FACILITY_A);
    }

    private static MailController.ComposeRequest composeBody(String subject, List<Recipient> to,
                                                              String facilityId) {
        return new MailController.ComposeRequest(facilityId, subject, "body", to,
                List.of(), List.of(), List.of());
    }

    private ResultActions send(String draftId, String actor, String org, List<String> confirmed, String key)
            throws Exception {
        as(actor, org);
        return mvc.perform(withHeaders(post(BASE + "/drafts/{id}/send", draftId), actor, org)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(mapper.writeValueAsString(new MailController.SendRequest(confirmed))));
    }

    private String idOf(ResultActions result) throws Exception {
        var node = mapper.readTree(result.andReturn().getResponse().getContentAsString());
        var value = node.has("message") ? node.get("message") : node;
        return value.get("id").asText();
    }

    private static MockHttpServletRequestBuilder withHeaders(MockHttpServletRequestBuilder request,
                                                             String actor, String org) {
        return request.header("X-Synthetic-Subject", actor).header("X-Active-Organization", org);
    }

    /** Folder placement values read back through a restarted service. */
    private record MailViewAfterRestart(MailView senderFolder, List<MailView> sentFolder,
                                        List<MailView> readerInbox) {
    }
}
