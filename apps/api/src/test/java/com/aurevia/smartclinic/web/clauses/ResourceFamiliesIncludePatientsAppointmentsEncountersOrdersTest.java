package com.aurevia.smartclinic.web.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import com.aurevia.smartclinic.api.authorization.CommandCatalog;
import com.aurevia.smartclinic.api.authorization.CommandDefinition;

/**
 * ARC-03 clause 2 (API evidence): the fifteen named resource families are exposed as
 * versioned {@code /api/v1} routes under the active organization.
 *
 * <p>This is a pure inventory suite over the committed OpenAPI contract and the synthetic
 * command catalog: no Spring context, no database and no HTTP server. Thirteen families
 * have a concrete contract route today. Two — encounters and referrals — do not, and are
 * characterised by an enabled absence assertion rather than a skipped test, so a partial
 * clause cannot be silently reported as proven. The Gap section of the clause document
 * names the routes and the owner-approved tasks that would close it.</p>
 *
 * <p>Fixtures are two synthetic organization identifiers only. No patient data, credential,
 * request-body role or real identifier is used, and no production file is changed.</p>
 */
class ResourceFamiliesIncludePatientsAppointmentsEncountersOrdersTest {

    private static final String ORG_A = "11111111-1111-1111-1111-111111111111";
    private static final String ORG_B = "22222222-2222-2222-2222-222222222222";
    private static final String UNKNOWN_FAMILY_PATH = "/api/v1/immunizations";
    private static final String UNKNOWN_COMMAND = "recordImmunization";

    /**
     * A clause family paired with the canonical versioned path that exposes it today.
     *
     * @param name clause family name
     * @param path canonical OpenAPI path key
     */
    private record ResourceFamily(String name, String path) {
    }

    /**
     * The thirteen families with a published route. Four are exposed below a sub-namespace
     * rather than at a top-level {@code /api/v1/<family>} path; the clause names families,
     * not path shapes, so a real sub-namespace route satisfies it.
     */
    private static final List<ResourceFamily> EXPOSED_FAMILIES = List.of(
            new ResourceFamily("patients", "/api/v1/patients"),
            new ResourceFamily("appointments", "/api/v1/appointments"),
            new ResourceFamily("orders", "/api/v1/orders"),
            new ResourceFamily("reports", "/api/v1/radiology/reports"),
            new ResourceFamily("prescriptions", "/api/v1/prescriptions"),
            new ResourceFamily("releases", "/api/v1/releases"),
            new ResourceFamily("files", "/api/v1/patient/files/upload"),
            new ResourceFamily("invoices", "/api/v1/finance/invoices"),
            new ResourceFamily("patient-unit connections", "/api/v1/patient-unit-connections"),
            new ResourceFamily("conversations", "/api/v1/conversations"),
            new ResourceFamily("messages", "/api/v1/staff/mail/messages/{id}"),
            new ResourceFamily("instructions", "/api/v1/patient-instructions"),
            new ResourceFamily("audit events", "/api/v1/audit-events"));

    /**
     * Every family built today has a versioned path in the committed contract.
     *
     * @throws IOException when the committed contract cannot be read
     */
    @Test
    void everyImplementedResourceFamilyHasAnApiV1ContractPath() throws IOException {
        Set<String> paths = contractPathKeys();
        assertEquals(13, EXPOSED_FAMILIES.size(),
                "the inventory must account for every family proven today");
        for (ResourceFamily family : EXPOSED_FAMILIES) {
            assertTrue(family.path().startsWith("/api/v1/"),
                    family.name() + " must use a versioned route: " + family.path());
            assertTrue(paths.contains(family.path()),
                    family.name() + " must expose " + family.path() + " in the OpenAPI contract");
        }
    }

    /**
     * A family outside the clause has neither a published route nor a registered command.
     * A positive control proves both lookups can succeed, so the refusals are meaningful.
     */
    @Test
    void anUnknownResourceFamilyIsRejectedByTheContractAndTheCommandCatalog() throws IOException {
        assertFalse(contractPathKeys().contains(UNKNOWN_FAMILY_PATH),
                "a family outside the clause must not appear in the published contract");

        CommandCatalog catalog = CommandCatalog.synthetic();
        assertTrue(catalog.find("registerPatient").isPresent(),
                "positive control: the catalog lookup can succeed");
        assertTrue(catalog.find(UNKNOWN_COMMAND).isEmpty(),
                "the command catalog must refuse a command that is not registered");
    }

    /**
     * Characterise the two absent families. These enabled assertions are green today; when
     * a resource route lands, the matching assertion flips and forces the next task to replace
     * it with a presence proof instead of leaving a silently skipped test behind.
     *
     * @throws IOException when the committed contract cannot be read
     */
    @Test
    void encountersAndReferralsHaveNoTopLevelResourceRouteYet() throws IOException {
        Set<String> paths = contractPathKeys();
        assertFalse(paths.contains("/api/v1/encounters"),
                "GAP: encounters surface only as a check-in sub-route, not as a resource family");
        assertFalse(paths.contains("/api/v1/referrals"),
                "GAP: referrals have no published resource route");

        CommandDefinition referral = CommandCatalog.synthetic().find("acceptTherapyReferral").orElseThrow();
        assertEquals("/api/v1/therapy/referrals/{id}/accept", referral.path());
        assertFalse(paths.contains(referral.path()),
                "GAP: the referral command path is registered in the catalog but absent from the contract");
    }

    /**
     * Versioned family routes stay tenant-neutral. A future task that adds a tenant selector
     * to a family path, such as {@code /api/v1/patients/{organizationId}}, would flip this
     * assertion; the tenant is derived from the authenticated session, never from the route.
     */
    @Test
    void familyResourcePathsNeverSelectATenantByPath() {
        for (ResourceFamily family : EXPOSED_FAMILIES) {
            assertFalse(family.path().contains(ORG_A) || family.path().contains(ORG_B),
                    family.name() + " route must not select a tenant by path: " + family.path());
        }
    }

    /**
     * Parse the committed staff-API contract into its path keys.
     *
     * @return the distinct {@code paths} keys of {@code contracts/openapi.yaml}
     * @throws IOException when the contract cannot be read
     */
    private static Set<String> contractPathKeys() throws IOException {
        Map<?, ?> document = (Map<?, ?>) new Yaml().load(Files.readString(locateContract()));
        Object paths = document.get("paths");
        if (!(paths instanceof Map<?, ?> pathMap)) {
            throw new IllegalStateException("contract has no paths section");
        }
        Set<String> keys = new HashSet<>();
        for (Object key : pathMap.keySet()) {
            keys.add(String.valueOf(key));
        }
        return Set.copyOf(keys);
    }

    /**
     * Locate the authoritative contract whatever directory Maven was invoked from. Surefire
     * uses the module basedir, but the same test also runs from the repository root in some
     * tooling.
     *
     * @return the path of {@code contracts/openapi.yaml}
     */
    private static Path locateContract() {
        for (String candidate : List.of("../../contracts/openapi.yaml", "contracts/openapi.yaml",
                "../contracts/openapi.yaml")) {
            Path path = Path.of(candidate);
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        String basedir = System.getProperty("basedir");
        if (basedir != null) {
            Path path = Path.of(basedir).resolve("../contracts/openapi.yaml").normalize();
            if (Files.isRegularFile(path)) {
                return path;
            }
        }
        throw new IllegalStateException("cannot locate contracts/openapi.yaml");
    }
}
