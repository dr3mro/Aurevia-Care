package com.aurevia.staffmsg.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * SMSG-02 acceptance on a real PostgreSQL: the staff-messaging migration runs on
 * an empty and a populated database, carries forced tenant isolation, keeps
 * message content immutable and allocates gap-free sequences.
 */
@Tag("integration")
class StaffMessagingSchemaIntegrationTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String FACILITY_A = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    private static final String FACILITY_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String USER_A = "nurse-a";
    private static final String USER_B = "nurse-b";
    private static final String PREFIX = "jdbc:postgresql://127.0.0.1:5432/";
    private static final String MIGRATE = "aurevia_migrate";
    private static final String APP = "aurevia_app";
    private static final String ADMIN = System.getenv().getOrDefault(
            "AUREVIA_PG_ADMIN", System.getProperty("user.name"));
    private static final List<String> TABLES = List.of(
            "staff_conversation", "staff_participant", "staff_message",
            "staff_mail_metadata", "staff_mail_folder", "staff_attachment_link");

    private record MessageRow(String id, long sequence) {
    }

    @Test
    void migrationCreatesForcedRlsTenantTablesOnAnEmptyDatabase() throws Exception {
        String database = "aurevia_staffmsg_empty";
        String url = freshDatabase(database, null);
        try (Connection connection = connect(url, MIGRATE);
             Statement statement = connection.createStatement()) {
            for (String table : TABLES) {
                try (ResultSet row = statement.executeQuery(
                        "SELECT relrowsecurity, relforcerowsecurity FROM pg_class "
                                + "WHERE oid = 'public." + table + "'::regclass")) {
                    assertTrue(row.next(), table + " must exist");
                    assertTrue(row.getBoolean(1), table + " must enable RLS");
                    assertTrue(row.getBoolean(2), table + " must force RLS");
                }
                try (ResultSet row = statement.executeQuery(
                        "SELECT count(*) FROM pg_policies WHERE schemaname = 'public' "
                                + "AND tablename = '" + table + "' "
                                + "AND policyname = 'tenant_isolation'")) {
                    row.next();
                    assertEquals(1, row.getInt(1), table + " must have a tenant policy");
                }
            }
            try (ResultSet row = statement.executeQuery("SELECT count(*) FROM public.staff_message")) {
                row.next();
                assertEquals(0, row.getInt(1));
            }
        } finally {
            dropDatabase(database);
        }
    }

    @Test
    void tenantColumnIsEnforcedForEveryStaffTable() throws Exception {
        String database = "aurevia_staffmsg_tenant";
        String url = freshDatabase(database, null);
        try {
            try (Connection seed = connect(url, MIGRATE)) {
                seedTenants(seed);
            }
            try (Connection connection = connect(url, APP);
                 Statement statement = connection.createStatement()) {
                statement.execute("SET app.current_org = '" + ORG_A + "'");
                statement.execute("SET app.actor = '" + USER_A + "'");
                String conversation = createConversation(statement, "direct");

                for (String table : TABLES) {
                    SQLException refused = assertThrows(SQLException.class,
                            () -> statement.execute(crossTenantInsert(table, conversation)),
                            table + " must reject a foreign tenant row");
                    assertEquals("42501", refused.getSQLState());
                }

                statement.execute("SET app.current_org = '" + ORG_B + "'");
                assertEquals(0, count(statement, "public.staff_conversation"));
                statement.execute("SET app.current_org = '" + ORG_A + "'");
                assertEquals(1, count(statement, "public.staff_conversation"));
            }
        } finally {
            dropDatabase(database);
        }
    }

    @Test
    void messageContentCannotBeEditedInPlace() throws Exception {
        String database = "aurevia_staffmsg_immutable";
        String url = freshDatabase(database, null);
        try {
            try (Connection seed = connect(url, MIGRATE)) {
                seedTenants(seed);
            }
            MessageRow message;
            try (Connection connection = connect(url, APP);
                 Statement statement = connection.createStatement()) {
                statement.execute("SET app.current_org = '" + ORG_A + "'");
                statement.execute("SET app.actor = '" + USER_A + "'");
                message = insertMessage(statement, createConversation(statement, "direct"),
                        "immutable-1", "original body", null, null);
                assertThrows(SQLException.class, () -> statement.execute(
                        "UPDATE public.staff_message SET body = 'edited' WHERE id = '" + message.id() + "'"));
            }
            // The trigger binds even the migration owner, not just the revoked app role.
            try (Connection owner = connect(url, MIGRATE);
                 Statement statement = owner.createStatement()) {
                statement.execute("SET app.current_org = '" + ORG_A + "'");
                statement.execute("SET app.actor = '" + USER_A + "'");
                SQLException update = assertThrows(SQLException.class, () -> statement.execute(
                        "UPDATE public.staff_message SET body = 'owner edit' WHERE id = '" + message.id() + "'"));
                assertEquals("44000", update.getSQLState());
                SQLException delete = assertThrows(SQLException.class, () -> statement.execute(
                        "DELETE FROM public.staff_message WHERE id = '" + message.id() + "'"));
                assertEquals("44000", delete.getSQLState());
                assertThrows(SQLException.class,
                        () -> statement.execute("TRUNCATE public.staff_message"));
                try (ResultSet row = statement.executeQuery(
                        "SELECT body FROM public.staff_message WHERE id = '" + message.id() + "'")) {
                    assertTrue(row.next());
                    assertEquals("original body", row.getString(1));
                }
            }
        } finally {
            dropDatabase(database);
        }
    }

    @Test
    void sequencesAreGapFreePerConversationAndRetriesAreDeduplicated() throws Exception {
        String database = "aurevia_staffmsg_sequence";
        String url = freshDatabase(database, null);
        try {
            try (Connection seed = connect(url, MIGRATE)) {
                seedTenants(seed);
            }
            try (Connection connection = connect(url, APP);
                 Statement statement = connection.createStatement()) {
                statement.execute("SET app.current_org = '" + ORG_A + "'");
                statement.execute("SET app.actor = '" + USER_A + "'");
                String conversation = createConversation(statement, "group");
                String otherConversation = createConversation(statement, "direct");

                // Caller-supplied sequence values are ignored by the allocator.
                MessageRow first = insertMessage(statement, conversation, "send-1", "one", "7", null);
                MessageRow second = insertMessage(statement, conversation, "send-2", "two", "99", null);
                MessageRow third = insertMessage(statement, conversation, "send-3", "three", "-3", null);
                MessageRow other = insertMessage(statement, otherConversation, "send-4", "other", "42", null);
                MessageRow correction = insertMessage(statement, conversation, "send-5", "corrected",
                        "500", first.id());

                assertEquals(1L, first.sequence());
                assertEquals(2L, second.sequence());
                assertEquals(3L, third.sequence());
                assertEquals(1L, other.sequence());
                assertEquals(4L, correction.sequence());

                List<Long> stored = new ArrayList<>();
                try (ResultSet row = statement.executeQuery(
                        "SELECT sequence FROM public.staff_message WHERE conversation_id = '"
                                + conversation + "' ORDER BY sequence")) {
                    while (row.next()) {
                        stored.add(row.getLong(1));
                    }
                }
                assertEquals(List.of(1L, 2L, 3L, 4L), stored);

                SQLException duplicate = assertThrows(SQLException.class,
                        () -> insertMessage(statement, conversation, "send-1", "retry", null, null));
                assertEquals("23505", duplicate.getSQLState());
            }
        } finally {
            dropDatabase(database);
        }
    }

    @Test
    void migrationRunsOnAPopulatedDatabaseWithoutLosingExistingRows() throws Exception {
        String database = "aurevia_staffmsg_populated";
        String url = freshDatabase(database, "90");
        try (Connection connection = connect(url, MIGRATE);
             Statement statement = connection.createStatement()) {
            try (ResultSet row = statement.executeQuery(
                    "SELECT to_regclass('public.staff_message')")) {
                row.next();
                assertEquals(null, row.getString(1));
            }
            seedTenants(connection);
            assertEquals(1, count(statement, "public.organization"));
        }

        migrate(url, null);

        try (Connection connection = connect(url, MIGRATE);
             Statement statement = connection.createStatement()) {
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            for (String table : TABLES) {
                try (ResultSet row = statement.executeQuery(
                        "SELECT to_regclass('public." + table + "')")) {
                    row.next();
                    assertNotNull(row.getString(1), table + " must be created by the upgrade");
                }
            }
            try (ResultSet row = statement.executeQuery(
                    "SELECT count(*) FROM public.organization WHERE organization_id = '" + ORG_A + "'")) {
                row.next();
                assertEquals(1, row.getInt(1));
            }
        } finally {
            dropDatabase(database);
        }
    }

    private static String freshDatabase(String database, String target) throws Exception {
        createRoles();
        try (Connection root = DriverManager.getConnection(PREFIX + "postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
            statement.execute("CREATE DATABASE " + database + " OWNER " + MIGRATE);
        }
        migrate(PREFIX + database, target);
        return PREFIX + database;
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

    private static void migrate(String url, String target) {
        FluentConfiguration configuration = Flyway.configure()
                .dataSource(url, MIGRATE, "")
                .locations("filesystem:../../db/migrations");
        if (target != null) {
            configuration.target(MigrationVersion.fromVersion(target));
        }
        configuration.load().migrate();
    }

    private static void dropDatabase(String database) throws Exception {
        try (Connection root = DriverManager.getConnection(PREFIX + "postgres", ADMIN, "");
             Statement statement = root.createStatement()) {
            statement.execute("DROP DATABASE IF EXISTS " + database + " WITH (FORCE)");
        }
    }

    private static Connection connect(String url, String role) throws Exception {
        return DriverManager.getConnection(url, role, "");
    }

    private static void seedTenants(Connection connection) throws Exception {
        try (Statement statement = connection.createStatement()) {
            for (String org : List.of(ORG_A, ORG_B)) {
                statement.execute("SET app.current_org = '" + org + "'");
                statement.execute("INSERT INTO public.organization (organization_id, display_name) "
                        + "VALUES ('" + org + "', 'Org " + org + "')");
                for (String subject : List.of(USER_A, USER_B)) {
                    statement.execute("INSERT INTO public.user_identity "
                            + "(organization_id, subject, display_name) VALUES ('" + org + "', '"
                            + subject + "', '" + subject + "')");
                }
            }
            statement.execute("SET app.current_org = '" + ORG_A + "'");
            statement.execute("INSERT INTO public.facility (organization_id, id, code, name) "
                    + "VALUES ('" + ORG_A + "', '" + FACILITY_A + "', 'fa', 'Facility A')");
            statement.execute("SET app.current_org = '" + ORG_B + "'");
            statement.execute("INSERT INTO public.facility (organization_id, id, code, name) "
                    + "VALUES ('" + ORG_B + "', '" + FACILITY_B + "', 'fb', 'Facility B')");
        }
    }

    private static String createConversation(Statement statement, String kind) throws Exception {
        try (ResultSet row = statement.executeQuery(
                "INSERT INTO public.staff_conversation (organization_id, facility_id, kind, topic) "
                        + "VALUES ('" + ORG_A + "', '" + FACILITY_A + "', '" + kind + "', 'topic') "
                        + "RETURNING id")) {
            assertTrue(row.next());
            return row.getString(1);
        }
    }

    private static MessageRow insertMessage(Statement statement, String conversation,
                                            String clientId, String body,
                                            String suppliedSequence, String corrects) throws Exception {
        String columns = "organization_id, conversation_id, client_message_id, sender_role, body";
        String values = "'" + ORG_A + "', '" + conversation + "', '" + clientId
                + "', 'nurse', '" + body + "'";
        if (suppliedSequence != null) {
            columns += ", sequence";
            values += ", " + suppliedSequence;
        }
        if (corrects != null) {
            columns += ", corrects_message_id";
            values += ", '" + corrects + "'";
        }
        try (ResultSet row = statement.executeQuery(
                "INSERT INTO public.staff_message (" + columns + ") VALUES (" + values
                        + ") RETURNING id, sequence")) {
            assertTrue(row.next());
            return new MessageRow(row.getString(1), row.getLong(2));
        }
    }

    private static String crossTenantInsert(String table, String conversation) {
        String org = ORG_B;
        return switch (table) {
            case "staff_conversation" -> "INSERT INTO public.staff_conversation "
                    + "(organization_id, facility_id, kind) VALUES ('" + org + "', '" + FACILITY_B + "', 'direct')";
            case "staff_participant" -> "INSERT INTO public.staff_participant "
                    + "(organization_id, conversation_id, staff_subject, role) VALUES ('"
                    + org + "', '" + conversation + "', '" + USER_B + "', 'member')";
            case "staff_message" -> "INSERT INTO public.staff_message "
                    + "(organization_id, conversation_id, client_message_id, sender_role, body) VALUES ('"
                    + org + "', '" + conversation + "', 'foreign-1', 'nurse', 'foreign')";
            case "staff_mail_metadata" -> "INSERT INTO public.staff_mail_metadata "
                    + "(organization_id, message_id, subject) VALUES ('"
                    + org + "', '" + conversation + "', 'foreign')";
            case "staff_mail_folder" -> "INSERT INTO public.staff_mail_folder "
                    + "(organization_id, message_id, staff_subject, folder) VALUES ('"
                    + org + "', '" + conversation + "', '" + USER_B + "', 'inbox')";
            case "staff_attachment_link" -> "INSERT INTO public.staff_attachment_link "
                    + "(organization_id, message_id, document_id, checksum) VALUES ('"
                    + org + "', '" + conversation + "', '99999999-9999-9999-9999-999999999999', 'sha256:x')";
            default -> throw new IllegalArgumentException("unexpected table " + table);
        };
    }

    private static int count(Statement statement, String table) throws SQLException {
        try (ResultSet row = statement.executeQuery("SELECT count(*) FROM " + table)) {
            row.next();
            return row.getInt(1);
        }
    }
}
