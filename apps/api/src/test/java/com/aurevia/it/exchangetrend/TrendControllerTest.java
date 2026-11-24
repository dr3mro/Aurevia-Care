package com.aurevia.it.exchangetrend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

import com.aurevia.exchange.trend.ComparabilityRequest;
import com.aurevia.exchange.trend.ComparabilityRule;
import com.aurevia.exchange.trend.TrendObservation;
import com.aurevia.exchange.trend.ObservationRequest;
import com.aurevia.exchange.trend.Qualifier;
import com.aurevia.exchange.trend.TrendController;
import com.aurevia.exchange.trend.TrendService;
import com.aurevia.exchange.trend.TrendView;

/** S-G2-07 fast web-adapter tests: the controller stays thin and delegates every command. */
class TrendControllerTest {

    private static final String PATIENT = "11111111-1111-1111-1111-111111111111";

    private TrendService service;
    private TrendController controller;

    @BeforeEach
    void setUp() {
        service = mock(TrendService.class);
        controller = new TrendController(service);
    }

    @Test
    void recordsAnObservationWithCreatedStatus() {
        ObservationRequest request = new ObservationRequest(PATIENT, "HBA1C", "Synthetic HBA1C",
                "LAB-HPLC", TrendObservation.FINAL, new BigDecimal("7.1"), "mmol/mol", "none",
                null, null, null, "2026-09-10T00:00:00Z", "high", null, "SYNTHETIC_LAB", "v1");
        TrendObservation observation = new TrendObservation("o1", PATIENT, "HBA1C", "Synthetic HBA1C",
                "LAB-HPLC", TrendObservation.FINAL, new BigDecimal("7.1"), "mmol/mol",
                Qualifier.NONE, null, null, null, Instant.parse("2026-09-10T00:00:00Z"),
                "high", null, "SYNTHETIC_LAB", "v1");
        when(service.recordObservation(request)).thenReturn(observation);

        var response = controller.record(request);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals(observation, response.getBody());
        verify(service).recordObservation(request);
    }

    @Test
    void definesComparabilityWithCreatedStatus() {
        ComparabilityRequest request = new ComparabilityRequest("HBA1C", "mmol/mol", "LAB-HPLC",
                true, "2026-09-01T00:00:00Z");
        ComparabilityRule rule = new ComparabilityRule("HBA1C", "mmol/mol", "LAB-HPLC", true, 1);
        when(service.defineComparability(request)).thenReturn(rule);

        var response = controller.define(request);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals(rule, response.getBody());
        verify(service).defineComparability(request);
    }

    @Test
    void readsATrendThroughTheService() {
        TrendView view = new TrendView("HBA1C", "mmol/mol", 1, List.of(), List.of());
        when(service.readTrend(PATIENT, "HBA1C")).thenReturn(view);

        assertEquals(view, controller.trend(PATIENT, "HBA1C"));
        verify(service).readTrend(PATIENT, "HBA1C");
    }
}
