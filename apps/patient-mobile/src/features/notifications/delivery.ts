import { eligibleTokens, invalidateToken, withInvalidatedDevice } from '../appointments/devices';
import type { DeviceToken, GenericNotice, OrganizationId } from '../appointments/types';
import type {
  DeliveryDisposition,
  DeliveryOutcome,
  DispatchResult,
  InboxItem,
  NoticeLogger,
  PushGateway,
} from './types';

/** Everything needed to dispatch one generic notice to one patient's devices. */
export interface DispatchInput {
  readonly devices: ReadonlyMap<string, DeviceToken>;
  readonly organizationId: OrganizationId;
  readonly patientId: string;
  /** Items already in the authenticated inbox; delivery never filters them. */
  readonly inbox: readonly InboxItem[];
  readonly notice: GenericNotice;
  readonly gateway: PushGateway;
  readonly logger: NoticeLogger;
  readonly at: string;
}

/**
 * Deliver a generic notice to the verified patient's currently eligible devices.
 *
 * A token the provider rejects is invalidated (so `eligibleTokens` stops
 * selecting it on the next evaluation) and reported through a log event that
 * never contains the provider token (PAT-01, OPS-02). A delivery failure does
 * not remove the inbox item: the patient still finds it in the authenticated
 * app (ARC-07, MSG-05).
 *
 * @param input tenant-scoped registry, generic notice and injected boundaries
 * @returns the updated registry, the unchanged inbox and per-device outcomes
 */
export async function dispatchNotice(input: DispatchInput): Promise<DispatchResult> {
  let devices: Map<string, DeviceToken> = new Map(input.devices);
  const outcomes: DeliveryOutcome[] = [];
  const recipients = eligibleTokens(input.devices, input.organizationId, input.patientId);

  for (const recipient of recipients) {
    const disposition: DeliveryDisposition = await input.gateway.send(recipient.token, input.notice);
    outcomes.push({ deviceId: recipient.id, disposition });
    if (disposition.kind === 'invalid') {
      const invalidated = invalidateToken(recipient, disposition.reason, input.at);
      input.logger.invalidToken(invalidated.event);
      devices = withInvalidatedDevice(
        devices,
        input.organizationId,
        invalidated.token.id,
        invalidated.token.patientId,
      );
    }
  }

  return { devices, inbox: input.inbox, outcomes };
}
