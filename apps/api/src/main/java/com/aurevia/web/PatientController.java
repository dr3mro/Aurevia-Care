package com.aurevia.web;

import java.net.URI;
import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.patient.Patient;
import com.aurevia.patient.PatientService;
import com.aurevia.patient.PatientView;

/**
 * Thin HTTP adapter for patient registration and search.
 *
 * <p>Identity invariants live in {@code PatientService}; this adapter only binds
 * inputs and maps outcomes to status codes.</p>
 */
@RestController
@RequestMapping("/api/v1/patients")
public class PatientController {

    private final PatientService service;

    /**
     * Create the controller.
     *
     * @param service patient domain service
     */
    public PatientController(PatientService service) {
        this.service = service;
    }

    /**
     * Register a new patient.
     *
     * @param request registration fields
     * @return HTTP 201 with the created patient
     */
    @PostMapping
    public ResponseEntity<Patient> register(@RequestBody RegisterRequest request) {
        Patient created = service.register(request.displayName(), request.mrn(), request.issuer(),
                request.dob(), request.dobUnknownReason(), request.language(), request.contacts(),
                request.identifiers(), request.consents());
        return ResponseEntity.created(URI.create("/api/v1/patients/" + created.id())).body(created);
    }

    /**
     * Read an effective patient with likely-duplicate warnings.
     *
     * @param id patient identifier
     * @return the patient view
     */
    @GetMapping("/{id}")
    public PatientView get(@PathVariable String id) {
        return service.get(id);
    }

    /**
     * Search patients by name.
     *
     * @param name display name or normalized prefix
     * @return matching patients
     */
    @GetMapping
    public List<Patient> search(@RequestParam String name) {
        return service.search(name);
    }

    /**
     * Registration request; organization and author are never accepted from the client.
     *
     * @param displayName      original name spelling
     * @param mrn              issuer-scoped MRN
     * @param issuer           MRN issuer
     * @param dob              civil date of birth
     * @param dobUnknownReason coded reason when DOB is unknown
     * @param language         patient language
     * @param contacts         contact records as JSON text
     * @param identifiers      identity records as JSON text
     * @param consents         consent records as JSON text
     * @return the immutable request
     */
    public record RegisterRequest(String displayName, String mrn, String issuer, String dob,
                                  String dobUnknownReason, String language, String contacts,
                                  String identifiers, String consents) {
    }
}
