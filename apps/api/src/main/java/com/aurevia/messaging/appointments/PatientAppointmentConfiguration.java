package com.aurevia.messaging.appointments;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring composition for the patient appointment self-service slice.
 *
 * <p>The domain service stays free of framework stereotypes; this configuration
 * is the only production place that composes its five ports. Stores, the
 * verified-link adapter and the inert boundaries are component-scanned
 * separately, so the controller receives a finished service by constructor and
 * never builds a collaborator itself.</p>
 */
@Configuration(proxyBeanMethods = false)
public class PatientAppointmentConfiguration {

    /**
     * Compose the tenant-scoped appointment service from its injected ports.
     *
     * @param links   verified patient-link port, re-read on every command
     * @param store   durable appointment projection store
     * @param devices durable device registry and invalid-token ledger
     * @param gateway local-only generic push boundary
     * @param audit   append-only audit sink
     * @return the composed service bean
     */
    @Bean
    PatientAppointmentService patientAppointmentService(VerifiedPatientLink links,
                                                        PatientAppointmentStore store,
                                                        DeviceTokenStore devices,
                                                        PushGateway gateway,
                                                        AuditSink audit) {
        return new PatientAppointmentService(links, store, devices, gateway, audit);
    }
}
