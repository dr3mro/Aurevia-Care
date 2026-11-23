import { describe, expect, it } from 'vitest';
import {
  PatientReferenceChip,
  ReferenceInserter,
  referenceKeys,
  referenceText,
  screenSubject,
} from './index';

describe('reference barrel', () => {
  it('exports the presentational components and screening helpers', () => {
    expect(PatientReferenceChip).toBeTypeOf('function');
    expect(ReferenceInserter).toBeTypeOf('function');
    expect(typeof screenSubject).toBe('function');
    expect(typeof referenceText).toBe('function');
    expect(referenceKeys.length).toBe(8);
  });
});
