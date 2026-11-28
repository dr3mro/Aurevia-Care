package com.aurevia.messaging.appointments;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Synthetic in-memory adapters for the patient self-service ports.
 *
 * <p>These adapters exist only so the workflow has a locally runnable HTTP entry
 * point with synthetic data. A verified link is seeded explicitly by an
 * integration fixture; demographics are never used to create one. Before real
 * patient enablement the verified-link, appointment-projection, device and push
 * ports must be replaced by tenant-scoped adapters over durable tables and a
 * real provider. No push token or patient payload is logged.</p>
 */
final class SyntheticPatientAppointmentPorts {

    final Links links = new Links();
    final Appointments appointments = new Appointments();
    final Devices devices = new Devices();
    final Gateway gateway = new Gateway();
    final Audit audit = new Audit();

    /** Create empty synthetic ports. */
    SyntheticPatientAppointmentPorts() {
    }

    /** Verified-link adapter seeded only by synthetic fixtures. */
    static final class Links implements VerifiedPatientLink {

        private final Map<String, Set<String>> values = new ConcurrentHashMap<>();

        /**
         * Seed a synthetic verified link.
         *
         * @param subject   authenticated patient subject
         * @param patientId linked patient identifier
         */
        void add(String subject, String patientId) {
            values.computeIfAbsent(subject, key -> new LinkedHashSet<>()).add(patientId);
        }

        /** {@inheritDoc} */
        @Override
        public boolean allows(String subject, String patientId) {
            return values.getOrDefault(subject, Set.of()).contains(patientId);
        }
    }

    /** Synthetic appointment projection adapter. */
    static final class Appointments implements PatientAppointmentStore {

        private final Map<String, PatientAppointment> values = new ConcurrentHashMap<>();

        /**
         * Seed a synthetic appointment.
         *
         * @param appointment appointment projection
         */
        void put(PatientAppointment appointment) {
            values.put(appointment.id(), appointment);
        }

        /** {@inheritDoc} */
        @Override
        public List<PatientAppointment> forPatient(String patientId) {
            return values.values().stream()
                    .filter(appointment -> appointment.patientId().equals(patientId))
                    .toList();
        }

        /** {@inheritDoc} */
        @Override
        public Optional<PatientAppointment> find(String appointmentId, String patientId) {
            return Optional.ofNullable(values.get(appointmentId))
                    .filter(appointment -> appointment.patientId().equals(patientId));
        }

        /** {@inheritDoc} */
        @Override
        public int confirm(String appointmentId, String patientId, int expectedVersion, Instant at) {
            PatientAppointment current = values.get(appointmentId);
            if (current == null || !current.patientId().equals(patientId)
                    || current.version() != expectedVersion) {
                return 0;
            }
            values.put(appointmentId, current.confirmed(at, current.version() + 1));
            return 1;
        }
    }

    /** Synthetic device registry adapter. */
    static final class Devices implements DeviceTokenStore {

        private final Map<String, DeviceToken> values = new ConcurrentHashMap<>();
        private final List<String> invalidLog = new CopyOnWriteArrayList<>();

        /** Return the recorded invalid-token dispositions. */
        List<String> invalidLog() {
            return List.copyOf(invalidLog);
        }

        /** {@inheritDoc} */
        @Override
        public void save(DeviceToken token) {
            values.put(token.tokenId(), token);
        }

        /** {@inheritDoc} */
        @Override
        public Optional<DeviceToken> find(String tokenId, String patientId) {
            return Optional.ofNullable(values.get(tokenId))
                    .filter(token -> token.patientId().equals(patientId));
        }

        /** {@inheritDoc} */
        @Override
        public Optional<DeviceToken> findByTokenId(String tokenId) {
            return Optional.ofNullable(values.get(tokenId));
        }

        /** {@inheritDoc} */
        @Override
        public List<DeviceToken> active(String patientId) {
            return values.values().stream()
                    .filter(token -> token.patientId().equals(patientId) && token.state().deliverable())
                    .toList();
        }

        /** {@inheritDoc} */
        @Override
        public void appendInvalidTokenLog(String tokenId, String patientId, String reason, Instant at) {
            invalidLog.add(tokenId + "|" + patientId + "|" + reason);
        }
    }

    /** Synthetic always-delivered push gateway: notices contain no clinical detail. */
    static final class Gateway implements PushGateway {

        /** {@inheritDoc} */
        @Override
        public Outcome send(String token, GenericPushNotice notice) {
            return Outcome.DELIVERED;
        }
    }

    /** Synthetic append-only audit sink. */
    static final class Audit implements AuditSink {

        private final List<Entry> entries = new CopyOnWriteArrayList<>();

        /** Immutable audit fact retained by the synthetic sink. */
        record Entry(String subject, String patientId, String action, String outcome, Instant at) {
        }

        /** Return the recorded synthetic audit facts. */
        List<Entry> entries() {
            return List.copyOf(entries);
        }

        /** {@inheritDoc} */
        @Override
        public void append(String subject, String patientId, String action, String outcome, Instant at) {
            entries.add(new Entry(subject, patientId, action, outcome, at));
        }
    }
}
