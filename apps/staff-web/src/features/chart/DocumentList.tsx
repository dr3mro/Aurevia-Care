import type { ReactNode } from 'react';
import { Button } from '../../components/Button';
import { MixedScriptText } from '../../components/MixedScriptText';
import type { Locale } from '../../i18n/messages';
import { useTheme } from '../../theme/ThemeProvider';
import { useBreakpoint } from '../../theme/useBreakpoint';
import type { DocumentAssociation } from './domain';
import { chartText } from './messages';

/** Props for the PDF/image association list (CLN-07). */
export interface DocumentListProps {
  documents: readonly DocumentAssociation[];
  locale: Locale;
  onVoid: (id: string) => void;
  onRotate: (id: string) => void;
}

/** Every displayed value of one association, shared by cards and table. */
interface DocumentValues {
  title: string;
  kind: string;
  version: number;
  language: string;
  checksum: string;
  linkedTo: string;
  rotation: string;
  status: string;
  voided: boolean;
}

/** Logical wrapping for clinical values: no fixed block size, no clipping. */
const wrappingStyle = {
  minInlineSize: 0,
  overflowWrap: 'anywhere',
  whiteSpace: 'normal',
} as const;

/**
 * Resolve the display values for one association exactly once. Both layouts
 * consume the same values so a width change can never disagree about what a
 * clinical field says. Direction is never forced here; MixedScriptText isolates
 * the title run instead.
 *
 * @param document - Synthetic document association to present.
 * @param locale - Active locale, used for labels and the localized title.
 * @returns The wrapped title, each labelled field, the status and void state.
 */
function documentValues(document: DocumentAssociation, locale: Locale): DocumentValues {
  return {
    title:
      locale === 'ar' && (document.titleLocal ?? '') !== ''
        ? (document.titleLocal ?? '')
        : document.title,
    kind: document.kind.toUpperCase(),
    version: document.version,
    language: chartText(locale, document.language === 'ar' ? 'languageAr' : 'languageEn'),
    checksum: document.checksum,
    linkedTo: document.linkedTo,
    rotation: `${document.rotationDegrees}°`,
    status: document.void
      ? `${chartText(locale, 'documentVoided')}: ${document.void.reason}`
      : chartText(locale, 'documentLive'),
    voided: document.void !== undefined,
  };
}

/** One label/value pair inside a compact document card. */
function CardField({ label, value }: { label: string; value: ReactNode }) {
  return (
    <div className="stack">
      <dt style={{ fontWeight: 600 }}>{label}</dt>
      <dd style={wrappingStyle}>{value}</dd>
    </div>
  );
}

/**
 * Render document associations with version, language, checksum and void state.
 *
 * Width is a presentation input only. Medium and expanded viewports keep one
 * semantic table with every column; on compact viewports the same fields become
 * stacked cards so no clinical value needs a horizontal scroll. Both layouts
 * keep the wrapped title, both actions, the empty state and the DOM focus order
 * (WEB-07, UX-01).
 */
export function DocumentList({ documents, locale, onVoid, onRotate }: DocumentListProps) {
  const { tokens } = useTheme();
  const breakpoint = useBreakpoint();
  const headers = [
    chartText(locale, 'documentTitle'),
    chartText(locale, 'documentKind'),
    chartText(locale, 'documentVersion'),
    chartText(locale, 'documentLanguage'),
    chartText(locale, 'documentChecksum'),
    chartText(locale, 'documentLinkedTo'),
    chartText(locale, 'documentRotation'),
    chartText(locale, 'documentStatus'),
    chartText(locale, 'actions'),
  ];
  const sectionStyle = { display: 'grid', gap: tokens.spacing.md } as const;

  if (breakpoint === 'compact') {
    return (
      <section aria-label={chartText(locale, 'documents')} style={sectionStyle}>
        <h3>{chartText(locale, 'documents')}</h3>
        <ul
          className="card-list"
          aria-label={chartText(locale, 'documentList')}
          style={{ listStyle: 'none', margin: 0, padding: 0 }}
        >
          {documents.length === 0 ? (
            <li>{chartText(locale, 'noEntries')}</li>
          ) : (
            documents.map((document) => {
              const key = `${document.organizationId}:${document.id}`;
              const values = documentValues(document, locale);
              return (
                <li className="stack" key={key}>
                  <h4>
                    <MixedScriptText text={values.title} lang={locale} />
                  </h4>
                  <dl style={{ margin: 0 }}>
                    <CardField label={chartText(locale, 'documentKind')} value={values.kind} />
                    <CardField
                      label={chartText(locale, 'documentVersion')}
                      value={values.version}
                    />
                    <CardField
                      label={chartText(locale, 'documentLanguage')}
                      value={values.language}
                    />
                    <CardField
                      label={chartText(locale, 'documentChecksum')}
                      value={values.checksum}
                    />
                    <CardField
                      label={chartText(locale, 'documentLinkedTo')}
                      value={values.linkedTo}
                    />
                    <CardField
                      label={chartText(locale, 'documentRotation')}
                      value={values.rotation}
                    />
                    <CardField label={chartText(locale, 'documentStatus')} value={values.status} />
                  </dl>
                  <div className="cluster">
                    <Button variant="secondary" onClick={() => onRotate(document.id)}>
                      {chartText(locale, 'rotate')}
                    </Button>
                    <Button
                      variant="secondary"
                      onClick={() => onVoid(document.id)}
                      disabled={values.voided}
                    >
                      {chartText(locale, 'void')}
                    </Button>
                  </div>
                </li>
              );
            })
          )}
        </ul>
      </section>
    );
  }

  return (
    <section aria-label={chartText(locale, 'documents')} style={sectionStyle}>
      <h3>{chartText(locale, 'documents')}</h3>
      <table style={{ borderCollapse: 'collapse', width: '100%' }}>
        <caption style={{ textAlign: 'start', paddingBlock: tokens.spacing.sm }}>
          {chartText(locale, 'documentList')}
        </caption>
        <thead>
          <tr>
            {headers.map((header) => (
              <th
                key={header}
                scope="col"
                style={{ border: `1px solid ${tokens.border}`, ...wrappingStyle }}
              >
                {header}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {documents.length === 0 ? (
            <tr>
              <td colSpan={headers.length}>{chartText(locale, 'noEntries')}</td>
            </tr>
          ) : (
            documents.map((document) => {
              const key = `${document.organizationId}:${document.id}`;
              const values = documentValues(document, locale);
              return (
                <tr key={key}>
                  <th scope="row" style={wrappingStyle}>
                    <MixedScriptText text={values.title} lang={locale} />
                  </th>
                  <td style={wrappingStyle}>{values.kind}</td>
                  <td style={wrappingStyle}>{values.version}</td>
                  <td style={wrappingStyle}>{values.language}</td>
                  <td style={wrappingStyle}>{values.checksum}</td>
                  <td style={wrappingStyle}>{values.linkedTo}</td>
                  <td style={wrappingStyle}>{values.rotation}</td>
                  <td style={wrappingStyle}>{values.status}</td>
                  <td style={wrappingStyle}>
                    <Button variant="secondary" onClick={() => onRotate(document.id)}>
                      {chartText(locale, 'rotate')}
                    </Button>
                    <Button
                      variant="secondary"
                      onClick={() => onVoid(document.id)}
                      disabled={values.voided}
                    >
                      {chartText(locale, 'void')}
                    </Button>
                  </td>
                </tr>
              );
            })
          )}
        </tbody>
      </table>
    </section>
  );
}
