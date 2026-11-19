import { afterEach, describe, expect, it, vi } from 'vitest';

const render = vi.fn();
vi.mock('react-dom/client', () => ({
  createRoot: vi.fn(() => ({ render })),
}));

afterEach(() => {
  vi.resetModules();
  document.getElementById('root')?.remove();
});

describe('Vite entry point', () => {
  it('mounts the app into the root element', async () => {
    const root = document.createElement('div');
    root.id = 'root';
    document.body.append(root);
    await import('../src/main');
    expect(render).toHaveBeenCalledTimes(1);
  });

  it('fails closed when the root element is missing', async () => {
    await expect(import('../src/main')).rejects.toThrow(/Root container/);
    expect(render).not.toHaveBeenCalled();
  });
});
