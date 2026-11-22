import { useState } from 'react';
import { Button } from '../components/Button';
import { DataTable } from '../components/DataTable';
import { Dialog } from '../components/Dialog';
import { Form } from '../components/Form';
import { FormField } from '../components/FormField';
import { MixedScriptText } from '../components/MixedScriptText';
import { mixedScriptLabel, syntheticFacility, syntheticPerson } from '../fixtures/synthetic';
import { displayLocalName, formatMeasurement } from '../i18n/format';
import { useI18n } from '../i18n/I18nProvider';
import { useTheme } from '../theme/ThemeProvider';
import { useBreakpoint } from '../theme/useBreakpoint';
import { ProtectedRoute } from './ProtectedRoute';
import { SignInPlaceholder } from './SignInPlaceholder';

/**
 * Synthetic design-preview page for visual review of the shared components.
 * Registered only in development builds.
 *
 * The outer `page` class gives the preview a single wrapping column, the
 * maximum reading width and logical safe-area padding, so the same DOM stays
 * readable from 320 px upward and mirrors under right-to-left. The active
 * breakpoint is exposed as `data-breakpoint` for layout checks; every child
 * component reads the same breakpoint hook and renders its own compact, medium
 * or expanded structure. Width changes presentation only: no text, action,
 * clinical column or API call is added, removed or hidden (WEB-07, UX-01).
 */
export function DesignPreview() {
  const { locale, t } = useI18n();
  const { tokens } = useTheme();
  const breakpoint = useBreakpoint();
  const [dialogOpen, setDialogOpen] = useState(false);
  const [note, setNote] = useState('');

  return (
    <div
      className="page"
      data-breakpoint={breakpoint}
      style={{ display: 'grid', gap: tokens.spacing.lg }}
    >
      <h2>{t('mixedScriptNote')}</h2>
      <p>
        <MixedScriptText text={mixedScriptLabel} lang={locale} />
      </p>
      <p>
        <MixedScriptText text={displayLocalName(syntheticPerson, locale)} lang={locale} />
      </p>
      <p>
        <MixedScriptText text={formatMeasurement(12.5, 'cm', locale)} lang={locale} />
      </p>

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
    </div>
  );
}
