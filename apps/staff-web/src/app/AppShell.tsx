import { Suspense, useEffect, useRef } from 'react';
import { Button } from '../components/Button';
import { MixedScriptText } from '../components/MixedScriptText';
import { syntheticOrganizations, syntheticStaff } from '../fixtures/synthetic';
import { useI18n } from '../i18n/I18nProvider';
import { useTheme } from '../theme/ThemeProvider';
import { ErrorBoundary } from './ErrorBoundary';
import { groupLabelKey, navigationGroupsFor, navigationLabel } from './navigation';
import type { Role } from './navigation';
import { OrgSwitcher } from './OrgSwitcher';
import { Link, matchRoute, useRoute } from './router';
import { routes } from './routes';
import { shellText } from './shellText';
import { useRole } from './useRole';

/** Props for the single authenticated staff portal shell. */
export interface AppShellProps {
  /** Optional role override for isolated rendering; production uses `useRole`. */
  role?: Role | undefined;
  /** Invoked by the user-menu Sign out button when a session owns it. */
  onSignOut?: (() => void) | undefined;
}

/**
 * Application shell from the screen map: header, role-scoped rail, status
 * banner and the matched route inside the main landmark. All data is synthetic.
 */
export function AppShell({ role, onSignOut }: AppShellProps) {
  const { locale, t, toggleLocale } = useI18n();
  const { mode, tokens, toggleMode } = useTheme();
  const signedInRole = useRole();
  const activeRole = role ?? signedInRole;
  const { path } = useRoute();
  const mainRef = useRef<HTMLElement>(null);

  useEffect(() => {
    // The effect runs only while <main> is mounted, so the ref is populated.
    mainRef.current!.focus();
  }, [path]);

  const matched = routes.find(
    (candidate) => candidate.pattern !== '*' && matchRoute(candidate.pattern, path) !== null,
  );
  const catchAll = routes.find((candidate) => candidate.pattern === '*');
  const ActivePage = (matched ?? catchAll)!.component;
  const isActive = (pattern: string): boolean => matchRoute(pattern, path) !== null;

  return (
    <ErrorBoundary>
      <div style={{ background: tokens.background, color: tokens.foreground, minHeight: '100vh' }}>
        <a href="#main" className="skip-link">
          {t('skipToMain')}
        </a>
        <header role="banner" style={{ padding: tokens.spacing.lg }}>
          <h1>{__APP_DISPLAY_NAME__} · {t('appTitle')}</h1>
          <nav role="navigation" aria-label={t('appTitle')}>
            <OrgSwitcher organizations={syntheticOrganizations} />
          </nav>
          <Button variant="secondary" onClick={toggleMode} pressed={mode === 'dark'}>
            {t('theme')}
          </Button>
          <Button variant="secondary" onClick={toggleLocale} pressed={locale === 'ar'}>
            {t('language')}
          </Button>
          <div role="group" aria-label={shellText(locale, 'userMenu')}>
            <span>{syntheticStaff.displayName}</span>
            <Button
              onClick={() => {
                if (onSignOut !== undefined) {
                  onSignOut();
                }
              }}
            >
              {shellText(locale, 'signOut')}
            </Button>
          </div>
        </header>

        <div style={{ display: 'flex', flexDirection: 'row', gap: tokens.spacing.lg }}>
          <div
            role="group"
            aria-label={navigationLabel(locale, 'navigation')}
            style={{ flex: '0 0 auto', position: 'sticky', insetInlineStart: 0 }}
          >
            {navigationGroupsFor(activeRole).map((group) => (
              <section key={group.group} aria-labelledby={`nav-heading-${group.group}`}>
                <h2 id={`nav-heading-${group.group}`}>
                  {navigationLabel(locale, groupLabelKey(group.group))}
                </h2>
                <ul style={{ listStyle: 'none', margin: 0, padding: 0 }}>
                  {group.items.map((item) => (
                    <li key={item.id}>
                      <Link
                        to={item.route}
                        ariaCurrent={isActive(item.route) ? 'page' : undefined}
                      >
                        {navigationLabel(locale, item.labelKey)}
                      </Link>
                    </li>
                  ))}
                </ul>
              </section>
            ))}
          </div>

          <div style={{ flex: '1 1 auto' }}>
            <div
              role="status"
              aria-label={shellText(locale, 'notifications')}
              aria-live="polite"
            />
            <main id="main" tabIndex={-1} ref={mainRef} style={{ padding: tokens.spacing.lg }}>
              <Suspense fallback={<p role="status">Loading</p>}>
                <ActivePage />
              </Suspense>
            </main>
          </div>
        </div>

        <footer role="contentinfo" style={{ padding: tokens.spacing.lg }}>
          <MixedScriptText text={t('signInNote')} lang={locale} />
        </footer>
      </div>
    </ErrorBoundary>
  );
}
