import { Button } from '../../components/Button';
import { MixedScriptText } from '../../components/MixedScriptText';
import { displayLocalName, formatInstant } from '../../i18n/format';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import type { Referral } from './domain';
import { referralStateMessageKey, therapyText } from './messages';

/** Props for the therapy referral worklist and its row actions. */
export interface ReferralWorklistProps {
  referrals: readonly Referral[];
  selectedReferralId: string;
  nextCursor: string | undefined;
  loadingMore: boolean;
  onSelect: (id: string) => void;
  onAccept: (id: string) => void;
  onLoadMore: () => void;
}

/** Render the accessible, tenant-scoped referral worklist. */
export function ReferralWorklist({
  referrals,
  selectedReferralId,
  nextCursor,
  loadingMore,
  onSelect,
  onAccept,
  onLoadMore,
}: ReferralWorklistProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const text = (key: Parameters<typeof therapyText>[1]) => therapyText(locale, key);

  return (
    <div style={{ display: 'grid', gap: tokens.spacing.sm }}>
      <table style={{ borderCollapse: 'collapse', width: '100%' }}>
        <caption style={{ textAlign: 'start', paddingBlock: tokens.spacing.sm }}>
          {text('referrals')}
        </caption>
        <thead>
          <tr>
            <th scope="col">{text('patient')}</th>
            <th scope="col">{text('patientId')}</th>
            <th scope="col">{text('referrer')}</th>
            <th scope="col">{text('reason')}</th>
            <th scope="col">{text('received')}</th>
            <th scope="col">{text('state')}</th>
            <th scope="col">{text('actions')}</th>
          </tr>
        </thead>
        <tbody>
          {referrals.length === 0 ? (
            <tr>
              <td colSpan={7}>{text('noReferrals')}</td>
            </tr>
          ) : (
            referrals.map((referral) => {
              const key = `${referral.organizationId}:${referral.id}`;
              const rowName = displayLocalName(referral.patientName, locale);
              return (
                <tr key={key} aria-selected={referral.id === selectedReferralId}>
                  <th scope="row">
                    <MixedScriptText text={rowName} lang={locale} />
                  </th>
                  <td>
                    <MixedScriptText text={referral.patientLocalId} lang="en" />
                  </td>
                  <td>
                    <MixedScriptText text={referral.referrer} lang={locale} />
                  </td>
                  <td>
                    <MixedScriptText text={referral.reason} lang={locale} />
                  </td>
                  <td>
                    <time dateTime={referral.receivedAt}>
                      {formatInstant(referral.receivedAt, 'UTC', locale)}
                    </time>
                  </td>
                  <td>{text(referralStateMessageKey[referral.state])}</td>
                  <td>
                    <Button variant="secondary" onClick={() => onSelect(referral.id)}>
                      {text('select')}
                    </Button>{' '}
                    {referral.state === 'pending' ? (
                      <Button onClick={() => onAccept(referral.id)}>{text('accept')}</Button>
                    ) : null}
                  </td>
                </tr>
              );
            })
          )}
        </tbody>
      </table>
      {nextCursor !== undefined ? (
        <Button onClick={onLoadMore} disabled={loadingMore}>
          {text('loadMore')}
        </Button>
      ) : null}
    </div>
  );
}
