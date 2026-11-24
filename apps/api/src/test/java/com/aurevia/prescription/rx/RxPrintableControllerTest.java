package com.aurevia.prescription.rx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;

import org.junit.jupiter.api.Test;

/** BUILD-10 (RX-02): the printable prescription is reachable through the prescription controller. */
class RxPrintableControllerTest {

    private static final String ID = "aaaaaaaa-0000-0000-0000-000000000001";

    @Test
    void delegatesThePrintableRequestToTheDomainService() {
        RxService service = mock(RxService.class);
        RxController controller = new RxController(service);
        PrintablePrescription expected = new PrintablePrescription(ID,
                "bbbbbbbb-0000-0000-0000-000000000001", "amoxicillin", "500 mg", "1 capsule",
                "oral", "twice daily", 30, "clinician-1", "pharmacy-x", "synthetic attestation",
                Instant.parse("2026-11-20T10:15:30Z"), RxChannel.PRINTABLE_LOCAL.wire());
        when(service.printable(ID)).thenReturn(expected);

        PrintablePrescription actual = controller.printable(ID);

        assertEquals(expected, actual);
        verify(service).printable(ID);
    }
}
