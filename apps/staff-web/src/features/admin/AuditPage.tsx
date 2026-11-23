import { useCallback, useState } from 'react';
import type { Dispatch, SetStateAction } from 'react';
import { useActiveOrganization } from '../../api/activeOrganization';
import { ApiPage } from '../../components/ApiPage';
import { Button } from '../../components/Button';
import { Form } from '../../components/Form';
import { FormField } from '../../components/FormField';
import { useI18n } from '../../i18n/I18nProvider';
import type { Locale } from '../../i18n/messages';
import { useBreakpoint } from '../../theme/useBreakpoint';
import { auditEventsFromPage, isValidUtcInstant, queryAuditEvents } from './api';
import type { AuditEventResponse, AuditFilters } from './api';

import { AuditEventList } from './AuditEventList';
import { FieldErrorSummary } from './FieldErrorSummary';
import type { FieldErrorLink } from './FieldErrorSummary';
import { syntheticTimeZone } from '../../fixtures/synthetic';
import { adminMessage, adminText } from './messages';
import type { AdminMessageKey } from './messages';
import { PageSink } from './PageSink';

/** Empty form reused after a successful submit or a filter reset. */
const EMPTY_FORM = { action: '', outcome: '', resourceType: '', from: '', to: '' };

/** Validation result for the two instant filters. */
type FilterErrors = Partial<Record<'from' | 'to', AdminMessageKey>>;

/** Props for the tenant-keyed audit content. */
interface AuditContentProps {
  organizationId: string;
}

/** Props for the explicit no-events state. */
interface AuditEmptyProps {
  locale: Locale;
  onClear: () => void;
}

/** A clear empty state that explains the filters and offers the next action. */
function AuditEmpty({ locale, onClear }: AuditEmptyProps) {
  return (
    <section
      role="status"
      aria-label={adminText(locale, 'auditNoEvents')}
      className="stack"
      data-audit-part="empty"
    >
      <h3>{adminText(locale, 'auditNoEvents')}</h3>
      <p>{adminText(locale, 'auditNoEventsHint')}</p>
      <Button onClick={onClear}>{adminText(locale, 'auditClearFilters')}</Button>
    </section>
  );
}

type AuditForm = typeof EMPTY_FORM;

/** Props for the filter form section. */
interface AuditFilterSectionProps {
  form: AuditForm;
  setForm: Dispatch<SetStateAction<AuditForm>>;
  filterErrors: FilterErrors;
  setFilterErrors: (errors: FilterErrors) => void;
  breakpoint: ReturnType<typeof useBreakpoint>;
  onApply: () => void;
  onClear: () => void;
}

/** Filter form with its error summary and reset action. */
function AuditFilterSection({
  form,
  setForm,
  filterErrors,
  setFilterErrors,
  breakpoint,
  onApply,
  onClear,
}: AuditFilterSectionProps) {
  const { locale } = useI18n();
  const errorLinks: FieldErrorLink[] = [];
  if (filterErrors.from !== undefined) {
    errorLinks.push({
      id: 'audit-from',
      label: adminText(locale, 'auditFrom'),
      message: adminText(locale, filterErrors.from),
    });
  }
  if (filterErrors.to !== undefined) {
    errorLinks.push({
      id: 'audit-to',
      label: adminText(locale, 'auditTo'),
      message: adminText(locale, filterErrors.to),
    });
  }
  const fromError =
    filterErrors.from === undefined ? undefined : adminText(locale, filterErrors.from);
  const toError = filterErrors.to === undefined ? undefined : adminText(locale, filterErrors.to);

  // The grid stacks on a phone and gains a second column from the medium
  // breakpoint; the flag is exposed for jsdom, which cannot evaluate media
  // queries. Layout never hides a filter or an audit value.
  const fieldsLayout = breakpoint === 'compact' ? 'stacked' : 'two-column';

  return (
    <section
      aria-label={adminText(locale, 'auditFilters')}
      className="stack"
      data-audit-part="filters"
    >
      <h2>{adminText(locale, 'auditPageTitle')}</h2>
      <FieldErrorSummary title={adminText(locale, 'errorSummary')} errors={errorLinks} />
      <Form
        label={adminText(locale, 'auditFilters')}
        submitLabel={adminText(locale, 'auditApplyFilters')}
        onSubmit={onApply}
      >
        <div className="grid-2" data-audit-part="filter-fields" data-audit-fields={fieldsLayout}>
          <FormField
            id="audit-action"
            label={adminText(locale, 'auditActionFilter')}
            value={form.action}
            onChange={(value) => setForm((current) => ({ ...current, action: value }))}
          />
          <FormField
            id="audit-outcome"
            label={adminText(locale, 'auditOutcomeFilter')}
            value={form.outcome}
            onChange={(value) => setForm((current) => ({ ...current, outcome: value }))}
          />
          <FormField
            id="audit-resource"
            label={adminText(locale, 'auditResourceFilter')}
            value={form.resourceType}
            onChange={(value) => setForm((current) => ({ ...current, resourceType: value }))}
          />
          <FormField
            id="audit-from"
            label={adminText(locale, 'auditFrom')}
            value={form.from}
            onChange={(value) => {
              setForm((current) => ({ ...current, from: value }));
              setFilterErrors({});
            }}
            placeholder="2026-03-01T00:00:00Z"
            {...(fromError === undefined ? {} : { error: fromError })}
          />
          <FormField
            id="audit-to"
            label={adminText(locale, 'auditTo')}
            value={form.to}
            onChange={(value) => {
              setForm((current) => ({ ...current, to: value }));
              setFilterErrors({});
            }}
            placeholder="2026-03-08T00:00:00Z"
            {...(toError === undefined ? {} : { error: toError })}
          />
        </div>
      </Form>
      <div className="cluster" data-audit-part="filter-actions">
        <Button variant="secondary" onClick={onClear}>
          {adminText(locale, 'auditClearFilters')}
        </Button>
      </div>
    </section>
  );
}

/**
 * Filter form, cursor paging and tenant-scoped audit events for one organization.
 *
 * Width is presentation only: the page grid keeps logical safe-area padding,
 * the filter fields stack on a phone and use two columns from medium upward,
 * and every audit value stays in the tree with wrapping room. No field or
 * column is hidden, so keyboard order and the tenant boundary are unchanged
 * across breakpoints (WEB-07, UX-01).
 */
function AuditContent({ organizationId }: AuditContentProps) {
  const { locale } = useI18n();
  const breakpoint = useBreakpoint();
  const [form, setForm] = useState(EMPTY_FORM);
  const [applied, setApplied] = useState<AuditFilters>({});
  const [filterErrors, setFilterErrors] = useState<FilterErrors>({});
  const [cursor, setCursor] = useState<string | undefined>(undefined);
  const [accumulated, setAccumulated] = useState<readonly AuditEventResponse[]>([]);

  // A stable callback keeps one resolved page from being appended twice by a
  // re-render (for example a locale change after the page was loaded).
  const append = useCallback((page: { events?: readonly AuditEventResponse[] }) => {
    setAccumulated((current) => [...current, ...(page.events ?? [])]);
  }, []);

  function applyFilters() {
    const errors: FilterErrors = {};
    const from = form.from.trim();
    const to = form.to.trim();
    if (from !== '' && !isValidUtcInstant(from)) {
      errors.from = 'invalidAuditInstant';
    }
    if (to !== '' && !isValidUtcInstant(to)) {
      errors.to = 'invalidAuditInstant';
    }
    setFilterErrors(errors);
    if (Object.keys(errors).length > 0) {
      return;
    }
    // A new filter set is a different result set: clear the earlier rows and
    // the old cursor in the same update so only one request is issued.
    setApplied({
      ...(form.action.trim() === '' ? {} : { action: form.action.trim() }),
      ...(form.outcome.trim() === '' ? {} : { outcome: form.outcome.trim() }),
      ...(form.resourceType.trim() === '' ? {} : { resourceType: form.resourceType.trim() }),
      ...(from === '' ? {} : { from }),
      ...(to === '' ? {} : { to }),
    });
    setAccumulated([]);
    setCursor(undefined);
  }

  function clearFilters() {
    setForm(EMPTY_FORM);
    setFilterErrors({});
    setApplied({});
    setAccumulated([]);
    setCursor(undefined);
  }

  return (
    <div className="page" data-audit-page="root" data-breakpoint={breakpoint}>
      <AuditFilterSection
        form={form}
        setForm={setForm}
        filterErrors={filterErrors}
        setFilterErrors={setFilterErrors}
        breakpoint={breakpoint}
        onApply={applyFilters}
        onClear={clearFilters}
      />

      <ApiPage
        resource={{
          label: adminText(locale, 'auditEvents'),
          load: () =>
            queryAuditEvents(
              { ...applied, ...(cursor === undefined ? {} : { cursor }) },
              organizationId,
            ),
          deps: [applied, cursor, organizationId],
        }}
      >
        {(page) => {
          const next = page.nextCursor;
          return (
            <div className="stack" data-audit-part="events">
              <PageSink page={page} onPage={append} />
              <p>{adminMessage(locale, 'auditFacilityZone', { zone: syntheticTimeZone })}</p>
              {accumulated.length === 0 ? (
                <AuditEmpty locale={locale} onClear={clearFilters} />
              ) : (
                <>
                  <AuditEventList
                    events={auditEventsFromPage({ events: accumulated }, organizationId, locale)}
                    organizationId={organizationId}
                    showChain={false}
                    timeZone={syntheticTimeZone}
                  />
                  {next !== null && next !== undefined ? (
                    <div className="cluster" data-audit-part="paging">
                      <Button onClick={() => setCursor(next)}>
                        {adminText(locale, 'loadMore')}
                      </Button>
                    </div>
                  ) : null}
                </>
              )}
            </div>
          );
        }}
      </ApiPage>
    </div>
  );
}

/** API-backed audit page; the tenant key prevents cross-tenant state. */
export function AuditPage() {
  const organizationId = useActiveOrganization();
  return <AuditContent key={organizationId} organizationId={organizationId} />;
}
