import { useState } from 'react';
import { Button } from '../../components/Button';
import { useI18n } from '../../i18n/I18nProvider';
import { useTheme } from '../../theme/ThemeProvider';
import type { FreeSlot, StaleSlot } from './domain';
import { schedulingText } from './messages';

/** Props for the stale-slot booking check panel. */
export interface BookingCheckPanelProps {
  staleSlot: StaleSlot | undefined;
  onPickSlot: (slot: FreeSlot) => void;
}

/**
 * Warn when a protected slot was just taken and offer the nearest free slots.
 * A single pick is latched locally: once one alternative is chosen no other
 * button can submit, so a stale double-click cannot book twice (CLN-01). The
 * server still owns real slot exclusivity; this guard is presentation only.
 */
export function BookingCheckPanel({ staleSlot, onPickSlot }: BookingCheckPanelProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const [picked, setPicked] = useState<FreeSlot | undefined>(undefined);

  if (staleSlot === undefined) {
    return (
      <section
        data-testid="booking-check-panel"
        style={{ paddingBlock: tokens.spacing.lg, paddingInline: tokens.spacing.lg, border: `1px solid ${tokens.border}`, background: tokens.background, borderRadius: tokens.spacing.sm }}
      >
        <h3>{schedulingText(locale, 'bookingCheckTitle')}</h3>
      </section>
    );
  }

  function pick(slot: FreeSlot) {
    if (picked !== undefined) {
      return;
    }
    setPicked(slot);
    onPickSlot(slot);
  }

  return (
    <section
      data-testid="booking-check-panel"
      aria-label={schedulingText(locale, 'bookingCheckTitle')}
      style={{
        display: 'grid',
        gap: tokens.spacing.md,
        paddingBlock: tokens.spacing.lg, paddingInline: tokens.spacing.lg,
        border: `1px solid ${tokens.border}`,
        borderRadius: tokens.spacing.sm,
        background: tokens.background,
        color: tokens.foreground,
        minInlineSize: 0,
        overflowWrap: 'anywhere',
      }}
    >
      <h3 style={{ marginBlock: 0 }}>{schedulingText(locale, 'bookingCheckTitle')}</h3>
      <div
        role="alert"
        style={{
          paddingBlock: tokens.spacing.md, paddingInline: tokens.spacing.md,
          border: `1px solid ${tokens.border}`,
          borderRadius: tokens.spacing.sm,
          background: 'var(--sc-color-status-warning-surface, transparent)',
        }}
      >
        <strong>{schedulingText(locale, 'slotTakenAlert')}</strong>
        <p style={{ marginBlock: 0 }}>
          {staleSlot.label} · {staleSlot.takenBy}
        </p>
      </div>
      <p style={{ marginBlock: 0 }}>{schedulingText(locale, 'nearestSlots')}</p>
      <div style={{ display: 'flex', flexWrap: 'wrap', gap: tokens.spacing.sm }}>
        {staleSlot.freeSlots.map((slot) => (
          <div key={slot.scheduledAt} data-testid="free-slot">
            <Button disabled={picked !== undefined} onClick={() => pick(slot)}>
              {slot.label}
            </Button>
          </div>
        ))}
      </div>
      {picked !== undefined ? (
        <p role="status" style={{ marginBlock: 0 }}>
          {schedulingText(locale, 'slotPicked')}: {picked.label}
        </p>
      ) : null}
      <p style={{ marginBlock: 0 }}>{schedulingText(locale, 'overlapNote')}</p>
    </section>
  );
}
