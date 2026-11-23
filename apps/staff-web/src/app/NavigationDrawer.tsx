import { useCallback, useEffect, useRef, useState } from 'react';
import { defined } from '../components/invariant';
import { useI18n } from '../i18n/I18nProvider';
import { groupLabelKey, navigationGroupsFor, navigationLabel } from './navigation';
import type { Role } from './navigation';
import { navigate } from './router';
import { getMatchMedia } from '../theme/matchMedia';

/** DOM id of the drawer, referenced by the menu button's `aria-controls`. */
const DRAWER_ID = 'staff-navigation-drawer';

/** Focusable descendants kept inside the drawer while it is modal. */
const FOCUSABLE = 'a[href], button:not([disabled])';

/** Slide used for the logical inset; removed when reduced motion is preferred. */
const SLIDE_TRANSITION = 'inset-inline-start 180ms cubic-bezier(0.2, 0, 0.2, 1)';

/** Props for the compact-breakpoint navigation drawer (WEB-07, UX-01). */
export interface NavigationDrawerProps {
  /** The signed-in role; only its usable screen-map items are rendered. */
  role: Role;
  /**
   * Called with the chosen route when a link is activated. When omitted the
   * in-application router performs the navigation.
   */
  onNavigate?: ((route: string) => void) | undefined;
}

/**
 * Read the reduced-motion preference from the media query. `matchMedia` is
 * absent during server render and in older test environments, where the
 * preference is treated as "no preference" and the normal transition applies.
 */
function prefersReducedMotion(): boolean {
  return getMatchMedia()?.('(prefers-reduced-motion: reduce)').matches ?? false;
}

/**
 * Off-canvas navigation for phones. A labelled menu button opens a modal drawer
 * holding the same role-scoped groups and links as the full rail. The drawer
 * opens from the inline-start edge, so the document direction mirrors it under
 * right-to-left without a second stylesheet (WEB-07, UX-01).
 */
export function NavigationDrawer({ role, onNavigate }: NavigationDrawerProps) {
  const { locale, direction, t } = useI18n();
  const [open, setOpen] = useState(false);
  const menuRef = useRef<HTMLButtonElement>(null);
  const closeRef = useRef<HTMLButtonElement>(null);
  const drawerRef = useRef<HTMLDivElement>(null);
  const reducedMotion = prefersReducedMotion();
  const groups = navigationGroupsFor(role);

  const close = useCallback(() => setOpen(false), []);

  useEffect(() => {
    if (!open) {
      return undefined;
    }

    // Lock the page behind the modal and restore the exact prior value on close.
    const opener = defined(menuRef.current, 'navigation menu button');
    const previousOverflow = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    defined(closeRef.current, 'navigation close button').focus();

    const onKeyDown = (event: KeyboardEvent): void => {
      if (event.key === 'Escape') {
        event.preventDefault();
        close();
        return;
      }
      if (event.key !== 'Tab') {
        return;
      }
      const focusable = Array.from(
        defined(drawerRef.current, 'navigation drawer').querySelectorAll<HTMLElement>(FOCUSABLE),
      );
      const first = defined(focusable[0], 'first focusable element');
      const last = defined(focusable[focusable.length - 1], 'last focusable element');
      if (event.shiftKey) {
        if (document.activeElement === first) {
          event.preventDefault();
          last.focus();
        }
        return;
      }
      if (document.activeElement === last) {
        event.preventDefault();
        first.focus();
      }
    };
    document.addEventListener('keydown', onKeyDown);

    return () => {
      document.removeEventListener('keydown', onKeyDown);
      document.body.style.overflow = previousOverflow;
      // The captured button is detached on unmount, where focus is a no-op.
      opener.focus();
    };
  }, [open, close]);

  const select = (route: string): void => {
    if (onNavigate) {
      onNavigate(route);
    } else {
      navigate(route);
    }
    close();
  };

  return (
    <>
      <button
        ref={menuRef}
        type="button"
        className="touch"
        aria-label={navigationLabel(locale, 'navigation')}
        aria-expanded={open}
        aria-controls={DRAWER_ID}
        onClick={() => setOpen(true)}
      >
        <span aria-hidden="true">☰</span>
      </button>

      {open ? (
        <>
          <div
            data-testid="nav-drawer-backdrop"
            aria-hidden="true"
            onClick={close}
            style={{ position: 'fixed', inset: 0, background: 'rgba(0, 0, 0, 0.45)' }}
          />
          <div
            ref={drawerRef}
            id={DRAWER_ID}
            role="dialog"
            aria-modal="true"
            aria-label={navigationLabel(locale, 'navigation')}
            dir={direction}
            style={{
              position: 'fixed',
              insetBlock: 0,
              insetInlineStart: '0',
              inlineSize: 'min(320px, 80vw)',
              overflow: 'auto',
              transition: reducedMotion ? 'none' : SLIDE_TRANSITION,
            }}
          >
            <button
              ref={closeRef}
              type="button"
              className="touch"
              aria-label={t('closeDialog')}
              onClick={close}
              style={{ display: 'block', marginInlineStart: 'auto' }}
            >
              ×
            </button>
            {groups.map((group) => (
              <section key={group.group} aria-labelledby={`nav-drawer-heading-${group.group}`}>
                <h2 id={`nav-drawer-heading-${group.group}`}>
                  {navigationLabel(locale, groupLabelKey(group.group))}
                </h2>
                <ul style={{ listStyle: 'none', margin: 0, padding: 0 }}>
                  {group.items.map((item) => (
                    <li key={item.id}>
                      <a
                        href={item.route}
                        onClick={(event) => {
                          event.preventDefault();
                          select(item.route);
                        }}
                      >
                        {navigationLabel(locale, item.labelKey)}
                      </a>
                    </li>
                  ))}
                </ul>
              </section>
            ))}
          </div>
        </>
      ) : null}
    </>
  );
}
