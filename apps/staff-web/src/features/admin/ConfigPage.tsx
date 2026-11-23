import { useEffect, useRef, useState } from 'react';
import type { ReactNode, RefObject } from 'react';
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
import { useBreakpoint, usePointerCoarse } from '../../theme/useBreakpoint';
import { AdminTableRegion } from './AdminTableRegion';
import {
  approveConfiguration,
  asAdminTransportError,
  configurationVersionsFromHistory,
  defineCatalogEntry,
  defineConfiguration,
  getConfigurationHistory,
  isValidCatalogFamily,
  isValidConfigKey,
  listCatalogFamily,
} from './api';
import type { CatalogEntryResponse } from './api';
import { ConfigEditor } from './ConfigEditor';
import type { ConfigurationDraft } from './domain';
import { isValidCivilDate } from './domain';
import { adminAnchorCivilDate } from './fixtures';
import { FieldErrorSummary } from './FieldErrorSummary';
import type { FieldErrorLink } from './FieldErrorSummary';
import { adminMessage, adminText } from './messages';
import type { AdminMessageKey } from './messages';

/** Empty lookup input reused after a tenant switch. */
const EMPTY_LOOKUP = '';
/** Empty catalog definition draft. */
const EMPTY_CATALOG_DRAFT = { code: '', value: '', effectiveFrom: '' };

/** Validation result for one catalog definition form. */
type CatalogErrors = Partial<Record<'code' | 'value' | 'effectiveFrom', AdminMessageKey>>;

/** One pending approval waiting for explicit confirmation. */
interface PendingApproval {
  version: number;
  note: string;
}

/** Props for the tenant-keyed configuration content. */
interface ConfigContentProps {
  organizationId: string;
}

/** Props for the history list of one looked-up key. */
interface ConfigHistoryProps {
  configKey: string;
  organizationId: string;
  locale: Locale;
  reloadToken: number;
  onPropose: (draft: ConfigurationDraft) => unknown;
  onApprove: (version: number) => void;
}

/** Load and display one key's append-only history through the shared editor. */
function ConfigHistory({
  configKey,
  organizationId,
  locale,
  reloadToken,
  onPropose,
  onApprove,
}: ConfigHistoryProps) {
  return (
    <ApiPage
      resource={{
        label: adminText(locale, 'configurationVersions'),
        load: () => getConfigurationHistory(configKey, organizationId),
        deps: [configKey, organizationId, reloadToken],
      }}
    >
      {(view) => {
        const versions = configurationVersionsFromHistory(view, organizationId);
        return (
          <>
            {versions.length === 0 ? <p>{adminText(locale, 'noVersionsForKey')}</p> : null}
            <ConfigEditor
              key={`${organizationId}:${configKey}`}
              entries={versions}
              organizationId={organizationId}
              configKey={configKey}
              anchorCivilDate={adminAnchorCivilDate}
              onPropose={onPropose}
              onApprove={onApprove}
            />
          </>
        );
      }}
    </ApiPage>
  );
}

/** Props for the effective catalog family list. */
interface CatalogFamilyProps {
  family: string;
  organizationId: string;
  locale: Locale;
  reloadToken: number;
}

/** Load the effective entries of one catalog family. */
function CatalogFamily({ family, organizationId, locale, reloadToken }: CatalogFamilyProps) {
  const { tokens } = useTheme();
  return (
    <ApiPage
      resource={{
        label: adminText(locale, 'catalogEntries'),
        load: () => listCatalogFamily(family, organizationId),
        deps: [family, organizationId, reloadToken],
      }}
      isEmpty={(entries) => entries.length === 0}
    >
      {(entries: readonly CatalogEntryResponse[]) => (
        <AdminTableRegion label={adminText(locale, 'catalogEntries')}>
          <table style={{ borderCollapse: 'collapse', width: '100%' }}>
            <caption style={{ textAlign: 'start', paddingBlock: tokens.spacing.sm }}>
              {adminText(locale, 'catalogEntries')}
            </caption>
            <thead>
              <tr>
                <th scope="col">{adminText(locale, 'catalogCode')}</th>
                <th scope="col">{adminText(locale, 'catalogValue')}</th>
                <th scope="col">{adminText(locale, 'version')}</th>
              </tr>
            </thead>
            <tbody>
              {entries.map((item) => (
                <tr key={`${family}:${item.code ?? ''}:${item.version ?? 0}`}>
                  <th scope="row" style={{ minInlineSize: 0, overflowWrap: 'anywhere' }}>
                    <MixedScriptText text={item.code ?? ''} lang="en" />
                  </th>
                  <td style={{ minInlineSize: 0, overflowWrap: 'anywhere' }}>
                    <MixedScriptText text={item.value ?? ''} lang={locale} />
                  </td>
                  <td>{item.version ?? 0}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </AdminTableRegion>
      )}
    </ApiPage>
  );
}

/** Props for the catalog definition form and its tenant-scoped command. */
interface CatalogSectionProps {
  family: string;
  organizationId: string;
  locale: Locale;
  reloadToken: number;
  onReload: () => void;
}

/** Define a pending catalog entry and refresh the effective family list. */
function CatalogSection({
  family,
  organizationId,
  locale,
  reloadToken,
  onReload,
}: CatalogSectionProps) {
  const { tokens } = useTheme();
  const [draft, setDraft] = useState(EMPTY_CATALOG_DRAFT);
  const [errors, setErrors] = useState<CatalogErrors>({});
  const [commandError, setCommandError] = useState<TransportError | undefined>(undefined);
  const [defined, setDefined] = useState<string | undefined>(undefined);

  function errorLinks(): FieldErrorLink[] {
    const links: FieldErrorLink[] = [];
    if (errors.code !== undefined) {
      links.push({
        id: 'catalog-code',
        label: adminText(locale, 'catalogCode'),
        message: adminText(locale, errors.code),
      });
    }
    if (errors.value !== undefined) {
      links.push({
        id: 'catalog-value',
        label: adminText(locale, 'catalogValue'),
        message: adminText(locale, errors.value),
      });
    }
    if (errors.effectiveFrom !== undefined) {
      links.push({
        id: 'catalog-effective',
        label: adminText(locale, 'effectiveFrom'),
        message: adminText(locale, errors.effectiveFrom),
      });
    }
    return links;
  }

  async function handleSubmit() {
    const next: CatalogErrors = {};
    if (draft.code.trim() === '') {
      next.code = 'invalidDraft';
    }
    if (draft.value.trim() === '') {
      next.value = 'invalidDraft';
    }
    const date = draft.effectiveFrom.trim();
    if (date !== '' && !isValidCivilDate(date)) {
      next.effectiveFrom = 'invalidDraft';
    }
    setErrors(next);
    setDefined(undefined);
    setCommandError(undefined);
    if (Object.keys(next).length > 0) {
      return;
    }
    try {
      await defineCatalogEntry(
        family,
        {
          code: draft.code.trim(),
          value: draft.value.trim(),
          ...(date === '' ? {} : { effectiveFrom: `${date}T00:00:00Z` }),
        },
        organizationId,
      );
      setDraft(EMPTY_CATALOG_DRAFT);
      setDefined(adminMessage(locale, 'catalogDefined', { family, code: draft.code.trim() }));
      onReload();
    } catch (failure: unknown) {
      setCommandError(asAdminTransportError(failure));
    }
  }

  const codeError = errors.code === undefined ? undefined : adminText(locale, errors.code);
  const valueError = errors.value === undefined ? undefined : adminText(locale, errors.value);
  const dateError =
    errors.effectiveFrom === undefined ? undefined : adminText(locale, errors.effectiveFrom);

  return (
    <section
      aria-label={adminText(locale, 'defineCatalogEntry')}
      className="stack"
      style={{ gap: tokens.spacing.md }}
    >
      <h3>{adminText(locale, 'defineCatalogEntry')}</h3>
      <FieldErrorSummary title={adminText(locale, 'errorSummary')} errors={errorLinks()} />
      <Form
        label={adminText(locale, 'defineCatalogEntry')}
        submitLabel={adminText(locale, 'defineCatalogEntry')}
        onSubmit={() => {
          void handleSubmit();
        }}
      >
        <div className="grid-2" style={{ gap: tokens.spacing.md }}>
          <FormField
            id="catalog-code"
            label={adminText(locale, 'catalogCode')}
            value={draft.code}
            onChange={(value) => setDraft((current) => ({ ...current, code: value }))}
            {...(codeError === undefined ? {} : { error: codeError })}
          />
          <FormField
            id="catalog-value"
            label={adminText(locale, 'catalogValue')}
            value={draft.value}
            onChange={(value) => setDraft((current) => ({ ...current, value: value }))}
            {...(valueError === undefined ? {} : { error: valueError })}
          />
          <FormField
            id="catalog-effective"
            label={adminText(locale, 'catalogEffectiveFrom')}
            value={draft.effectiveFrom}
            onChange={(value) => setDraft((current) => ({ ...current, effectiveFrom: value }))}
            placeholder="2026-07-01"
            {...(dateError === undefined ? {} : { error: dateError })}
          />
        </div>
      </Form>
      <p role="status" data-testid="catalog-status">
        <MixedScriptText text={defined ?? ''} lang={locale} />
      </p>
      {commandError !== undefined ? StateView.forTransportError(commandError) : null}
      <CatalogFamily
        key={`${family}:${reloadToken}`}
        family={family}
        organizationId={organizationId}
        locale={locale}
        reloadToken={reloadToken}
      />
    </section>
  );
}

/** Props for the configuration key lookup section. */
interface ConfigLookupSectionProps {
  lookup: string;
  lookupError: AdminMessageKey | undefined;
  onChange: (value: string) => void;
  onSubmit: () => void;
}

/** Configuration key lookup form with its error summary. */
function ConfigLookupSection({
  lookup,
  lookupError,
  onChange,
  onSubmit,
}: ConfigLookupSectionProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const lookupLinks: FieldErrorLink[] =
    lookupError === undefined
      ? []
      : [
          {
            id: 'config-key',
            label: adminText(locale, 'configurationKeyLookup'),
            message: adminText(locale, lookupError),
          },
        ];
  const lookupMessage = lookupError === undefined ? undefined : adminText(locale, lookupError);
  return (
    <section
      aria-label={adminText(locale, 'configLookup')}
      className="stack"
      style={{ gap: tokens.spacing.md }}
    >
      <h2>{adminText(locale, 'configPageTitle')}</h2>
      <p>{adminText(locale, 'configLookupIntro')}</p>
      <FieldErrorSummary title={adminText(locale, 'errorSummary')} errors={lookupLinks} />
      <Form
        label={adminText(locale, 'configLookup')}
        submitLabel={adminText(locale, 'configLookup')}
        onSubmit={onSubmit}
      >
        <div className="stack" style={{ gap: tokens.spacing.md }}>
          <FormField
            id="config-key"
            label={adminText(locale, 'configurationKeyLookup')}
            value={lookup}
            onChange={onChange}
            {...(lookupMessage === undefined ? {} : { error: lookupMessage })}
          />
        </div>
      </Form>
    </section>
  );
}

/** Props for the catalog family lookup section. */
interface CatalogFamilySectionProps {
  family: string;
  familyError: AdminMessageKey | undefined;
  onChange: (value: string) => void;
  onSubmit: () => void;
  children?: ReactNode;
}

/** Catalog family lookup form; the resolved catalog renders as children. */
function CatalogFamilySection({
  family,
  familyError,
  onChange,
  onSubmit,
  children,
}: CatalogFamilySectionProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const familyLinks: FieldErrorLink[] =
    familyError === undefined
      ? []
      : [
          {
            id: 'catalog-family',
            label: adminText(locale, 'catalogFamily'),
            message: adminText(locale, familyError),
          },
        ];
  const familyMessage = familyError === undefined ? undefined : adminText(locale, familyError);
  return (
    <section className="stack" style={{ gap: tokens.spacing.md }}>
      <h3>{adminText(locale, 'catalogFamily')}</h3>
      <FieldErrorSummary title={adminText(locale, 'errorSummary')} errors={familyLinks} />
      <Form
        label={adminText(locale, 'catalogFamilyLookup')}
        submitLabel={adminText(locale, 'catalogFamilyLookup')}
        onSubmit={onSubmit}
      >
        <div className="stack" style={{ gap: tokens.spacing.md }}>
          <FormField
            id="catalog-family"
            label={adminText(locale, 'catalogFamily')}
            value={family}
            onChange={onChange}
            {...(familyMessage === undefined ? {} : { error: familyMessage })}
          />
        </div>
      </Form>
      {children}
    </section>
  );
}

/** Props for the irreversible approval confirmation dialog. */
interface ApprovalDialogProps {
  configKey: string;
  approval: PendingApproval;
  approveError: TransportError | undefined;
  cancelRef: RefObject<HTMLButtonElement | null>;
  onChange: (approval: PendingApproval | undefined) => void;
  onConfirm: () => void;
}

/** Approval confirmation with a note field; cancel takes initial focus. */
function ApprovalDialog({
  configKey,
  approval,
  approveError,
  cancelRef,
  onChange,
  onConfirm,
}: ApprovalDialogProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const title = adminMessage(locale, 'approveTitle', {
    key: configKey,
    version: String(approval.version),
  });
  return (
    <section role="dialog" aria-label={title} className="stack" style={{ gap: tokens.spacing.md }}>
      <h3>{title}</h3>
      <p>{adminText(locale, 'approveDetail')}</p>
      <FormField
        id="approval-note"
        label={adminText(locale, 'approvalNote')}
        value={approval.note}
        onChange={(note) => onChange({ version: approval.version, note })}
      />
      {approveError !== undefined ? StateView.forTransportError(approveError) : null}
      <div>
        <Button variant="secondary" buttonRef={cancelRef} onClick={() => onChange(undefined)}>
          {adminText(locale, 'cancel')}
        </Button>{' '}
        <Button onClick={onConfirm}>{adminText(locale, 'approveConfirm')}</Button>
      </div>
    </section>
  );
}

/**
 * Lookup, definition, approval and catalog commands for one organization.
 *
 * Layout uses only the shared responsive classes: the `page` root caps the
 * reading measure and maps the safe-area insets with logical padding, `stack`
 * stacks each section and wraps long values, and `grid-2` puts the short catalog
 * fields side by side from the medium band while leaving them stacked on phones.
 * The resolved layout class and pointer type are exposed as data attributes so
 * jsdom can assert the structure without evaluating media queries; direction is
 * never set here, so the same DOM mirrors under right-to-left.
 */
function ConfigContent({ organizationId }: ConfigContentProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const breakpoint = useBreakpoint();
  const coarsePointer = usePointerCoarse();
  const [lookup, setLookup] = useState(EMPTY_LOOKUP);
  const [lookupError, setLookupError] = useState<AdminMessageKey | undefined>(undefined);
  const [configKey, setConfigKey] = useState<string | undefined>(undefined);
  const [reloadToken, setReloadToken] = useState(0);
  const [defined, setDefined] = useState<string | undefined>(undefined);
  const [approved, setApproved] = useState<string | undefined>(undefined);
  const [commandError, setCommandError] = useState<TransportError | undefined>(undefined);
  const [approval, setApproval] = useState<PendingApproval | undefined>(undefined);
  const [approveError, setApproveError] = useState<TransportError | undefined>(undefined);
  const [family, setFamily] = useState(EMPTY_LOOKUP);
  const [appliedFamily, setAppliedFamily] = useState<string | undefined>(undefined);
  const [familyError, setFamilyError] = useState<AdminMessageKey | undefined>(undefined);
  const [catalogReloadToken, setCatalogReloadToken] = useState(0);
  const cancelRef = useRef<HTMLButtonElement>(null);

  // The safe choice takes initial focus in the irreversible approval dialog.
  useEffect(() => {
    if (approval !== undefined) {
      cancelRef.current?.focus();
    }
  }, [approval]);

  function handleLookup() {
    const trimmed = lookup.trim();
    if (!isValidConfigKey(trimmed)) {
      setLookupError('invalidConfigKey');
      return;
    }
    setLookupError(undefined);
    setConfigKey(trimmed);
    setDefined(undefined);
    setApproved(undefined);
  }

  function handleFamilyLookup() {
    const trimmed = family.trim();
    if (!isValidCatalogFamily(trimmed)) {
      setFamilyError('invalidCatalogFamily');
      return;
    }
    setFamilyError(undefined);
    setAppliedFamily(trimmed);
  }

  async function handlePropose(key: string, draft: ConfigurationDraft): Promise<boolean> {
    setCommandError(undefined);
    setDefined(undefined);
    try {
      await defineConfiguration(
        {
          key,
          value: draft.value,
          effectiveFrom: `${draft.effectiveFrom}T00:00:00Z`,
        },
        organizationId,
      );
      setDefined(adminMessage(locale, 'defineVersion', { key }));
      setReloadToken((value) => value + 1);
      return true;
    } catch (failure: unknown) {
      setCommandError(asAdminTransportError(failure));
      return false;
    }
  }

  function openApproval(version: number) {
    setApproval({ version, note: '' });
    setApproveError(undefined);
    setApproved(undefined);
  }

  async function confirmApproval(key: string, version: number, note: string) {
    setApproveError(undefined);
    try {
      await approveConfiguration(key, version, note, organizationId);
      setApproved(adminMessage(locale, 'approvedNotice', { key, version: String(version) }));
      setApproval(undefined);
      setReloadToken((value) => value + 1);
    } catch (failure: unknown) {
      setApproveError(asAdminTransportError(failure));
    }
  }

  return (
    <div
      className="page"
      data-breakpoint={breakpoint}
      data-coarse-pointer={coarsePointer}
      style={{ gap: tokens.spacing.lg }}
    >
      <ConfigLookupSection
        lookup={lookup}
        lookupError={lookupError}
        onChange={(value) => {
          setLookup(value);
          setLookupError(undefined);
        }}
        onSubmit={handleLookup}
      />

      {configKey === undefined ? null : (
        <>
          <p role="status" data-testid="config-status">
            <MixedScriptText text={defined ?? approved ?? ''} lang={locale} />
          </p>
          {commandError !== undefined ? StateView.forTransportError(commandError) : null}
          <ConfigHistory
            configKey={configKey}
            organizationId={organizationId}
            locale={locale}
            reloadToken={reloadToken}
            onPropose={(draft) => handlePropose(configKey, draft)}
            onApprove={openApproval}
          />
          <CatalogFamilySection
            family={family}
            familyError={familyError}
            onChange={(value) => {
              setFamily(value);
              setFamilyError(undefined);
            }}
            onSubmit={handleFamilyLookup}
          >
            {appliedFamily !== undefined ? (
              <CatalogSection
                key={`${organizationId}:${appliedFamily}`}
                family={appliedFamily}
                organizationId={organizationId}
                locale={locale}
                reloadToken={catalogReloadToken}
                onReload={() => setCatalogReloadToken((value) => value + 1)}
              />
            ) : null}
          </CatalogFamilySection>
        </>
      )}

      {configKey !== undefined && approval !== undefined ? (
        <ApprovalDialog
          configKey={configKey}
          approval={approval}
          approveError={approveError}
          cancelRef={cancelRef}
          onChange={setApproval}
          onConfirm={() => {
            void confirmApproval(configKey, approval.version, approval.note);
          }}
        />
      ) : null}
    </div>
  );
}

/** API-backed configuration page; the tenant key prevents cross-tenant state. */
export function ConfigPage() {
  const organizationId = useActiveOrganization();
  return <ConfigContent key={organizationId} organizationId={organizationId} />;
}
