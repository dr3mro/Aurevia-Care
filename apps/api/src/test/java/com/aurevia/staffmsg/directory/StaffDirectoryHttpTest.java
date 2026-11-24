package com.aurevia.staffmsg.directory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.TransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import com.aurevia.api.ApiErrorHandler;
import com.aurevia.staff.StaffMember;
import com.aurevia.staff.StaffRepository;
import com.aurevia.staff.StaffRepository.StaffRow;
import com.aurevia.staffmsg.directory.DirectoryContract.FacilityDirectory;
import com.aurevia.staffmsg.directory.DirectoryContract.StaffDirectory;
import com.aurevia.tenancy.Facility;
import com.aurevia.tenancy.FacilityRepository;
import com.aurevia.tenancy.FacilityRepository.FacilityRow;
import com.aurevia.tenant.TenantContext;
import com.aurevia.web.TenantContextInterceptor;

/**
 * HTTP-level entry-point proof for the directory. The adapter is a test-local thin controller:
 * a production mapping would need a contract change, which is outside this story's scope.
 */
class StaffDirectoryHttpTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String CODE = "FAC-A";
    private static final String BASE = "/api/v1/staff/directory";

    private MockMvc mvc;
    private FacilityRepository facilities;
    private StaffRepository staff;

    @BeforeEach
    void setUp() {
        facilities = mock(FacilityRepository.class);
        staff = mock(StaffRepository.class);
        TransactionTemplate transactions = mock(TransactionTemplate.class);
        when(transactions.execute(any())).thenAnswer(invocation -> {
            TransactionCallback<?> callback = invocation.getArgument(0);
            return callback.doInTransaction(mock(TransactionStatus.class));
        });
        StaffDirectoryService service = new StaffDirectoryService(staff, facilities, transactions,
                mock(ActiveStaffGuard.class));
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles("synthetic-auth");
        mvc = MockMvcBuilders.standaloneSetup(new DirectoryAdapter(service))
                .setControllerAdvice(new ApiErrorHandler())
                .addInterceptors(new TenantContextInterceptor(environment))
                .build();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    void directoryIsReachableOverHttpWithFacilitiesAndMessagableStaff() throws Exception {
        when(facilities.query(isNull(), isNull(), eq(Integer.MAX_VALUE)))
                .thenReturn(List.of(new FacilityRow(facility(), "id-a")));
        when(facilities.findByCode(CODE)).thenReturn(Optional.of(facility()));
        when(staff.query(isNull(), isNull(), eq("active"), eq(Integer.MAX_VALUE)))
                .thenReturn(List.of(
                        new StaffRow(new StaffMember("nurse-a", "Nurse A", "active", List.of("nurse")), "id-1"),
                        new StaffRow(new StaffMember("ghost", "No Role", "active", List.of()), "id-2")));

        MvcResult facilitiesResult = mvc.perform(withHeaders(get(BASE + "/facilities")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.facilities[0].code").value(CODE))
                .andReturn();
        assertEquals(200, facilitiesResult.getResponse().getStatus());

        mvc.perform(withHeaders(get(BASE + "/facilities/{facilityCode}/staff", CODE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.staff.length()").value(1))
                .andExpect(jsonPath("$.staff[0].subject").value("nurse-a"))
                .andExpect(jsonPath("$.staff[0].facilityCode").value(CODE));
    }

    @Test
    void unknownFacilityReturnsNotFoundThroughHttp() throws Exception {
        when(facilities.findByCode(CODE)).thenReturn(Optional.empty());

        mvc.perform(withHeaders(get(BASE + "/facilities/{facilityCode}/staff", CODE)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("FACILITY_NOT_FOUND"));
    }

    private static MockHttpServletRequestBuilder withHeaders(MockHttpServletRequestBuilder request) {
        return request.header("X-Synthetic-Subject", "nurse-a").header("X-Active-Organization", ORG);
    }

    private static Facility facility() {
        return new Facility(ORG, CODE, "North Clinic", "Europe/London");
    }

    /** Thin HTTP adapter; the service is injected and all decisions stay in the domain. */
    @RestController
    static class DirectoryAdapter {
        private final StaffDirectoryService service;

        DirectoryAdapter(StaffDirectoryService service) {
            this.service = service;
        }

        @GetMapping("/api/v1/staff/directory/facilities")
        FacilityDirectory facilities() {
            return service.facilities();
        }

        @GetMapping("/api/v1/staff/directory/facilities/{facilityCode}/staff")
        StaffDirectory staff(@PathVariable String facilityCode) {
            return service.staff(facilityCode);
        }
    }
}
