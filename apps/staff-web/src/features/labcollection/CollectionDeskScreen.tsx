import { useState } from 'react';
import { Button } from '../../components/Button';
import { defined } from '../../components/invariant';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import { CollectionForm } from './CollectionForm';
import { FindOrderPanel } from './FindOrderPanel';
import { IdentityVerification } from './IdentityVerification';
import { LabelSheet } from './LabelSheet';
import { RejectionForm } from './RejectionForm';
import type {
  CollectionOrder,
  LabelData,
  PresentedIdentity,
  RejectionDraft,
  SpecimenDetails,
} from './domain';
import { checkMatch } from './domain';
import type { CollectBody, RejectBody } from './api';
import { labText } from './messages';

/** Props for the presentational collection desk. */
export interface CollectionDeskScreenProps {
  orders: readonly CollectionOrder[];
  selected: CollectionOrder | undefined;
  onSelect: (id: string) => void;
  label: LabelData | undefined;
  /** Command outcome supplied by the page; only a confirmed success sets it. */
  statusMessage: string;
  organizationId: string;
  onCollect: (order: CollectionOrder, body: CollectBody) => void;
  onPrint: (order: CollectionOrder) => void;
  onReject: (order: CollectionOrder, body: RejectBody) => void;
}

const blankPresented: PresentedIdentity = { patientId: '', accessionNo: '', accessionIssuer: '' };
const blankSpecimen: SpecimenDetails = {
  specimenType: '',
  siteCode: '',
  collector: '',
  collectedAt: '',
  quality: '',
};
const blankRejection: RejectionDraft = { reason: '', detail: '', notifyPatient: false };

/** Identity match for the selected order; nothing matches without a selection. */
function matchFor(selected: CollectionOrder | undefined, presented: PresentedIdentity) {
  return selected === undefined ? { matched: false, reasons: [] } : checkMatch(selected, presented);
}

/** Collection may proceed only for a selected order with every confirmation given. */
function isReadyToProceed(
  selected: CollectionOrder | undefined,
  matched: boolean,
  confirmations: readonly boolean[],
): boolean {
  return selected !== undefined && matched && confirmations.every(Boolean);
}

/**
 * Compose order search, bedside verification, collection, label printing and
 * rejection. All durable commands are delegated to the page; local drafts are
 * held here and cleared whenever a different order is opened so one patient's
 * typed identifiers never carry over to another.
 */
export function CollectionDeskScreen({
  orders,
  selected,
  onSelect,
  label,
  statusMessage,
  organizationId,
  onCollect,
  onPrint,
  onReject,
}: CollectionDeskScreenProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const [presented, setPresented] = useState<PresentedIdentity>(blankPresented);
  const [namesConfirmed, setNamesConfirmed] = useState(false);
  const [accessionConfirmed, setAccessionConfirmed] = useState(false);
  const [specimen, setSpecimen] = useState<SpecimenDetails>(blankSpecimen);
  const [rejection, setRejection] = useState<RejectionDraft>(blankRejection);

  const [selectedKey, setSelectedKey] = useState(selected?.id);
  if (selectedKey !== selected?.id) {
    setSelectedKey(selected?.id);
    setPresented(blankPresented);
    setNamesConfirmed(false);
    setAccessionConfirmed(false);
    setSpecimen(blankSpecimen);
    setRejection(blankRejection);
  }

  const match = matchFor(selected, presented);
  const canProceed = isReadyToProceed(selected, match.matched, [
    namesConfirmed,
    accessionConfirmed,
  ]);

  // The command controls only render with a selected order; defined() records
  // that invariant without an unreachable early-return branch.
  function handleCollect() {
    const order = defined(selected, 'selected collection order');
    onCollect(order, {
      patientId: presented.patientId,
      accessionNo: presented.accessionNo,
      accessionIssuer: presented.accessionIssuer,
      specimenType: specimen.specimenType,
      siteCode: specimen.siteCode,
      collector: specimen.collector,
      collectedAt: specimen.collectedAt,
      quality: specimen.quality,
      expectedVersion: order.version,
    });
  }

  function handlePrint() {
    onPrint(defined(selected, 'selected collection order'));
  }

  function handleReject() {
    const order = defined(selected, 'selected collection order');
    onReject(order, {
      reason: rejection.reason,
      detail: rejection.detail,
      orderingClinician: 'synthetic.ordering.clinician',
      notifyPatient: rejection.notifyPatient,
      expectedVersion: order.version,
    });
  }

  return (
    <section
      aria-label={labText(locale, 'collectionDesk')}
      style={{ display: 'grid', gap: tokens.spacing.lg }}
    >
      <h2>{labText(locale, 'collectionDesk')}</h2>
      <p>{labText(locale, 'syntheticNotice')}</p>
      <p role="status" data-testid="collection-status">
        {statusMessage}
      </p>
      <FindOrderPanel orders={orders} selectedId={selected?.id} onSelect={onSelect} />
      {selected === undefined ? (
        <p role="status">{labText(locale, 'selectPrompt')}</p>
      ) : (
        <>
          <IdentityVerification
            order={selected}
            match={match}
            presented={presented}
            onPresentedChange={setPresented}
            namesConfirmed={namesConfirmed}
            accessionConfirmed={accessionConfirmed}
            onNamesConfirmed={setNamesConfirmed}
            onAccessionConfirmed={setAccessionConfirmed}
          />
          {!canProceed ? <p role="status">{labText(locale, 'verificationRequired')}</p> : null}
          <CollectionForm
            value={specimen}
            onChange={setSpecimen}
            onCollect={handleCollect}
            disabled={!canProceed}
          />
          <Button onClick={handlePrint} disabled={!canProceed}>
            {labText(locale, 'printLabels')}
          </Button>
          <RejectionForm
            value={rejection}
            onChange={setRejection}
            onReject={handleReject}
            disabled={!canProceed}
          />
          {label !== undefined ? (
            <LabelSheet
              label={label}
              organizationId={organizationId}
              printedBy={specimen.collector}
              printedAt={specimen.collectedAt}
            />
          ) : null}
        </>
      )}
    </section>
  );
}
