package com.aurevia.authz.specialty;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.api.ApiException;
import com.aurevia.api.IfMatch;
import com.aurevia.authz.specialty.SpecialtyContract.Attestation;
import com.aurevia.authz.specialty.SpecialtyContract.AttestationRequest;
import com.aurevia.authz.specialty.SpecialtyContract.PermissionView;

/**
 * Thin HTTP adapter for the S-G4-06 specialty slice (ARC-03, ARC-04, PRD-06).
 *
 * <p>The controller parses the {@code If-Match} precondition and the bounded
 * list limit. All authorization, clinical decision derivation and durable
 * evidence live in the domain service.</p>
 */
@RestController
@RequestMapping("/api/v1/specialty")
public class SpecialtyController {

    private final SpecialtyAttestationService service;

    /**
     * Create the controller.
     *
     * @param service specialty attestation domain service
     */
    public SpecialtyController(SpecialtyAttestationService service) {
        this.service = service;
    }

    /**
     * Read the caller's effective permissions in the active organization.
     *
     * @return active roles and permitted actions
     */
    @GetMapping("/permissions")
    public PermissionView permissions() {
        return service.permissions();
    }

    /**
     * Record a human clinical attestation.
     *
     * @param request command fields
     * @param ifMatch revision of the artifact the human read first
     * @return HTTP 201 with the immutable attestation
     */
    @PostMapping("/attestations")
    public ResponseEntity<Attestation> attest(@RequestBody AttestationRequest request,
                                              @RequestHeader(value = "If-Match", required = false) String ifMatch) {
        Attestation created = service.attest(request, IfMatch.parse(ifMatch));
        return ResponseEntity.created(URI.create("/api/v1/specialty/attestations/" + created.id())).body(created);
    }

    /**
     * Read one attestation with its human provenance.
     *
     * @param id attestation identifier
     * @return the visible attestation
     */
    @GetMapping("/attestations/{id}")
    public Attestation get(@PathVariable String id) {
        return service.get(id);
    }

    /**
     * List visible attestations with stable ordering.
     *
     * @param limit raw bounded limit
     * @return newest attestations first
     */
    @GetMapping("/attestations")
    public List<Attestation> list(@RequestParam(defaultValue = "50") String limit) {
        int parsed;
        try {
            parsed = Integer.parseInt(limit);
        } catch (NumberFormatException exception) {
            throw new ApiException(422, "INVALID_LIMIT", "limit must be an integer");
        }
        return service.list(parsed);
    }
}
