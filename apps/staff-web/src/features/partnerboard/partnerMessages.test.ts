import { describe, expect, it } from 'vitest';

import {
  fallbackDetailKey,
  fallbackLabelKey,
  healthKey,
  partnerKeys,
  partnerMessage,
  partnerMessages,
  partnerText,
  responseKey,
  reviewKey,
  stuckKey,
  transmissionKey,
  waitMinutesText,
  workflowKey,
} from './partnerMessages';

/** Named placeholder tokens in a message, sorted for comparison. */
function placeholders(value: string): string[] {
  return [...value.matchAll(/\{(\w+)\}/g)].map((match) => required(match[1])).sort();
}

describe('partner board bilingual bundle', () => {
  it('keeps identical key sets in English and Arabic', () => {
    expect(Object.keys(partnerMessages.en).sort()).toEqual([...partnerKeys].sort());
    expect(Object.keys(partnerMessages.ar).sort()).toEqual([...partnerKeys].sort());
  });

  it('gives every key a non-empty label in both locales', () => {
    for (const key of partnerKeys) {
      expect(partnerMessages.en[key].trim()).not.toBe('');
      expect(partnerMessages.ar[key].trim()).not.toBe('');
    }
  });

  it('uses the same named placeholders in both locales', () => {
    for (const key of partnerKeys) {
      expect(placeholders(partnerMessages.ar[key])).toEqual(placeholders(partnerMessages.en[key]));
    }
  });

  it('translates Arabic labels into Arabic script', () => {
    for (const key of partnerKeys) {
      expect(
        /[\u0621-\u063A\u0641-\u064A\u066E-\u066F\u0671-\u06D3\u06D5\u06FA-\u06FC\u06FF]/.test(
          partnerMessages.ar[key],
        ),
      ).toBe(true);
    }
  });

  it('looks up a label for either locale', () => {
    expect(partnerText('en', 'title')).toBe('Partner interface and outage board');
    expect(partnerText('ar', 'healthDegraded')).toBe('متدنٍ');
  });
});

describe('partner board message helpers', () => {
  it('fills named placeholders and leaves unknown ones visible', () => {
    expect(partnerMessage('en', 'waitingCount', { count: '3' })).toBe('Waiting: 3');
    expect(partnerMessage('en', 'waitingCount', {})).toBe('Waiting: {count}');
  });

  it('formats wait minutes with Latin digits in both locales', () => {
    expect(waitMinutesText(47, 'en')).toBe('47');
    expect(waitMinutesText(47, 'ar')).toBe('47');
  });

  it('maps each interface health state to its label', () => {
    expect(healthKey('healthy')).toBe('healthHealthy');
    expect(healthKey('degraded')).toBe('healthDegraded');
    expect(healthKey('down')).toBe('healthDown');
  });

  it('maps each manual fallback to its label and procedure detail', () => {
    expect(fallbackLabelKey('print')).toBe('fallbackPrint');
    expect(fallbackLabelKey('call')).toBe('fallbackCall');
    expect(fallbackLabelKey('manual-upload')).toBe('fallbackManualUpload');
    expect(fallbackDetailKey('print')).toBe('fallbackPrintDetail');
    expect(fallbackDetailKey('call')).toBe('fallbackCallDetail');
    expect(fallbackDetailKey('manual-upload')).toBe('fallbackManualUploadDetail');
  });

  it('maps every transmission and workflow state to its label', () => {
    expect(transmissionKey('pending')).toBe('transmissionPending');
    expect(transmissionKey('sent')).toBe('transmissionSent');
    expect(transmissionKey('delivered')).toBe('transmissionDelivered');
    expect(transmissionKey('failed')).toBe('transmissionFailed');
    expect(workflowKey('requested')).toBe('workflowRequested');
    expect(workflowKey('accepted')).toBe('workflowAccepted');
    expect(workflowKey('processing')).toBe('workflowProcessing');
    expect(workflowKey('completed')).toBe('workflowCompleted');
    expect(workflowKey('rejected')).toBe('workflowRejected');
    expect(workflowKey('cancelled')).toBe('workflowCancelled');
  });

  it('maps every review and recipient state to its label', () => {
    expect(reviewKey('unreviewed')).toBe('reviewUnreviewed');
    expect(reviewKey('acknowledged')).toBe('reviewAcknowledged');
    expect(reviewKey('action-pending')).toBe('reviewActionPending');
    expect(reviewKey('resolved')).toBe('reviewResolved');
    expect(responseKey('accepted')).toBe('responseAccepted');
    expect(responseKey('rejected')).toBe('responseRejected');
    expect(responseKey('clarification-requested')).toBe('responseClarification');
    expect(responseKey(null)).toBe('responseNone');
  });

  it('labels stuck and on-track rows with words', () => {
    expect(stuckKey(true)).toBe('stuck');
    expect(stuckKey(false)).toBe('onTrack');
  });
});

function required<T>(value: T | null | undefined): T {
  if (value === null || value === undefined) {
    throw new Error('Expected value to be present');
  }
  return value;
}
