package com.aurevia.staffmsg.attachments;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.documents.DocumentStore;
import com.aurevia.documents.storage.ObjectStorePort;

/**
 * MSG-03 durable audience checks for attachment downloads. Two mail messages in
 * one thread have disjoint frozen recipients, and an instant conversation has one
 * active and one departed participant. The tenant-scoped store must grant only
 * the audience of the exact message, so a sibling reader, a former member and a
 * foreign tenant cannot download this message's attachment. Synthetic data only.
 */
@Tag("integration")
class StaffAttachmentAudienceIntegrationTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String FACILITY_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String FACILITY_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String SENDER = "nurse-a";
    private static final String RECIPIENT = "clinician-b";
    private static final String SIBLING = "clinician-c";
    private static final String ACTIVE = "nurse-b";
    private static final String FORMER = "nurse-former";
    private static final String OUTSIDER = "outsider-b";
    private static final String MAIL_THREAD = "33333333-3333-3333-3333-333333333333";
    private static final String MAIL_A = "44444444-4444-4444-4444-444444444444";
    private static final String MAIL_B = "55555555-5555-5555-5555-555555555555";
    private static final String INSTANT_CONVERSATION = "66666666-6666-6666-6666-666666666666";
    private static final String INSTANT_MESSAGE = "77777777-7777-7777-7777-777777777777";
    private static final String DATABASE = "aurevia_attachment_audience_it";
    private static final String PREFIX = "jdbc:postgresql://127.0.0.1:5432/";
    private static final String MIGRATE = "aurevia_migrate";
    private static final String APP = "aurevia_app";
    private static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));

    private SingleConnectionDataSource dataSource;
    private Connection connection;

    /** Rebuild a fresh synthetic database with the approved migrations. */
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
    }

    /** Release the guarded connection and drop the synthetic database. */
    @AfterEach
    void tearDown() throws Exception {
        if (dataSource != null) {
            dataSource.destroy();
        }
        try (Connection root = DriverManager.getConnection(PREFIX + "postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + DATABASE + " WITH (FORCE)");
        }
    }

    /** Frozen per-message mail audiences and active instant membership gate downloads. */
    @Test
    void onlyTheExactMessagesAudienceCanDownloadItsAttachment() throws Exception {
        as(SENDER, ORG_A);
        JdbcStaffAttachmentStore store = new JdbcStaffAttachmentStore(
                new NamedParameterJdbcTemplate(dataSource), transactions(), mock(DocumentStore.class),
                mock(ObjectStorePort.class));

        assertTrue(store.isRecipient(ORG_A, MAIL_A, RECIPIENT));
        assertFalse(store.isRecipient(ORG_A, MAIL_A, SIBLING));
        assertTrue(store.isRecipient(ORG_A, MAIL_B, SIBLING));
        assertFalse(store.isRecipient(ORG_A, MAIL_B, RECIPIENT));

        assertTrue(store.isRecipient(ORG_A, INSTANT_MESSAGE, SENDER));
        assertTrue(store.isRecipient(ORG_A, INSTANT_MESSAGE, ACTIVE));
        assertFalse(store.isRecipient(ORG_A, INSTANT_MESSAGE, FORMER));

        as(OUTSIDER, ORG_B);
        assertFalse(store.isRecipient(ORG_B, MAIL_A, OUTSIDER));
    }

    private TransactionTemplate transactions() {
        return new TransactionTemplate(new DataSourceTransactionManager(dataSource));
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
            for (String[] tenant : new String[][] {{ORG_A, FACILITY_A, "fa", "Facility A"},
                    {ORG_B, FACILITY_B, "fb", "Facility B"}}) {
                statement.execute("SET app.current_org = '" + tenant[0] + "'");
                statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                        + "VALUES ('" + tenant[0] + "', '" + tenant[3] + "')");
                statement.execute("INSERT INTO public.facility (organization_id, id, code, name) "
                        + "VALUES ('" + tenant[0] + "', '" + tenant[1] + "', '" + tenant[2]
                        + "', '" + tenant[3] + "')");
            }
            for (String subject : new String[] {SENDER, RECIPIENT, SIBLING, ACTIVE, FORMER}) {
                statement.execute("SET app.current_org = '" + ORG_A + "'");
                statement.execute("SET app.actor = '" + subject + "'");
                statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                        + "VALUES ('" + ORG_A + "', '" + subject + "', '" + subject + "')");
            }
            statement.execute("SET app.current_org = '" + ORG_B + "'");
            statement.execute("SET app.actor = '" + OUTSIDER + "'");
            statement.execute("INSERT INTO public.user_identity (organization_id, subject, display_name) "
                    + "VALUES ('" + ORG_B + "', '" + OUTSIDER + "', '" + OUTSIDER + "')");

            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("SET app.actor = '" + SENDER + "'");
            statement.execute("INSERT INTO public.staff_conversation "
                    + "(organization_id, id, facility_id, kind, topic) VALUES ('" + ORG_A + "', '"
                    + MAIL_THREAD + "', '" + FACILITY_A + "', 'mail_thread', 'handover')");
            statement.execute("INSERT INTO public.staff_conversation "
                    + "(organization_id, id, facility_id, kind, topic) VALUES ('" + ORG_A + "', '"
                    + INSTANT_CONVERSATION + "', '" + FACILITY_A + "', 'direct', 'chat')");
            statement.execute("INSERT INTO public.staff_message "
                    + "(organization_id, id, conversation_id, client_message_id, sender_role, body) VALUES "
                    + "('" + ORG_A + "', '" + MAIL_A + "', '" + MAIL_THREAD + "', 'mail-a', 'staff', 'body'), "
                    + "('" + ORG_A + "', '" + MAIL_B + "', '" + MAIL_THREAD + "', 'mail-b', 'staff', 'body')");
            statement.execute("INSERT INTO public.staff_mail_metadata "
                    + "(organization_id, message_id, subject, resolved_recipients) VALUES "
                    + "('" + ORG_A + "', '" + MAIL_A + "', 'a', ARRAY['" + RECIPIENT + "']::text[]), "
                    + "('" + ORG_A + "', '" + MAIL_B + "', 'b', ARRAY['" + SIBLING + "']::text[])");
            statement.execute("INSERT INTO public.staff_message "
                    + "(organization_id, id, conversation_id, client_message_id, sender_role, body) VALUES "
                    + "('" + ORG_A + "', '" + INSTANT_MESSAGE + "', '" + INSTANT_CONVERSATION
                    + "', 'instant-1', 'staff', 'body')");
            statement.execute("INSERT INTO public.staff_participant "
                    + "(organization_id, conversation_id, staff_subject, role, left_at) VALUES "
                    + "('" + ORG_A + "', '" + INSTANT_CONVERSATION + "', '" + SENDER + "', 'owner', NULL), "
                    + "('" + ORG_A + "', '" + INSTANT_CONVERSATION + "', '" + ACTIVE + "', 'member', NULL), "
                    + "('" + ORG_A + "', '" + INSTANT_CONVERSATION + "', '" + FORMER
                    + "', 'member', now() - interval '1 hour')");
        }
    }
}
