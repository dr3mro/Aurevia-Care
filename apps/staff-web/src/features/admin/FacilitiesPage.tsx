import { useCallback, useEffect, useState } from 'react';
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
import { useBreakpoint } from '../../theme/useBreakpoint';
import { AdminTableRegion } from './AdminTableRegion';
import {
  asAdminTransportError,
  createFacility,
  facilityFieldForCode,
  listFacilities,
  validateFacility,
  validationKeyForField,
} from './api';
import type { FacilityPageResponse, FacilityResponse } from './api';
import { FieldErrorSummary } from './FieldErrorSummary';
import type { FieldErrorLink } from './FieldErrorSummary';
import { PageSink } from './PageSink';
import { adminMessage, adminText } from './messages';

/** Empty draft used both initially and after a successful create. */
const EMPTY_DRAFT = { code: '', name: '' };

/** Props for the tenant-keyed facilities content. */
interface FacilitiesContentProps {
  organizationId: string;
}

/** Build the summary links for one facility validation result. */
function facilityErrorLinks(
  errors: ReturnType<typeof validateFacility>,
  locale: Locale,
): FieldErrorLink[] {
  const links: FieldErrorLink[] = [];
  if (errors.code !== undefined) {
    links.push({
      id: 'facility-code',
      label: adminText(locale, 'facilityCode'),
      message: adminText(locale, errors.code),
    });
  }
  if (errors.name !== undefined) {
    links.push({
      id: 'facility-name',
      label: adminText(locale, 'facilityName'),
      message: adminText(locale, errors.name),
    });
  }
  return links;
}

/** Props for the cursor-paginated, tenant-scoped facility list. */
interface FacilitiesListProps {
  organizationId: string;
  locale: Locale;
  /** Changed after a successful command to request a fresh first page. */
  reloadToken: number;
}

/**
 * Render the facility table through `ApiPage` and append each resolved page.
 *
 * The table keeps its real `table`, `caption` and `th scope` semantics at every
 * width; the caller-owned `AdminTableRegion` supplies the labelled, keyboard
 * scroll region on phones, so no clinical column is hidden. Logical inline
 * properties let long English and Arabic values wrap without a page-level
 * horizontal scroll. The active band is exposed as `data-breakpoint` because
 * jsdom cannot evaluate the real media queries (WEB-07, UX-01).
 */
function FacilitiesList({ organizationId, locale, reloadToken }: FacilitiesListProps) {
  const breakpoint = useBreakpoint();
  const [cursor, setCursor] = useState<string | undefined>(undefined);
  const [accumulated, setAccumulated] = useState<readonly FacilityResponse[]>([]);

  const append = useCallback((page: FacilityPageResponse) => {
    setAccumulated((current) => [...current, ...page.items]);
  }, []);

  // A command changes the underlying collection: drop every earlier page and
  // the old cursor so the next request reads the first page only.
  useEffect(() => {
    setAccumulated([]);
    setCursor(undefined);
  }, [reloadToken]);

  return (
    <ApiPage
      resource={{
        label: adminText(locale, 'facilities'),
        load: () => listFacilities(cursor, organizationId),
        deps: [cursor, reloadToken],
      }}
      isEmpty={(page) => page.items.length === 0}
    >
      {(page) => {
        const next = page.nextCursor;
        return (
          <>
            <PageSink page={page} onPage={append} />
            <AdminTableRegion label={adminText(locale, 'facilities')}>
              <table
                data-breakpoint={breakpoint}
                style={{ borderCollapse: 'collapse', inlineSize: '100%' }}
              >
                <caption style={{ textAlign: 'start' }}>
                  {adminText(locale, 'facilities')}
                </caption>
                <thead>
                  <tr>
                    <th scope="col">{adminText(locale, 'facilityCode')}</th>
                    <th scope="col">{adminText(locale, 'facilityName')}</th>
                    <th scope="col">{adminText(locale, 'timeZone')}</th>
                  </tr>
                </thead>
                <tbody>
                  {accumulated.map((item) => (
                    <tr key={`${item.organizationId}:${item.code}`}>
                      <th scope="row">
                        <MixedScriptText text={item.code} lang="en" />
                      </th>
                      <td>
                        <MixedScriptText text={item.name} lang={locale} />
                      </td>
                      <td>
                        <MixedScriptText text={item.timeZone ?? 'UTC'} lang="en" />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </AdminTableRegion>
            {next !== null && (
              <Button onClick={() => setCursor(next)}>{adminText(locale, 'loadMore')}</Button>
            )}
          </>
        );
      }}
    </ApiPage>
  );
}

/**
 * Form, command handling and live list for one active organization.
 *
 * The whole page is one logical `.page` grid so it occupies a single full-width
 * column on phones and centres to the shared reading width on larger viewports;
 * the form and its fields use the shared `.stack` / `.grid-2` utilities so they
 * stack on phones and sit side by side from the medium band up. Direction stays
 * with the document — no subtree overrides it, so the same markup mirrors under
 * right-to-left. The active band is exposed as `data-breakpoint` because jsdom
 * cannot evaluate the real media queries (WEB-07, UX-01).
 */
function FacilitiesContent({ organizationId }: FacilitiesContentProps) {
  const { locale } = useI18n();
  const breakpoint = useBreakpoint();
  const [draft, setDraft] = useState(EMPTY_DRAFT);
  const [fieldErrors, setFieldErrors] = useState<ReturnType<typeof validateFacility>>({});
  const [commandError, setCommandError] = useState<TransportError | undefined>(undefined);
  const [createdName, setCreatedName] = useState<string | undefined>(undefined);
  const [reloadToken, setReloadToken] = useState(0);

  async function handleSubmit() {
    setCommandError(undefined);
    setCreatedName(undefined);
    const errors = validateFacility(draft);
    setFieldErrors(errors);
    if (Object.keys(errors).length > 0) {
      return;
    }
    try {
      const created = await createFacility(draft, organizationId);
      setDraft(EMPTY_DRAFT);
      setFieldErrors({});
      setCreatedName(created.name);
      setReloadToken((value) => value + 1);
    } catch (failure: unknown) {
      const error = asAdminTransportError(failure);
      if (error.kind === 'invalid') {
        const field = facilityFieldForCode(error.code);
        if (field !== undefined) {
          setFieldErrors({ [field]: validationKeyForField(field) });
        } else {
          setCommandError(error);
        }
      } else {
        setCommandError(error);
      }
    }
  }

  const codeError = fieldErrors.code === undefined ? undefined : adminText(locale, fieldErrors.code);
  const nameError = fieldErrors.name === undefined ? undefined : adminText(locale, fieldErrors.name);

  return (
    <div className="page" data-breakpoint={breakpoint}>
      <section aria-label={adminText(locale, 'createFacility')} className="stack">
        <h2>{adminText(locale, 'facilities')}</h2>
        <p>{adminText(locale, 'facilitiesIntro')}</p>
        <FieldErrorSummary
          title={adminText(locale, 'errorSummary')}
          errors={facilityErrorLinks(fieldErrors, locale)}
        />
        <Form
          label={adminText(locale, 'createFacility')}
          submitLabel={adminText(locale, 'createFacility')}
          onSubmit={handleSubmit}
        >
          <div className={breakpoint === 'compact' ? 'stack' : 'grid-2'}>
            <FormField
              id="facility-code"
              label={adminText(locale, 'facilityCode')}
              value={draft.code}
              onChange={(value) => setDraft((current) => ({ ...current, code: value }))}
              {...(codeError === undefined ? {} : { error: codeError })}
            />
            <FormField
              id="facility-name"
              label={adminText(locale, 'facilityName')}
              value={draft.name}
              onChange={(value) => setDraft((current) => ({ ...current, name: value }))}
              {...(nameError === undefined ? {} : { error: nameError })}
            />
          </div>
        </Form>
        {/* The summary is the single live announcement for a failed submit. */}
        <p role="status" data-testid="facilities-status">
          <MixedScriptText
            text={createdName === undefined ? '' : adminMessage(locale, 'facilityCreated', { name: createdName })}
            lang={locale}
          />
        </p>
      </section>
      {commandError !== undefined && (
        <div data-testid="facilities-command-error">{StateView.forTransportError(commandError)}</div>
      )}
      <FacilitiesList organizationId={organizationId} locale={locale} reloadToken={reloadToken} />
    </div>
  );
}

/** API-backed facilities page; the tenant key prevents cross-tenant state. */
export function FacilitiesPage() {
  const organizationId = useActiveOrganization();
  return <FacilitiesContent key={organizationId} organizationId={organizationId} />;
}
