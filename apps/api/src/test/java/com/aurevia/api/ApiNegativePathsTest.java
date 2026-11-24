package com.aurevia.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.sql.SQLException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.converter.HttpMessageNotReadableException;

import com.aurevia.api.authorization.CommandAuthorization;
import com.aurevia.api.authorization.CommandAuthorization.CommandRequest;
import com.aurevia.api.authorization.CommandAuthorization.Decision;
import com.aurevia.api.authorization.CommandAuthorization.MemberContext;
import com.aurevia.api.authorization.CommandAuthorization.MembershipResolver;
import com.aurevia.api.authorization.CommandAuthorization.ServerIdentity;
import com.aurevia.api.authorization.CommandCatalog;
import com.aurevia.api.authorization.InMemoryDecisionSink;
import com.aurevia.api.contract.ApiErrorCode;
import com.aurevia.api.contract.ApiVersion;
import com.aurevia.api.contract.CanonicalUuid;
import com.aurevia.api.contract.CatalogCode;
import com.aurevia.api.contract.Cursor;
import com.aurevia.api.contract.FacilityCode;
import com.aurevia.api.contract.IdempotencyKey;
import com.aurevia.api.contract.IdempotencyLedger;
import com.aurevia.api.contract.PageRequest;
import com.aurevia.api.contract.RequestDigest;
import com.aurevia.api.contract.Sha256Hex;
import com.aurevia.api.contract.Subject;
import com.aurevia.tenant.TenantContext;

/**
 * Cross-cutting negative paths for the API package (WEB-04, ARC-02).
 *
 * <p>The neighbouring tests cover each primitive in isolation. This class walks
 * the same trust boundaries together and asserts the two properties that only
 * appear when the paths are composed: every failure is rendered by the single
 * uniform error handler as a machine-readable body that echoes no payload, SQL,
 * stack frame, internal class name or other tenant (WEB-04), and an identifier
 * that belongs to another tenant is indistinguishable from an invalid or
 * inaccessible one (ARC-02). All fixtures are synthetic.</p>
 */
class ApiNegativePathsTest {

    private static final String ORG = "11111111-2222-3333-4444-555555555555";
    private static final String OTHER_ORG = "99999999-8888-7777-6666-555555555555";
    private static final String SUBJECT = "actor-1";
    private static final String COMMAND = "orders.accept";
    private static final String OUTCOME = "order-1:v2:ACCEPTED";
    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-11-19T12:00:00Z"), ZoneOffset.UTC);

    private final ApiErrorHandler handler = new ApiErrorHandler();

    /** Resolver whose active roles are supplied per test; membership stays active. */
    private static final class Resolver implements MembershipResolver {

        private List<String> roles = List.of();

        @Override
        public MemberContext resolve(ServerIdentity identity) {
            return new MemberContext(roles, true);
        }
    }

    @AfterEach
    void clearContext() {
        TenantContext.clear();
    }

    /** Render a domain failure through the same advice every protected route uses. */
    private ApiErrorHandler.ErrorBody render(ApiException failure) {
        return handler.handleApi(failure).getBody();
    }

    /** Assert the WEB-04 non-disclosure invariant on a rendered body. */
    private static void assertNoDisclosure(ApiErrorHandler.ErrorBody body) {
        assertNotNull(body);
        assertFalse(body.code().isBlank());
        assertFalse(body.message().isBlank());
        assertFalse(body.message().contains("com.aurevia"), "internal class name leaked");
        assertFalse(body.message().contains("SQLException"), "SQL detail leaked");
        assertFalse(body.message().contains("\tat "), "stack frame leaked");
        assertFalse(body.message().contains(OTHER_ORG), "other tenant leaked");
    }

    /** Render a rejection from a bounded primitive through the uniform invalid-input handler. */
    private void assertRenderedAsInvalidInput(IllegalArgumentException failure, String detail) {
        assertEquals(detail, failure.getMessage());
        var response = handler.handleInvalid(failure);
        assertEquals(422, response.getStatusCode().value());
        assertEquals("INVALID_INPUT", response.getBody().code());
        assertNoDisclosure(response.getBody());
    }

    @Test
    void missingAndOversizedInputFailClosedWithMachineReadableCodes() {
        ApiException missingIfMatch = assertThrows(ApiException.class, () -> IfMatch.parse(null));
        assertEquals(428, missingIfMatch.status());
        assertEquals("PRECONDITION_REQUIRED", missingIfMatch.code());
        ApiErrorHandler.ErrorBody ifMatchBody = render(missingIfMatch);
        assertEquals("PRECONDITION_REQUIRED", ifMatchBody.code());
        assertEquals("unassigned", ifMatchBody.correlationId());
        assertNoDisclosure(ifMatchBody);

        ApiException missingKey = assertThrows(ApiException.class, () -> IdempotencyKey.parse("  "));
        assertEquals(428, missingKey.status());
        assertEquals("IDEMPOTENCY_KEY_REQUIRED", missingKey.code());
        assertNoDisclosure(render(missingKey));

        // 256 characters is one past the bounded 255-character cache key.
        ApiException oversizedKey = assertThrows(ApiException.class,
                () -> IdempotencyKey.parse("a".repeat(256)));
        assertEquals(422, oversizedKey.status());
        assertEquals("INVALID_IDEMPOTENCY_KEY", oversizedKey.code());
        assertNoDisclosure(render(oversizedKey));

        ApiException invalidLimit = assertThrows(ApiException.class,
                () -> PageRequest.parse(null, "0", ORG));
        assertEquals(422, invalidLimit.status());
        assertEquals("INVALID_LIMIT", invalidLimit.code());
        assertNoDisclosure(render(invalidLimit));
    }

    @Test
    void malformedIdentifiersAreRejectedAndRenderedAsUnprocessableInput() {
        ApiException unsupportedVersion = assertThrows(ApiException.class, () -> ApiVersion.from("v2"));
        assertEquals(422, unsupportedVersion.status());
        assertEquals("UNSUPPORTED_API_VERSION", unsupportedVersion.code());
        assertNoDisclosure(render(unsupportedVersion));

        assertRenderedAsInvalidInput(
                assertThrows(IllegalArgumentException.class, () -> CanonicalUuid.uuid("not-a-uuid")),
                CanonicalUuid.INVALID_MESSAGE);
        assertRenderedAsInvalidInput(
                assertThrows(IllegalArgumentException.class, () -> CatalogCode.code("a")),
                CatalogCode.INVALID_MESSAGE);
        assertRenderedAsInvalidInput(
                assertThrows(IllegalArgumentException.class,
                        () -> FacilityCode.facilityCode("A".repeat(33))),
                FacilityCode.INVALID_MESSAGE);
        assertRenderedAsInvalidInput(
                assertThrows(IllegalArgumentException.class, () -> Subject.subject("Uppercase")),
                Subject.INVALID_MESSAGE);
        assertRenderedAsInvalidInput(
                assertThrows(IllegalArgumentException.class, () -> Sha256Hex.sha256Hex("z".repeat(64))),
                Sha256Hex.INVALID_MESSAGE);
    }

    @Test
    void malformedBodyReturnsAFixedDetailThatCannotEchoTheSubmission() {
        TenantContext.set(new TenantContext(ORG, SUBJECT, "test", "corr-body"));
        var response = handler.handleUnreadableBody(new HttpMessageNotReadableException(
                "SubmittedFragment", null));
        assertEquals(400, response.getStatusCode().value());
        assertEquals("MALFORMED_REQUEST_BODY", response.getBody().code());
        // Jackson's message may quote the payload; only the fixed detail is rendered.
        assertEquals("request body could not be read", response.getBody().message());
        assertFalse(response.getBody().message().contains("SubmittedFragment"), "submission echoed");
        assertEquals("corr-body", response.getBody().correlationId());
        assertNoDisclosure(response.getBody());
    }

    @Test
    void wrongRoleIsDeniedRecordedAndRenderedWithoutDisclosure() {
        Resolver resolver = new Resolver();
        resolver.roles = List.of("reception");
        InMemoryDecisionSink sink = new InMemoryDecisionSink();
        CommandAuthorization authorization =
                CommandAuthorization.synthetic(CommandCatalog.synthetic(), resolver, sink, CLOCK);

        // The claimed admin role is inert: only freshly resolved roles decide.
        CommandRequest forged = new CommandRequest("createFacility", "tenant_admin", List.of("*"));
        ApiException denial = assertThrows(ApiException.class,
                () -> authorization.authorize(forged, new ServerIdentity(ORG, SUBJECT, "corr-role")));
        assertEquals(403, denial.status());
        assertEquals("FORBIDDEN", denial.code());

        Decision decision = sink.decisions().get(0);
        assertFalse(decision.allowed());
        assertEquals("COMMAND_FORBIDDEN", decision.reasonCode());
        assertEquals(List.of("reception"), decision.rolesUsed());
        assertEquals("createFacility", decision.commandId());

        TenantContext.set(new TenantContext(ORG, SUBJECT, "test", "corr-role"));
        ApiErrorHandler.ErrorBody body = render(denial);
        assertEquals("FORBIDDEN", body.code());
        assertEquals("corr-role", body.correlationId());
        assertNoDisclosure(body);
    }

    @Test
    void identifiersFromAnotherTenantAreConcealed() {
        String foreignToken = Cursor.encode(new Cursor(OTHER_ORG, "sort", "record-1"));
        ApiException foreignCursor = assertThrows(ApiException.class,
                () -> Cursor.decode(foreignToken, ORG));
        assertEquals(422, foreignCursor.status());
        assertEquals("INVALID_CURSOR", foreignCursor.code());
        ApiErrorHandler.ErrorBody cursorBody = render(foreignCursor);
        assertEquals("cursor is invalid", cursorBody.message());
        assertFalse(cursorBody.message().contains(OTHER_ORG), "foreign tenant revealed by cursor error");
        assertFalse(cursorBody.message().contains("record-1"), "record id revealed by cursor error");
        assertNoDisclosure(cursorBody);
        // A foreign cursor and a malformed token are deliberately indistinguishable.
        ApiException malformedCursor = assertThrows(ApiException.class, () -> Cursor.decode("!!!", ORG));
        assertEquals(foreignCursor.getMessage(), malformedCursor.getMessage());

        // Under forced RLS an invisible foreign reference surfaces as a FK violation.
        ApiException invisibleReference = SqlFailureTranslator.translate(
                new DataIntegrityViolationException("write failed",
                        new SQLException("constraint", "23503")));
        assertEquals(404, invisibleReference.status());
        assertEquals("NOT_FOUND", invisibleReference.code());
        ApiErrorHandler.ErrorBody referenceBody = render(invisibleReference);
        assertEquals("referenced record not found", referenceBody.message());
        assertFalse(referenceBody.message().contains("23503"), "SQL state leaked");
        assertNoDisclosure(referenceBody);
    }

    @Test
    void staleVersionYieldsPreconditionFailedWithoutRecordDetail() {
        assertEquals(3, IfMatch.parse("W/\"3\""));
        // A malformed precondition is a 422; only a version mismatch against the
        // stored revision is the stale-version 412 asserted below.
        ApiException malformedVersion = assertThrows(ApiException.class, () -> IfMatch.parse("0"));
        assertEquals(422, malformedVersion.status());
        assertEquals("INVALID_IF_MATCH", malformedVersion.code());

        ApiException stale = ApiErrorCode.STALE_VERSION.exception();
        assertEquals(412, stale.status());
        assertEquals("STALE_VERSION", stale.code());
        assertEquals(412, handler.handleApi(stale).getStatusCode().value());
        ApiErrorHandler.ErrorBody body = render(stale);
        assertEquals("STALE_VERSION", body.code());
        assertEquals("the resource version is stale", body.message());
        assertFalse(body.message().contains("record-1"), "record id revealed by stale version");
        assertNoDisclosure(body);
    }

    @Test
    void repeatedKeyReplaysOrConflictsAndConcealsUnauthorizedResults() {
        IdempotencyLedger ledger = new IdempotencyLedger.InMemoryIdempotencyLedger();
        IdempotencyLedger.Scope scope = new IdempotencyLedger.Scope(ORG, SUBJECT, COMMAND);
        IdempotencyKey key = IdempotencyKey.parse("retry-key-1");
        RequestDigest digest = RequestDigest.of(ORG, SUBJECT, COMMAND, "payload-a");

        IdempotencyLedger.Resolution claim = ledger.claim(scope, key, digest, () -> true);
        assertEquals(IdempotencyLedger.Decision.PROCEED, claim.decision());
        assertNull(claim.outcome());
        assertNotNull(claim.claim());
        // A second caller holding the same key while the first is in flight conflicts.
        assertEquals(IdempotencyLedger.Decision.CONFLICT,
                ledger.claim(scope, key, digest, () -> true).decision());

        ledger.complete(scope, key, claim.claim(), OUTCOME);
        IdempotencyLedger.Resolution replay = ledger.claim(scope, key, digest, () -> true);
        assertEquals(IdempotencyLedger.Decision.REPLAY, replay.decision());
        assertEquals(OUTCOME, replay.outcome());

        IdempotencyLedger.Resolution changed = ledger.claim(scope, key,
                RequestDigest.of(ORG, SUBJECT, COMMAND, "payload-b"), () -> true);
        assertEquals(IdempotencyLedger.Decision.CONFLICT, changed.decision());
        assertNull(changed.outcome());

        // Replay re-authorizes through the protected path; a loss of authority is
        // concealed as not found rather than returning the original outcome.
        ApiException unauthorized = assertThrows(ApiException.class,
                () -> ledger.claim(scope, key, digest, () -> false));
        assertEquals(404, unauthorized.status());
        assertEquals("NOT_FOUND", unauthorized.code());
        ApiErrorHandler.ErrorBody body = render(unauthorized);
        assertFalse(body.message().contains(OUTCOME), "prior outcome leaked to unauthorized replay");
        assertNoDisclosure(body);

        // The key is scoped to the tenant, so the same key in a peer tenant proceeds.
        IdempotencyLedger.Scope peerTenant = new IdempotencyLedger.Scope(OTHER_ORG, SUBJECT, COMMAND);
        assertEquals(IdempotencyLedger.Decision.PROCEED,
                ledger.claim(peerTenant, key, digest, () -> true).decision());
    }
}
