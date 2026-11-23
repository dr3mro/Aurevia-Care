import { describe, expect, it } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { I18nProvider } from '../../src/i18n/I18nProvider';
import { ThemeProvider } from '../../src/theme/ThemeProvider';
import { OrgSwitcher } from '../../src/app/OrgSwitcher';
import { syntheticOrganizations } from '../../src/fixtures/synthetic';

function renderSwitcher(organizations = syntheticOrganizations) {
  return render(
    <ThemeProvider>
      <I18nProvider>
        <OrgSwitcher organizations={organizations} />
      </I18nProvider>
    </ThemeProvider>,
  );
}

describe('tenant/organization switcher placeholder', () => {
  it('lists synthetic organizations and reflects the active selection', () => {
    renderSwitcher();
    const select = screen.getByLabelText<HTMLSelectElement>('Organization');
    const options = screen.getAllByRole<HTMLOptionElement>('option');
    expect(options).toHaveLength(syntheticOrganizations.length);
    expect(select.value).toBe(syntheticOrganizations[0]?.id);
    const second = syntheticOrganizations[1];
    if (!second) {
      throw new Error('fixture needs a second organization');
    }
    fireEvent.change(select, { target: { value: second.id } });
    expect(screen.getByRole('status').textContent).toBe(second.name);
  });

  it('does not crash with no organizations', () => {
    renderSwitcher([]);
    expect(screen.queryAllByRole('option')).toHaveLength(0);
    expect(screen.getByRole('status').textContent).toBe('');
  });
});
