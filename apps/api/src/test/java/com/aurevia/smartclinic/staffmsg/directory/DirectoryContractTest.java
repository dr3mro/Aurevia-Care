package com.aurevia.smartclinic.staffmsg.directory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.aurevia.smartclinic.messaging.units.MessagingContract.CareUnit;
import com.aurevia.smartclinic.staff.StaffMember;
import com.aurevia.smartclinic.staffmsg.directory.DirectoryContract.FacilityDirectory;
import com.aurevia.smartclinic.staffmsg.directory.DirectoryContract.FacilityEntry;
import com.aurevia.smartclinic.staffmsg.directory.DirectoryContract.StaffDirectory;
import com.aurevia.smartclinic.staffmsg.directory.DirectoryContract.StaffEntry;
import com.aurevia.smartclinic.staffmsg.directory.DirectoryContract.UnitEntry;
import com.aurevia.smartclinic.tenancy.Facility;

/** Directory views map only the fields a message author needs to choose a recipient. */
class DirectoryContractTest {

    private static final String ORG = "11111111-1111-1111-1111-111111111111";
    private static final String FACILITY = "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa";

    @Test
    void facilityEntryMapsTenantFacility() {
        FacilityEntry entry = FacilityEntry.from(new Facility(ORG, "FAC-A", "North Clinic", "Europe/London"));
        assertEquals("FAC-A", entry.code());
        assertEquals("North Clinic", entry.name());
        assertEquals("Europe/London", entry.timeZone());
    }

    @Test
    void unitEntryMapsTenantCareUnit() {
        CareUnit unit = new CareUnit("unit-1", ORG, FACILITY, "Triage", List.of("advice"), true, null);
        UnitEntry entry = UnitEntry.from(unit);
        assertEquals("unit-1", entry.id());
        assertEquals("Triage", entry.name());
        assertEquals(FACILITY, entry.facilityId());
        assertTrue(entry.active());
        assertEquals(List.of("advice"), entry.topics());
    }

    @Test
    void staffEntryCarriesTheBrowsedFacilityWithActiveRoles() {
        StaffMember member = new StaffMember("nurse-a", "Nurse A", "active", List.of("nurse", "staff"));
        StaffEntry entry = StaffEntry.from(member, "FAC-A");
        assertEquals("nurse-a", entry.subject());
        assertEquals("Nurse A", entry.displayName());
        assertEquals("FAC-A", entry.facilityCode());
        assertEquals(List.of("nurse", "staff"), entry.roles());
    }

    @Test
    void directoryWrappersFreezeTheirLists() {
        FacilityEntry facility = FacilityEntry.from(new Facility(ORG, "FAC-A", "North", "UTC"));
        StaffEntry staff = StaffEntry.from(new StaffMember("nurse-a", "Nurse A", "active", List.of("nurse")),
                "FAC-A");
        FacilityDirectory facilities = new FacilityDirectory(List.of(facility));
        StaffDirectory people = new StaffDirectory(List.of(staff));
        assertEquals(List.of(facility), facilities.facilities());
        assertEquals(List.of(staff), people.staff());
        assertFalse(facilities.facilities().isEmpty());
        assertNotNull(new DirectoryContract());
    }
}
