package com.aurevia.pharmacyflow.substitution;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import com.aurevia.prescription.PrescriptionRepository;

/** Every read of the JDBC adapter runs inside the tenant-guarded transaction. */
class JdbcSubstitutionStoreTest {

    private PrescriptionRepository repository;
    private JdbcSubstitutionStore catalog;

    @BeforeEach
    void setUp() {
        repository = mock(PrescriptionRepository.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        TransactionStatus status = mock(TransactionStatus.class);
        when(transactions.execute(any(TransactionCallback.class))).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(status);
        });
        catalog = new JdbcSubstitutionStore(repository, transactions, mock(NamedParameterJdbcTemplate.class));
    }

    @Test
    void draftReadsTheVisibleDraftOrNull() {
        when(repository.find(SubstitutionFixtures.PRESCRIPTION))
                .thenReturn(Optional.of(SubstitutionFixtures.draft()));
        assertEquals(SubstitutionFixtures.PRESCRIPTION,
                catalog.draft(SubstitutionFixtures.PRESCRIPTION).id());
        when(repository.find("missing")).thenReturn(Optional.empty());
        assertNull(catalog.draft("missing"));
    }

    @Test
    void entriesDelegatesToTheCatalogFold() {
        when(repository.loadCatalog()).thenReturn(SubstitutionFixtures.entries());
        assertEquals(3, catalog.entries().size());
    }

    @Test
    void allergiesDelegatesToThePatientFold() {
        when(repository.allergyAssessment(SubstitutionFixtures.PATIENT))
                .thenReturn(SubstitutionFixtures.noAllergies());
        assertEquals(SubstitutionFixtures.noAllergies(),
                catalog.allergies(SubstitutionFixtures.PATIENT));
    }

    @Test
    void routeDelegatesToTheApprovedCatalogRoute() {
        when(repository.catalogRoute(anyString(), anyInt())).thenReturn("oral");
        assertEquals("oral", catalog.route(SubstitutionFixtures.ALTERNATIVE_ID, 1));
    }
}
