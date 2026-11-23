import { describe, expect, it } from 'vitest';
import type { ActiveOrder, OrderDraftContent } from './domain';
import {
  canOrder,
  contentFromDraft,
  duplicateActiveOrders,
  emptyContent,
  hasImaging,
  OrderConflict,
  removeSelectedItem,
  searchCatalog,
  selectCatalogItem,
  validateContent,
} from './domain';
import {
  entryAlphaOrganizationId,
  entryBetaOrganizationId,
  syntheticActiveOrders,
  syntheticCatalog,
  syntheticFacilityId,
  syntheticPatientLocalId,
  syntheticSavedDraft,
} from './fixtures';

const imaging = required(syntheticCatalog.find((item) => item.id === 'rad-ct'));
const lab = required(syntheticCatalog.find((item) => item.id === 'lab-cbc'));

describe('order entry domain', () => {
  it('searches only the active tenant and excludes retired items', () => {
    expect(searchCatalog(syntheticCatalog, entryAlphaOrganizationId, '')).not.toContainEqual(
      expect.objectContaining({ id: 'lab-retired' }),
    );
    expect(searchCatalog(syntheticCatalog, entryAlphaOrganizationId, '')).not.toContainEqual(
      expect.objectContaining({ organizationId: entryBetaOrganizationId }),
    );
    expect(searchCatalog(syntheticCatalog, entryAlphaOrganizationId, '')).toHaveLength(4);
  });

  it('matches a catalog item by code, latin name and local name, case-insensitively', () => {
    expect(searchCatalog(syntheticCatalog, entryAlphaOrganizationId, 'lab-cbc')).toEqual([lab]);
    expect(searchCatalog(syntheticCatalog, entryAlphaOrganizationId, 'FASTING')).toEqual([
      syntheticCatalog.find((item) => item.id === 'lab-fast'),
    ]);
    expect(searchCatalog(syntheticCatalog, entryAlphaOrganizationId, 'بطن')).toEqual([imaging]);
    expect(searchCatalog(syntheticCatalog, entryAlphaOrganizationId, 'no such test')).toEqual([]);
  });

  it('selects an item once, capturing its version and prefilling blank preparation', () => {
    const empty = emptyContent(syntheticPatientLocalId, 'enc-001', syntheticFacilityId);
    const selected = selectCatalogItem(empty, imaging);
    expect(selected.items).toEqual([
      {
        itemId: imaging.id,
        version: imaging.version,
        code: imaging.code,
        name: imaging.name,
        category: imaging.category,
      },
    ]);
    expect(selected.preparationInstructions).toBe(imaging.preparationInstructions);
    expect(selectCatalogItem(selected, imaging)).toBe(selected);
  });

  it('keeps clinician-edited preparation instructions when selecting another item', () => {
    const edited: OrderDraftContent = {
      ...emptyContent(syntheticPatientLocalId, 'enc-001', syntheticFacilityId),
      preparationInstructions: 'Clinician edited preparation.',
    };
    const selected = selectCatalogItem(edited, imaging);
    expect(selected.preparationInstructions).toBe('Clinician edited preparation.');
  });

  it('removes only the requested selected item', () => {
    const withTwo = selectCatalogItem(
      selectCatalogItem(emptyContent(syntheticPatientLocalId, 'enc-001', syntheticFacilityId), lab),
      imaging,
    );
    const removed = removeSelectedItem(withTwo, lab.id);
    expect(removed.items.map((item) => item.itemId)).toEqual([imaging.id]);
    expect(removeSelectedItem(withTwo, 'missing')).toBe(withTwo);
  });

  it('copies a saved draft without sharing its mutable item array', () => {
    const copied = contentFromDraft(syntheticSavedDraft);
    expect(copied).toEqual(syntheticSavedDraft.content);
    copied.items.pop();
    expect(syntheticSavedDraft.content.items).toHaveLength(1);
  });

  it('detects imaging in the selection and requires a clinical question only then', () => {
    const labOnly = selectCatalogItem(
      emptyContent(syntheticPatientLocalId, 'enc-001', syntheticFacilityId),
      lab,
    );
    expect(hasImaging(labOnly)).toBe(false);
    expect(validateContent(labOnly)).toEqual([]);

    const imagingDraft = selectCatalogItem(labOnly, imaging);
    expect(hasImaging(imagingDraft)).toBe(true);
    expect(validateContent(imagingDraft)).toEqual(['clinicalQuestion']);
    expect(
      validateContent({ ...imagingDraft, clinicalQuestion: '  Synthetic question  ' }),
    ).toEqual([]);
  });

  it('reports an empty selection before any clinical question check', () => {
    const empty = emptyContent(syntheticPatientLocalId, 'enc-001', syntheticFacilityId);
    expect(validateContent(empty)).toEqual(['noItems']);
  });

  it('warns only for active orders in the same tenant that match a selected code', () => {
    const withImaging = selectCatalogItem(
      emptyContent(syntheticPatientLocalId, 'enc-001', syntheticFacilityId),
      imaging,
    );
    const matches = duplicateActiveOrders(
      withImaging,
      syntheticActiveOrders,
      entryAlphaOrganizationId,
    );
    expect(matches.map((order) => order.id)).toEqual(['order-existing']);
    expect(
      duplicateActiveOrders(withImaging, syntheticActiveOrders, entryBetaOrganizationId),
    ).toEqual([]);
    expect(duplicateActiveOrders(withImaging, [], entryAlphaOrganizationId)).toEqual([]);

    const otherTenantOrder: ActiveOrder = {
      organizationId: entryAlphaOrganizationId,
      id: 'other',
      catalogCode: 'LAB-CBC',
      display: 'Other',
      status: 'draft',
    };
    expect(
      duplicateActiveOrders(withImaging, [otherTenantOrder], entryAlphaOrganizationId),
    ).toEqual([]);
  });

  it('reserves ordering to the clinician role', () => {
    expect(canOrder('clinician')).toBe(true);
    expect(canOrder('reception')).toBe(false);
    expect(canOrder('lab')).toBe(false);
  });

  it('carries the server draft in a stale-version conflict', () => {
    const conflict = new OrderConflict(syntheticSavedDraft);
    expect(conflict.name).toBe('OrderConflict');
    expect(conflict.server).toBe(syntheticSavedDraft);
    expect(conflict.message).toContain('Stale order draft');
  });
});

function required<T>(value: T | null | undefined): T {
  if (value === null || value === undefined) {
    throw new Error('Expected value to be present');
  }
  return value;
}
