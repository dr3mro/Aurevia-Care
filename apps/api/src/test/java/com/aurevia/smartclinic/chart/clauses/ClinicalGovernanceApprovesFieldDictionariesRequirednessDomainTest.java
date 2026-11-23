package com.aurevia.smartclinic.chart.clauses;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.clinical.templates.BaselineTemplates;
import com.aurevia.smartclinic.clinical.templates.FieldDefinition;
import com.aurevia.smartclinic.clinical.templates.FieldDictionary;
import com.aurevia.smartclinic.clinical.templates.InMemoryTemplateStore;
import com.aurevia.smartclinic.clinical.templates.TemplateActor;
import com.aurevia.smartclinic.clinical.templates.TemplateKind;
import com.aurevia.smartclinic.clinical.templates.TemplateService;
import com.aurevia.smartclinic.clinical.templates.TemplateVersion;

/**
 * CLN-04 clause 6 proof: only clinical governance may approve a site field
 * dictionary and a template version, and an approval freezes the field
 * requiredness that notes are later validated against. A caller's authority is
 * taken from a fresh active-membership snapshot, never from the command itself,
 * and an approval never crosses a tenant boundary (IAM-01, ARC-02).
 *
 * <p>Synthetic fixtures only. This proof exercises the domain service through
 * its normal public entry point with the same in-memory store the neighbouring
 * template tests use; it changes no production code.</p>
 */
class ClinicalGovernanceApprovesFieldDictionariesRequirednessDomainTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String OTHER_ORG = "22222222-2222-2222-2222-222222222222";
    private static final String DICTIONARY_KEY = "obstetric_history";
    private static final List<String> ENTRIES = List.of("G0P0", "G1P0", "G2P0");

    private final TemplateActor admin = TemplateActor.of(ORG, "admin-clause-six", TemplateService.TENANT_ADMIN);
    private final TemplateActor governor = TemplateActor.of(ORG, "gov-clause-six", TemplateService.CLINICAL_GOVERNANCE);
    private final TemplateActor clinician = TemplateActor.of(ORG, "clin-clause-six", TemplateService.CLINICIAN);

    private TemplateService service;

    @BeforeEach
    void setUp() {
        service = new TemplateService(new InMemoryTemplateStore());
    }

    /** Define a pending dictionary as the tenant administrator. */
    private void definePendingDictionary() {
        service.defineDictionary(admin, DICTIONARY_KEY, ENTRIES);
    }

    /** Define a pending OB/GYN version whose CODE field references the dictionary. */
    private TemplateVersion defineObgynVersion() {
        return service.defineVersion(admin, TemplateKind.OBGYN,
                BaselineTemplates.fieldsFor(TemplateKind.OBGYN));
    }

    @Test
    void governanceApprovesDictionaryAndTemplateVersionFreezingRequiredness() {
        definePendingDictionary();
        assertFalse(service.dictionary(clinician, DICTIONARY_KEY).approved(),
                "a freshly defined dictionary is pending, not approved");

        FieldDictionary dictionary = service.approveDictionary(governor, DICTIONARY_KEY);
        assertTrue(dictionary.approved());
        assertEquals(governor.subject(), dictionary.approvedBy());
        assertEquals(ENTRIES, dictionary.entries());

        TemplateVersion draft = defineObgynVersion();
        assertFalse(draft.approved(), "a freshly defined version is pending");

        TemplateVersion approved = service.approveVersion(governor, TemplateKind.OBGYN, 1);
        assertTrue(approved.approved());
        assertEquals(governor.subject(), approved.approvedBy());
        // Governance approval is the act that fixes the requiredness and
        // dictionary binding a note will be validated against.
        assertTrue(field(approved.fields(), "lmp").required());
        FieldDefinition coded = field(approved.fields(), "obstetricHistory");
        assertTrue(coded.required());
        assertEquals(DICTIONARY_KEY, coded.dictionaryKey());
        assertEquals(approved, service.effective(clinician, TemplateKind.OBGYN));
    }

    @Test
    void tenantAdminCannotApproveDictionary() {
        definePendingDictionary();

        ApiException denial = assertThrows(ApiException.class,
                () -> service.approveDictionary(admin, DICTIONARY_KEY));

        assertEquals(403, denial.status());
        assertEquals("FORBIDDEN", denial.code());
        assertTrue(denial.getMessage().contains("TEMPLATE_APPROVE"));
        assertFalse(service.dictionary(clinician, DICTIONARY_KEY).approved(),
                "a denied approval leaves the dictionary pending");
    }

    @Test
    void tenantAdminCannotApproveTemplateVersion() {
        definePendingDictionary();
        service.approveDictionary(governor, DICTIONARY_KEY);
        defineObgynVersion();

        ApiException denial = assertThrows(ApiException.class,
                () -> service.approveVersion(admin, TemplateKind.OBGYN, 1));

        assertEquals(403, denial.status());
        assertEquals("FORBIDDEN", denial.code());
        assertFalse(service.history(clinician, TemplateKind.OBGYN).get(0).approved(),
                "a denied approval leaves the version pending");
    }

    @Test
    void versionCannotBeApprovedUntilItsDictionaryIsApproved() {
        definePendingDictionary();
        defineObgynVersion();

        ApiException denial = assertThrows(ApiException.class,
                () -> service.approveVersion(governor, TemplateKind.OBGYN, 1));

        assertEquals(422, denial.status());
        assertEquals("DICTIONARY_NOT_APPROVED", denial.code());
        assertFalse(service.history(clinician, TemplateKind.OBGYN).get(0).approved());
    }

    @Test
    void governanceCannotApproveAnotherTenantsTemplate() {
        definePendingDictionary();
        service.approveDictionary(governor, DICTIONARY_KEY);
        defineObgynVersion();
        TemplateActor otherGovernor = TemplateActor.of(OTHER_ORG, "gov-other", TemplateService.CLINICAL_GOVERNANCE);

        ApiException denial = assertThrows(ApiException.class,
                () -> service.approveVersion(otherGovernor, TemplateKind.OBGYN, 1));

        assertEquals(404, denial.status());
        assertEquals("NOT_FOUND", denial.code());
        assertFalse(service.history(clinician, TemplateKind.OBGYN).get(0).approved(),
                "an approval from another tenant cannot change the local version");
    }

    private static FieldDefinition field(List<FieldDefinition> fields, String key) {
        return fields.stream().filter(candidate -> key.equals(candidate.key())).findFirst().orElseThrow();
    }
}
