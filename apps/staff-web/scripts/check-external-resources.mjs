/* global console, process, URL */
import { readFileSync, readdirSync } from 'node:fs';
import { join, relative, resolve, sep } from 'node:path';
import { fileURLToPath, pathToFileURL } from 'node:url';

/**
 * Third-party hosts that must never appear in a staff-web build (WEB-08).
 * Fonts, scripts and trackers are loaded from the application origin only.
 */
export const THIRD_PARTY_HOSTS = Object.freeze([
  'fonts.googleapis.com',
  'fonts.gstatic.com',
  'cdn.jsdelivr.net',
  'unpkg.com',
  'cdnjs.cloudflare.com',
  'www.google-analytics.com',
  'googletagmanager.com',
  'www.googletagservices.com',
  'connect.facebook.net',
  'platform.twitter.com',
  'cdn.segment.com',
  'api.segment.io',
  'plausible.io',
  'static.hotjar.com',
  'www.hotjar.com',
]);

/** Text file extensions in the build output that can contain resource URLs. */
const TEXT_EXTENSIONS = new Set(['.html', '.css', '.js', '.mjs']);

/**
 * Patterns that represent an actually fetched resource, not a bare URL in a
 * message or a stack trace. `group` selects the captured absolute reference.
 */
const RESOURCE_PATTERNS = [
  {
    kind: 'script',
    group: 2,
    regex: /\bsrc\s*=\s*(["'])((?:https?:)?\/\/[^"'\s>]+)\1/gi,
  },
  {
    kind: 'link',
    group: 2,
    regex: /\bhref\s*=\s*(["'])((?:https?:)?\/\/[^"'\s>]+)\1/gi,
  },
  {
    kind: 'url',
    group: 2,
    regex: /\burl\(\s*(["']?)((?:https?:)?\/\/[^"')]+)\1/gi,
  },
  {
    kind: 'import',
    group: 1,
    regex: /@import\s+["']?((?:https?:)?\/\/[^;"'\s]+)/gi,
  },
];

/**
 * Extract the host of an absolute or protocol-relative URL reference.
 *
 * @param {string} reference URL reference beginning with `//`, `http://` or `https://`
 * @returns {string} lower-case hostname, or an empty string when unparseable
 */
function hostFrom(reference) {
  const match = /^(?:https?:)?\/\/([^/:?#\s]+)/i.exec(reference);
  return match[1].toLowerCase();
}

/**
 * Find external resources referenced by one built text file.
 *
 * @param {string} contents text of a built HTML, CSS or JS file
 * @param {string} [label] path used in failure messages
 * @returns {Array<{label: string, kind: string, host: string}>} sorted findings
 */
export function findExternalResources(contents, label = 'output') {
  const findings = [];
  const seen = new Set();
  const add = (kind, host) => {
    const key = `${kind}|${host}`;
    if (seen.has(key)) {
      return;
    }
    seen.add(key);
    findings.push({ label, kind, host });
  };

  for (const pattern of RESOURCE_PATTERNS) {
    for (const match of contents.matchAll(pattern.regex)) {
      add(pattern.kind, hostFrom(match[pattern.group] ?? ''));
    }
  }

  const lower = contents.toLowerCase();
  for (const host of THIRD_PARTY_HOSTS) {
    if (lower.includes(host)) {
      add('third-party', host);
    }
  }

  return findings.sort(
    (left, right) =>
      left.label.localeCompare(right.label) ||
      left.kind.localeCompare(right.kind) ||
      left.host.localeCompare(right.host),
  );
}

/**
 * Scan every text asset in a built output tree for external resources.
 *
 * @param {string|URL} directory path or file URL of Vite's `dist` output
 * @returns {Array<{label: string, kind: string, host: string}>} all findings
 */
export function scanBuiltOutput(directory) {
  const root = typeof directory === 'string' ? directory : fileURLToPath(directory);
  const findings = [];

  const walk = (current) => {
    for (const entry of readdirSync(current, { withFileTypes: true })) {
      const path = join(current, entry.name);
      if (entry.isDirectory()) {
        walk(path);
        continue;
      }
      if (!entry.isFile() || !TEXT_EXTENSIONS.has(entry.name.slice(entry.name.lastIndexOf('.')))) {
        continue;
      }
      const label = relative(root, path).split(sep).join('/');
      findings.push(...findExternalResources(readFileSync(path, 'utf8'), label));
    }
  };
  walk(root);
  return findings;
}

/**
 * Fail when the built output references any third-party resource (WEB-08).
 *
 * @param {string|URL} directory path or file URL of Vite's `dist` output
 * @returns {Array<{label: string, kind: string, host: string}>} an empty list
 * @throws when an external script, font, stylesheet or tracker is referenced
 */
export function assertNoExternalResources(directory) {
  const findings = scanBuiltOutput(directory);
  if (findings.length > 0) {
    const detail = findings.map((item) => `${item.label}: ${item.kind} -> ${item.host}`).join('; ');
    throw new Error(`External resources are forbidden (WEB-08): ${detail}`);
  }
  return findings;
}

/** Print findings from the real build output and fail the process on any. */
function main() {
  const findings = scanBuiltOutput(new URL('../dist/', import.meta.url));
  for (const item of findings) {
    console.error(`${item.label}: ${item.kind} -> ${item.host}`);
  }
  if (findings.length > 0) {
    console.error('External scripts, fonts and trackers are forbidden (WEB-08).');
    process.exitCode = 1;
  }
}

const invokedDirectly =
  process.argv[1] !== undefined && import.meta.url === pathToFileURL(resolve(process.argv[1])).href;
if (invokedDirectly) {
  main();
}
