package com.aurevia.staffmsg.directory;

import java.util.List;

import com.aurevia.messaging.units.MessagingContract.CareUnit;
import com.aurevia.staff.StaffMember;
import com.aurevia.tenancy.Facility;

/**
 * Read-only views returned by the staff messaging directory.
 *
 * <p>Each view carries only fields an author needs to choose a recipient. The
 * mapping drops another tenant's identifiers: every source record is resolved by
 * the tenant-guarded repositories before it reaches these factories. A staff view
 * carries the facility being browsed because the authoritative staff projection has
 * no facility column; facility scope is folded by the database from membership.</p>
 */
public final class DirectoryContract {

    /**
     * A facility the actor may browse in the active organization.
     *
     * @param code     tenant-local facility code used by the scope fold
     * @param name     display name
     * @param timeZone IANA time zone that owns display and availability
     * @return this view
     */
    public record FacilityEntry(String code, String name, String timeZone) {

        /**
         * Map an authoritative facility projection.
         *
         * @param facility facility resolved by the tenant-guarded repository
         * @return the directory view
         */
        public static FacilityEntry from(Facility facility) {
            return new FacilityEntry(facility.code(), facility.name(), facility.timeZone());
        }
    }

    /**
     * A care unit a message author may see, with its owning facility.
     *
     * @param id         tenant-local unit identifier
     * @param name       display name
     * @param facilityId identifier of the owning facility
     * @param active     whether the unit is staffed and accepting messages
     * @param topics     bounded topics supported by the unit
     * @return this view
     */
    public record UnitEntry(String id, String name, String facilityId, boolean active, List<String> topics) {

        /**
         * Create an immutable unit view; the topic list is defensively copied.
         */
        public UnitEntry {
            topics = List.copyOf(topics);
        }

        /**
         * Map an authoritative care unit projection.
         *
         * @param unit care unit resolved in the active organization
         * @return the directory view
         */
        public static UnitEntry from(CareUnit unit) {
            return new UnitEntry(unit.id(), unit.name(), unit.facilityId(), unit.active(), unit.topics());
        }
    }

    /**
     * A staff member who may be messaged, shown with the facility being browsed.
     *
     * @param subject      authenticated subject of the recipient
     * @param displayName  display name
     * @param facilityCode facility whose directory listed this person
     * @param roles        active role keys that make the person addressable
     * @return this view
     */
    public record StaffEntry(String subject, String displayName, String facilityCode, List<String> roles) {

        /**
         * Create an immutable staff view; the role list is defensively copied.
         */
        public StaffEntry {
            roles = List.copyOf(roles);
        }

        /**
         * Map an authoritative staff projection onto the browsed facility.
         *
         * @param member       active staff member with at least one active role
         * @param facilityCode facility whose directory listed this person
         * @return the directory view
         */
        public static StaffEntry from(StaffMember member, String facilityCode) {
            return new StaffEntry(member.subject(), member.displayName(), facilityCode, member.roles());
        }
    }

    /**
     * Facilities visible to the actor in the active organization.
     *
     * @param facilities visible facility views
     * @return this view
     */
    public record FacilityDirectory(List<FacilityEntry> facilities) {

        /**
         * Create an immutable directory page.
         */
        public FacilityDirectory {
            facilities = List.copyOf(facilities);
        }
    }

    /**
     * Messagable staff visible in one facility of the active organization.
     *
     * @param staff visible staff views, each carrying the browsed facility
     * @return this view
     */
    public record StaffDirectory(List<StaffEntry> staff) {

        /**
         * Create an immutable directory page.
         */
        public StaffDirectory {
            staff = List.copyOf(staff);
        }
    }
}
