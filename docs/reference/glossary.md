# Bilingual glossary of clinical and product terms

This glossary maps the clinical and product words used in Aurevia V1 to the Arabic strings the interfaces actually show, to a plain-language meaning, and to the code name where one exists. It supports UX-01 (English and Arabic, RTL and mixed-script text) and PRD-01 (one longitudinal chart per patient per organization).

The Arabic column is copied **verbatim** from the message files; no Arabic term here was invented. The source bundles are `apps/staff-web/src/i18n/messages.ts`, `apps/staff-web/src/components/stateViewMessages.ts`, and the `messages.ts` under `apps/staff-web/src/features/` (`patients`, `admin`, `system`, `duplicates`, `chart`, `scheduling`), plus `apps/patient-mobile/src/app/messages.ts` and `apps/patient-mobile/src/features/attachments/messages.ts`. A code name is the Java class or the database table that owns the idea; the same idea can have both. A value that is a message key such as `{name}` is shown as it appears in the bundle.

## Terms

| English term | Arabic term (as shown in the interface) | Plain meaning | Code name (Java class / table) |
|---|---|---|---|
| Organization | المؤسسة | The organization whose data and staff you are working in. | `TenantContext`, `organization` |
| Facility | المرفق (staff web) / المنشأة (patient app) | A location within an organization; see Inconsistencies. | `tenancy.Facility`, `facility` |
| Facilities | المرافق | The list of locations in the active organization. | `FacilityService`, `facility` |
| Facility code | رمز المرفق | Short identifier for a facility. | `Facility.code`, `facility.code` |
| Time zone | المنطقة الزمنية | The local clock region used to display appointment and audit times. | `Facility`, `facility.time_zone` |
| Staff administration | إدارة الموظفين | Managing the people who have a role in the active organization. | `StaffMember`, `StaffService`, `membership` |
| Staff subject | معرّف الموظف | Stable identifier of the staff identity. | `user_identity` |
| Role | الدور / الدور المحدود | A bounded permission set (for example tenant_admin, reception). | `role_assignment` |
| Membership history | سجل العضوية ومنح الأدوار للموظفين | The running record of who was invited, granted a role or deactivated. | `StaffHistory`, `membership` |
| Invite staff | دعوة موظف | Create an invitation to join the organization. | `StaffInvitation`, `staff_invitation` |
| Deactivate | إلغاء التنشيط | Switch a staff member off; their access ends on the next request. | `StaffService`, `membership` |
| Grantor | المانح | Who granted a role. | `role_assignment.granted_by` |
| Append-only event | حدث غير قابل للتعديل | Immutable record of a sensitive action. | `AuditEvent`, `audit_event` |
| Audit trail / audit review | أحداث التدقيق وسلامة السلسلة | The record of sensitive actions and the check that it has not been altered. | `AuditService`, `audit_event` |
| Purpose | الغرض | Business reason recorded for a sensitive action. | `AuditEvent.purpose` |
| Scope | النطاق | What an audited action covered. | `AuditEvent.scope` |
| Actor | الفاعل | Who performed an audited action. | `AuditEvent.actorId` |
| Outcome | النتيجة | Result of an action (success, denied, not found, conflict). | `audit_event.outcome` |
| Correlation ID | معرّف الارتباط | Request identifier that ties logs and errors together. | `CorrelationIdFilter` |
| Chain verified | السلسلة موثّقة | The check that the audit trail has not been altered. | `AuditService`, `audit_event` |
| Configuration and catalogs | التكوين والكتالوجات | Settings for one organization, saved as versions that never change. | `ConfigEntry`, `config_entry` |
| Configuration key | مفتاح التكوين | Tenant-local key that names one setting. | `ConfigEntry`, `config_entry.key` |
| Version | الإصدار | An immutable revision of a setting, note or document. | `ConfigEntry`, `ClinicalNoteRevision` |
| Effective from | ساري من | When a version starts to apply. | `ConfigEntry.effectiveFrom` |
| Approval state / approved | حالة الاعتماد / معتمد | Named sign-off that makes a version eligible to take effect. | `config_approval`, `ConfigEntry` |
| Pending approval | بانتظار الاعتماد | A version that exists but is not yet approved. | `config_approval` |
| Catalog family | عائلة الكتالوج | Named group of catalog entries. | `CatalogService`, `catalog.CatalogEntry` |
| Catalog entries / entry code | إدخالات الكتالوج السارية / رمز الإدخال | The code-and-value rows in effect for a catalog family. | `CatalogEntry` |
| Patient / patients | المريض / المرضى | The person whose record is in the chart. | `Patient`, `patient_record` |
| Patient ID | معرّف المريض | Identifier of the patient record. | `Patient.id`, `patient_record.id` |
| MRN | رقم الملف | Issuer-scoped medical record number. | `Patient.mrn`, `patient_record` |
| Issuer | الجهة المُصدرة / جهة إصدار رقم الملف | The body that issued the MRN; see Inconsistencies. | `Patient.mrnIssuer` |
| Date of birth | تاريخ الميلاد | Civil date of birth, or a coded unknown reason. | `Patient.dob` |
| Unknown-date reason | سبب تاريخ الميلاد غير المعروف (رمز) | Coded reason when the birth date is not known. | `Patient.dobUnknownReason` |
| Not known / not disclosed / unable to collect | غير معروف / لم يُفصح عنه / تعذّر الجمع | Coded reasons when a birth date is not known. | `Patient.dobUnknownReason` |
| Display name | الاسم المعروض | Original spelling shown for a person. | `Patient.displayName`, `LocalizedName` |
| Language code | رمز اللغة | Code such as `en` or `ar` for a person or a document. | `UiLocale`, `LocalizedText` |
| Register patient | تسجيل مريض | Create a patient record with issuer-scoped identifiers. | `PatientService`, `patient_record` |
| Identity | الهوية | Confirming that a patient record belongs to the right person. | `PatientAccount`, `patient_account` |
| Enrollment | التسجيل (patient app) | Site-verified setup of a patient account. | `PatientAccessService`, `patient_access_account` |
| Recovery / lockout | استعادة الوصول / القفل | Regaining access and account lock state. | `PatientAccessService` |
| Duplicate records | سجلات مرجّح تكرارها | Records that may belong to the same patient. | `DuplicateReview`, `patient_link` |
| Duplicate review | مراجعة المرضى المكررين | Same-tenant review of a suspected duplicate pair. | `DuplicateReview`, `Consolidation` |
| Surviving identity / surviving record | الهوية الباقية / السجل الباقي | The record kept when two identities are merged. | `Consolidation`, `chart_association` |
| Duplicate identity / duplicate record | الهوية المكررة / السجل المكرر | The record whose chart visibility is moved. | `Consolidation`, `chart_association` |
| Conflicting demographics | البيانات الديموغرافية المتعارضة | Fields that differ between the two identities. | `ReviewDetail` |
| Dependencies | التبعيات | Orders, patient links or grants that block consolidation. | `DuplicatePolicy` |
| Dry run | تجربة جافة (لم يتغيّر أي بيانات) | Preview of a consolidation that changes no data. | `ReviewDetail.dryRun`, `ConsolidationService` |
| Second-reviewer approval | موافقة المراجع الثاني | A different authorized reviewer must approve. | `DuplicatePolicy` |
| Reason for consolidation | سبب الدمج | Why two records are being merged. | `Consolidation` |
| Chart / longitudinal chart | الملف السريري الطولي | The patient's clinical record over time; see Inconsistencies. | `ChartView`, `ChartService`, `chart_association` |
| Open chart | فتح الملف الطبي | Navigate to a patient's chart. | `ChartController`, `ChartView` |
| Problems | المشكلات | The patient's ongoing problems. | `chart.record.ChartEntry` |
| Allergies | الحساسيات | Recorded allergies and ingredient checks. | `patient_allergy`, `AllergyChecker` |
| Medications | الأدوية | The patient's current and past medicines. | `chart.record.ChartEntry`, `drug_catalog_entry` |
| Vitals with units | القياسات الحيوية بالوحدات | Measurements with their units and time. | `chart.record.ChartEntry` |
| Observation / measurement | الملاحظة / القياس | A single recorded clinical observation. | `chart.record.EntryType`, `TrendObservation` |
| Verification | حالة التحقق | Whether an entry is verified, unverified, unknown or none. | `VerificationState`, `ChartEntry` |
| Source | المصدر | Where a chart entry came from. | `EntrySource` |
| Effective date | تاريخ السريان / التاريخ السريري للسريان | When a clinical entry takes effect. | `chart.record.ChartEntry` |
| Clinical author | الكاتب السريري | Clinician responsible for the content. | `ClinicalNoteRevision` |
| Entered by | أدخل البيانات | Actor who keyed the data (distinct from the author). | `TemplateActor` |
| Clinical note | الملاحظة السريرية | A draft or signed note in the chart. | `ClinicalNoteRevision`, `clinical_note_revision` |
| Draft / signed / amendment | مسودة / موقّعة / مسودة تعديل | Note lifecycle states. | `NoteState`, `clinical_note_revision` |
| Entered in error | سجل كخطأ إدخال | A signed note marked wrong while the original is kept. | `NoteState.ENTERED_IN_ERROR` |
| Sign draft | توقيع المسودة | Attest to and lock a note revision. | `ClinicalNoteService`, `NoteChecksum` |
| Signer attestation | إقرار الموقّع / أقرّ بهذه الملاحظة وسياق المريض | Explicit statement by the signer. | `ClinicalNoteService` |
| Checksum | المجموع الاختباري / SHA-256 | Integrity value for note or file content; see Inconsistencies. | `NoteChecksum`, `EvidenceHash` |
| Revision history | سجل المراجعات | All saved revisions of a note. | `ClinicalNoteRevision`, `clinical_note_revision` |
| Documents / document associations | المستندات / ارتباطات الملف والمستندات | Original files linked to a chart, order or result. | `DocumentModel`, `document_association` |
| Void association | إلغاء الارتباط | Invalidate a document link while keeping its history. | `DocumentService`, `document_association_event` |
| Rotate view | تدوير العرض | Presentation-only image rotation. | `DocumentModel`, `PdfBindingService` |
| Active / voided (document) | نشط / مُلغى | Current state of a document association. | `document_association` |
| Scheduling | جدول المواعيد | Booking and worklist for visits. | `AppointmentService`, `appointment` |
| Appointments | المواعيد | Booked visits shown on the worklist or the patient tab. | `Appointment`, `appointment` |
| Visit state | حالة الزيارة | Lifecycle of a visit; see Inconsistencies. | `AppointmentState` |
| Booked / checked in / in progress / completed | محجوز / تم تسجيل الوصول / قيد التنفيذ / مكتمل | Visit states in order. | `AppointmentState`, `appointment.state` |
| Cancelled / no-show | ملغي / لم يحضر | Terminating visit states. | `AppointmentState` |
| Check in | تسجيل الوصول | Reception verifies identity at the visit. | `CheckInService`, `check_in_verification` |
| Protected resource | المورد المحمي | A clinician or room that cannot be double-booked. | `ResourceView`, `scheduling_resource` |
| Assign to | إسناد إلى | Set the assignee for a visit or work item. | `WorklistItem`, `worklist_entry` |
| Worklist | نطاق قائمة العمل | The list of visits at a facility, ordered by time. | `WorklistRepository`, `worklist_entry` |
| Visit type | نوع الزيارة | Configured kind of visit. | `VisitTypeView`, `visit_type` |
| Reports | التقارير / لا توجد تقارير مُصدرّة | Released reports the patient may view. | `ReleasedReport`, `ReportRelease` |
| Messages | الرسائل | Secure conversations with care units. | `ConversationService`, `conversation` |
| Care unit | وحدة الرعاية المستقبِلة | A named, staffed inbox for a care team. | `CareUnitDirectoryService`, `care_unit` |
| Unit | الوحدة | A care unit in the patient app. | `care_unit` |
| Instructions | التعليمات | Versioned care instructions shared with the patient. | `InstructionService` |
| Attachments | مراجعة المرفقات | Files reviewed before an authorized send. | `AttachmentService`, `attachment` |
| Scan state | حالة الفحص | Whether an uploaded file passed its safety scan. | `QuarantineService` |
| Quarantined / clean / failed | في الحجر — غير متاح بعد / نظيف — جاهز بعد الإرسال المصرّح به / فشل الفحص — احذف أو استبدل | File-scan outcomes. | `AttachmentService`, `QuarantineService` |
| Provenance | السجل المصدر | Origin and custodian of a file or record. | `ImportProvenance` |
| Patient-supplied material | مواد قدّمها المريض | A file sent by the patient to a unit. | `AttachmentService` |
| Loading / empty / retry | جار التحميل… / لا يوجد شيء لعرضه / إعادة المحاولة | Common screen states. | none (UI-only; bundle listed above) |
| Forbidden | لا تملك صلاحية الوصول | The role cannot perform the action (403). | `AccessController` |
| Conflict | تغيّر هذا السجل | The record changed since it was opened (409). | `ApiErrorHandler` |
| Session expired | انتهت جلستك | The authenticated session has ended. | `SecurityConfiguration` |
| Sign out | لقد سجّلت الخروج / تم تسجيل الخروج | End the current session; see Inconsistencies. | `web.SecurityConfiguration` |
| Mixed-script clinical text | تسمية سريرية: ليلى Haddad · MAIN | A label with both Arabic and Latin script. | `MixedScriptSupport`, `MixedScriptRun` |

## Inconsistencies

These are places where the interfaces use two different words for one idea, or one word for two ideas. They are observations only; no message file was changed.

1. **Facility — المرفق vs المنشأة.** The staff web uses «المرفق» for the English *Facility*: `apps/staff-web/src/i18n/messages.ts` (`facilities` = المرافق, `facilityDetails` = تفاصيل المرفق) and `apps/staff-web/src/features/admin/messages.ts` (`facility` = المرفق, `facilityCode` = رمز المرفق), also `apps/staff-web/src/features/scheduling/messages.ts` (`facilityCode` = رمز المرفق). The patient app uses «المنشأة» in `apps/patient-mobile/src/features/attachments/messages.ts` (`facility` = المنشأة). One English term, two Arabic words.

2. **Chart — الملف السريري vs الملف الطبي.** `apps/staff-web/src/features/chart/messages.ts` renders *Chart* as «الملف السريري الطولي» (`chart`) and uses «الملف» inside «ارتباطات الملف والمستندات» (`documentList`), while `apps/staff-web/src/features/patients/messages.ts` renders "Open chart" as «فتح الملف الطبي» (`chartLink`) and `apps/staff-web/src/features/duplicates/messages.ts` uses the same «الملف الطبي» in `approved`. The same clinical record is named two ways in Arabic.

3. **Checksum — المجموع الاختباري vs SHA-256.** `apps/staff-web/src/features/chart/messages.ts` translates *Checksum* as «المجموع الاختباري للمحتوى» / «المجموع الاختباري», but `apps/patient-mobile/src/features/attachments/messages.ts` shows the literal algorithm name «SHA-256» for `checksum`. One idea, a translated phrase on staff web and an algorithm token on the patient app.

4. **State / Status → حالة.** English keeps two words but Arabic uses «حالة» for both: *visit state* «حالة الزيارة» (`apps/staff-web/src/features/scheduling/messages.ts`, `state`), *verification* «حالة التحقق» (`apps/staff-web/src/features/chart/messages.ts`, `verification`), *scan state* «حالة الفحص» (`apps/patient-mobile/src/features/attachments/messages.ts`, `scanState`), and *status* «حالة جدول المواعيد» / «الحالة» / «حالة الملف» (`scheduling`, `admin` and `chart` message files: `status`, `documentStatus`). Several English ideas share one Arabic word.

5. **Cancel / Deactivate / Void / Cancelled → إلغاء.** One Arabic root covers four distinct operations: *Cancel* «إلغاء» (`apps/staff-web/src/features/patients/messages.ts`, `cancel`), *Deactivate* «إلغاء التنشيط» and «تم إلغاء التنشيط» (`apps/staff-web/src/features/admin/messages.ts`, `deactivate`, `eventDeactivated`), *Void association* «إلغاء الارتباط» (`apps/staff-web/src/features/chart/messages.ts`, `void`), and the visit state *Cancelled* «ملغي» (`apps/staff-web/src/features/scheduling/messages.ts`, `stateCancelled`).

6. **Register / Sign in / Check-in / Enrollment / Recorded → تسجيل.** The Arabic root «تسجيل» is reused for *Register* «تسجيل» / «تم تسجيل {name}» (`apps/staff-web/src/features/patients/messages.ts`, `register`, `registered`), *Sign in* «تسجيل الدخول» (`apps/staff-web/src/i18n/messages.ts`, `apps/staff-web/src/features/system/messages.ts`, `apps/staff-web/src/components/stateViewMessages.ts`, `apps/patient-mobile/src/app/messages.ts`), *Check in* «تسجيل الوصول» (`apps/staff-web/src/features/scheduling/messages.ts`, `checkIn`), *Enrollment* «التسجيل» (`apps/patient-mobile/src/app/messages.ts`, `accountEnrollment`), and *Recorded at* «وقت التسجيل (UTC)» (`apps/staff-web/src/features/chart/messages.ts`, `recordedAt`).

7. **Issuer — الجهة المُصدرة vs جهة إصدار رقم الملف.** Inside one bundle, `apps/staff-web/src/features/patients/messages.ts` uses «الجهة المُصدرة» for `colIssuer` and `issuer` but «جهة إصدار رقم الملف» for `fieldIssuer` (English "MRN issuer"). The two English labels are close, the Arabic renderings are not the same.

8. **History / Record / Provenance → السجل.** «السجل» stands for *History* (`apps/staff-web/src/features/admin/messages.ts`, `history`; `apps/staff-web/src/features/chart/messages.ts`, `history` = سجل المراجعات), *record* (`apps/staff-web/src/features/duplicates/messages.ts`, `survivor` = السجل الباقي, `duplicate` = السجل المكرر), and *provenance* (`apps/patient-mobile/src/features/attachments/messages.ts`, `provenance` = السجل المصدر). Three English ideas, one Arabic word.

9. **Sign out — لقد سجّلت الخروج vs تم تسجيل الخروج.** `apps/staff-web/src/features/system/messages.ts` uses «لقد سجّلت الخروج» for `signedOutTitle`, while `apps/patient-mobile/src/app/messages.ts` uses «تم تسجيل الخروج» for the same key. The same state is phrased differently on the two platforms.

10. **Display / Display name → الاسم المعروض.** `apps/staff-web/src/features/chart/messages.ts` translates the short label *Display* as «الاسم المعروض» (`display`), the same Arabic string used for *Display name* in `apps/staff-web/src/features/patients/messages.ts` (`fieldDisplayName`) and `apps/staff-web/src/features/admin/messages.ts` (`staffDisplayName`). Two English labels share one Arabic string.

11. **Approve / approval — اعتماد vs موافقة.** `apps/staff-web/src/features/admin/messages.ts` uses the root «اعتماد» (`approveConfirm`, `approve`, `approved`, `approvedNotice`, `approvalState` = حالة الاعتماد). `apps/staff-web/src/features/duplicates/messages.ts` uses «موافقة» instead (`approvalTitle` = موافقة المراجع الثاني, `approve` = الموافقة على الدمج, `approved` = تمت الموافقة على الدمج ونقل ظهور الملف الطبي.). One English idea, two Arabic roots.

12. **Conflict — تغيّر هذا السجل vs تعارض.** `apps/staff-web/src/components/stateViewMessages.ts` uses «تغيّر هذا السجل» for a stale record (409), while `apps/staff-web/src/features/admin/messages.ts` uses «تعارض» for `outcomeConflict` and `apps/staff-web/src/features/duplicates/messages.ts` uses the same root in `conflictsTitle` (البيانات الديموغرافية المتعارضة). The same English outcome has two Arabic renderings.

13. **Loading — جار التحميل… vs جارٍ التحميل….** `apps/staff-web/src/components/stateViewMessages.ts` writes «جار التحميل…» while `apps/patient-mobile/src/app/messages.ts` writes «جارٍ التحميل…» with a diacritic on the ي. The same word is spelled differently across the two platforms.

Note on mixed script: `apps/staff-web/src/i18n/messages.ts` deliberately keeps «ليلى Haddad · MAIN» in both `mixedScriptNote` values, with the surrounding words in the reader's language ("Clinical label:" / "تسمية سريرية:"). This is required by UX-01 and is not an inconsistency.
