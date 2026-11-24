# Staff web screen recipe

- Requirements: UX-01, WEB-08, WEB-09
- Status: contract version 1.0.0, proposed. Documentation only.
- Audience: workers and maintainers adding or changing a staff web screen.

This is the one way every staff web screen is built, so a screen written by any worker looks and behaves like the others. It follows the existing `apps/staff-web/src/features/scheduling` feature. Read that folder before you start; copy its style.

## 1. Folder layout

One folder per feature under `apps/staff-web/src/features/<feature>/`, named after the business area (`patients`, `orders`, `results`), never after a task ID.

| File | What it holds |
|---|---|
| `types.ts` | TypeScript types for the data the screen shows. Take field names from the API response, not from your imagination. |
| `api.ts` | One function per backend call. Each builds a request descriptor with `buildRequestDescriptor` from `src/api/client.ts` and sends it with `send` from `src/api/transport.ts`. Returns typed data or throws `TransportError`. |
| `messages.ts` | English and Arabic text for this feature. Copy the structure of `features/scheduling/messages.ts` (a keys list plus a parity test that both languages have every key). |
| `<Name>Screen.tsx` | Presentational component. Receives data and callbacks as props, renders the table or form, never calls the network. |
| `<Name>Page.tsx` | Container. Calls `api.ts` through the loader hook, shows the state views from `src/components/StateViews.tsx`, passes data to the screen. |
| `*.test.ts(x)` | Tests next to the code. |

## 2. The seven states

The page must render all of these (see `docs/design/screens-and-navigation.md` section 4): loading, empty, error (with correlation ID), timeout or offline (with retry), forbidden, conflict (stale version), session expired. Map a `TransportError` to a state with `StateView.forTransportError`.

## 3. Rules

- Use only the components in `src/components` and the tokens from `useTheme()`. No raw colours, pixel font sizes or new CSS files.
- Every visible string comes from `messages.ts` in both languages. Layout uses logical properties (`marginInlineStart`, `textAlign: 'start'`) so right-to-left works.
- Patient name and the opaque patient ID are shown together. Names go through `MixedScriptText`.
- Dates and times use `src/i18n/format.ts` with the facility time zone; never format by hand.
- Lists use cursor pagination: show a "Load more" button while the server returns `nextCursor`. Never fetch everything.
- Commands that change data send `If-Match` (version) and, where the contract says so, `Idempotency-Key` (a new `crypto.randomUUID()` per user action, reused on retry of the same action).
- A 409 or 412 shows the conflict state and keeps what the user typed.
- Never put patient data in a URL, page title, `localStorage` or `sessionStorage`.
- No new npm dependency without a note in `docs/pr/notes/<task-id>.md` saying why none of the existing ones works.

## 4. Tests every screen needs

1. A parity test: every message key exists in English and Arabic.
2. One test per state in section 2, using a mocked `send`.
3. A keyboard test: the primary action is reachable with Tab and activates with Enter.
4. A right-to-left render test (`initialLocale="ar"`) that checks `dir="rtl"` is set and the table still renders.
5. A tenant test: switching organization clears the previous organization's rows.
6. For every command: a success test and a conflict test.

## 5. Finish

Add the route to `src/app/navigation.ts` if the table in `screens-and-navigation.md` lists it, run `bash apps/staff-web/check.sh`, and record the output in `docs/pr/notes/<task-id>.md`.
