package com.aurevia.messaging.appointments;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Stateless deny-all ports for the legacy single-argument controller constructor.
 *
 * <p>This class exists only so a synthetic standalone HTTP guard test can
 * construct a controller without Spring. It is never a Spring bean, holds no
 * data and cannot persist or disclose any appointment fact: links are refused,
 * queries are empty, writes are discarded and the audit sink is inert. The
 * production route uses the injected service composed by
 * {@link com.aurevia.messaging.appointments.PatientAppointmentConfiguration} and
 * the tenant-owned JDBC stores, so this seam cannot become an in-memory
 * fallback for the durable implementation.</p>
 */
public final class FailClosedPatientAppointmentPorts {

    private FailClosedPatientAppointmentPorts() {
    }

    /** Refuse every patient link; a denial always precedes any store access. */
    static final class DenyAllLinks implements VerifiedPatientLink {

        /** The single stateless instance. */
        static final DenyAllLinks INSTANCE = new DenyAllLinks();

        private DenyAllLinks() {
        }

        /** {@inheritDoc} */
        @Override
        public boolean allows(String subject, String patientId) {
            return false;
        }
    }

    /** Return no appointment facts and acknowledge no confirmation. */
    static final class EmptyAppointments implements PatientAppointmentStore {

        /** The single stateless instance. */
        static final EmptyAppointments INSTANCE = new EmptyAppointments();

        private EmptyAppointments() {
        }

        /** {@inheritDoc} */
        @Override
        public List<PatientAppointment> forPatient(String patientId) {
            return List.of();
        }

        /** {@inheritDoc} */
        @Override
        public Optional<PatientAppointment> find(String appointmentId, String patientId) {
            return Optional.empty();
        }

        /** {@inheritDoc} */
        @Override
        public int confirm(String appointmentId, String patientId, int expectedVersion, Instant at) {
            return 0;
        }
    }

    /** Return no device facts and retain nothing. */
    static final class EmptyDevices implements DeviceTokenStore {

        /** The single stateless instance. */
        static final EmptyDevices INSTANCE = new EmptyDevices();

        private EmptyDevices() {
        }

        /** {@inheritDoc} */
        @Override
        public void save(DeviceToken token) {
            // Intentionally discarded: this seam is never selected in production.
        }

        /** {@inheritDoc} */
        @Override
        public Optional<DeviceToken> find(String tokenId, String patientId) {
            return Optional.empty();
        }

        /** {@inheritDoc} */
        @Override
        public Optional<DeviceToken> findByTokenId(String tokenId) {
            return Optional.empty();
        }

        /** {@inheritDoc} */
        @Override
        public List<DeviceToken> active(String patientId) {
            return List.of();
        }

        /** {@inheritDoc} */
        @Override
        public void appendInvalidTokenLog(String tokenId, String patientId, String reason, Instant at) {
            // Intentionally discarded: no disposition is durable on this seam.
        }
    }

    /** Accept the generic notice without contacting a provider. */
    static final class InertGateway implements PushGateway {

        /** The single stateless instance. */
        static final InertGateway INSTANCE = new InertGateway();

        private InertGateway() {
        }

        /** {@inheritDoc} */
        @Override
        public Outcome send(String token, GenericPushNotice notice) {
            return Outcome.DELIVERED;
        }
    }

    /** Discard audit facts; only the production sink writes durable events. */
    static final class InertAudit implements AuditSink {

        /** The single stateless instance. */
        static final InertAudit INSTANCE = new InertAudit();

        private InertAudit() {
        }

        /** {@inheritDoc} */
        @Override
        public void append(String subject, String patientId, String action, String outcome, Instant at) {
            // Intentionally discarded: this seam is never selected in production.
        }
    }

    /**
     * Build the fail-closed service used only by the single-argument constructor.
     *
     * @return a service that denies every command before any port is read
     */
    static PatientAppointmentService deniedService() {
        return new PatientAppointmentService(DenyAllLinks.INSTANCE, EmptyAppointments.INSTANCE,
                EmptyDevices.INSTANCE, InertGateway.INSTANCE, InertAudit.INSTANCE);
    }
}
