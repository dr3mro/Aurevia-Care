import { describe, expect, it, vi } from 'vitest';
import { fireEvent, render, screen } from '@testing-library/react';
import { ThemeProvider } from '../../src/theme/ThemeProvider';
import { Dialog } from '../../src/components/Dialog';

function renderDialog(onClose: () => void, open = true) {
  return render(
    <ThemeProvider>
      <Dialog open={open} title="Details" closeLabel="Close dialog" onClose={onClose}>
        <span>synthetic detail</span>
      </Dialog>
    </ThemeProvider>,
  );
}

describe('accessible Dialog', () => {
  it('renders nothing when closed', () => {
    const { container } = renderDialog(() => undefined, false);
    expect(container.firstChild).toBeNull();
    expect(screen.queryByRole('dialog')).toBeNull();
  });

  it('renders a labelled modal, moves focus inside and closes on the explicit action', () => {
    const onClose = vi.fn();
    renderDialog(onClose);
    const dialog = screen.getByRole('dialog', { name: 'Details' });
    expect(dialog.getAttribute('aria-modal')).toBe('true');
    expect(dialog.getAttribute('aria-labelledby')).toBeTruthy();
    expect(screen.getByText('synthetic detail')).toBeTruthy();
    const closeButton = screen.getByRole('button', { name: 'Close dialog' });
    expect(document.activeElement).toBe(closeButton);
    closeButton.click();
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('closes on Escape and ignores unrelated keys', () => {
    const onClose = vi.fn();
    renderDialog(onClose);
    fireEvent.keyDown(document, { key: 'Enter' });
    expect(onClose).not.toHaveBeenCalled();
    fireEvent.keyDown(document, { key: 'Escape' });
    expect(onClose).toHaveBeenCalledTimes(1);
  });

  it('keeps Tab focus inside the modal', () => {
    renderDialog(() => undefined);
    const closeButton = screen.getByRole('button', { name: 'Close dialog' });
    const notPrevented = fireEvent.keyDown(document, { key: 'Tab' });
    expect(notPrevented).toBe(false);
    expect(document.activeElement).toBe(closeButton);
  });

  it('restores focus to the trigger when the dialog unmounts', () => {
    const triggerRender = render(<button type="button">trigger</button>);
    const trigger = triggerRender.getByRole('button', { name: 'trigger' });
    trigger.focus();
    const dialog = renderDialog(() => undefined);
    expect(document.activeElement).toBe(screen.getByRole('button', { name: 'Close dialog' }));
    dialog.unmount();
    expect(document.activeElement).toBe(trigger);
  });

  it('closes cleanly when no element was focused before opening', () => {
    const descriptor = Object.getOwnPropertyDescriptor(document, 'activeElement');
    Object.defineProperty(document, 'activeElement', { configurable: true, value: null });
    try {
      const dialog = renderDialog(() => undefined);
      expect(screen.getByRole('dialog')).toBeTruthy();
      dialog.unmount();
    } finally {
      if (descriptor) {
        Object.defineProperty(document, 'activeElement', descriptor);
      } else {
        Reflect.deleteProperty(document, 'activeElement');
      }
    }
  });
});
