import { useState } from 'react';
import type { ReactNode } from 'react';
import { Button } from '../components/Button';
import { DataTable } from '../components/DataTable';
import { Dialog } from '../components/Dialog';
import { Form } from '../components/Form';
import { FormField } from '../components/FormField';
import { MixedScriptText } from '../components/MixedScriptText';
import {
  mixedScriptLabel,
  syntheticFacility,
  syntheticOrganizations,
  syntheticPerson,
} from '../fixtures/synthetic';
import { displayLocalName, formatMeasurement } from '../i18n/format';
import { useI18n } from '../i18n/I18nProvider';
import { useTheme } from '../theme/ThemeProvider';
import { OrgSwitcher } from './OrgSwitcher';
import { ProtectedRoute } from './ProtectedRoute';
import { SignInPlaceholder } from './SignInPlaceholder';

/** Props for the staff portal shell. Children are the active route content. */
export interface AppShellProps {
  /** Route content rendered inside the main landmark. */
  children?: ReactNode;
}

/** Root visual layout for the staff portal; all content is synthetic. */
export function AppShell({ children }: AppShellProps) {
  const { locale, t, toggleLocale } = useI18n();
  const { mode, tokens, toggleMode } = useTheme();
  const [dialogOpen, setDialogOpen] = useState(false);
  const [note, setNote] = useState('');

  return (
    <div style={{ background: tokens.background, color: tokens.foreground, minHeight: '100vh' }}>
      <a href="#main" className="skip-link">
        {t('skipToMain')}
      </a>
      <header role="banner" style={{ padding: tokens.spacing.lg }}>
        <h1>{__APP_DISPLAY_NAME__} · {t('appTitle')}</h1>
        <nav role="navigation" aria-label={t('appTitle')}>
          <OrgSwitcher organizations={syntheticOrganizations} />
        </nav>
        <Button variant="secondary" onClick={toggleMode} pressed={mode === 'dark'}>
          {t('theme')}
        </Button>
        <Button variant="secondary" onClick={toggleLocale} pressed={locale === 'ar'}>
          {t('language')}
        </Button>
      </header>

      <main id="main" tabIndex={-1} style={{ padding: tokens.spacing.lg }}>
        <h2>{t('mixedScriptNote')}</h2>
        <p>
          <MixedScriptText text={mixedScriptLabel} lang={locale} />
        </p>
        <p>
          <MixedScriptText text={displayLocalName(syntheticPerson, locale)} lang={locale} />
        </p>
        <p>{formatMeasurement(12.5, 'cm', locale)}</p>

        <DataTable
          caption={t('facilities')}
          columns={[
            { key: 'code', header: t('code') },
            { key: 'name', header: t('name') },
          ]}
          rows={[{ code: syntheticFacility.code, name: syntheticFacility.name }]}
          emptyLabel={t('facilities')}
        />

        <Button onClick={() => setDialogOpen(true)}>{t('showDetails')}</Button>
        <Dialog
          open={dialogOpen}
          title={t('facilityDetails')}
          closeLabel={t('closeDialog')}
          onClose={() => setDialogOpen(false)}
        >
          <MixedScriptText text={syntheticFacility.name} lang={locale} />
        </Dialog>

        <Form label={t('noteForm')} submitLabel={t('saveNote')} onSubmit={() => setNote(note)}>
          <FormField id="shift-note" label={t('shiftNote')} value={note} onChange={setNote} />
        </Form>
        <output aria-label={t('echo')} role="status">
          {note}
        </output>

        <SignInPlaceholder />
        <ProtectedRoute>
          <p>synthetic protected content</p>
        </ProtectedRoute>
        {children}
      </main>

      <footer role="contentinfo" style={{ padding: tokens.spacing.lg }}>
        <MixedScriptText text={t('signInNote')} lang={locale} />
      </footer>
    </div>
  );
}
