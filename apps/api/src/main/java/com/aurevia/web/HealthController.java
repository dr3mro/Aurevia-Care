package com.aurevia.web;

import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Unauthenticated liveness and readiness probes (WEB-05).
 *
 * <p>These are the only routes outside the versioned {@code /api/v1} prefix.
 * They require no login or tenant selection and never return patient or tenant
 * data. Liveness stays process-local; readiness performs a minimal database
 * round trip and reduces any failure to a status-only body so connection
 * strings, host names, versions and exception text cannot leak.</p>
 */
@RestController
public class HealthController {

    private final JdbcTemplate jdbc;

    /**
     * Create the controller with the auto-configured probe client.
     *
     * @param jdbc template used only by the readiness probe
     */
    public HealthController(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Report that the process is running; deliberately avoids the database.
     *
     * @return HTTP 200 with an {@code UP} status body
     */
    @GetMapping("/health/live")
    public HealthStatus live() {
        return new HealthStatus("UP");
    }

    /**
     * Report whether the database answers a minimal probe.
     *
     * <p>A successful probe yields HTTP 200; any data-access failure yields
     * HTTP 503. Neither body carries diagnostic detail.</p>
     *
     * @return HTTP 200 when the database answers, otherwise HTTP 503
     */
    @GetMapping("/health/ready")
    public ResponseEntity<HealthStatus> ready() {
        try {
            jdbc.queryForObject("SELECT 1", Integer.class);
            return ResponseEntity.ok(new HealthStatus("UP"));
        } catch (DataAccessException exception) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(new HealthStatus("DOWN"));
        }
    }

    /**
     * Status-only health payload; never carries diagnostics or identifiers.
     *
     * @param status {@code UP} or {@code DOWN}
     */
    public record HealthStatus(String status) {
    }
}
