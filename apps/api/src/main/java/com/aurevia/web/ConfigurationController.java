package com.aurevia.web;

import java.net.URI;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.configuration.ConfigEntry;
import com.aurevia.configuration.ConfigHistory;
import com.aurevia.configuration.ConfigurationService;

/**
 * Thin HTTP adapter for tenant configuration administration.
 *
 * <p>Draft, approval and immutability invariants live in the configuration domain
 * service; this adapter only maps outcomes to status codes and headers.</p>
 */
@RestController
@RequestMapping("/api/v1/configuration")
public class ConfigurationController {

    private final ConfigurationService service;

    /**
     * Create the controller.
     *
     * @param service configuration domain service
     */
    public ConfigurationController(ConfigurationService service) {
        this.service = service;
    }

    /**
     * Append a draft configuration version.
     *
     * @param request validated draft request
     * @return HTTP 201 with a Location header and the pending entry
     */
    @PostMapping
    public ResponseEntity<ConfigEntry> create(@RequestBody CreateRequest request) {
        ConfigEntry created = service.create(request.key(), request.value(), request.effectiveFrom());
        return ResponseEntity.created(URI.create("/api/v1/configuration/" + created.key())).body(created);
    }

    /**
     * Append a named approval for one version.
     *
     * @param key     tenant-local configuration key
     * @param version version to approve
     * @param request optional approval note
     * @return the approved entry
     */
    @PostMapping("/{key}/versions/{version}/approve")
    public ConfigEntry approve(@PathVariable String key, @PathVariable int version,
                               @RequestBody ApproveRequest request) {
        return service.approve(key, version, request.note());
    }

    /**
     * Read the single effective approved version.
     *
     * @param key tenant-local configuration key
     * @return the effective entry
     */
    @GetMapping("/{key}")
    public ConfigEntry get(@PathVariable String key) {
        return service.get(key);
    }

    /**
     * Read the append-only version and approval history.
     *
     * @param key tenant-local configuration key
     * @return the visible history
     */
    @GetMapping("/{key}/history")
    public ConfigHistory history(@PathVariable String key) {
        return service.history(key);
    }

    /**
     * Draft request body; organization, version and author are never accepted from the client.
     *
     * @param key           tenant-local configuration key
     * @param value         opaque configuration value
     * @param effectiveFrom UTC instant, or {@code null} for immediate effect on approval
     * @return the immutable request
     */
    public record CreateRequest(String key, String value, String effectiveFrom) {
    }

    /**
     * Approval request body; the approver identity is always derived from the actor.
     *
     * @param note optional approval note
     * @return the immutable request
     */
    public record ApproveRequest(String note) {
    }
}
