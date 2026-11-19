/**
 * MSG-07 domain types for messaging audit, retention, legal hold, provenance,
 * copy requests and revocation effects.
 *
 * Every type is a non-sensitive governance projection of the server contract.
 * No field carries a message body, attachment bytes or a clinical finding. The
 * client relies on server-side authorization; these types only describe the
 * data shown to an authorized patient or unit reviewer.
 */

/** MSG-07 action vocabulary, mirroring the server audit action enum. */
export type MessagingAuditAction =
  | 'open'
  | 'view'
  | 'send'
  | 'assign'
  | 'download'
  | 'acknowledge'
  | 'file'
  | 'correct'
  | 'restrict'
  | 'export'
  | 'hold_place'
  | 'hold_release'
  | 'chart_link'
  | 'copy_request'
  | 'retention_apply'
  | 'audit_read';

/** Recorded outcome of an audited messaging action. */
export type MessagingAuditOutcome = 'success' | 'denied' | 'not_found' | 'conflict';

/** One append-only, non-sensitive audit fact. */
export interface MessagingAuditEvent {
  readonly id: string;
  readonly organizationId: string;
  readonly subject: string;
  readonly purpose: string;
  readonly action: MessagingAuditAction;
  readonly resourceId: string;
  readonly unitId: string;
  readonly correlationId: string;
  readonly outcome: MessagingAuditOutcome;
  readonly occurredAt: string;
}

/** Tenant-approved retention window for one resource family. */
export interface RetentionPolicy {
  readonly organizationId: string;
  readonly resourceType: string;
  readonly retentionDays: number;
}

/** A synthetic messaging resource tracked for retention and holds. */
export interface MessagingResource {
  readonly id: string;
  readonly organizationId: string;
  readonly resourceType: string;
  readonly createdAt: string;
}

/** A legal hold that suspends retention for one resource. */
export interface LegalHold {
  readonly id: string;
  readonly organizationId: string;
  readonly resourceId: string;
  readonly reason: string;
  readonly active: boolean;
  readonly placedBy: string;
  readonly placedAt: string;
  readonly releasedBy?: string;
  readonly releasedAt?: string;
}

/** Verified record-access policy used after a connection is revoked. */
export interface RecordAccessPolicy {
  readonly organizationId: string;
  readonly priorConversationsReadable: boolean;
  readonly policyVersion: string;
}

/** A tenant-scoped connection reference used for revocation effects. */
export interface MessagingConnection {
  readonly id: string;
  readonly organizationId: string;
  readonly unitId: string;
  readonly revokedAt?: string;
}

/** Provenance of a clinically relevant message or instruction filed in a chart. */
export interface ProvenanceLink {
  readonly id: string;
  readonly organizationId: string;
  readonly conversationId: string;
  readonly messageId: string;
  readonly sourceType: string;
  readonly sourceReference: string;
  readonly revision: string;
  readonly linkedBy: string;
  readonly linkedAt: string;
}

/** The recorded consequences of revoking a patient-unit connection. */
export interface RevocationEffect {
  readonly id: string;
  readonly organizationId: string;
  readonly connectionId: string;
  readonly messagingBlocked: boolean;
  readonly attachmentAccessBlocked: boolean;
  readonly priorConversationsReadable: boolean;
  readonly recordAccessBasis: string;
  readonly recordedAt: string;
}

/** A request for copies routed to the site's record-access procedure. */
export interface CopyRequest {
  readonly id: string;
  readonly organizationId: string;
  readonly resourceId: string;
  readonly requestedBy: string;
  readonly purpose: string;
  readonly status: string;
  readonly createdAt: string;
}

/** Synthetic fixture for the in-memory MSG-07 governance client. */
export interface GovernanceFixture {
  readonly organizationId: string;
  readonly now: string;
  readonly auditEvents: readonly MessagingAuditEvent[];
  readonly resources: readonly MessagingResource[];
  readonly retentionPolicies: readonly RetentionPolicy[];
  readonly legalHolds: readonly LegalHold[];
  readonly recordAccessPolicy: RecordAccessPolicy;
  readonly connections: readonly MessagingConnection[];
  /** Provenance links filed by authorized staff, shown read-only to the patient. */
  readonly provenanceLinks: readonly ProvenanceLink[];
  readonly newId: () => string;
}

/**
 * Typed boundary for MSG-07 governance reads and patient commands.
 *
 * Filing or linking material to the chart is an authorized staff action and is
 * therefore a read-only projection here; the server enforces CHART_LINK
 * authorization before any provenance link is created.
 */
export interface GovernanceClient {
  auditTrail(resourceId?: string): readonly MessagingAuditEvent[];
  canAccessAttachment(connectionId: string): boolean;
  requestCopy(resourceId: string, purpose: string): CopyRequest;
  provenanceLinks(messageId: string): readonly ProvenanceLink[];
}
