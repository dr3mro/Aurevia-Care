package com.aurevia.pharmacyflow.clarify;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.aurevia.api.ApiException;
import com.aurevia.pharmacyflow.clarify.ClarificationCommands.AnswerCommand;
import com.aurevia.pharmacyflow.clarify.ClarificationCommands.OpenCommand;
import com.aurevia.pharmacyflow.clarify.ClarificationContract.Entry;
import com.aurevia.pharmacyflow.clarify.ClarificationContract.Party;
import com.aurevia.pharmacyflow.clarify.ClarificationContract.State;
import com.aurevia.pharmacyflow.clarify.ClarificationContract.Thread;
import com.aurevia.pharmacyflow.clarify.ClarificationContract.ThreadView;
import com.aurevia.prescription.rx.RxService;

/**
 * RX-02 / MSG-04 clarification thread domain kernel.
 *
 * <p>The kernel owns the tenant-scoped thread trails and the idempotency
 * records. It has no HTTP or persistence dependency: every command is supplied
 * with verified membership roles, the active tenant and an {@code If-Match}
 * precondition, all of which the caller must re-derive. The prescriber address
 * is resolved from the prescription through the tenant-bound prescription
 * service, never accepted from the request body. An {@code Idempotency-Key}
 * replays the original result so a retried action records once.</p>
 *
 * <p>A request is visible only to the pharmacy requester and the named
 * prescriber in the same tenant. The answer closes the request, is appended to
 * the same trail and becomes visible to the pharmacy requester. A request that
 * remains unanswered beyond the configured limit is flagged overdue.</p>
 */
@Service
public final class ClarificationService {

    /** Default unanswered age after which a request is reported as overdue. */
    public static final Duration DEFAULT_OVERDUE_AFTER = Duration.ofHours(48);

    private static final String IDEM_SEPARATOR = "|";
    private static final String COMMAND_OPEN = "open";
    private static final String COMMAND_ANSWER = "answer";

    private final Clock clock;
    private final Duration overdueAfter;
    private final RxService prescriptions;
    private final Map<String, Map<String, Thread>> threads = new HashMap<>();
    private final Map<String, List<Entry>> trails = new HashMap<>();
    private final Map<String, Thread> openIdempotency = new HashMap<>();
    private final Map<String, Thread> answerIdempotency = new HashMap<>();

    /**
     * Create the kernel with the domain default overdue limit.
     *
     * @param clock         application clock used for occurrence and overdue times
     * @param prescriptions tenant-bound prescription lookup for prescriber resolution
     */
    @Autowired
    public ClarificationService(Clock clock, RxService prescriptions) {
        this(clock, DEFAULT_OVERDUE_AFTER, prescriptions);
    }

    /**
     * Create the kernel with an explicit overdue limit.
     *
     * @param clock         application clock used for occurrence and overdue times
     * @param overdueAfter  configured unanswered age limit
     * @param prescriptions tenant-bound prescription lookup for prescriber resolution
     */
    public ClarificationService(Clock clock, Duration overdueAfter, RxService prescriptions) {
        this.clock = clock;
        this.overdueAfter = overdueAfter;
        this.prescriptions = prescriptions;
    }

    /**
     * Open a clarification request on a prescription addressed to its prescriber.
     *
     * @param command open command with verified tenant, actor and roles
     * @return the new thread with its request entry
     * @throws ApiException on invalid input, missing permission, denial by the
     *         prescription lookup, or a replay conflict
     */
    public synchronized ThreadView open(OpenCommand command) {
        ClarificationInputs.requireText(command.organizationId(), "INVALID_ORGANIZATION",
                "active organization is required");
        ClarificationInputs.requireText(command.subject(), "INVALID_ACTOR", "actor is required");
        ClarificationInputs.requireText(command.prescriptionId(), "INVALID_PRESCRIPTION",
                "prescription identifier is required");
        ClarificationInputs.requireText(command.question(), "QUESTION_REQUIRED",
                "a clarification question is required");
        ClarificationInputs.requireText(command.idempotencyKey(), "INVALID_IDEMPOTENCY_KEY",
                "an idempotency key is required");
        if (!ClarificationRules.mayOpen(command.roles())) {
            throw new ApiException(403, "FORBIDDEN", "missing pharmacy clarification permission");
        }
        String key = key(command.organizationId(), null, command.subject(), COMMAND_OPEN,
                command.idempotencyKey());
        Thread replay = openIdempotency.get(key);
        if (replay != null) {
            return view(replay);
        }
        String prescriber = prescriptions.get(command.prescriptionId()).prescription().prescriberSubject();
        String id = ClarificationInputs.newId();
        Instant now = clock.instant();
        Thread thread = new Thread(id, command.organizationId(), command.prescriptionId(),
                command.subject(), prescriber, State.OPEN, now, 1, null, null, null, null);
        Entry request = new Entry(ClarificationInputs.newId(), id, 1, Party.PHARMACY,
                command.subject(), command.question(), null, now);
        threads.computeIfAbsent(command.organizationId(), ignored -> new HashMap<>()).put(id, thread);
        trails.put(id, List.of(request));
        openIdempotency.put(key, thread);
        return view(thread);
    }

    /**
     * Answer an open request with text, an amended revision, or both, closing it.
     *
     * @param command answer command with the version read by the caller
     * @return the closed thread with the prescriber's answer entry
     * @throws ApiException on invalid input, denial, an invisible thread, a
     *         stale version, a closed thread, or a replay conflict
     */
    public synchronized ThreadView answer(AnswerCommand command) {
        ClarificationInputs.requireText(command.organizationId(), "INVALID_ORGANIZATION",
                "active organization is required");
        ClarificationInputs.requireText(command.subject(), "INVALID_ACTOR", "actor is required");
        ClarificationInputs.requireText(command.threadId(), "INVALID_THREAD", "thread identifier is required");
        ClarificationInputs.requireText(command.idempotencyKey(), "INVALID_IDEMPOTENCY_KEY",
                "an idempotency key is required");
        ClarificationInputs.requirePositive(command.expectedVersion(), "INVALID_VERSION",
                "version must be a positive integer");
        boolean hasText = command.answerBody() != null && !command.answerBody().isBlank();
        boolean hasRevision = command.amendedRevisionId() != null && !command.amendedRevisionId().isBlank();
        if (!hasText && !hasRevision) {
            throw new ApiException(422, "ANSWER_REQUIRED", "an answer needs text or an amended revision");
        }
        if (!ClarificationRules.mayAnswer(command.roles())) {
            throw new ApiException(403, "FORBIDDEN", "missing prescriber clarification permission");
        }
        Thread current = thread(command.organizationId(), command.threadId());
        if (current == null) {
            throw new ApiException(404, "NOT_FOUND", "clarification thread not found");
        }
        if (!command.subject().equals(current.prescriberSubject())) {
            throw new ApiException(404, "NOT_FOUND", "clarification thread not found");
        }
        String key = key(command.organizationId(), command.threadId(), command.subject(), COMMAND_ANSWER,
                command.idempotencyKey());
        Thread replay = answerIdempotency.get(key);
        if (replay != null) {
            return view(replay);
        }
        if (current.version() != command.expectedVersion()) {
            throw new ApiException(412, "STALE_VERSION", "the clarification thread changed since it was read");
        }
        if (current.state() == State.ANSWERED) {
            throw new ApiException(409, "CLOSED_THREAD", "an answered clarification thread is read-only");
        }
        Instant now = clock.instant();
        int nextVersion = current.version() + 1;
        Entry reply = new Entry(ClarificationInputs.newId(), command.threadId(), nextVersion,
                Party.PRESCRIBER, command.subject(), command.answerBody(), command.amendedRevisionId(), now);
        Thread answered = new Thread(current.id(), current.organizationId(), current.prescriptionId(),
                current.requesterSubject(), current.prescriberSubject(), State.ANSWERED, current.openedAt(),
                nextVersion, command.answerBody(), command.amendedRevisionId(), now, command.subject());
        threads.get(command.organizationId()).put(command.threadId(), answered);
        List<Entry> trail = new ArrayList<>(trails.get(command.threadId()));
        trail.add(reply);
        trails.put(command.threadId(), List.copyOf(trail));
        answerIdempotency.put(key, answered);
        return view(answered);
    }

    /**
     * Read one thread when the caller is one of its two parties in the active tenant.
     *
     * @param organizationId active tenant
     * @param threadId       tenant-local thread identifier
     * @param subject        authenticated subject
     * @param roles          active roles in the tenant
     * @return the thread with its trail and overdue flag
     * @throws ApiException on invalid input or an invisible thread
     */
    public ThreadView view(String organizationId, String threadId, String subject, Set<String> roles) {
        ClarificationInputs.requireText(organizationId, "INVALID_ORGANIZATION",
                "active organization is required");
        ClarificationInputs.requireText(threadId, "INVALID_THREAD", "thread identifier is required");
        ClarificationInputs.requireText(subject, "INVALID_ACTOR", "actor is required");
        Thread current = thread(organizationId, threadId);
        if (current == null) {
            throw new ApiException(404, "NOT_FOUND", "clarification thread not found");
        }
        if (subject.equals(current.requesterSubject())) {
            if (!ClarificationRules.mayOpen(roles)) {
                throw new ApiException(404, "NOT_FOUND", "clarification thread not found");
            }
        } else if (subject.equals(current.prescriberSubject())) {
            if (!ClarificationRules.mayAnswer(roles)) {
                throw new ApiException(404, "NOT_FOUND", "clarification thread not found");
            }
        } else {
            throw new ApiException(404, "NOT_FOUND", "clarification thread not found");
        }
        return view(current);
    }

    /**
     * List overdue open requests visible to the caller in the active tenant.
     *
     * @param organizationId active tenant
     * @param subject        authenticated subject
     * @param roles          active roles in the tenant
     * @return overdue thread views, newest trail first
     * @throws ApiException on invalid input or a missing staff role
     */
    public List<ThreadView> overdue(String organizationId, String subject, Set<String> roles) {
        ClarificationInputs.requireText(organizationId, "INVALID_ORGANIZATION",
                "active organization is required");
        ClarificationInputs.requireText(subject, "INVALID_ACTOR", "actor is required");
        if (!ClarificationRules.mayView(roles)) {
            throw new ApiException(403, "FORBIDDEN", "missing clarification queue permission");
        }
        Instant now = clock.instant();
        Map<String, Thread> tenant = threads.get(organizationId);
        List<ThreadView> overdue = new ArrayList<>();
        if (tenant != null) {
            for (Thread candidate : tenant.values()) {
                if (ClarificationRules.isParty(subject, candidate)
                        && ClarificationRules.isOverdue(candidate, now, overdueAfter)) {
                    overdue.add(view(candidate));
                }
            }
        }
        return List.copyOf(overdue);
    }

    private ThreadView view(Thread thread) {
        return new ThreadView(thread, trails.getOrDefault(thread.id(), List.of()),
                ClarificationRules.isOverdue(thread, clock.instant(), overdueAfter));
    }

    private Thread thread(String organizationId, String threadId) {
        Map<String, Thread> tenant = threads.get(organizationId);
        if (tenant == null) {
            return null;
        }
        return tenant.get(threadId);
    }

    private static String key(String organizationId, String threadId, String subject, String command,
                              String idempotencyKey) {
        return organizationId + IDEM_SEPARATOR + threadId + IDEM_SEPARATOR + subject + IDEM_SEPARATOR
                + command + IDEM_SEPARATOR + idempotencyKey;
    }
}
