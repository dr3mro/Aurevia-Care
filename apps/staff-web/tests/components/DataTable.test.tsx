import { describe, expect, it } from 'vitest';
import { render, screen } from '@testing-library/react';
import { ThemeProvider } from '../../src/theme/ThemeProvider';
import { DataTable } from '../../src/components/DataTable';

const columns = [
  { key: 'code', header: 'Code' },
  { key: 'name', header: 'Name' },
];

describe('accessible DataTable', () => {
  it('renders a captioned table with column headers and rows', () => {
    render(
      <ThemeProvider>
        <DataTable
          caption="Facilities"
          columns={columns}
          rows={[{ code: 'MAIN', name: 'Alpha Main' }]}
          emptyLabel="None"
        />
      </ThemeProvider>,
    );
    const table = screen.getByRole('table', { name: 'Facilities' });
    expect(table.querySelector('caption')?.textContent).toBe('Facilities');
    expect(screen.getByRole('columnheader', { name: 'Code' }).getAttribute('scope')).toBe('col');
    expect(screen.getByRole('cell', { name: 'Alpha Main' })).toBeTruthy();
  });

  it('preserves an empty cell and shows an empty state without rows', () => {
    const { rerender } = render(
      <ThemeProvider>
        <DataTable
          caption="Facilities"
          columns={columns}
          rows={[{ code: 'MAIN' }]}
          emptyLabel="None"
        />
      </ThemeProvider>,
    );
    expect(screen.getAllByRole('cell')).toHaveLength(2);
    expect(screen.getAllByRole('cell')[1]?.textContent).toBe('');

    rerender(
      <ThemeProvider>
        <DataTable caption="Facilities" columns={columns} rows={[]} emptyLabel="No facilities" />
      </ThemeProvider>,
    );
    expect(screen.getByRole('cell', { name: 'No facilities' }).getAttribute('colspan')).toBe('2');
  });
});
