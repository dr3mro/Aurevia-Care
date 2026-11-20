package com.aurevia.smartclinic.therapy;

import java.net.URI;
import java.time.Clock;
import java.util.Set;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.smartclinic.api.ApiException;
import com.aurevia.smartclinic.staff.StaffRepository;
import com.aurevia.smartclinic.tenant.TenantContext;
import com.aurevia.smartclinic.therapy.TherapyContract.Actor;
import com.aurevia.smartclinic.therapy.TherapyContract.Episode;
import com.aurevia.smartclinic.therapy.TherapyContract.ReferralRequest;
import com.aurevia.smartclinic.therapy.TherapyContract.TherapyAggregate;

/**
 * Thin HTTP adapter for the PT-01 therapy episode workflow.
 *
 * <p>The organization, actor and active roles come from the request context and
 * freshly resolved membership; episode state, clinical authorization and the
 * append-only audit stay in {@link com.aurevia.smartclinic.therapy.TherapyService}.</p>
 */
@RestController
@RequestMapping("/api/v1/therapy/episodes")
public final class TherapyController {

    private final TherapyService service;
    private final StaffRepository staff;
    private final TransactionTemplate transactions;

    /**
     * Create the route with the synthetic in-memory therapy store.
     *
     * @param clock        shared application clock
     * @param staff        fresh membership and role source
     * @param transactions tenant-guarded transaction template
     */
    @Autowired
    public TherapyController(Clock clock, StaffRepository staff, TransactionTemplate transactions) {
        this(new TherapyService(new InMemoryTherapyStore(), clock), staff, transactions);
    }

    /**
     * Create the route over a supplied service, used by HTTP tests with fixtures.
     *
     * @param service      therapy domain service
     * @param staff        fresh membership and role source
     * @param transactions tenant-guarded transaction template
     */
    TherapyController(TherapyService service, StaffRepository staff, TransactionTemplate transactions) {
        this.service = service;
        this.staff = staff;
        this.transactions = transactions;
    }

    /**
     * Accept a referral into a new therapy episode.
     *
     * @param request referral fields; identity is supplied by the server
     * @return HTTP 201 with the created episode
     */
    @PostMapping
    public ResponseEntity<Episode> accept(@RequestBody ReferralRequest request) {
        Episode created = service.acceptReferral(actor(context()), request);
        return ResponseEntity.created(URI.create("/api/v1/therapy/episodes/" + created.id()))
                .body(created);
    }

    /**
     * Read one therapy episode in the active tenant.
     *
     * @param episodeId episode identifier
     * @return the complete clinical aggregate
     */
    @GetMapping("/{episodeId}")
    public TherapyAggregate read(@PathVariable String episodeId) {
        return service.get(actor(context()), episodeId);
    }

    /**
     * Read the active tenant context, failing closed when none is bound.
     *
     * @return the bound tenant context
     */
    private static TenantContext context() {
        TenantContext context = TenantContext.current();
        if (context == null) {
            throw new ApiException(401, "UNAUTHENTICATED", "authentication required");
        }
        return context;
    }

    /**
     * Build a fresh actor from the bound context and current membership roles.
     *
     * @param context bound tenant context
     * @return the actor for this request
     */
    private Actor actor(TenantContext context) {
        Set<String> roles = Set.copyOf(
                transactions.execute(status -> staff.activeRoles(context.subject())));
        return new Actor(context.organizationId(), context.subject(), roles);
    }
}
