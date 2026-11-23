import { lazy, Suspense, useCallback, useEffect, useRef, useState } from 'react';
import { Button } from '../components/Button';
import { defined } from '../components/invariant';
import { MixedScriptText } from '../components/MixedScriptText';
import { syntheticOrganizations, syntheticStaff } from '../fixtures/synthetic';
import { useI18n } from '../i18n/I18nProvider';
import type { Locale } from '../i18n/messages';
import type { Breakpoint } from '../theme/breakpoints';
import { useTheme } from '../theme/ThemeProvider';
import { useBreakpoint } from '../theme/useBreakpoint';
import { ErrorBoundary } from './ErrorBoundary';
import { NavigationDrawer } from './NavigationDrawer';
import { canAccessRoute, groupLabelKey, navigationGroupsFor, navigationLabel } from './navigation';
import type { NavigationGroup, NavigationItem, Role } from './navigation';
import { OrgSwitcher } from './OrgSwitcher';
import { Link, matchRoute, navigate, useRoute } from './router';
import { routes } from './routes';
import { shellText } from './shellText';
import { useRole } from './useRole';

/** Hold duration before a touch press reveals an icon-rail tooltip (WEB-07). */
const LONG_PRESS_MS = 400;

/**
 * The fixed denial page stays lazy like the other system screens, so a direct
 * link denied by role does not pull the forbidden page into the shell's chunk.
 */
const ForbiddenPage = lazy(() =>
  import('../features/system/ForbiddenPage').then(({ ForbiddenPage: Page }) => ({ default: Page })),
);

/**
 * Decorative glyph per screen-map group for the medium icon rail. Every item is
 * still named in the accessibility tree by its translated label, so the glyph
 * never carries meaning on its own (UX-01).
 */
const GROUP_ICONS: Record<NavigationGroup, string> = {
  Today: '◉',
  Scheduling: '▦',
  Clinic: '✚',
  Patients: '☻',
  Orders: '▤',
  Results: '◫',
  Specialty: '⚙',
  Messages: '✉',
  Finance: '◈',
  Administration: '⚑',
  Operations: '◴',
  Account: '◍',
};

/** Logical top padding that keeps the banner clear of a notch (WEB-07, UX-01). */
const HEADER_SAFE_PADDING = 'max(var(--sc-space-4, 16px), var(--safe-block-start, 0px))';

/** Logical bottom padding that keeps the footer above the home indicator. */
const FOOTER_SAFE_PADDING = 'max(var(--sc-space-4, 16px), var(--safe-block-end, 0px))';

/** Props for a link in the expanded, fully labelled rail. */
interface FullRailLinkProps {
  item: NavigationItem;
  label: string;
  active: boolean;
}

/** A labelled screen-map link; the text is its accessible name. */
function FullRailLink({ item, label, active }: FullRailLinkProps) {
  return (
    <li>
      <Link to={item.route} ariaCurrent={active ? 'page' : undefined}>
        {label}
      </Link>
    </li>
  );
}

/** Props for one glyph link in the medium icon rail. */
interface IconRailLinkProps {
  item: NavigationItem;
  label: string;
  active: boolean;
  icon: string;
  tooltipOpen: boolean;
  onShow: (id: string) => void;
  onHide: () => void;
  onPress: (id: string) => void;
  onRelease: () => void;
  /** Abort only the pending long-press timer, leaving an open tooltip intact. */
  onCancelPress: () => void;
}

/**
 * A compact navigation link whose accessible name and hover title carry the
 * full label. The visual tooltip is an enhancement: it opens on keyboard focus
 * and on a sustained touch press, never on hover alone. A tooltip opened by
 * focus stays until focus moves, so a stray pointer leave cannot hide it
 * (UX-01, WCAG 1.4.13).
 */
function IconRailLink({
  item,
  label,
  active,
  icon,
  tooltipOpen,
  onShow,
  onHide,
  onPress,
  onRelease,
  onCancelPress,
}: IconRailLinkProps) {
  return (
    <li>
      <a
        href={item.route}
        className="touch"
        title={label}
        aria-label={label}
        aria-current={active ? 'page' : undefined}
        onFocus={() => onShow(item.id)}
        onBlur={onHide}
        onPointerDown={() => onPress(item.id)}
        onPointerUp={onRelease}
        onPointerCancel={onCancelPress}
        onPointerLeave={onCancelPress}
        onClick={(event) => {
          // Keep the router Link's click contract on a hand-rolled anchor: a
          // modified or non-primary click belongs to the browser (new tab or
          // window), and an already-handled click must not be hijacked. Link
          // itself cannot be used here because it exposes no aria-label/className.
          if (event.defaultPrevented) {
            return;
          }
          if (event.button !== 0) {
            return;
          }
          if (event.metaKey || event.ctrlKey || event.shiftKey || event.altKey) {
            return;
          }
          // An in-application link must not trigger a document reload.
          event.preventDefault();
          navigate(item.route);
        }}
      >
        <span aria-hidden="true">{icon}</span>
        <span id={`rail-tip-${item.id}`} role="tooltip" hidden={!tooltipOpen}>
          {label}
        </span>
      </a>
    </li>
  );
}

/** Tooltip visibility for the icon rail, including the long-press timer. */
function useRailTooltips() {
  const [openTooltip, setOpenTooltip] = useState<string | null>(null);
  const longPressTimer = useRef<ReturnType<typeof setTimeout> | null>(null);

  const cancelLongPress = useCallback(() => {
    if (longPressTimer.current !== null) {
      clearTimeout(longPressTimer.current);
      longPressTimer.current = null;
    }
  }, []);

  // A cancelled press must not leave a timer that reveals a stale tooltip.
  useEffect(() => cancelLongPress, [cancelLongPress]);

  const showTooltip = useCallback((id: string) => setOpenTooltip(id), []);
  const hideTooltip = useCallback(() => setOpenTooltip(null), []);
  const beginLongPress = useCallback(
    (id: string) => {
      cancelLongPress();
      longPressTimer.current = setTimeout(() => {
        setOpenTooltip(id);
        longPressTimer.current = null;
      }, LONG_PRESS_MS);
    },
    [cancelLongPress],
  );
  const releaseLongPress = useCallback(() => {
    cancelLongPress();
    setOpenTooltip(null);
  }, [cancelLongPress]);

  return {
    openTooltip,
    showTooltip,
    hideTooltip,
    beginLongPress,
    releaseLongPress,
    cancelLongPress,
  };
}

/** Props for the role-scoped navigation rail. */
interface NavRailProps {
  groups: ReturnType<typeof navigationGroupsFor>;
  iconRail: boolean;
  breakpoint: Breakpoint;
  locale: Locale;
  isActive: (pattern: string) => boolean;
  tooltips: ReturnType<typeof useRailTooltips>;
}

/** The medium icon rail or the expanded full rail, grouped by screen-map group. */
function NavRail({ groups, iconRail, breakpoint, locale, isActive, tooltips }: NavRailProps) {
  return (
    <div
      role="group"
      aria-label={navigationLabel(locale, 'navigation')}
      className="app-shell__nav"
      data-breakpoint={breakpoint}
      data-rail={iconRail ? 'icon' : 'full'}
      style={{ flex: '0 0 auto', position: 'sticky', insetInlineStart: 0 }}
    >
      {groups.map((group) => (
        <section key={group.group} aria-labelledby={`nav-heading-${group.group}`}>
          <h2 id={`nav-heading-${group.group}`} className="app-shell__label">
            {navigationLabel(locale, groupLabelKey(group.group))}
          </h2>
          <ul style={{ listStyle: 'none', margin: 0, padding: 0 }}>
            {group.items.map((item) => {
              const label = navigationLabel(locale, item.labelKey);
              const active = isActive(item.route);
              return iconRail ? (
                <IconRailLink
                  key={item.id}
                  item={item}
                  label={label}
                  active={active}
                  icon={GROUP_ICONS[group.group]}
                  tooltipOpen={tooltips.openTooltip === item.id}
                  onShow={tooltips.showTooltip}
                  onHide={tooltips.hideTooltip}
                  onPress={tooltips.beginLongPress}
                  onRelease={tooltips.releaseLongPress}
                  onCancelPress={tooltips.cancelLongPress}
                />
              ) : (
                <FullRailLink key={item.id} item={item} label={label} active={active} />
              );
            })}
          </ul>
        </section>
      ))}
    </div>
  );
}

/** Props for the single authenticated staff portal shell. */
export interface AppShellProps {
  /** Optional role override for isolated rendering; production uses `useRole`. */
  role?: Role | undefined;
  /** Invoked by the user-menu Sign out button when a session owns it. */
  onSignOut?: (() => void) | undefined;
}

/**
 * Application shell from the screen map: header, role-scoped navigation, status
 * banner and the matched route inside the main landmark. The compact band uses a
 * drawer, the medium band an icon rail and the expanded band a full rail; width
 * changes presentation only, never the data or the available actions (WEB-07).
 */
export function AppShell({ role, onSignOut }: AppShellProps) {
  const { locale, t, toggleLocale } = useI18n();
  const { mode, tokens, toggleMode } = useTheme();
  const breakpoint = useBreakpoint();
  const signedInRole = useRole();
  const activeRole = role ?? signedInRole;
  const { path } = useRoute();
  const mainRef = useRef<HTMLElement>(null);
  const [userMenuOpen, setUserMenuOpen] = useState(false);
  useEffect(() => {
    // The effect runs only while <main> is mounted, so the ref is populated.
    defined(mainRef.current, 'main landmark').focus();
  }, [path]);

  const tooltips = useRailTooltips();

  const matched = routes.find(
    (candidate) => candidate.pattern !== '*' && matchRoute(candidate.pattern, path) !== null,
  );
  const catchAll = routes.find((candidate) => candidate.pattern === '*');
  const ActivePage = defined(matched ?? catchAll, 'catch-all route').component;
  // A direct link must be as restricted as the rail: when the role cannot use
  // the matched route, render the fixed forbidden page instead of the screen.
  // Unrestricted routes (system pages, detail pages, the catch-all) stay open.
  const accessDenied = matched !== undefined && !canAccessRoute(activeRole, matched.pattern);
  const isActive = (pattern: string): boolean => matchRoute(pattern, path) !== null;

  const compact = breakpoint === 'compact';
  const iconRail = breakpoint === 'medium';
  const groups = navigationGroupsFor(activeRole);

  const themeButton = (
    <Button variant="secondary" onClick={toggleMode} pressed={mode === 'dark'}>
      {t('theme')}
    </Button>
  );
  const languageButton = (
    <Button variant="secondary" onClick={toggleLocale} pressed={locale === 'ar'}>
      {t('language')}
    </Button>
  );
  const signOutButton = (
    <Button
      onClick={() => {
        if (onSignOut !== undefined) {
          onSignOut();
        }
      }}
    >
      {shellText(locale, 'signOut')}
    </Button>
  );

  return (
    <ErrorBoundary>
      <div
        className="app-shell"
        data-breakpoint={breakpoint}
        style={{ background: tokens.background, color: tokens.foreground, minHeight: '100vh' }}
      >
        <a href="#main" className="skip-link">
          {t('skipToMain')}
        </a>
        <header
          role="banner"
          style={{
            gridColumn: '1 / -1',
            paddingBlockStart: HEADER_SAFE_PADDING,
            paddingInline: tokens.spacing.lg,
          }}
        >
          {compact ? <NavigationDrawer role={activeRole} /> : null}
          <h1>
            {__APP_DISPLAY_NAME__} · {t('appTitle')}
          </h1>
          <nav role="navigation" aria-label={t('appTitle')}>
            <OrgSwitcher organizations={syntheticOrganizations} />
          </nav>
          {compact ? (
            <div>
              <button
                type="button"
                className="touch"
                aria-label={shellText(locale, 'userMenu')}
                aria-expanded={userMenuOpen}
                aria-controls="app-user-menu"
                onClick={() => setUserMenuOpen(!userMenuOpen)}
              >
                <span aria-hidden="true">☰</span>
                {shellText(locale, 'userMenu')}
              </button>
              {userMenuOpen ? (
                <div id="app-user-menu" role="group" aria-label={shellText(locale, 'userMenu')}>
                  {themeButton}
                  {languageButton}
                  {signOutButton}
                </div>
              ) : null}
            </div>
          ) : (
            <>
              {themeButton}
              {languageButton}
              <div role="group" aria-label={shellText(locale, 'userMenu')}>
                <span>{syntheticStaff.displayName}</span>
                {signOutButton}
              </div>
            </>
          )}
        </header>

        {compact ? null : (
          <NavRail
            groups={groups}
            iconRail={iconRail}
            breakpoint={breakpoint}
            locale={locale}
            isActive={isActive}
            tooltips={tooltips}
          />
        )}

        <div className="app-shell__content" style={{ minInlineSize: 0 }}>
          <div role="status" aria-label={shellText(locale, 'notifications')} aria-live="polite" />
          <main id="main" tabIndex={-1} ref={mainRef} className="page">
            <Suspense fallback={<p role="status">Loading</p>}>
              {accessDenied ? <ForbiddenPage /> : <ActivePage />}
            </Suspense>
          </main>
        </div>

        <footer
          role="contentinfo"
          style={{
            gridColumn: '1 / -1',
            paddingBlockEnd: FOOTER_SAFE_PADDING,
            paddingInline: tokens.spacing.lg,
          }}
        >
          <MixedScriptText text={t('signInNote')} lang={locale} />
        </footer>
      </div>
    </ErrorBoundary>
  );
}
