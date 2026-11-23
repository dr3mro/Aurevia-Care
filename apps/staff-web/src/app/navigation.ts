import type { Locale } from '../i18n/messages';

/**
 * Staff roles that can use the portal (IAM-01). The names match the bounded G0
 * roles already used by the administration feature, plus the specialty,
 * clinical, finance, operations and messaging roles in the screen map.
 * `staff` is the base role held by every authenticated user: the screen map's
 * "all staff" rows are available to every role through it.
 */
export type Role =
  | 'staff'
  | 'reception'
  | 'clinician'
  | 'lab'
  | 'radiology'
  | 'therapist'
  | 'pharmacy'
  | 'finance'
  | 'operator'
  | 'unit_staff'
  | 'tenant_admin'
  | 'privacy_auditor';

/** The base role that grants the screen map's "all staff" items to everyone. */
export const ALL_STAFF_ROLE: Role = 'staff';

/** Groups of the staff web screen map, in the order they appear there. */
export type NavigationGroup =
  | 'Today'
  | 'Scheduling'
  | 'Clinic'
  | 'Patients'
  | 'Orders'
  | 'Results'
  | 'Specialty'
  | 'Messages'
  | 'Finance'
  | 'Administration'
  | 'Operations'
  | 'Account';

/** Message keys for the rail: one per item, one per group, plus the rail name. */
export type NavigationLabelKey =
  | 'navigation'
  | 'home'
  | 'schedule'
  | 'scheduleBook'
  | 'checkIn'
  | 'patients'
  | 'patientChart'
  | 'chart'
  | 'noteEditor'
  | 'orders'
  | 'orderDetail'
  | 'results'
  | 'criticalResults'
  | 'accessions'
  | 'radiology'
  | 'therapy'
  | 'pharmacy'
  | 'resultEntry'
  | 'labRelease'
  | 'turnaroundBoard'
  | 'messages'
  | 'finance'
  | 'facilities'
  | 'staff'
  | 'configuration'
  | 'audit'
  | 'queues'
  | 'exports'
  | 'imports'
  | 'partnerBoard'
  | 'sessions'
  | 'groupToday'
  | 'groupScheduling'
  | 'groupClinic'
  | 'groupPatients'
  | 'groupOrders'
  | 'groupResults'
  | 'groupSpecialty'
  | 'groupMessages'
  | 'groupFinance'
  | 'groupAdministration'
  | 'groupOperations'
  | 'groupAccount';

/** One row of the staff web screen map (section 2). */
export interface NavigationItem {
  /** Stable, domain-derived identifier; never a story or gate ID. */
  id: string;
  /** Stable route; opaque identifiers only, no patient names or free text. */
  route: string;
  group: NavigationGroup;
  labelKey: NavigationLabelKey;
  /** Roles allowed to use the item; `staff` means the table's "all staff". */
  roles: Role[];
}

/**
 * Every section-2 row except the System group, in table order. Routes for
 * screens that do not exist yet remain entries; the router renders a
 * "not available yet" page for them (a later application-shell story).
 */
export const navigation: NavigationItem[] = [
  { id: 'home', route: '/', group: 'Today', labelKey: 'home', roles: ['staff'] },
  { id: 'schedule', route: '/schedule', group: 'Scheduling', labelKey: 'schedule', roles: ['reception', 'clinician'] },
  { id: 'schedule-book', route: '/schedule/book', group: 'Scheduling', labelKey: 'scheduleBook', roles: ['reception'] },
  { id: 'check-in', route: '/checkin', group: 'Clinic', labelKey: 'checkIn', roles: ['reception'] },
  { id: 'patients', route: '/patients', group: 'Patients', labelKey: 'patients', roles: ['reception', 'clinician'] },
  { id: 'patient-overview', route: '/patients/:id', group: 'Patients', labelKey: 'patientChart', roles: ['clinician'] },
  { id: 'chart', route: '/patients/:id/chart', group: 'Patients', labelKey: 'chart', roles: ['clinician'] },
  { id: 'note-editor', route: '/patients/:id/notes/:noteId', group: 'Patients', labelKey: 'noteEditor', roles: ['clinician'] },
  { id: 'orders', route: '/orders', group: 'Orders', labelKey: 'orders', roles: ['clinician', 'lab', 'radiology'] },
  { id: 'order-detail', route: '/orders/:id', group: 'Orders', labelKey: 'orderDetail', roles: ['clinician', 'lab', 'radiology'] },
  { id: 'results', route: '/results', group: 'Results', labelKey: 'results', roles: ['clinician', 'operator'] },
  { id: 'critical-results', route: '/results/critical', group: 'Results', labelKey: 'criticalResults', roles: ['clinician'] },
  { id: 'accessions', route: '/lab/accessions', group: 'Specialty', labelKey: 'accessions', roles: ['lab'] },
  { id: 'lab-result-entry', route: '/lab/results/entry', group: 'Specialty', labelKey: 'resultEntry', roles: ['lab'] },
  { id: 'lab-release', route: '/lab/release', group: 'Specialty', labelKey: 'labRelease', roles: ['lab'] },
  { id: 'lab-turnaround-board', route: '/lab/turnaround-board', group: 'Specialty', labelKey: 'turnaroundBoard', roles: ['lab'] },
  { id: 'radiology', route: '/radiology', group: 'Specialty', labelKey: 'radiology', roles: ['radiology'] },
  { id: 'therapy', route: '/therapy', group: 'Specialty', labelKey: 'therapy', roles: ['therapist'] },
  { id: 'pharmacy', route: '/pharmacy', group: 'Specialty', labelKey: 'pharmacy', roles: ['clinician', 'pharmacy'] },
  { id: 'messages', route: '/messages', group: 'Messages', labelKey: 'messages', roles: ['unit_staff'] },
  { id: 'finance', route: '/finance', group: 'Finance', labelKey: 'finance', roles: ['finance'] },
  { id: 'admin-facilities', route: '/admin/facilities', group: 'Administration', labelKey: 'facilities', roles: ['tenant_admin'] },
  { id: 'admin-staff', route: '/admin/staff', group: 'Administration', labelKey: 'staff', roles: ['tenant_admin'] },
  { id: 'admin-config', route: '/admin/config', group: 'Administration', labelKey: 'configuration', roles: ['tenant_admin'] },
  { id: 'admin-audit', route: '/admin/audit', group: 'Administration', labelKey: 'audit', roles: ['privacy_auditor', 'tenant_admin'] },
  { id: 'operations-queues', route: '/operations/queues', group: 'Operations', labelKey: 'queues', roles: ['operator'] },
  { id: 'operations-exports', route: '/operations/exports', group: 'Operations', labelKey: 'exports', roles: ['tenant_admin'] },
  { id: 'operations-imports', route: '/operations/imports', group: 'Operations', labelKey: 'imports', roles: ['tenant_admin'] },
  { id: 'operations-partner-board', route: '/operations/partner-board', group: 'Operations', labelKey: 'partnerBoard', roles: ['operator'] },
  { id: 'account-sessions', route: '/account/sessions', group: 'Account', labelKey: 'sessions', roles: ['staff'] },
];

/** English and Arabic labels for the rail, with identical key sets (UX-01). */
export const navigationLabels: Record<Locale, Record<NavigationLabelKey, string>> = {
  en: {
    navigation: 'Sections',
    home: 'Today',
    schedule: 'Schedule',
    scheduleBook: 'Book appointment',
    checkIn: 'Check-in',
    patients: 'Patients',
    patientChart: 'Patient overview',
    chart: 'Chart',
    noteEditor: 'Note editor',
    orders: 'Orders',
    orderDetail: 'Order detail',
    results: 'Results',
    criticalResults: 'Critical results',
    accessions: 'Specimen accessions',
    radiology: 'Radiology',
    therapy: 'Therapy',
    pharmacy: 'Pharmacy',
    resultEntry: 'Result entry',
    labRelease: 'Release results',
    turnaroundBoard: 'Turnaround board',
    messages: 'Messages',
    finance: 'Finance',
    facilities: 'Facilities',
    staff: 'Staff',
    configuration: 'Configuration',
    audit: 'Audit',
    queues: 'Operations queues',
    exports: 'Exports',
    imports: 'Imports',
    partnerBoard: 'Partner board',
    sessions: 'Sessions',
    groupToday: 'Today',
    groupScheduling: 'Scheduling',
    groupClinic: 'Clinic',
    groupPatients: 'Patients',
    groupOrders: 'Orders',
    groupResults: 'Results',
    groupSpecialty: 'Specialty',
    groupMessages: 'Messages',
    groupFinance: 'Finance',
    groupAdministration: 'Administration',
    groupOperations: 'Operations',
    groupAccount: 'Account',
  },
  ar: {
    navigation: 'الأقسام',
    home: 'اليوم',
    schedule: 'الجدول',
    scheduleBook: 'حجز موعد',
    checkIn: 'تسجيل الوصول',
    patients: 'المرضى',
    patientChart: 'نظرة عامة على المريض',
    chart: 'الملف الطبي',
    noteEditor: 'محرر الملاحظة',
    orders: 'الطلبات',
    orderDetail: 'تفاصيل الطلب',
    results: 'النتائج',
    criticalResults: 'النتائج الحرجة',
    accessions: 'تسليم العينات',
    radiology: 'الأشعة',
    therapy: 'العلاج الطبيعي',
    pharmacy: 'الصيدلية',
    resultEntry: 'إدخال النتائج',
    labRelease: 'إصدار النتائج',
    turnaroundBoard: 'لوحة زمن الإنجاز',
    messages: 'الرسائل',
    finance: 'المالية',
    facilities: 'المرافق',
    staff: 'الموظفون',
    configuration: 'الإعدادات',
    audit: 'سجل التدقيق',
    queues: 'قوائم العمليات',
    exports: 'التصدير',
    imports: 'الاستيراد',
    partnerBoard: 'لوحة الشركاء',
    sessions: 'الجلسات',
    groupToday: 'اليوم',
    groupScheduling: 'الجدولة',
    groupClinic: 'العيادة',
    groupPatients: 'المرضى',
    groupOrders: 'الطلبات',
    groupResults: 'النتائج',
    groupSpecialty: 'التخصصات',
    groupMessages: 'الرسائل',
    groupFinance: 'المالية',
    groupAdministration: 'الإدارة',
    groupOperations: 'العمليات',
    groupAccount: 'الحساب',
  },
};

/** Map a group name to its message key without a runtime branch. */
const groupLabelKeyByGroup: Record<NavigationGroup, NavigationLabelKey> = {
  Today: 'groupToday',
  Scheduling: 'groupScheduling',
  Clinic: 'groupClinic',
  Patients: 'groupPatients',
  Orders: 'groupOrders',
  Results: 'groupResults',
  Specialty: 'groupSpecialty',
  Messages: 'groupMessages',
  Finance: 'groupFinance',
  Administration: 'groupAdministration',
  Operations: 'groupOperations',
  Account: 'groupAccount',
};

/** Resolve the message key for a group heading. */
export function groupLabelKey(group: NavigationGroup): NavigationLabelKey {
  return groupLabelKeyByGroup[group];
}

/** Look up a translated rail label for the active locale (UX-01). */
export function navigationLabel(locale: Locale, key: NavigationLabelKey): string {
  return navigationLabels[locale][key];
}

/**
 * Return only the items the role may use, in screen-map order. Every role also
 * receives the base `staff` items ("all staff" in the table).
 */
export function navigationFor(role: Role): NavigationItem[] {
  return navigation.filter(
    (item) => item.roles.includes(role) || item.roles.includes(ALL_STAFF_ROLE),
  );
}

/** Items of one group, used to render the grouped rail. */
export interface NavigationGrouping {
  group: NavigationGroup;
  items: NavigationItem[];
}

/** Group a role's items into consecutive groups in table order. */
export function navigationGroupsFor(role: Role): NavigationGrouping[] {
  const groups: NavigationGrouping[] = [];
  for (const item of navigationFor(role)) {
    const current = groups.find((group) => group.group === item.group);
    if (current) {
      current.items.push(item);
    } else {
      groups.push({ group: item.group, items: [item] });
    }
  }
  return groups;
}

/**
 * Direct-link access that differs from rail visibility, keyed by exact route.
 *
 * The screen-map test fixes the privacy auditor's rail to three items, but the
 * import service authorizes that role to review a dry run and sign it off. This
 * narrow rule keeps a direct link consistent with the feature's own guard
 * without changing the tested rail; the rail entry stays tenant-admin-only.
 */
export const routeAccessRoles: Readonly<Record<string, readonly Role[]>> = {
  '/operations/imports': ['tenant_admin', 'privacy_auditor'],
};

/** Result for a route the shell must not guard. */
export const UNRESTRICTED_ROUTE = null;

/** Roles allowed to open a route, or `UNRESTRICTED_ROUTE` when anyone may. */
export type RouteAccess = readonly Role[] | typeof UNRESTRICTED_ROUTE;

/**
 * Resolve the roles allowed to open a registered route pattern.
 *
 * An exact navigation match wins; otherwise trailing `:parameter` segments are
 * removed until a navigation entry is found, which covers detail routes such
 * as `/pharmacy/:id`. A route with no entry, or one whose entry is available to
 * every authenticated user (`staff`), is unrestricted. This is the single role
 * source for both the rail and the shell guard; no role is read from a URL.
 *
 * @param pattern registered route pattern, never a patient name or free text
 * @returns the allowed roles, or `UNRESTRICTED_ROUTE` when the shell must not guard
 */
export function rolesForRoute(pattern: string): RouteAccess {
  let candidate = pattern;
  for (;;) {
    const override = routeAccessRoles[candidate];
    if (override !== undefined) {
      return override;
    }
    const item = navigation.find((entry) => entry.route === candidate);
    if (item !== undefined) {
      // 'staff' is the base role of every authenticated user, so such an item
      // can never be denied and the shell must leave it unguarded.
      return item.roles.includes(ALL_STAFF_ROLE) ? UNRESTRICTED_ROUTE : item.roles;
    }
    const separator = candidate.lastIndexOf('/');
    const tail = candidate.slice(separator + 1);
    if (!tail.startsWith(':')) {
      break;
    }
    candidate = candidate.slice(0, separator);
  }
  return UNRESTRICTED_ROUTE;
}
