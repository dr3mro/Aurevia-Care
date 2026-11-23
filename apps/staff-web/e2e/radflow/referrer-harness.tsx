import { StrictMode, useState } from 'react';
import type { ReactElement } from 'react';
import { createRoot } from 'react-dom/client';
import { AddendumBanner } from '../../src/features/radreferrer/AddendumBanner';
import { ImagingReportCard } from '../../src/features/radreferrer/ImagingReportCard';
import type { AddendumAcknowledgment } from '../../src/features/radreferrer/domain';
import {
  alphaOrganizationId,
  syntheticAddenda,
  syntheticOrderId,
  syntheticSignedReport,
} from '../../src/features/radreferrer/fixtures';
import { I18nProvider, useI18n } from '../../src/i18n/I18nProvider';
import { ThemeProvider } from '../../src/theme/ThemeProvider';

/**
 * Browser-only harness for the exported referrer components (RADFLOW-05).
 * The components are deliberately not routed in the staff portal yet, so this
 * harness mounts them through their real providers and synthetic fixtures to
 * give the end-to-end journey a genuine product entry point. It is served by
 * the Vite dev server and is never part of the production build.
 */

/** Stable instant used only to render an acknowledged receipt in the harness. */
const syntheticAcknowledgedAt = '2026-03-09T14:00:00Z';

/** The interactive referrer view: report, open addenda and their acknowledgment. */
function ReferrerView(): ReactElement {
  const { locale, toggleLocale } = useI18n();
  const [addenda, setAddenda] = useState<readonly AddendumAcknowledgment[]>(syntheticAddenda);
  const [lastAcknowledged, setLastAcknowledged] = useState('');

  function acknowledge(receipt: AddendumAcknowledgment): void {
    setAddenda((current) =>
      current.map((item) =>
        item.organizationId === receipt.organizationId &&
        item.orderId === receipt.orderId &&
        item.version === receipt.version
          ? {
              ...item,
              state: 'acknowledged',
              acknowledgedAt: syntheticAcknowledgedAt,
              acknowledgedBy: 'synthetic.referrer',
            }
          : item,
      ),
    );
    setLastAcknowledged(`${receipt.organizationId}:${receipt.orderId}:${receipt.version}`);
  }

  return (
    <>
      <button type="button" onClick={toggleLocale}>
        {locale === 'en' ? 'العربية' : 'English'}
      </button>
      <ImagingReportCard report={syntheticSignedReport} locale={locale} />
      <AddendumBanner
        addenda={addenda}
        organizationId={alphaOrganizationId}
        orderId={syntheticOrderId}
        locale={locale}
        onAcknowledge={acknowledge}
      />
      <output data-testid="harness-acknowledgment">{lastAcknowledged}</output>
    </>
  );
}

/** Mount the harness tree with the same providers the staff portal uses. */
function Harness(): ReactElement {
  return (
    <StrictMode>
      <ThemeProvider>
        <I18nProvider>
          <ReferrerView />
        </I18nProvider>
      </ThemeProvider>
    </StrictMode>
  );
}

const container = document.getElementById('radflow-harness');
if (container === null) {
  throw new Error('radflow-harness container is required');
}
createRoot(container).render(<Harness />);
