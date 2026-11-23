import { useState } from 'react';
import { Button } from '../../../components/Button';
import { Form } from '../../../components/Form';
import { FormField } from '../../../components/FormField';
import { MixedScriptText } from '../../../components/MixedScriptText';
import { ForbiddenState } from '../../../components/StateViews';
import { formatCivilDate } from '../../../i18n/format';
import { useI18n } from '../../../i18n/I18nProvider';
import { useTheme } from '../../../theme/ThemeProvider';
import type { HandoutLanguage } from '../components/messages';
import {
  approveProfile,
  canManagePrintSettings,
  effectiveProfile,
  proposeProfile,
  validateProfileDraft,
  versionsForFacility,
} from './domain';
import type {
  ApprovedLogoAsset,
  PaperSize,
  PrintProfileDraft,
  PrintProfileVersion,
  PrintSettingsRole,
  ProfileErrors,
} from './domain';
import { settingsMessage, settingsText } from './labels';
import type { SettingsMessageKey } from './labels';

/** Props for the tenant-scoped print profile administration screen. */
export interface PrintSettingsScreenProps {
  organizationId: string;
  facilityCode: string;
  /** Active role of the viewer, never taken from the form body. */
  role: PrintSettingsRole;
  /** Catalog of approved logo assets; any other reference is refused. */
  approvedAssets: readonly ApprovedLogoAsset[];
  versions: readonly PrintProfileVersion[];
  /** Civil date used to decide which approved version is effective. */
  anchorCivilDate: string;
  onPropose: (version: PrintProfileVersion) => void;
  onApprove: (version: number) => void;
}

/** Empty form state; the handout language and paper size have bounded defaults. */
const EMPTY_DRAFT: PrintProfileDraft = {
  facilityName: '',
  address: '',
  logoReference: '',
  footerText: '',
  defaultLanguage: 'en',
  paperSize: 'A4',
  effectiveFrom: '',
};

/** Stable option order in both directions. */
const languageOptions: readonly HandoutLanguage[] = ['en', 'ar'];
const paperOptions: readonly PaperSize[] = ['A4', 'LETTER'];

/** Map bounded field values to their localized labels. */
const languageMessageKey: Record<HandoutLanguage, SettingsMessageKey> = {
  en: 'languageEn',
  ar: 'languageAr',
};
const paperMessageKey: Record<PaperSize, SettingsMessageKey> = {
  A4: 'paperA4',
  LETTER: 'paperLetter',
};

interface HistorySectionProps {
  rows: readonly PrintProfileVersion[];
  onApprove: (version: number) => void;
}

interface DraftFieldsProps {
  draft: PrintProfileDraft;
  errors: ProfileErrors;
  onChange: (patch: Partial<PrintProfileDraft>) => void;
}

/** Shared locale, tokens and translator for the settings sections. */
function useSettingsView() {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const text = (key: SettingsMessageKey) => settingsText(locale, key);
  return { locale, tokens, text };
}

function CurrentProfileSection({ effective }: { effective: PrintProfileVersion | undefined }) {
  const { locale, tokens, text } = useSettingsView();
  return (
    <section
      aria-label={text('currentProfile')}
      style={{ display: 'grid', gap: tokens.spacing.sm }}
    >
      <h3>{text('currentProfile')}</h3>
      {effective === undefined ? (
        <p>{text('noEffectiveProfile')}</p>
      ) : (
        <dl style={{ display: 'grid', gap: tokens.spacing.sm }}>
          <dt>{text('facilityName')}</dt>
          <dd>
            <MixedScriptText text={effective.facilityName} lang="en" />
          </dd>
          <dt>{text('address')}</dt>
          <dd>
            <MixedScriptText text={effective.address} lang={locale} />
          </dd>
          <dt>{text('logoReference')}</dt>
          <dd>
            <MixedScriptText text={effective.logoReference} lang="en" />
          </dd>
          <dt>{text('footerText')}</dt>
          <dd>
            <MixedScriptText text={effective.footerText} lang={locale} />
          </dd>
          <dt>{text('defaultLanguage')}</dt>
          <dd>{text(languageMessageKey[effective.defaultLanguage])}</dd>
          <dt>{text('paperSize')}</dt>
          <dd>{text(paperMessageKey[effective.paperSize])}</dd>
          <dt>{text('effectiveFrom')}</dt>
          <dd>
            <time dateTime={effective.effectiveFrom}>
              {formatCivilDate(effective.effectiveFrom, locale)}
            </time>
          </dd>
        </dl>
      )}
    </section>
  );
}

function HistorySection({ rows, onApprove }: HistorySectionProps) {
  const { locale, tokens, text } = useSettingsView();
  return (
    <section aria-label={text('history')} style={{ display: 'grid', gap: tokens.spacing.sm }}>
      <h3>{text('history')}</h3>
      <table style={{ borderCollapse: 'collapse', width: '100%' }}>
        <caption style={{ textAlign: 'start', paddingBlock: tokens.spacing.sm }}>
          {text('history')}
        </caption>
        <thead>
          <tr>
            <th scope="col">{text('version')}</th>
            <th scope="col">{text('effectiveFrom')}</th>
            <th scope="col">{text('defaultLanguage')}</th>
            <th scope="col">{text('paperSize')}</th>
            <th scope="col">{text('approver')}</th>
          </tr>
        </thead>
        <tbody>
          {rows.length === 0 ? (
            <tr>
              <td colSpan={5}>{text('noHistory')}</td>
            </tr>
          ) : (
            rows.map((entry) => (
              <tr key={`${entry.organizationId}:${entry.version}`}>
                <th scope="row">{entry.version}</th>
                <td>
                  <time dateTime={entry.effectiveFrom}>
                    {formatCivilDate(entry.effectiveFrom, locale)}
                  </time>
                </td>
                <td>{text(languageMessageKey[entry.defaultLanguage])}</td>
                <td>{text(paperMessageKey[entry.paperSize])}</td>
                <td>
                  {entry.approved ? text('approved') : text('pending')}{' '}
                  {entry.approved ? null : (
                    <Button variant="secondary" onClick={() => onApprove(entry.version)}>
                      {text('approve')}
                    </Button>
                  )}
                  {entry.approver === undefined ? null : (
                    <MixedScriptText text={entry.approver} lang="en" />
                  )}
                </td>
              </tr>
            ))
          )}
        </tbody>
      </table>
    </section>
  );
}

function DraftFields({ draft, errors, onChange }: DraftFieldsProps) {
  const { tokens, text } = useSettingsView();
  return (
    <div style={{ display: 'grid', gap: tokens.spacing.md }}>
      <FormField
        id="print-settings-facility-name"
        label={text('facilityName')}
        value={draft.facilityName}
        onChange={(value) => onChange({ facilityName: value })}
        {...(errors.facilityName === undefined ? {} : { error: text(errors.facilityName) })}
      />
      <FormField
        id="print-settings-address"
        label={text('address')}
        value={draft.address}
        onChange={(value) => onChange({ address: value })}
        {...(errors.address === undefined ? {} : { error: text(errors.address) })}
      />
      <FormField
        id="print-settings-logo"
        label={text('logoReference')}
        value={draft.logoReference}
        onChange={(value) => onChange({ logoReference: value })}
        {...(errors.logoReference === undefined ? {} : { error: text(errors.logoReference) })}
      />
      <FormField
        id="print-settings-footer"
        label={text('footerText')}
        value={draft.footerText}
        onChange={(value) => onChange({ footerText: value })}
        {...(errors.footerText === undefined ? {} : { error: text(errors.footerText) })}
      />
      <div style={{ display: 'grid', gap: tokens.spacing.sm }}>
        <label htmlFor="print-settings-language">{text('defaultLanguage')}</label>
        <select
          id="print-settings-language"
          value={draft.defaultLanguage}
          onChange={(event) => onChange({ defaultLanguage: event.target.value as HandoutLanguage })}
          style={{ minHeight: '32px' }}
        >
          {languageOptions.map((option) => (
            <option key={option} value={option}>
              {text(languageMessageKey[option])}
            </option>
          ))}
        </select>
      </div>
      <div style={{ display: 'grid', gap: tokens.spacing.sm }}>
        <label htmlFor="print-settings-paper">{text('paperSize')}</label>
        <select
          id="print-settings-paper"
          value={draft.paperSize}
          onChange={(event) => onChange({ paperSize: event.target.value as PaperSize })}
          style={{ minHeight: '32px' }}
        >
          {paperOptions.map((option) => (
            <option key={option} value={option}>
              {text(paperMessageKey[option])}
            </option>
          ))}
        </select>
      </div>
      <FormField
        id="print-settings-effective"
        label={text('effectiveFrom')}
        value={draft.effectiveFrom}
        placeholder="2026-07-01"
        onChange={(value) => onChange({ effectiveFrom: value })}
        {...(errors.effectiveFrom === undefined ? {} : { error: text(errors.effectiveFrom) })}
      />
    </div>
  );
}

/**
 * Admin screen for per-facility letterhead, footer text and print profile
 * settings. Proposals and approvals append versions; the effective profile is
 * the newest approved version already effective on the anchor date.
 * Non-administrators see the shared forbidden state and no editor.
 */
export function PrintSettingsScreen(props: PrintSettingsScreenProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const [draft, setDraft] = useState<PrintProfileDraft>(EMPTY_DRAFT);
  const [errors, setErrors] = useState<ProfileErrors>({});
  const [versions, setVersions] = useState<readonly PrintProfileVersion[]>(() => props.versions);
  const [status, setStatus] = useState<string | undefined>(undefined);

  if (!canManagePrintSettings(props.role)) {
    return <ForbiddenState />;
  }

  const text = (key: SettingsMessageKey) => settingsText(locale, key);
  const rows = versionsForFacility(versions, props.organizationId, props.facilityCode);
  const effective = effectiveProfile(
    versions,
    props.organizationId,
    props.facilityCode,
    props.anchorCivilDate,
  );
  const errorKeys = Object.values<SettingsMessageKey>(errors);

  function submit() {
    const nextErrors = validateProfileDraft(draft, props.approvedAssets);
    setErrors(nextErrors);
    setStatus(undefined);
    if (Object.keys(nextErrors).length > 0) {
      return;
    }
    const created = proposeProfile(versions, props.organizationId, props.facilityCode, draft);
    setVersions((current) => [...current, created]);
    setDraft(EMPTY_DRAFT);
    setStatus(settingsMessage(locale, 'proposalSaved', { version: String(created.version) }));
    props.onPropose(created);
  }

  function approve(version: number) {
    setVersions((current) =>
      current.map((entry) =>
        entry.version === version ? approveProfile(entry, text('syntheticApprover')) : entry,
      ),
    );
    setStatus(settingsMessage(locale, 'approvedNotice', { version: String(version) }));
    props.onApprove(version);
  }

  function changeDraft(patch: Partial<PrintProfileDraft>) {
    setDraft((current) => ({ ...current, ...patch }));
    setErrors({});
  }

  return (
    <div style={{ display: 'grid', gap: tokens.spacing.lg }}>
      <h2>{text('title')}</h2>
      <p>{text('intro')}</p>
      <p>{text('syntheticNotice')}</p>

      <CurrentProfileSection effective={effective} />

      <HistorySection rows={rows} onApprove={approve} />

      <section aria-label={text('editor')} style={{ display: 'grid', gap: tokens.spacing.md }}>
        <h3>{text('editor')}</h3>
        {errorKeys.length === 0 ? null : (
          <ul role="alert" data-testid="settings-error-summary">
            {errorKeys.map((key) => (
              <li key={key}>{text(key)}</li>
            ))}
          </ul>
        )}
        <Form label={text('editor')} submitLabel={text('propose')} onSubmit={submit}>
          <DraftFields draft={draft} errors={errors} onChange={changeDraft} />
        </Form>
        <p role="status" data-testid="print-settings-status">
          {status ?? ''}
        </p>
      </section>
    </div>
  );
}
