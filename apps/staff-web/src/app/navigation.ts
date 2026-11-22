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
  | 'messages'
  | 'finance'
  | 'facilities'
  | 'staff'
  | 'configuration'
  | 'audit'
  | 'queues'
  | 'sessions'
  | 'exports'
  | 'imports'
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
  { id: 'operations-imports', route: '/operations/imports', group: 'Operations', labelKey: 'imports', roles: ['privacy_auditor'] },
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
    messages: 'Messages',
    finance: 'Finance',
    facilities: 'Facilities',
    staff: 'Staff',
    configuration: 'Configuration',
    audit: 'Audit',
    queues: 'Operations queues',
    sessions: 'Sessions',
    exports: 'Tenant export',
    imports: 'Onboarding import',
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
    messages: 'الرسائل',
    finance: 'المالية',
    facilities: 'المرافق',
    staff: 'الموظفون',
    configuration: 'الإعدادات',
    audit: 'سجل التدقيق',
    queues: 'قوائم العمليات',
    sessions: 'الجلسات',
    exports: 'تصدير المستأجر',
    imports: 'استيراد التسجيل',
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

/**
 * Roles declared for a registered route pattern, or `null` when the pattern has
 * no navigation entry and is therefore unrestricted (system and detail pages).
 *
 * The lookup uses the exact registered pattern rather than the concrete
 * pathname: a route such as `/patients/:id/chart` is matched with bound
 * parameters, so the pattern is the only stable key shared with the router.
 */
export function routeAccessRoles(pattern: string): Role[] | null {
  const item = navigation.find((candidate) => candidate.route === pattern);
  return item ? item.roles : null;
}

/**
 * Whether a role may open a registered route. Routes without a navigation entry
 * stay open; an entry grants access through an explicit role or through the base
 * `staff` role held by every signed-in user. This mirrors `navigationFor`, so a
 * link the rail hides for a role cannot be opened by pasting its URL.
 */
export function canAccessRoute(role: Role, pattern: string): boolean {
  const roles = routeAccessRoles(pattern);
  return roles === null || roles.includes(role) || roles.includes(ALL_STAFF_ROLE);
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
