import { useCallback, useEffect, useRef, useState } from 'react';
import { useActiveOrganization } from '../../api/activeOrganization';
import type { TransportError } from '../../api/transport';
import { ApiPage } from '../../components/ApiPage';
import { Button } from '../../components/Button';
import { Form } from '../../components/Form';
import { FormField } from '../../components/FormField';
import { MixedScriptText } from '../../components/MixedScriptText';
import { StateView } from '../../components/StateViews';
import { useI18n } from '../../i18n/I18nProvider';
import type { Locale } from '../../i18n/messages';
import { useTheme } from '../../theme/ThemeProvider';
import { useBreakpoint } from '../../theme/useBreakpoint';
import {
  asAdminTransportError,
  deactivateStaff,
  getStaffHistory,
  inviteStaff,
  listStaff,
  staffFieldForCode,
  toStaffHistoryEvents,
  validateInvite,
  validationKeyForField,
} from './api';
import type { StaffMemberResponse, StaffPageResponse } from './api';
import { FieldErrorSummary } from './FieldErrorSummary';
import type { FieldErrorLink } from './FieldErrorSummary';
import { PageSink } from './PageSink';
import { StaffHistoryView } from './StaffHistoryView';
import { adminMessage, adminText } from './messages';
import type { AdminMessageKey } from './messages';

/** Empty invitation draft reused after a successful invite. */
const EMPTY_INVITE = { subject: '', displayName: '', role: '' };

/**
 * Logical wrapping shared by every clinical table cell. Long English and Arabic
 * values wrap at 320 px and at 200 percent zoom instead of clipping or forcing a
 * page-level horizontal scroll; the value's unit and identifier stay together
 * in their isolated run (WEB-07, UX-01).
 */
const wrappingStyle = {
  minInlineSize: 0,
  overflowWrap: 'anywhere',
  whiteSpace: 'normal',
} as const;

/** Props for the tenant-keyed staff content. */
interface StaffContentProps {
  organizationId: string;
}

/** Build the summary links for one invitation validation result. */
function inviteErrorLinks(
  errors: ReturnType<typeof validateInvite>,
  locale: Locale,
): FieldErrorLink[] {
  const labels = {
    subject: adminText(locale, 'staffSubject'),
    displayName: adminText(locale, 'staffDisplayName'),
    role: adminText(locale, 'staffRole'),
  };
  const links: FieldErrorLink[] = [];
  if (errors.subject !== undefined) {
    links.push({ id: 'staff-subject', label: labels.subject, message: adminText(locale, errors.subject) });
  }
  if (errors.displayName !== undefined) {
    links.push({
      id: 'staff-displayName',
      label: labels.displayName,
      message: adminText(locale, errors.displayName),
    });
  }
  if (errors.role !== undefined) {
    links.push({ id: 'staff-role', label: labels.role, message: adminText(locale, errors.role) });
  }
  return links;
}

/** Props for the cursor-paginated, tenant-scoped staff list. */
interface StaffListProps {
  organizationId: string;
  locale: Locale;
  reloadToken: number;
  onHistory: (subject: string) => void;
  onDeactivate: (member: StaffMemberResponse) => void;
}

/** Render the staff table through `ApiPage` and append each resolved page. */
function StaffList({
  organizationId,
  locale,
  reloadToken,
  onHistory,
  onDeactivate,
}: StaffListProps) {
  const { tokens } = useTheme();
  const breakpoint = useBreakpoint();
  const [cursor, setCursor] = useState<string | undefined>(undefined);
  const [accumulated, setAccumulated] = useState<readonly StaffMemberResponse[]>([]);

  const append = useCallback((page: StaffPageResponse) => {
    setAccumulated((current) => [...current, ...page.items]);
  }, []);

  useEffect(() => {
    setAccumulated([]);
    setCursor(undefined);
  }, [reloadToken]);

  const label = adminText(locale, 'staffAdministration');
  return (
    <ApiPage
      resource={{
        label,
        load: () => listStaff(cursor, undefined, organizationId),
        deps: [cursor, reloadToken],
      }}
      isEmpty={(page) => page.items.length === 0}
    >
      {(page) => {
        const next = page.nextCursor;
        // One table at every band: no clinical column is added, removed or
        // hidden. Only the labelled, keyboard-scrollable wrapper is compact-only
        // so the page never scrolls horizontally on a phone (WEB-07, UX-01).
        const table = (
          <table data-breakpoint={breakpoint} style={{ borderCollapse: 'collapse', inlineSize: '100%' }}>
            <caption style={{ textAlign: 'start', paddingBlock: tokens.spacing.sm }}>{label}</caption>
            <thead>
              <tr>
                <th scope="col" style={wrappingStyle}>
                  {adminText(locale, 'staffSubject')}
                </th>
                <th scope="col" style={wrappingStyle}>
                  {adminText(locale, 'staffDisplayName')}
                </th>
                <th scope="col" style={wrappingStyle}>
                  {adminText(locale, 'status')}
                </th>
                <th scope="col" style={wrappingStyle}>
                  {adminText(locale, 'roles')}
                </th>
                <th scope="col" style={wrappingStyle}>
                  {adminText(locale, 'actions')}
                </th>
              </tr>
            </thead>
            <tbody>
              {accumulated.map((item) => (
                <tr key={item.subject}>
                  <th scope="row" style={wrappingStyle}>
                    <MixedScriptText text={item.subject} lang="en" />
                  </th>
                  <td style={wrappingStyle}>
                    <MixedScriptText text={item.displayName} lang={locale} />
                  </td>
                  <td style={wrappingStyle}>
                    <MixedScriptText text={item.status} lang="en" />
                  </td>
                  <td style={wrappingStyle}>
                    <MixedScriptText text={item.roles.join(', ')} lang="en" />
                  </td>
                  <td style={wrappingStyle}>
                    <Button onClick={() => onHistory(item.subject)}>
                      {adminText(locale, 'history')}
                    </Button>{' '}
                    {item.status === 'active' && (
                      <Button onClick={() => onDeactivate(item)}>
                        {adminText(locale, 'deactivate')}
                      </Button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        );
        return (
          <>
            <PageSink page={page} onPage={append} />
            {breakpoint === 'compact' ? (
              <div
                className="table-region"
                role="region"
                aria-label={label}
                tabIndex={0}
                data-breakpoint={breakpoint}
              >
                {table}
              </div>
            ) : (
              table
            )}
            {next !== null && (
              <Button onClick={() => setCursor(next)}>{adminText(locale, 'loadMore')}</Button>
            )}
          </>
        );
      }}
    </ApiPage>
  );
}

/** Props for one subject's immutable history panel. */
interface StaffHistoryPanelProps {
  subject: string;
  organizationId: string;
  locale: Locale;
  onClose: () => void;
}

/** Load immutable history through `ApiPage` and reuse the existing history view. */
function StaffHistoryPanel({ subject, organizationId, locale, onClose }: StaffHistoryPanelProps) {
  const { tokens } = useTheme();
  const breakpoint = useBreakpoint();
  return (
    <section
      aria-label={adminText(locale, 'history')}
      className="stack"
      data-part="history-panel"
      data-breakpoint={breakpoint}
      style={{ gap: tokens.spacing.md }}
    >
      <Button onClick={onClose}>{adminText(locale, 'closeHistory')}</Button>
      <ApiPage
        resource={{
          label: adminText(locale, 'staffHistory'),
          load: () => getStaffHistory(subject, organizationId),
          deps: [subject],
        }}
        isEmpty={(view) => view.memberships.length + view.roleGrants.length === 0}
      >
        {(view) => (
          <StaffHistoryView
            events={toStaffHistoryEvents(view, organizationId, locale)}
            organizationId={organizationId}
            subject={view.subject}
            reasonLang={locale}
          />
        )}
      </ApiPage>
    </section>
  );
}

/** Invitation, deactivation and history commands for one active organization. */
function StaffContent({ organizationId }: StaffContentProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const breakpoint = useBreakpoint();
  const [invite, setInvite] = useState(EMPTY_INVITE);
  const [inviteErrors, setInviteErrors] = useState<ReturnType<typeof validateInvite>>({});
  const [inviteError, setInviteError] = useState<TransportError | undefined>(undefined);
  const [invitedName, setInvitedName] = useState<string | undefined>(undefined);
  const [reloadToken, setReloadToken] = useState(0);
  const [confirming, setConfirming] = useState<StaffMemberResponse | undefined>(undefined);
  const [reason, setReason] = useState('');
  const [reasonError, setReasonError] = useState<AdminMessageKey | undefined>(undefined);
  const [deactivateError, setDeactivateError] = useState<TransportError | undefined>(undefined);
  const [deactivated, setDeactivated] = useState<{ name: string; reason: string } | undefined>(
    undefined,
  );
  const [historySubject, setHistorySubject] = useState<string | undefined>(undefined);
  const cancelRef = useRef<HTMLButtonElement>(null);

  // The safe choice takes initial focus in the destructive confirmation.
  useEffect(() => {
    if (confirming !== undefined) {
      cancelRef.current?.focus();
    }
  }, [confirming]);

  async function handleInvite() {
    setInviteError(undefined);
    setInvitedName(undefined);
    const errors = validateInvite(invite);
    setInviteErrors(errors);
    if (Object.keys(errors).length > 0) {
      return;
    }
    try {
      const created = await inviteStaff(invite, organizationId);
      setInvite(EMPTY_INVITE);
      setInviteErrors({});
      setInvitedName(created.displayName);
      setReloadToken((value) => value + 1);
    } catch (failure: unknown) {
      const error = asAdminTransportError(failure);
      if (error.kind === 'invalid') {
        const field = staffFieldForCode(error.code);
        if (field !== undefined) {
          setInviteErrors({ [field]: validationKeyForField(field) });
        } else {
          setInviteError(error);
        }
      } else {
        setInviteError(error);
      }
    }
  }

  function openConfirmation(member: StaffMemberResponse) {
    setConfirming(member);
    setReason('');
    setReasonError(undefined);
    setDeactivateError(undefined);
  }

  async function handleConfirm(subject: string, displayName: string) {
    if (reason.trim() === '') {
      setReasonError('deactivateReasonRequired');
      return;
    }
    setReasonError(undefined);
    setDeactivateError(undefined);
    try {
      await deactivateStaff(subject, organizationId);
      setDeactivated({ name: displayName, reason });
      setConfirming(undefined);
      setReloadToken((value) => value + 1);
    } catch (failure: unknown) {
      setDeactivateError(asAdminTransportError(failure));
    }
  }

  const subjectError = inviteErrors.subject === undefined ? undefined : adminText(locale, inviteErrors.subject);
  const displayNameError =
    inviteErrors.displayName === undefined ? undefined : adminText(locale, inviteErrors.displayName);
  const roleError = inviteErrors.role === undefined ? undefined : adminText(locale, inviteErrors.role);
  const reasonErrorText = reasonError === undefined ? undefined : adminText(locale, reasonError);

  const reasonLinks: FieldErrorLink[] =
    reasonError === undefined
      ? []
      : [
          {
            id: 'deactivate-reason',
            label: adminText(locale, 'deactivateReason'),
            message: adminText(locale, reasonError),
          },
        ];

  return (
    <div className="page" data-part="staff-page" data-breakpoint={breakpoint} style={{ gap: tokens.spacing.lg }}>
      <section
        aria-label={adminText(locale, 'inviteStaff')}
        className="stack"
        data-part="invite"
        style={{ gap: tokens.spacing.md }}
      >
        <h2>{adminText(locale, 'staffAdministration')}</h2>
        <p>{adminText(locale, 'staffIntro')}</p>
        <FieldErrorSummary title={adminText(locale, 'errorSummary')} errors={inviteErrorLinks(inviteErrors, locale)} />
        <Form
          label={adminText(locale, 'inviteStaff')}
          submitLabel={adminText(locale, 'inviteStaff')}
          onSubmit={handleInvite}
        >
          <div
            className={breakpoint === 'compact' ? 'stack' : 'grid-2'}
            data-part="invite-fields"
            data-layout={breakpoint === 'compact' ? 'single-column' : 'two-column'}
            style={{ gap: tokens.spacing.md }}
          >
            <FormField
              id="staff-subject"
              label={adminText(locale, 'staffSubject')}
              value={invite.subject}
              onChange={(value) => setInvite((current) => ({ ...current, subject: value }))}
              {...(subjectError === undefined ? {} : { error: subjectError })}
            />
            <FormField
              id="staff-displayName"
              label={adminText(locale, 'staffDisplayName')}
              value={invite.displayName}
              onChange={(value) => setInvite((current) => ({ ...current, displayName: value }))}
              {...(displayNameError === undefined ? {} : { error: displayNameError })}
            />
            <FormField
              id="staff-role"
              label={adminText(locale, 'staffRole')}
              value={invite.role}
              onChange={(value) => setInvite((current) => ({ ...current, role: value }))}
              {...(roleError === undefined ? {} : { error: roleError })}
            />
          </div>
        </Form>
        <p role="status" data-testid="staff-status">
          <MixedScriptText
            text={invitedName === undefined ? '' : adminMessage(locale, 'staffInvited', { name: invitedName })}
            lang={locale}
          />
        </p>
      </section>
      {inviteError !== undefined && (
        <div data-testid="staff-command-error">{StateView.forTransportError(inviteError)}</div>
      )}
      {deactivated !== undefined && (
        <p role="status" data-testid="staff-deactivation-status">
          <MixedScriptText
            text={adminMessage(locale, 'deactivated', {
              name: deactivated.name,
              reason: deactivated.reason,
            })}
            lang={locale}
          />
        </p>
      )}
      <StaffList
        organizationId={organizationId}
        locale={locale}
        reloadToken={reloadToken}
        onHistory={setHistorySubject}
        onDeactivate={openConfirmation}
      />
      {confirming !== undefined && (
        <section
          role="dialog"
          aria-label={adminMessage(locale, 'deactivateTitle', { name: confirming.displayName })}
          className="stack"
          data-part="deactivate-dialog"
          data-breakpoint={breakpoint}
          style={{ gap: tokens.spacing.md }}
        >
          <h3>{adminMessage(locale, 'deactivateTitle', { name: confirming.displayName })}</h3>
          <p>{adminText(locale, 'deactivateDetail')}</p>
          <FieldErrorSummary title={adminText(locale, 'errorSummary')} errors={reasonLinks} />
          <FormField
            id="deactivate-reason"
            label={adminText(locale, 'deactivateReason')}
            value={reason}
            onChange={setReason}
            {...(reasonErrorText === undefined ? {} : { error: reasonErrorText })}
          />
          {deactivateError !== undefined && StateView.forTransportError(deactivateError)}
          <div className="cluster" data-part="dialog-actions">
            <Button variant="secondary" buttonRef={cancelRef} onClick={() => setConfirming(undefined)}>
              {adminText(locale, 'cancel')}
            </Button>{' '}
            <Button onClick={() => handleConfirm(confirming.subject, confirming.displayName)}>
              {adminText(locale, 'confirm')}
            </Button>
          </div>
        </section>
      )}
      {historySubject !== undefined && (
        <StaffHistoryPanel
          key={`${organizationId}:${historySubject}`}
          subject={historySubject}
          organizationId={organizationId}
          locale={locale}
          onClose={() => setHistorySubject(undefined)}
        />
      )}
    </div>
  );
}

/** API-backed staff page; the tenant key prevents cross-tenant state. */
export function StaffPage() {
  const organizationId = useActiveOrganization();
  return <StaffContent key={organizationId} organizationId={organizationId} />;
}
