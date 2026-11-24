package com.aurevia.web;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * WEB-05 HTTP adapter tests for the two unauthenticated health routes.
 *
 * <p>Standalone MockMvc keeps the routes free of tenant interception and
 * security, proving no login or tenant header is required. The database is a
 * synthetic mock so failure text can be asserted to never leak.</p>
 */
class HealthControllerTest {

    private JdbcTemplate jdbc;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        mvc = MockMvcBuilders.standaloneSetup(new HealthController(jdbc)).build();
    }

    @Test
    void liveReturnsUpWithoutTouchingTheDatabase() throws Exception {
        mvc.perform(get("/health/live"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        verifyNoInteractions(jdbc);
    }

    @Test
    void readyReturnsUpWhenTheProbeSucceeds() throws Exception {
        when(jdbc.queryForObject("SELECT 1", Integer.class)).thenReturn(1);
        mvc.perform(get("/health/ready"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        verify(jdbc).queryForObject("SELECT 1", Integer.class);
    }

    @Test
    void readyReturnsDownWithoutLeakingExceptionDetailWhenTheProbeFails() throws Exception {
        String probeMessage = "jdbc:synthetic-host:diagnostic-marker-42";
        when(jdbc.queryForObject("SELECT 1", Integer.class))
                .thenThrow(new DataAccessResourceFailureException(probeMessage));

        String body = mvc.perform(get("/health/ready"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.status").value("DOWN"))
                .andReturn().getResponse().getContentAsString();

        assertFalse(body.contains(probeMessage), "readiness body must not contain exception text");
        verify(jdbc).queryForObject("SELECT 1", Integer.class);
    }
}
