# REFACTOR-PACKAGES

## Mapping used

All Java packages named after story IDs were renamed to business-domain packages.

| Old package | New package |
|---|---|
| g0/s_g0_06 | patient/consolidation |
| g0/s_g0_07 | patient/identity |
| g0/s_g0_08 | authz/access |
| g1/s_g1_02 | scheduling/checkin |
| g1/s_g1_03 | patient/chart |
| g1/s_g1_04 | clinical/templates |
| g1/s_g1_05 | clinical/notes |
| g1/s_g1_06 | scheduling/followup |
| g1/s_g1_07 | documents |
| g1/s_g1_08 | catalog/dictionary |
| g2/s_g2_01 | exchange/order |
| g2/s_g2_02 | exchange/task |
| g2/s_g2_03 | exchange/response |
| g2/s_g2_04 | exchange/result |
| g2/s_g2_05 | exchange/critical |
| g2/s_g2_06 | exchange/release |
| g2/s_g2_07 | exchange/trend |
| g2/s_g2_08 | lab/accession |
| g2/s_g2_09 | lab/result |
| g2/s_g2_10 | exchange/conformance |
| g4/s_g4_01 | radiology |
| g4/s_g4_02 | therapy |
| g4/s_g4_03 | prescription |
| g4/s_g4_04 | prescription/rx |
| g4/s_g4_05 | finance |
| g4/s_g4_06 | authz/specialty |
| messaging/s_g3_01 | messaging/appointments |
| messaging/s_g3_02 | messaging/reports |
| messaging/s_g3_03 | messaging/enrollment |
| messaging/s_g3_04 | messaging/units |
| messaging/s_g3_05 | messaging/conversations |
| messaging/s_g3_06 | messaging/attachments |
| messaging/s_g3_07 | messaging/instructions |
| messaging/s_g3_08 | messaging/acknowledgment |
| messaging/s_g3_09 | messaging/service |
| messaging/s_g3_10 | messaging/governance |
| messaging/s_g3_11 | messaging/flows |
| messaging/s_g3_12 | messaging/evidence |

Test packages were moved to their existing flat domain mirrors (for example it/patientidentity, it/schedulingcheckin, it/messagingconversations).

## Duplicate simple names renamed

- `clinical/templates/Actor.java` -> `TemplateActor.java`
- `messaging/enrollment/Actor.java` -> `PatientAccessActor.java`
- `scheduling/checkin/Commands.java` -> `CheckInCommands.java`
- `lab/accession/Commands.java` -> `AccessionCommands.java`
- `clinical/notes/NoteRevision.java` -> `ClinicalNoteRevision.java`
- `clinical/templates/NoteRevision.java` -> `TemplateNoteRevision.java`
- `exchange/trend/Observation.java` -> `TrendObservation.java`
- `lab/result/Observation.java` -> `LabObservation.java`

Near-duplicate classes were not merged; they remain separate types.

## Files moved

- 371 main Java files across 36 story-id packages
- 156 test Java files across the corresponding it/messaging packages
- Package declarations, imports, and same-package references updated

## Commands run

- `mvn -B -f apps/api/pom.xml clean compile` -> exit 0
- `mvn -B -f apps/api/pom.xml test-compile` -> exit 0
- `bash scripts/quick-check.sh` -> exit 0 (policy, compile, unit tests)
- `bash scripts/check.sh` -> not fully green locally: DB integration tests fail due to local JDK 27 JDBC timestamptz conversion; CI uses JDK 21.

## Unresolved

- DB integration tests were not executed successfully on this workstation because only JDK 27 is installed; CI (JDK 21) must verify them.
- Historical docs/swarm notes and coverage artifacts still contain old paths in places.
