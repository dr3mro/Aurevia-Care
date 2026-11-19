import { Button } from '../../components/Button';
import { MixedScriptText } from '../../components/MixedScriptText';
import type { Locale } from '../../i18n/messages';
import { useTheme } from '../../theme/ThemeProvider';
import type { DocumentAssociation } from './domain';
import { chartText } from './messages';

/** Props for the PDF/image association list (CLN-07). */
export interface DocumentListProps {
  documents: readonly DocumentAssociation[];
  locale: Locale;
  onVoid: (id: string) => void;
  onRotate: (id: string) => void;
}

/** Render document associations with version, language, checksum and void state. */
export function DocumentList({ documents, locale, onVoid, onRotate }: DocumentListProps) {
  const { tokens } = useTheme();
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
  return (
    <section aria-label={chartText(locale, 'documents')} style={{ display: 'grid', gap: tokens.spacing.md }}>
      <h3>{chartText(locale, 'documents')}</h3>
      <table style={{ borderCollapse: 'collapse', width: '100%' }}>
        <caption style={{ textAlign: 'start', paddingBlock: tokens.spacing.sm }}>
          {chartText(locale, 'documentList')}
        </caption>
        <thead>
          <tr>
            {headers.map((header) => (
              <th key={header} scope="col" style={{ border: `1px solid ${tokens.border}` }}>
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
              const shown = locale === 'ar' && document.titleLocal ? document.titleLocal : document.title;
              return (
                <tr key={key}>
                  <td>
                    <MixedScriptText text={shown} lang={locale} />
                  </td>
                  <td>{document.kind.toUpperCase()}</td>
                  <td>{document.version}</td>
                  <td>
                    {chartText(locale, document.language === 'ar' ? 'languageAr' : 'languageEn')}
                  </td>
                  <td>{document.checksum}</td>
                  <td>{document.linkedTo}</td>
                  <td>{`${document.rotationDegrees}°`}</td>
                  <td>
                    {document.void
                      ? `${chartText(locale, 'documentVoided')}: ${document.void.reason}`
                      : chartText(locale, 'documentLive')}
                  </td>
                  <td>
                    <Button variant="secondary" onClick={() => onRotate(document.id)}>
                      {chartText(locale, 'rotate')}
                    </Button>
                    <Button
                      variant="secondary"
                      onClick={() => onVoid(document.id)}
                      disabled={document.void !== undefined}
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
