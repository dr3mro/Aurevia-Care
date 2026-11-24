package com.aurevia.staff;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.api.ApiException;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.TenantContextInterceptor;

/**
 * BUILD-01 / IAM-02 HTTP adapter acceptance: the four invitation commands are
 * reachable through the normal versioned API with synthetic identity headers.
 */
class StaffInvitationControllerTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String ID = "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb";
    private static final String TOKEN = "synthetic-token-abcdefghijklmnop";

    private StaffInvitationService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(StaffInvitationService.class);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(new StaffInvitationController(service))
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private static String admin() {
        return "/api/v1/staff-invitations";
    }

    @Test
    void withoutLoginReturns401() throws Exception {
        mvc.perform(post(admin()).header("X-Active-Organization", ORG)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void createReturns201WithALocationHeader() throws Exception {
        when(service.create("new-staff-a", "New Staff", ID, List.of("reception"), 600L))
                .thenReturn(new StaffInvitationIssue(ID, "new-staff-a", TOKEN,
                        "2026-11-21T13:00:00Z", List.of("reception")));
        mvc.perform(post(admin())
                        .header("X-Synthetic-Subject", "admin-a")
                        .header("X-Active-Organization", ORG)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"new-staff-a\",\"displayName\":\"New Staff\","
                                + "\"facilityId\":\"" + ID + "\",\"roles\":[\"reception\"],\"ttlSeconds\":600}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/staff-invitations/" + ID))
                .andExpect(jsonPath("$.token").value(TOKEN))
                .andExpect(jsonPath("$.roles[0]").value("reception"));
    }

    @Test
    void createForbiddenMapsTo403() throws Exception {
        when(service.create(any(), any(), any(), any(), anyLong()))
                .thenThrow(new ApiException(403, "FORBIDDEN", "missing action permission"));
        mvc.perform(post(admin())
                        .header("X-Synthetic-Subject", "reception-a")
                        .header("X-Active-Organization", ORG)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"subject\":\"new-staff-a\",\"displayName\":\"New Staff\","
                                + "\"facilityId\":\"" + ID + "\",\"roles\":[\"reception\"],\"ttlSeconds\":600}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void listPendingReturns200() throws Exception {
        when(service.listPending()).thenReturn(List.of(new StaffInvitation(ID, "new-staff-a", "New Staff",
                ID, List.of("reception"), "2026-11-21T13:00:00Z", "pending", "admin-a")));
        mvc.perform(get(admin() + "/pending")
                        .header("X-Synthetic-Subject", "admin-a")
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("pending"))
                .andExpect(jsonPath("$[0].subject").value("new-staff-a"));
    }

    @Test
    void revokeReturns200() throws Exception {
        when(service.revoke(ID)).thenReturn(new StaffInvitation(ID, "new-staff-a", "New Staff", ID,
                List.of("reception"), "2026-11-21T13:00:00Z", "revoked", "admin-a"));
        mvc.perform(post(admin() + "/" + ID + "/revoke")
                        .header("X-Synthetic-Subject", "admin-a")
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("revoked"));
        verify(service).revoke(ID);
    }

    @Test
    void revokeNotFoundMapsTo404() throws Exception {
        when(service.revoke(ID)).thenThrow(new ApiException(404, "NOT_FOUND", "invitation not found"));
        mvc.perform(post(admin() + "/" + ID + "/revoke")
                        .header("X-Synthetic-Subject", "admin-a")
                        .header("X-Active-Organization", ORG))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void acceptReturns200WithTheNewStatus() throws Exception {
        when(service.accept(TOKEN)).thenReturn(new StaffInvitationAcceptance("new-staff-a", "active"));
        mvc.perform(post(admin() + "/accept")
                        .header("X-Synthetic-Subject", "new-staff-a")
                        .header("X-Active-Organization", ORG)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + TOKEN + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subject").value("new-staff-a"))
                .andExpect(jsonPath("$.status").value("active"));
        verify(service).accept(TOKEN);
    }

    @Test
    void requestRecordsExposeTheirPartsAndValueSemantics() {
        StaffInvitationController.CreateInvitationRequest create =
                new StaffInvitationController.CreateInvitationRequest("new-staff-a", "New Staff", ID,
                        List.of("reception"), 600L);
        assertEquals("new-staff-a", create.subject());
        assertEquals("New Staff", create.displayName());
        assertEquals(ID, create.facilityId());
        assertEquals(List.of("reception"), create.roles());
        assertEquals(600L, create.ttlSeconds());
        StaffInvitationController.CreateInvitationRequest same =
                new StaffInvitationController.CreateInvitationRequest("new-staff-a", "New Staff", ID,
                        List.of("reception"), 600L);
        assertEquals(create, same);
        assertEquals(create.hashCode(), same.hashCode());
        assertEquals(create.toString(), same.toString());

        StaffInvitationController.AcceptInvitationRequest accept =
                new StaffInvitationController.AcceptInvitationRequest(TOKEN);
        assertEquals(TOKEN, accept.token());
        StaffInvitationController.AcceptInvitationRequest sameAccept =
                new StaffInvitationController.AcceptInvitationRequest(TOKEN);
        assertEquals(accept, sameAccept);
        assertEquals(accept.hashCode(), sameAccept.hashCode());
        assertEquals(accept.toString(), sameAccept.toString());

        StaffInvitationController.CreateInvitationRequest missingRoles =
                new StaffInvitationController.CreateInvitationRequest("new-staff-a", "New Staff", ID, null, 600L);
        assertTrue(missingRoles.roles().isEmpty());
    }

    @Test
    void acceptUnusableTokenMapsTo409() throws Exception {
        when(service.accept(TOKEN)).thenThrow(new ApiException(409, "INVITATION_UNAVAILABLE",
                "invitation expired or already used"));
        mvc.perform(post(admin() + "/accept")
                        .header("X-Synthetic-Subject", "new-staff-a")
                        .header("X-Active-Organization", ORG)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"token\":\"" + TOKEN + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVITATION_UNAVAILABLE"));
    }
}
