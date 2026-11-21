import { act, render, screen } from '@testing-library/react';
import { beforeEach, describe, expect, it } from 'vitest';
import { alphaOrganizationId, betaOrganizationId, conversationId } from './fixtures';
import { markConversationRead, resetReadReceipts, useUnreadCount } from './unreadStore';

/** Minimal consumer of the unread hook. */
function Probe({
  organizationId,
  serverCount,
}: {
  organizationId: string;
  serverCount: number;
}) {
  const count = useUnreadCount(organizationId, conversationId, serverCount);
  return <span data-testid={`count-${organizationId}`}>{count}</span>;
}

beforeEach(() => {
  resetReadReceipts();
});

describe('in-memory read receipts', () => {
  it('ignores a receipt written while nobody is subscribed', () => {
    expect(() => markConversationRead(alphaOrganizationId, conversationId, 0)).not.toThrow();
  });

  it('shows the server count until a successful read replaces it', () => {
    render(<Probe organizationId={alphaOrganizationId} serverCount={3} />);
    expect(screen.getByTestId(`count-${alphaOrganizationId}`).textContent).toBe('3');

    act(() => markConversationRead(alphaOrganizationId, conversationId, 0));
    expect(screen.getByTestId(`count-${alphaOrganizationId}`).textContent).toBe('0');
  });

  it('restores the server count when receipts are reset', () => {
    render(<Probe organizationId={alphaOrganizationId} serverCount={2} />);
    act(() => markConversationRead(alphaOrganizationId, conversationId, 0));
    expect(screen.getByTestId(`count-${alphaOrganizationId}`).textContent).toBe('0');

    act(() => resetReadReceipts());
    expect(screen.getByTestId(`count-${alphaOrganizationId}`).textContent).toBe('2');
  });

  it('keeps receipts of two tenants with the same conversation id apart', () => {
    render(
      <>
        <Probe organizationId={alphaOrganizationId} serverCount={4} />
        <Probe organizationId={betaOrganizationId} serverCount={5} />
      </>,
    );
    act(() => markConversationRead(betaOrganizationId, conversationId, 0));
    expect(screen.getByTestId(`count-${alphaOrganizationId}`).textContent).toBe('4');
    expect(screen.getByTestId(`count-${betaOrganizationId}`).textContent).toBe('0');
  });

  it('unsubscribes when the consumer unmounts', () => {
    const view = render(<Probe organizationId={alphaOrganizationId} serverCount={1} />);
    view.unmount();
    expect(() => act(() => markConversationRead(alphaOrganizationId, conversationId, 0))).not.toThrow();
  });
});
