/** API-backed order detail with verified study linking and signed addenda. */

import { useEffect, useState } from 'react';
import { useActiveOrganization } from '../../api/activeOrganization';
import type { TransportError } from '../../api/transport';
import { useApi } from '../../api/useApi';
import { useRoute } from '../../app/router';
import { Button } from '../../components/Button';
import { EmptyState, LoadingState, StateView } from '../../components/StateViews';
import { useI18n } from '../../i18n/I18nProvider';
import type { ImagingOrderResponse } from './api';
import {
  addAddendum,
  asRadiologyTransportError,
  getImagingOrder,
  linkStudy,
  scheduleImagingOrder,
  signInterpretationReport,
  toImagingOrder,
} from './api';
import {
  appendAddendum,
  linkStudyToOrder,
  scheduleOrder,
  signInterpretation,
  signInterpretationForOrder,
  verifyStudyLink,
} from './domain';
import type {
  ImagingOrder,
  InterpretationInput,
  ScheduleInput,
  StudyLink,
  StudyLinkCheck,
} from './domain';
import { OrderDetailScreen } from './OrderDetailScreen';
import { radiologyText } from './messages';

/** Synthetic signing identity; never a real clinician. */
const SIGNER = 'synthetic.radiologist';

/** Normalise an unexpected command rejection without leaking response text. */
function toRadiologyError(failure: unknown): TransportError {
  return asRadiologyTransportError(failure);
}

/** Load one order and drive each command through the verified domain rules. */
export function OrderDetailPage() {
  const organizationId = useActiveOrganization();
  const { locale } = useI18n();
  const { params } = useRoute();
  const id = params.id ?? '';
  const [order, setOrder] = useState<ImagingOrder | undefined>(undefined);
  const [mismatch, setMismatch] = useState<StudyLinkCheck | undefined>(undefined);
  const [commandError, setCommandError] = useState<TransportError | undefined>(undefined);

  const result = useApi<ImagingOrderResponse | null>(
    () => getImagingOrder(id, organizationId),
    [id],
  );

  useEffect(() => {
    if (result.status !== 'ready') {
      return;
    }
    setOrder(result.data === null ? undefined : toImagingOrder(result.data));
  }, [result.status, result.data]);

  useEffect(() => {
    setOrder(undefined);
    setMismatch(undefined);
    setCommandError(undefined);
  }, [organizationId]);

  function handleReload() {
    setCommandError(undefined);
    setMismatch(undefined);
    result.reload();
  }

  async function handleSchedule(input: ScheduleInput) {
    const current = order!;
    try {
      const response = await scheduleImagingOrder(current.id, current.version, input, organizationId);
      // The version returned by the API is authoritative: without it the next
      // step would resend a stale If-Match and fail permanently with 412.
      setOrder({ ...scheduleOrder(current, input), version: response.version });
      setCommandError(undefined);
    } catch (failure: unknown) {
      setCommandError(toRadiologyError(failure));
    }
  }

  async function handleLinkStudy(study: StudyLink) {
    const current = order!;
    const check = verifyStudyLink(current, study);
    if (!check.ok) {
      // A wrong-patient or wrong-accession study is shown but never saved.
      setMismatch(check);
      return;
    }
    setMismatch(undefined);
    try {
      const response = await linkStudy(current.id, current.version, study, organizationId);
      setOrder({ ...linkStudyToOrder(current, study), version: response.version });
      setCommandError(undefined);
    } catch (failure: unknown) {
      setCommandError(toRadiologyError(failure));
    }
  }

  async function handleSign(input: InterpretationInput) {
    const current = order!;
    try {
      const interpretation = signInterpretation(input, SIGNER, new Date().toISOString());
      const response = await signInterpretationReport(current.id, current.version, input, organizationId);
      setOrder({ ...signInterpretationForOrder(current, interpretation), version: response.version });
      setCommandError(undefined);
    } catch (failure: unknown) {
      setCommandError(toRadiologyError(failure));
    }
  }

  async function handleAddendum(text: string) {
    const current = order!;
    try {
      const response = await addAddendum(current.id, current.version, text, organizationId);
      setOrder({
        ...appendAddendum(current, {
          id: crypto.randomUUID(),
          text,
          author: SIGNER,
          addedAt: new Date().toISOString(),
        }),
        version: response.version,
      });
      setCommandError(undefined);
    } catch (failure: unknown) {
      setCommandError(toRadiologyError(failure));
    }
  }

  if (result.status === 'loading' && order === undefined) {
    return <LoadingState />;
  }
  if (result.status === 'error' && order === undefined) {
    return <>{StateView.forTransportError(result.error)}</>;
  }
  if (order === undefined) {
    return <EmptyState />;
  }
  return (
    <div>
      {commandError !== undefined && (
        <div data-testid="radiology-command-error">
          {StateView.forTransportError(commandError)}
          <Button onClick={handleReload}>{radiologyText(locale, 'reload')}</Button>
        </div>
      )}
      <OrderDetailScreen
        order={order}
        locale={locale}
        mismatch={mismatch}
        onSchedule={handleSchedule}
        onLinkStudy={handleLinkStudy}
        onSign={handleSign}
        onAddendum={handleAddendum}
      />
    </div>
  );
}
