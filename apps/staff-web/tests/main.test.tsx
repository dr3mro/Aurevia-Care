import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';
import type { MockInstance } from 'vitest';

/**
 * Shared render spy, hoisted so the hoisted `vi.mock` factory can reference it.
 * The real browser-support guard is deliberately left unmocked: synthetic user
 * agents and viewport widths must drive the support decision, not a stub.
 */
const mocks = vi.hoisted(() => ({
  render: vi.fn<(element: unknown) => void>(),
}));

vi.mock('react-dom/client', () => ({
  createRoot: vi.fn(() => ({ render: mocks.render })),
}));

const here = dirname(fileURLToPath(import.meta.url));
const readEntry = (): string => readFileSync(resolve(here, '..', 'src', 'main.tsx'), 'utf8');

/** Current desktop Chrome: recognised and supported by the real guard. */
const SUPPORTED_UA =
  'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36';
/** Internet Explorer is explicitly unsupported and receives no data (WEB-07). */
const UNSUPPORTED_UA = 'Mozilla/5.0 (compatible; MSIE 10.0; Windows NT 6.2)';

/** Component name of a rendered React element, or '' for a non-element child. */
function typeName(value: unknown): string {
  if (value === null || typeof value !== 'object') {
    return '';
  }
  const type = (value as { type?: unknown }).type;
  return typeof type === 'function' ? ((type as { name?: string }).name ?? '') : '';
}

/** The single child rendered inside StrictMode on the most recent render call. */
function renderedContent(): unknown {
  const call = mocks.render.mock.calls[mocks.render.mock.calls.length - 1];
  const rootElement = call?.[0] as { props?: { children?: unknown } } | undefined;
  return rootElement?.props?.children;
}

/** Walk the rendered element tree and report whether a component name appears. */
function containsComponent(value: unknown, name: string): boolean {
  if (value === null || value === undefined || typeof value !== 'object') {
    return false;
  }
  const node = value as { type?: unknown; props?: { children?: unknown } };
  if (typeof node.type === 'function' && (node.type as { name?: string }).name === name) {
    return true;
  }
  const children = node.props?.children;
  if (Array.isArray(children)) {
    return children.some((child) => containsComponent(child, name));
  }
  return containsComponent(children, name);
}

/** Viewport change handlers captured from `window.addEventListener`. */
const viewportHandlers: Record<string, () => void> = {};
let addSpy: MockInstance<typeof window.addEventListener>;

function setWidth(width: number): void {
  Object.defineProperty(window, 'innerWidth', { configurable: true, writable: true, value: width });
}

function setUserAgent(userAgent: string): void {
  Object.defineProperty(navigator, 'userAgent', { configurable: true, value: userAgent });
}

beforeEach(() => {
  setUserAgent(SUPPORTED_UA);
  setWidth(1024);
  addSpy = vi.spyOn(window, 'addEventListener').mockImplementation((type, listener) => {
    if (type === 'resize' || type === 'orientationchange') {
      viewportHandlers[type] = listener as () => void;
    }
  });
});

afterEach(() => {
  vi.resetModules();
  mocks.render.mockClear();
  addSpy.mockRestore();
  for (const key of Object.keys(viewportHandlers)) {
    Reflect.deleteProperty(viewportHandlers, key);
  }
  document.getElementById('root')?.remove();
  Reflect.deleteProperty(navigator, 'userAgent');
  setWidth(1024);
});

/** Add the mount container the entry point requires. */
function mountRoot(): void {
  const root = document.createElement('div');
  root.id = 'root';
  document.body.append(root);
}

describe('Vite entry point', () => {
  it('imports the responsive stylesheet at start-up', () => {
    expect(readEntry()).toMatch(/import\s+['"]\.\/theme\/responsive\.css['"]/);
  });

  it('mounts the app when the browser check passes', async () => {
    mountRoot();
    await import('../src/main');
    expect(mocks.render).toHaveBeenCalledTimes(1);
    expect(typeName(renderedContent())).toBe('App');
    expect(viewportHandlers.resize).toBeTypeOf('function');
    expect(viewportHandlers.orientationchange).toBeTypeOf('function');
  });

  it('keeps the application mounted when resizing across 768 px', async () => {
    mountRoot();
    setWidth(800);
    await import('../src/main');
    expect(typeName(renderedContent())).toBe('App');

    setWidth(360); // below the old 768 floor, above the 320 reflow floor
    viewportHandlers.resize?.();

    expect(mocks.render).toHaveBeenCalledTimes(2);
    expect(typeName(renderedContent())).toBe('App');
  });

  it('re-evaluates support when the device rotates', async () => {
    mountRoot();
    await import('../src/main');
    expect(typeName(renderedContent())).toBe('App');

    viewportHandlers.orientationchange?.();

    expect(mocks.render).toHaveBeenCalledTimes(2);
    expect(typeName(renderedContent())).toBe('App');
  });

  it('uses the new floor when a sustained resize falls below 320 px', async () => {
    mountRoot();
    await import('../src/main');
    expect(typeName(renderedContent())).toBe('App');

    setWidth(319);
    viewportHandlers.resize?.();

    expect(mocks.render).toHaveBeenCalledTimes(2);
    expect(typeName(renderedContent())).toBe('ThemeProvider');
    expect(containsComponent(renderedContent(), 'UnsupportedBrowserPage')).toBe(true);
  });

  it('mounts only the plain unsupported notice and no app for an unsupported browser', async () => {
    setUserAgent(UNSUPPORTED_UA);
    mountRoot();
    await import('../src/main');
    expect(mocks.render).toHaveBeenCalledTimes(1);
    const content = renderedContent();
    expect(typeName(content)).toBe('ThemeProvider');
    expect(containsComponent(content, 'I18nProvider')).toBe(true);
    expect(containsComponent(content, 'UnsupportedBrowserPage')).toBe(true);
    expect(containsComponent(content, 'App')).toBe(false);
  });

  it('keeps the plain notice and no app when an unsupported client resizes or rotates', async () => {
    setUserAgent(UNSUPPORTED_UA);
    mountRoot();
    await import('../src/main');
    expect(mocks.render).toHaveBeenCalledTimes(1);

    viewportHandlers.resize?.();
    viewportHandlers.orientationchange?.();

    expect(mocks.render).toHaveBeenCalledTimes(3);
    const content = renderedContent();
    expect(typeName(content)).toBe('ThemeProvider');
    expect(containsComponent(content, 'I18nProvider')).toBe(true);
    expect(containsComponent(content, 'UnsupportedBrowserPage')).toBe(true);
    expect(containsComponent(content, 'App')).toBe(false);
  });

  it('fails closed when the root element is missing', async () => {
    await expect(import('../src/main')).rejects.toThrow(/Root container/);
    expect(mocks.render).not.toHaveBeenCalled();
  });
});
