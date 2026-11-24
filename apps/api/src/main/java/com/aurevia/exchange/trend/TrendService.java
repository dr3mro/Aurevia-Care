package com.aurevia.exchange.trend;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import com.aurevia.api.ApiException;
import com.aurevia.audit.AuditAppender;
import com.aurevia.tenant.TenantContext;

/**
 * Commands for XCH-07 trending.
 *
 * <p>Each command runs in one tenant-guarded transaction: membership and role
 * are re-derived on every request, the tenant context comes from authentication
 * and the explicit active organization, and a denial is audited before it is
 * returned. TrendObservation and trend reads require the clinical role; changing
 * comparability policy is a separate administrative act. Absent comparability
 * is never an error that hides data: all observations are returned as visible
 * exclusions.</p>
 */
@Service
public class TrendService {

    private static final String SCOPE = "TRENDING";
    private static final String OBSERVATION = "OBSERVATION";
    private static final String COMPARABILITY = "COMPARABILITY";
    private static final String TREND = "TREND";

    private final TrendRepository repository;
    private final TrendActionPolicy policy;
    private final TransactionTemplate transactions;
    private final AuditAppender audit;
    private final TrendAnalyzer analyzer;

    /**
     * Create the service.
     *
     * @param repository    RLS-bounded trend repository
     * @param policy        XCH-07 action policy
     * @param transactions  tenant-guarded transaction template
     * @param audit         transactional audit appender
     * @param analyzer      pure comparability analyzer
     */
    public TrendService(TrendRepository repository, TrendActionPolicy policy,
                        TransactionTemplate transactions, AuditAppender audit,
                        TrendAnalyzer analyzer) {
        this.repository = repository;
        this.policy = policy;
        this.transactions = transactions;
        this.audit = audit;
        this.analyzer = analyzer;
    }

    /**
     * Record one observation with its source provenance.
     *
     * @param request observation command
     * @return the persisted observation
     * @throws ApiException on invalid input, denial, unknown patient or a
     *                      duplicate source revision
     */
    public TrendObservation recordObservation(ObservationRequest request) {
        String patientId = uuid(request.patientId(), "patientId");
        Instant effectiveAt = instant(request.effectiveAt());
        TrendObservation observation = new TrendObservation(UUID.randomUUID().toString(), patientId,
                request.testCode(), request.testDisplay(), request.comparabilityGroup(),
                request.revisionStatus(), request.value(), request.unit(),
                Qualifier.from(request.qualifier()), request.referenceLow(), request.referenceHigh(),
                request.referenceUnit(), effectiveAt, request.interpretation(),
                request.qualitativeValue(), request.sourceSystem(), request.sourceVersion());
        AtomicReference<ApiException> failure = new AtomicReference<>();
        TrendObservation saved = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, "OBSERVATION_RECORD", OBSERVATION, patientId,
                    policy::allowsWrite);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            if (!repository.patientExists(patientId)) {
                throw new ApiException(404, "NOT_FOUND", "patient not found");
            }
            try {
                repository.insertObservation(observation.id(), observation);
            } catch (DataIntegrityViolationException exception) {
                throw new ApiException(409, "DUPLICATE_OBSERVATION",
                        "the source revision is already recorded");
            }
            audit.append(context, "OBSERVATION_RECORD", "OBSERVATION_RECORD", OBSERVATION, SCOPE,
                    observation.id(), "SUCCESS");
            return repository.findObservation(observation.id()).orElseThrow();
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return saved;
    }

    /**
     * Define the next version of a comparability policy.
     *
     * @param request policy command
     * @return the new effective rule
     * @throws ApiException on invalid input, denial or a version conflict
     */
    public ComparabilityRule defineComparability(ComparabilityRequest request) {
        String testCode = code(request.testCode(), "testCode");
        String canonicalUnit = code(request.canonicalUnit(), "canonicalUnit");
        String group = code(request.comparabilityGroup(), "comparabilityGroup");
        Instant effectiveFrom = request.effectiveFrom() == null
                ? Instant.now() : instant(request.effectiveFrom());
        AtomicReference<ApiException> failure = new AtomicReference<>();
        ComparabilityRule rule = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, "COMPARABILITY_DEFINE", COMPARABILITY, testCode,
                    policy::allowsPolicyWrite);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            int version = repository.nextComparabilityVersion(testCode);
            ComparabilityRule next = new ComparabilityRule(testCode, canonicalUnit, group,
                    Boolean.TRUE.equals(request.trendable()), version);
            try {
                repository.insertComparability(next, effectiveFrom);
            } catch (DataIntegrityViolationException exception) {
                throw new ApiException(409, "VERSION_CONFLICT", "the policy version was taken");
            }
            audit.append(context, "COMPARABILITY_DEFINE", "COMPARABILITY_DEFINE", COMPARABILITY,
                    SCOPE, testCode, "SUCCESS");
            return next;
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return rule;
    }

    /**
     * Read a patient's trend. Observations that cannot be compared are returned
     * as visible exclusions rather than being dropped.
     *
     * @param patientId tenant-local patient identifier
     * @param testCode  requested test identity
     * @return comparable points and visible exclusions
     * @throws ApiException on invalid input, denial or a concealed patient
     */
    public TrendView readTrend(String patientId, String testCode) {
        String patient = uuid(patientId, "patientId");
        String test = code(testCode, "testCode");
        AtomicReference<ApiException> failure = new AtomicReference<>();
        TrendView view = transactions.execute(status -> {
            TenantContext context = TenantContext.current();
            ApiException denial = authorize(context, "TREND_READ", TREND, patient, policy::allowsRead);
            if (denial != null) {
                failure.set(denial);
                return null;
            }
            if (!repository.patientExists(patient)) {
                throw new ApiException(404, "NOT_FOUND", "patient not found");
            }
            ComparabilityRule rule = repository.findComparability(test).orElse(null);
            List<TrendObservation> observations = repository.observationsForPatient(patient);
            TrendView result = analyzer.analyze(test, rule, observations);
            audit.append(context, "TREND_READ", "TREND_READ", TREND, SCOPE, patient, "SUCCESS");
            return result;
        });
        if (failure.get() != null) {
            throw failure.get();
        }
        return view;
    }

    private ApiException authorize(TenantContext context, String action, String resourceType,
                                   String resourceId, Predicate<String> roleCheck) {
        if (!repository.hasActiveMembership(context.subject())) {
            audit.append(context, action, action, resourceType, SCOPE, resourceId, "DENIED");
            return new ApiException(403, "FORBIDDEN", "no active membership");
        }
        if (repository.activeRoles(context.subject()).stream().noneMatch(roleCheck)) {
            audit.append(context, action, action, resourceType, SCOPE, resourceId, "DENIED");
            return new ApiException(403, "FORBIDDEN", "missing permission for this action");
        }
        return null;
    }

    private static String code(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new ApiException(422, "INVALID_INPUT", field + " is required");
        }
        return value;
    }

    private static String uuid(String value, String field) {
        try {
            return UUID.fromString(value).toString();
        } catch (RuntimeException exception) {
            throw new ApiException(422, "INVALID_INPUT", field + " must be a UUID");
        }
    }

    private static Instant instant(String value) {
        try {
            return Instant.parse(value);
        } catch (RuntimeException exception) {
            throw new ApiException(422, "INVALID_INPUT", "time must be an ISO-8601 instant");
        }
    }
}
