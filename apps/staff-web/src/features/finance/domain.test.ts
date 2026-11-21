import { describe, expect, it } from 'vitest';
import { availableActions, invoiceStates } from './domain';
import { syntheticInvoice } from './fixtures';

describe('finance domain', () => {
  it('lists all five invoice lifecycle states in filter order', () => {
    expect(invoiceStates).toEqual(['draft', 'issued', 'partially_paid', 'paid', 'void']);
  });

  it('offers issue and void for a draft', () => {
    expect(availableActions(syntheticInvoice({ state: 'draft' }))).toEqual({
      issue: true,
      payment: false,
      refund: false,
      credit: false,
      void: true,
    });
  });

  it('offers payment, credit and void for an issued invoice', () => {
    expect(availableActions(syntheticInvoice({ state: 'issued' }))).toEqual({
      issue: false,
      payment: true,
      refund: false,
      credit: true,
      void: true,
    });
  });

  it('offers refund only after money has been received on a partially paid invoice', () => {
    expect(availableActions(syntheticInvoice({ state: 'partially_paid', paidMinor: 100 }))).toEqual({
      issue: false,
      payment: true,
      refund: true,
      credit: true,
      void: false,
    });
    expect(availableActions(syntheticInvoice({ state: 'partially_paid', paidMinor: 0 }))).toEqual({
      issue: false,
      payment: true,
      refund: false,
      credit: true,
      void: false,
    });
  });

  it('offers only a refund for a paid invoice', () => {
    expect(availableActions(syntheticInvoice({ state: 'paid', paidMinor: 5000 }))).toEqual({
      issue: false,
      payment: false,
      refund: true,
      credit: false,
      void: false,
    });
  });

  it('offers no command for a terminal void invoice', () => {
    expect(availableActions(syntheticInvoice({ state: 'void' }))).toEqual({
      issue: false,
      payment: false,
      refund: false,
      credit: false,
      void: false,
    });
  });
});
