# Public information pages

Requirement: WEB-13. These files are a separately hosted, read-only public
presence. They are **not** part of the authenticated staff web or patient app
and hold no personal or clinical information.

## Files

- `status.html` — current operating status and service notices.
- `maintenance.html` — notice of planned maintenance windows.
- `support.html` — support contact and links to the policies.
- `privacy.html` — privacy notice (placeholder marked `Pending approval`).
- `accessibility.html` — accessibility statement (placeholder marked
  `Pending approval`).
- `terms.html` — terms of use (placeholder marked `Pending approval`).
- `styles.css` — the one shared stylesheet used by every page.

## How these files are hosted

Copy this directory unchanged to a static website host or a public-read object
storage bucket configured as a website endpoint. The host must be separate
from the authenticated applications and must serve only these static files —
there is no build step and no server-side code.

- Keep all seven files at the same path level: every link is relative, so
  moving a page without its neighbours breaks navigation.
- Serve `status.html` as the directory index (or add a redirect to it).
- Set `Content-Type: text/html; charset=utf-8` for the pages and
  `text/css; charset=utf-8` for the stylesheet.
- No JavaScript, third-party fonts, external scripts, analytics or trackers
  are used, and the stylesheet imports no remote resources. Do not add any.
- The legal placeholders are marked `Pending approval`. Replace them with
  wording approved by the accountable owners before publication.
- Replace the `support@example.invalid` placeholder with an approved contact
  address before publication. `example.invalid` is reserved for documentation
  and is never a live domain.

The pages are verified by `apps/staff-web/tests/publicPages.test.ts`, which
reads these files from disk and is run by `bash apps/staff-web/check.sh`.
