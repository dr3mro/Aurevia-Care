package com.aurevia.acceptance.scenarios;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.audit.AuditAppender;
import com.aurevia.tenant.TenantContext;

/**
 * Synthetic fixture shared by the cross-cutting scenario acceptance tests.
 *
 * <p>Two clinics and a partner lab are represented by three opaque organization
 * identifiers. Every clinic uses the same facility code, issuer-scoped MRN and
 * subject spellings, so an assertion that reads the peer tenant's record can only
 * pass when the tenant context is actually enforced. The fixture owns the clock,
 * the audit sink and a transaction template that runs its callback inline; each
 * scenario composes the production services it exercises with its own mocked
 * repositories. All identifiers and notes are synthetic.</p>
 */
abstract class FirstSliceFixture {

    static final String CLINIC_A = "11111111-1111-1111-1111-111111111111";
    static final String CLINIC_B = "22222222-2222-2222-2222-222222222222";
    static final String PARTNER_LAB = "33333333-3333-3333-3333-333333333333";

    static final String FACILITY = "MAIN";
    static final String MRN = "MRN-SHARED";
    static final String ISSUER = "CLINIC";
    static final String PATIENT = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";
    static final String PATIENT_B = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";

    static final Instant NOW = Instant.parse("2026-11-23T10:00:00Z");
    static final String PURPOSE = "SCENARIO_EVIDENCE";
    static final String CORRELATION = "corr-scenario-slice";

    AuditAppender audit;
    TransactionTemplate transactions;

    /** Create the shared audit and transaction doubles and bind the first clinic. */
    @BeforeEach
    void setUpFixture() {
        audit = mock(AuditAppender.class);
        transactions = mock(TransactionTemplate.class);
        doAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        }).when(transactions).execute(any());
        bind(CLINIC_A, "syn-reception");
    }

    /** Always clear thread-local tenant state so parallel tests cannot inherit it. */
    @AfterEach
    void clearFixture() {
        TenantContext.clear();
    }

    /**
     * Bind the tenant context for one synthetic actor.
     *
     * @param organizationId active organization
     * @param subject        authenticated synthetic subject
     */
    static void bind(String organizationId, String subject) {
        TenantContext.set(new TenantContext(organizationId, subject, PURPOSE, CORRELATION));
    }

    /** Fixed clock at the scenario's evaluation instant. */
    static Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    /**
     * Fixed clock at a supplied instant, for deadline and effective-date branches.
     *
     * @param instant instant the clock reports
     * @return a deterministic clock
     */
    static Clock clockAt(Instant instant) {
        return Clock.fixed(instant, ZoneOffset.UTC);
    }
}
