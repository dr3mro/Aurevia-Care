import { describe, expect, it } from 'vitest';
import type { DependencyWire } from './domain';
import {
  approvalBlocker,
  approvalBody,
  approvalFormValid,
  confirmationIssue,
  hasUnresolvedBlockers,
  identifierReference,
  identifierReferences,
  languageForName,
  toggleReference,
  validateApprovalForm,
} from './domain';
import {
  depsResolved,
  depsUnambiguous,
  depsUnresolved,
  detailWithoutReview,
  openerSubject,
  reviewOpen,
  secondReviewerSubject,
  syntheticDetail,
} from './fixtures';
import { must } from '../../../tests/must';

describe('identifier references', () => {
  it('builds an issuer-scoped reference from a patient', () => {
    expect(identifierReference(detailWithoutReview.survivor)).toBe('MRN-1001@SYNTHETIC');
  });

  it('returns both references in survivor order', () => {
    expect(identifierReferences(detailWithoutReview)).toEqual([
      'MRN-1001@SYNTHETIC',
      'MRN-1002@SYNTHETIC',
    ]);
  });

  it('toggles a reference on and off without duplicating it', () => {
    expect(toggleReference([], 'MRN-1001@SYNTHETIC')).toEqual(['MRN-1001@SYNTHETIC']);
    expect(toggleReference(['MRN-1001@SYNTHETIC'], 'MRN-1001@SYNTHETIC')).toEqual([]);
  });

  it('derives the run language from the script, not the interface locale', () => {
    expect(languageForName('ليلى حداد')).toBe('ar');
    expect(languageForName('Layla Haddad')).toBe('en');
  });
});

describe('hasUnresolvedBlockers', () => {
  it('blocks only on an ambiguous dependency that is not resolved', () => {
    expect(hasUnresolvedBlockers(depsUnresolved)).toBe(true);
  });

  it('does not block on a resolved ambiguous dependency', () => {
    expect(hasUnresolvedBlockers(depsResolved)).toBe(false);
  });

  it('does not block on an unambiguous dependency', () => {
    expect(hasUnresolvedBlockers(depsUnambiguous)).toBe(false);
  });

  it('does not block on an empty dependency list', () => {
    expect(hasUnresolvedBlockers([])).toBe(false);
  });
});

describe('approvalBlocker', () => {
  it('blocks when no review ticket has been opened', () => {
    expect(approvalBlocker(null, [], secondReviewerSubject)).toBe('no-open-review');
  });

  it('refuses the reviewer who opened the request', () => {
    expect(approvalBlocker(reviewOpen, [], openerSubject)).toBe('same-reviewer');
  });

  it('refuses a different reviewer while dependencies are unresolved', () => {
    expect(approvalBlocker(reviewOpen, depsUnresolved, secondReviewerSubject)).toBe(
      'unresolved-dependencies',
    );
  });

  it('allows a different reviewer when the pair is clear', () => {
    expect(approvalBlocker(reviewOpen, [], secondReviewerSubject)).toBeUndefined();
  });

  it('gives the same-reviewer rule precedence over unresolved dependencies', () => {
    expect(approvalBlocker(reviewOpen, depsUnresolved, openerSubject)).toBe('same-reviewer');
  });

  it('reads the blocker from the detail review when present', () => {
    expect(syntheticDetail.review?.openedBySubject).toBe(openerSubject);
  });
});

describe('confirmationIssue', () => {
  const first = 'MRN-1001@SYNTHETIC';
  const second = 'MRN-1002@SYNTHETIC';

  it('refuses identifiers and an alternate process together', () => {
    expect(confirmationIssue([first, second], 'documented_process')).toBe('both');
  });

  it('accepts a bounded alternate-process code on its own', () => {
    expect(confirmationIssue([], 'documented_process')).toBeUndefined();
  });

  it('refuses a malformed alternate-process code', () => {
    expect(confirmationIssue([], 'Not A Code')).toBe('alternate-format');
  });

  it('requires two identifiers when no alternate process is given', () => {
    expect(confirmationIssue([], '')).toBe('identifiers-required');
    expect(confirmationIssue([first], '')).toBe('identifiers-required');
  });

  it('requires two distinct identifiers', () => {
    expect(confirmationIssue([first, first], '')).toBe('identifiers-required');
  });

  it('refuses a malformed issuer-scoped identifier', () => {
    expect(confirmationIssue(['not-a-reference', second], '')).toBe('invalid-identifier');
  });

  it('accepts two distinct well-formed identifiers', () => {
    expect(confirmationIssue([first, second], '')).toBeUndefined();
  });
});

describe('validateApprovalForm', () => {
  const first = 'MRN-1001@SYNTHETIC';
  const second = 'MRN-1002@SYNTHETIC';

  it('reports a reason that is too short', () => {
    const errors = validateApprovalForm('x', [first, second], '');
    expect(errors.reason).toBe('reason');
    expect(errors.confirmation).toBeUndefined();
    expect(approvalFormValid(errors)).toBe(false);
  });

  it('reports a confirmation that is not established', () => {
    const errors = validateApprovalForm('duplicate registration', [], '');
    expect(errors.confirmation).toBe('confirmation');
    expect(approvalFormValid(errors)).toBe(false);
  });

  it('reports both problems together', () => {
    const errors = validateApprovalForm('x', [], '');
    expect(errors.reason).toBe('reason');
    expect(errors.confirmation).toBe('confirmation');
  });

  it('accepts a full form', () => {
    const errors = validateApprovalForm('duplicate registration', [first, second], '');
    expect(approvalFormValid(errors)).toBe(true);
  });
});

describe('approvalBody', () => {
  it('trims text and copies the identifier list without letting the caller mutate it', () => {
    const confirmed = ['MRN-1001@SYNTHETIC', 'MRN-1002@SYNTHETIC'];
    const body = approvalBody('  duplicate registration  ', confirmed, '  documented_process  ');
    expect(body).toEqual({
      reason: 'duplicate registration',
      confirmedIdentifiers: ['MRN-1001@SYNTHETIC', 'MRN-1002@SYNTHETIC'],
      alternateProcess: 'documented_process',
    });
    expect(body.confirmedIdentifiers).not.toBe(confirmed);
  });
});

/** Compile-time guard: dependency flags are the exact wire shape the guard reads. */
describe('dependency wire shape', () => {
  it('carries the ambiguous and resolved flags used by the blocker', () => {
    const dependency: DependencyWire = must(depsUnresolved[0]);
    expect(dependency.ambiguous).toBe(true);
    expect(dependency.resolved).toBe(false);
  });
});
