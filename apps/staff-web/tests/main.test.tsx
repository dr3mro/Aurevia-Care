import { afterEach, describe, expect, it, vi } from 'vitest';

/** Shared spies, hoisted so vi.mock factories can reference them safely. */
const mocks = vi.hoisted(() => ({
  render: vi.fn<(element: unknown) => void>(),
  isSupported: vi.fn<(userAgent: string, width: number) => boolean>(),
}));

vi.mock('react-dom/client', () => ({
  createRoot: vi.fn(() => ({ render: mocks.render })),
}));

vi.mock('../src/features/system/browserSupport', () => ({
  isSupportedBrowser: mocks.isSupported,
}));

/** Minimal view of a React element, used only to inspect the entry tree. */
interface ElementNode {
  type: unknown;
  props: { children?: unknown };
}

/** Treat a value from the render spy as a React element. */
function asNode(value: unknown): ElementNode {
  return value as ElementNode;
}

/** Component name, stable across the module resets between entry-point runs. */
function typeName(node: ElementNode): string {
  return (node.type as { name?: string }).name ?? '';
}

afterEach(() => {
  vi.resetModules();
  mocks.render.mockClear();
  mocks.isSupported.mockReset();
  document.getElementById('root')?.remove();
});

/** Add the mount container the entry point requires. */
function mountRoot(): void {
  const root = document.createElement('div');
  root.id = 'root';
  document.body.append(root);
}

describe('Vite entry point', () => {
  it('mounts the app when the browser check passes', async () => {
    mountRoot();
    mocks.isSupported.mockReturnValue(true);
    await import('../src/main');
    expect(mocks.isSupported).toHaveBeenCalledWith(navigator.userAgent, window.innerWidth);
    expect(mocks.render).toHaveBeenCalledTimes(1);
    const rootElement = asNode(mocks.render.mock.calls[0]![0]);
    expect(typeName(asNode(rootElement.props.children))).toBe('App');
  });

  it('mounts only the unsupported page when the browser check fails', async () => {
    mountRoot();
    mocks.isSupported.mockReturnValue(false);
    await import('../src/main');
    expect(mocks.render).toHaveBeenCalledTimes(1);
    const rootElement = asNode(mocks.render.mock.calls[0]![0]);
    const themeProvider = asNode(rootElement.props.children);
    expect(typeName(themeProvider)).toBe('ThemeProvider');
    const i18nProvider = asNode(themeProvider.props.children);
    expect(typeName(i18nProvider)).toBe('I18nProvider');
    const page = asNode(i18nProvider.props.children);
    expect(typeName(page)).toBe('UnsupportedBrowserPage');
  });

  it('fails closed when the root element is missing', async () => {
    mocks.isSupported.mockReturnValue(true);
    await expect(import('../src/main')).rejects.toThrow(/Root container/);
    expect(mocks.render).not.toHaveBeenCalled();
  });
});
