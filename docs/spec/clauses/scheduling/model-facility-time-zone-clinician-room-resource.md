# CLN-01 clause 1 — model the scheduling domain

## Clause

Model facility/time zone, clinician and room/resource availability, visit duration, recurrence templates, holds and configurable booking rules.

## Status

Proven by tests

## Evidence

Scope: this status covers the domain model named in the clause. Publishing the booking routes in the OpenAPI contract is a separate tracked concern (see `## Gap`) and is not claimed here.

The clause test is [ModelFacilityTimeZoneClinicianRoomResourceTest.java](../../../../apps/api/src/test/java/com/aurevia/scheduling/clauses/ModelFacilityTimeZoneClinicianRoomResourceTest.java). It uses the same synthetic Mockito fixtures and tenant context as the neighbouring `scheduling` unit tests, with a fixed clock, a facility in `America/New_York`, a 45-minute visit type, a clinician and a room resource, and mocked `AppointmentRepository` / `SchedulingRepository` at the service boundary. It is enabled (no `@Disabled`) and green: 6 tests, 0 failures, 0 errors, 0 skipped.

- `holdUsesTheFacilityZoneBothResourcesVisitDurationAndTheConfiguredHoldMinutes` (rule met) — a hold passes the facility's IANA zone to both the clinician and room availability checks, derives the slot end from the visit type's `durationMinutes`, and sets `expiresAt` from the tenant-approved `booking.hold_minutes` setting.
- `recurrenceKeepsTheFacilityLocalClockTimeAcrossDaylightSaving` (rule met) — a three-occurrence weekly series expands to the facility's 09:00 local clock time on both sides of the spring-forward transition, and the persisted recurrence template records that local time and zone.
- `refusesASlotOutsideTheClinicianAvailabilityWindow` (negative) — the command raises `422 OUTSIDE_AVAILABILITY` and inserts no appointment when the clinician resource has no window covering the slot.
- `refusesASlotOutsideTheRoomAvailabilityWindow` (negative) — the same refusal and no insert when the room resource has no window, proving the room half of the availability rule independently.
- `refusesASlotInsideTheConfiguredMinimumNotice` (negative) — a configured `booking.min_notice_minutes` of 120 makes a slot 60 minutes away raise `422 MIN_NOTICE_NOT_MET`, with no insert.
- `refusesAFacilityWithAnInvalidTimeZone` (negative) — a facility whose `timeZone` is not a valid IANA identifier raises `422 INVALID_TIME_ZONE`, with no insert.

Production classes exercised through their normal entry point (`AppointmentService.book`, `hold` and `bookSeries`):

- [AppointmentService.java](../../../../apps/api/src/main/java/com/aurevia/scheduling/AppointmentService.java) — the command boundary that resolves reference data, applies the rules and writes the appointment, history and audit together in one tenant-guarded transaction.
- [FacilityInfo.java](../../../../apps/api/src/main/java/com/aurevia/scheduling/FacilityInfo.java) and [SchedulingInputs.java](../../../../apps/api/src/main/java/com/aurevia/scheduling/SchedulingInputs.java) — facility identity plus the IANA `ZoneId` that owns availability windows and recurrence expansion.
- [ResourceView.java](../../../../apps/api/src/main/java/com/aurevia/scheduling/ResourceView.java), [AvailabilityView.java](../../../../apps/api/src/main/java/com/aurevia/scheduling/AvailabilityView.java) and [VisitTypeView.java](../../../../apps/api/src/main/java/com/aurevia/scheduling/VisitTypeView.java) — clinician/room resources, local weekly windows and the visit type that owns slot duration.
- [RecurrenceTemplate.java](../../../../apps/api/src/main/java/com/aurevia/scheduling/RecurrenceTemplate.java) and [SeriesRequest.java](../../../../apps/api/src/main/java/com/aurevia/scheduling/SeriesRequest.java) — the civil recurrence template and its command.
- [BookingRules.java](../../../../apps/api/src/main/java/com/aurevia/scheduling/BookingRules.java) and [BookingRequest.java](../../../../apps/api/src/main/java/com/aurevia/scheduling/BookingRequest.java) — tenant-approved booking rules, the hold lifetime and the booking command.
- [Appointment.java](../../../../apps/api/src/main/java/com/aurevia/scheduling/Appointment.java) and [AppointmentState.java](../../../../apps/api/src/main/java/com/aurevia/scheduling/AppointmentState.java) — the held/appointed projection and lifecycle.
- [SchedulingRepository.java](../../../../apps/api/src/main/java/com/aurevia/scheduling/SchedulingRepository.java) and [AppointmentRepository.java](../../../../apps/api/src/main/java/com/aurevia/scheduling/AppointmentRepository.java) — the RLS-bounded store interfaces; the durable schema is [V8__scheduling.sql](../../../../db/migrations/V8__scheduling.sql) and the HTTP entry point is [AppointmentController.java](../../../../apps/api/src/main/java/com/aurevia/web/AppointmentController.java). End-to-end acceptance over real PostgreSQL is [SchedulingBookingTest.java](../../../../apps/api/src/test/java/com/aurevia/it/SchedulingBookingTest.java).

## Gap

None for the model this clause names: the facility/time-zone, resource and availability, duration, recurrence, hold and booking-rule model is implemented and testable. Separately, the booking routes are baselined in `contracts/openapi.yaml` and their publication is tracked by the owner task `FIX-OPENAPI-03` (WEB-05); that is a contract-publication concern, not a missing part of this clause's model, and no production file is changed by this test-only story.
