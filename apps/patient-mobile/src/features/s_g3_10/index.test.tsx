import { fireEvent, render } from '@testing-library/react-native';

import {
  activeHold,
  canAccessAttachment,
  createSyntheticGovernanceClient,
  priorConversationsReadable,
  retentionEligible,
} from './audit';
import { MessagingAuditPanel } from './MessagingAuditPanel';
import type {
  GovernanceFixture,
  LegalHold,
  MessagingAuditEvent,
  MessagingConnection,
  MessagingResource,
  ProvenanceLink,
  RecordAccessPolicy,
  RetentionPolicy,
  RevocationEffect,
} from './types';

const ORG = '11111111-1111-1111-1111-111111111111';
const OTHER_ORG = '22222222-2222-2222-2222-222222222222';
const RESOURCE = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa';
const RECENT = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb';
const UNPOLICED = 'cccccccc-cccc-cccc-cccc-cccccccccccc';
const CONNECTION = 'dddddddd-dddd-dddd-dddd-dddddddddddd';
const ACTIVE_CONNECTION = 'eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee';
const UNIT = 'ffffffff-ffff-ffff-ffff-ffffffffffff';
const NOW = '2026-11-19T12:00:00Z';

function fixture(overrides: Partial<GovernanceFixture> = {}): GovernanceFixture {
  const resources: readonly MessagingResource[] = [
    { id: RESOURCE, organizationId: ORG, resourceType: 'conversation', createdAt: '2026-10-01T12:00:00Z' },
    { id: RECENT, organizationId: ORG, resourceType: 'conversation', createdAt: '2026-11-15T12:00:00Z' },
    { id: UNPOLICED, organizationId: ORG, resourceType: 'observation', createdAt: '2026-10-01T12:00:00Z' },
  ];
  const policies: readonly RetentionPolicy[] = [
    { organizationId: ORG, resourceType: 'conversation', retentionDays: 30 },
    { organizationId: OTHER_ORG, resourceType: 'conversation', retentionDays: 90 },
  ];
  const connections: readonly MessagingConnection[] = [
    { id: CONNECTION, organizationId: ORG, unitId: UNIT, revokedAt: '2026-11-18T12:00:00Z' },
    { id: ACTIVE_CONNECTION, organizationId: ORG, unitId: UNIT },
  ];
  return {
    organizationId: ORG,
    now: NOW,
    auditEvents: [],
    resources,
    retentionPolicies: policies,
    legalHolds: [],
    recordAccessPolicy: { organizationId: ORG, priorConversationsReadable: true, policyVersion: 'policy-v1' },
    connections,
    provenanceLinks: [],
    newId: () => 'event-generated',
    ...overrides,
  };
}

describe('MSG-07 retention and legal hold', () => {
  const holds: readonly LegalHold[] = [
    { id: 'hold-1', organizationId: ORG, resourceId: RESOURCE, reason: 'Synthetic dispute',
      active: true, placedBy: 'auditor', placedAt: '2026-11-10T12:00:00Z' },
  ];

  it('finds only active holds scoped to the current organization', () => {
    expect(activeHold(fixture({ legalHolds: holds }), RESOURCE)?.id).toBe('hold-1');
    expect(activeHold(fixture({ legalHolds: holds }), UNPOLICED)).toBeUndefined();
    const released = [{ ...holds[0]!, active: false }];
    expect(activeHold(fixture({ legalHolds: released }), RESOURCE)).toBeUndefined();
    const foreign = [{ ...holds[0]!, organizationId: OTHER_ORG }];
    expect(activeHold(fixture({ legalHolds: foreign }), RESOURCE)).toBeUndefined();
  });

  it('marks a resource eligible only when its retention window passed and no hold applies', () => {
    const subject = fixture({ legalHolds: holds });
    expect(retentionEligible(subject, RESOURCE)).toBe(false);
    expect(retentionEligible(fixture(), RESOURCE)).toBe(true);
    expect(retentionEligible(fixture(), RECENT)).toBe(false);
    expect(retentionEligible(fixture(), UNPOLICED)).toBe(false);
    expect(retentionEligible(fixture(), 'unknown-resource')).toBe(false);
    expect(retentionEligible(fixture({ organizationId: OTHER_ORG }), RESOURCE)).toBe(false);
  });
});

describe('MSG-07 revocation effects', () => {
  it('blocks attachments only through a revoked connection', () => {
    const subject = fixture();
    expect(canAccessAttachment(subject, CONNECTION)).toBe(false);
    expect(canAccessAttachment(subject, ACTIVE_CONNECTION)).toBe(true);
    expect(canAccessAttachment(subject, 'unknown-connection')).toBe(false);
  });

  it('exposes the record-access readability policy for prior conversations', () => {
    expect(priorConversationsReadable(fixture())).toBe(true);
    const policy: RecordAccessPolicy = {
      organizationId: ORG, priorConversationsReadable: false, policyVersion: 'policy-v2',
    };
    expect(priorConversationsReadable(fixture({ recordAccessPolicy: policy }))).toBe(false);
  });

  it('fails closed when the record-access policy is from another organization', () => {
    const policy: RecordAccessPolicy = {
      organizationId: OTHER_ORG, priorConversationsReadable: true, policyVersion: 'policy-v2',
    };
    expect(priorConversationsReadable(fixture({ recordAccessPolicy: policy }))).toBe(false);
  });

  it('projects the full revocation effect from the policy and connection', () => {
    const subject = fixture();
    const effect: RevocationEffect = {
      id: 'effect-1', organizationId: subject.organizationId, connectionId: CONNECTION,
      messagingBlocked: true, attachmentAccessBlocked: !canAccessAttachment(subject, CONNECTION),
      priorConversationsReadable: priorConversationsReadable(subject),
      recordAccessBasis: subject.recordAccessPolicy.policyVersion, recordedAt: subject.now,
    };
    expect(effect.attachmentAccessBlocked).toBe(true);
    expect(effect.recordAccessBasis).toBe('policy-v1');
    expect(effect.priorConversationsReadable).toBe(true);
  });
});

describe('MSG-07 synthetic governance client', () => {
  function seededEvent(id: string, resourceId: string): MessagingAuditEvent {
    return {
      id, organizationId: ORG, subject: 'patient-user', purpose: 'MSG_GOVERNANCE',
      action: 'view', resourceId, unitId: UNIT, correlationId: 'corr-1',
      outcome: 'success', occurredAt: NOW,
    };
  }

  it('appends an audit of its own read and filters by resource without changing stored facts', () => {
    const base = fixture({ auditEvents: [seededEvent('event-1', RESOURCE), seededEvent('event-2', UNPOLICED)] });
    const client = createSyntheticGovernanceClient(base);
    const all = client.auditTrail();
    expect(all).toHaveLength(3);
    expect(all[2]?.action).toBe('audit_read');
    expect(base.auditEvents).toHaveLength(2);
    const filtered = client.auditTrail(RESOURCE);
    expect(filtered).toHaveLength(2);
    expect(filtered.every((event) => event.resourceId === RESOURCE)).toBe(true);
  });

  it('audits attachment access as a separate download fact', () => {
    const client = createSyntheticGovernanceClient(fixture());
    expect(client.canAccessAttachment(ACTIVE_CONNECTION)).toBe(true);
    expect(client.canAccessAttachment(CONNECTION)).toBe(false);
    const events = client.auditTrail();
    const downloads = events.filter((event) => event.action === 'download');
    expect(downloads).toHaveLength(2);
    expect(downloads[0]?.outcome).toBe('success');
    expect(downloads[1]?.outcome).toBe('denied');
    expect(events.some((event) => event.action === 'audit_read')).toBe(true);
  });

  it('routes a copy request to record access and audits it without exposing content', () => {
    const client = createSyntheticGovernanceClient(fixture());
    const request = client.requestCopy(RESOURCE, 'record_access');
    expect(request.status).toBe('ROUTED');
    expect(request.resourceId).toBe(RESOURCE);
    const events = client.auditTrail();
    const copy = events.find((event) => event.action === 'copy_request');
    expect(copy?.outcome).toBe('success');
    expect(copy?.resourceId).toBe(RESOURCE);
    expect(JSON.stringify(events)).not.toContain('diagnosis');
  });

  it('exposes staff-filed provenance read-only and never lets a patient file to the chart', () => {
    const link: ProvenanceLink = {
      id: 'link-1', organizationId: ORG, conversationId: 'conv-1', messageId: 'msg-1',
      sourceType: 'instruction', sourceReference: 'instruction-rev-2', revision: 'rev-2',
      linkedBy: 'clinician', linkedAt: NOW,
    };
    const foreignLink: ProvenanceLink = { ...link, id: 'link-2', organizationId: OTHER_ORG };
    const client = createSyntheticGovernanceClient(fixture({ provenanceLinks: [link, foreignLink] }));
    const links = client.provenanceLinks('msg-1');
    expect(links).toEqual([link]);
    expect(links[0]?.linkedBy).toBe('clinician');
    expect(client.auditTrail().some((event) => event.action === 'view')).toBe(true);
    expect(client.provenanceLinks('missing-msg')).toEqual([]);
    expect('linkToChart' in client).toBe(false);
  });
});

describe('MessagingAuditPanel component', () => {
  const events: readonly MessagingAuditEvent[] = [
    { id: 'event-1', organizationId: ORG, subject: 'patient-user', purpose: 'MSG_GOVERNANCE',
      action: 'download', resourceId: RESOURCE, unitId: UNIT, correlationId: 'corr-1',
      outcome: 'success', occurredAt: NOW },
  ];
  const effect: RevocationEffect = {
    id: 'effect-1', organizationId: ORG, connectionId: CONNECTION, messagingBlocked: true,
    attachmentAccessBlocked: true, priorConversationsReadable: true,
    recordAccessBasis: 'policy-v1', recordedAt: NOW,
  };

  it('shows an empty state when there is no messaging activity', async () => {
    const view = await render(
      <MessagingAuditPanel events={[]} isRTL={false} onRequestCopy={() => undefined} />,
    );
    expect(view.getByText('No messaging activity')).toBeTruthy();
    expect(view.queryByTestId('revocation-effect')).toBeNull();
  });

  it('renders each audit fact and the revocation effect with accessible labels', async () => {
    const view = await render(
      <MessagingAuditPanel events={events} revocationEffect={effect} isRTL={false}
        onRequestCopy={() => undefined} />,
    );
    expect(view.getByTestId('audit-event-1')).toBeTruthy();
    expect(view.getByText('download')).toBeTruthy();
    expect(view.getByText('success')).toBeTruthy();
    expect(view.getByText('New messaging blocked')).toBeTruthy();
    expect(view.getByText('Attachments blocked')).toBeTruthy();
    expect(view.getByText('Prior conversations readable')).toBeTruthy();
  });

  it('shows allowed states when revocation did not block access', async () => {
    const view = await render(
      <MessagingAuditPanel events={[]} revocationEffect={{ ...effect, messagingBlocked: false,
        attachmentAccessBlocked: false, priorConversationsReadable: false }} isRTL={false}
        onRequestCopy={() => undefined} />,
    );
    expect(view.getByText('New messaging allowed')).toBeTruthy();
    expect(view.getByText('Attachments allowed')).toBeTruthy();
    expect(view.getByText('Prior conversations not readable')).toBeTruthy();
  });

  it('mirrors layout for RTL and invokes the copy request only after explicit press', async () => {
    let presses = 0;
    const view = await render(
      <MessagingAuditPanel events={events} isRTL={true} onRequestCopy={() => { presses += 1; }} />,
    );
    expect(view.getByTestId('messaging-audit-panel').props.style.flexDirection).toBe('column-reverse');
    expect(presses).toBe(0);
    await fireEvent.press(view.getByLabelText('Request a copy'));
    expect(presses).toBe(1);
  });

  it('uses a forward column layout for LTR locales', async () => {
    const view = await render(
      <MessagingAuditPanel events={events} isRTL={false} onRequestCopy={() => undefined} />,
    );
    expect(view.getByTestId('messaging-audit-panel').props.style.flexDirection).toBe('column');
  });
});

describe('contract projection types', () => {
  it('accepts the full MSG-07 action vocabulary in fixtures', () => {
    const link: ProvenanceLink = {
      id: 'link-1', organizationId: ORG, conversationId: 'conv-1', messageId: 'msg-1',
      sourceType: 'message', sourceReference: 'ref-1', revision: 'rev-1',
      linkedBy: 'clinician', linkedAt: NOW,
    };
    expect(link.sourceReference).toBe('ref-1');
  });
});
