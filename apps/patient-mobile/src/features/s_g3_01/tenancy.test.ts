import { lookup, tenantKey } from './tenancy';

/** S-G3-01: identical local identifiers in two tenants never merge (PAT-01, IAM-01). */
describe('tenant-scoped keys', () => {
  it('separates the same local id in two organizations', () => {
    const a = tenantKey('org-a', 'apt-1');
    const b = tenantKey('org-b', 'apt-1');
    expect(a).not.toBe(b);
    expect(tenantKey('org-a', 'apt-1')).toBe(a);
  });

  it('looks up only the active tenant row', () => {
    const records = new Map<string, string>([
      [tenantKey('org-a', 'apt-1'), 'alpha'],
      [tenantKey('org-b', 'apt-1'), 'beta'],
    ]);
    expect(lookup(records, 'org-a', 'apt-1')).toBe('alpha');
    expect(lookup(records, 'org-b', 'apt-1')).toBe('beta');
    expect(lookup(records, 'org-c', 'apt-1')).toBeUndefined();
    expect(lookup(records, 'org-a', 'apt-2')).toBeUndefined();
  });
});
