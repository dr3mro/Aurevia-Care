package com.aurevia.scheduling;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

/** Verifies that the immutable appointment view cannot expose caller-owned state. */
class AppointmentViewTest {

    @Test
    void historyIsDefensivelyCopied() {
        List<AppointmentEvent> history = new ArrayList<>();
        AppointmentView view = new AppointmentView(null, history);

        history.add(null);

        assertEquals(0, view.history().size());
        assertThrows(UnsupportedOperationException.class, () -> view.history().add(null));
    }
}
