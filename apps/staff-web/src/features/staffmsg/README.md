# Staff messaging: routes and public barrel

- Requirements: MSG-02, MSG-03, WEB-09.
- This folder owns one route module, one component barrel and these hand-off
  instructions. It does not edit the application shell, the navigation table or
  any other feature. Every example below uses synthetic, tenant-local identifiers.

## 1. Router registration — no edit is needed

`src/app/routes.tsx` discovers feature routes with
`import.meta.glob('../features/*/routes.ts', { eager: true })`. Committing this
folder's `routes.ts` therefore registers both pages automatically. **Do not add
a manual route to the app router**: `collectFeatureRoutes` treats a repeated
pattern as a programming error and throws.

The exact declarations in `routes.ts` are:

```ts
export const routes: AppRoute[] = [
  {
    pattern: '/messaging/mail',
    component: lazy(() =>
      import('./mail/MailPage').then(({ MailPage: Page }) => ({ default: Page })),
    ),
  },
  {
    pattern: '/messaging/directory',
    component: lazy(() =>
      import('./directory/DirectoryPage').then(({ DirectoryPage: Page }) => ({ default: Page })),
    ),
  },
];
```

`MailPage` and `DirectoryPage` need no injected command, so they are safe as
standalone pages. `MessengerPanel`, `MailScreen`, `SettingsPage` and the small
parts need data and callbacks from an owning screen and are not routed here.
`/messages` is deliberately not claimed: the unit-inbox feature owns it.

## 2. Navigation entry — exact lines for the owner

This story does **not** edit `src/app/navigation.ts`. The navigation owner adds
all four blocks below. Without them the routes are unrestricted:
`routeAccessRoles('/messaging/mail')` returns `null` and `canAccessRoute()` grants
every signed-in role. The `unit_staff` entries are owner work and must merge
before these pages are treated as role-gated.

### 2.1 Extend the closed label-key union

In `NavigationLabelKey` (the union ending with `'groupAccount';`), insert after
`| 'messages',`:

```ts
  | 'staffMail'
  | 'staffDirectory'
```

### 2.2 Add the menu rows

In `navigation`, insert after the existing `{ id: 'messages', ... }` entry:

```ts
  {
    id: 'staff-mail',
    route: '/messaging/mail',
    group: 'Messages',
    labelKey: 'staffMail',
    roles: ['unit_staff'],
  },
  {
    id: 'staff-directory',
    route: '/messaging/directory',
    group: 'Messages',
    labelKey: 'staffDirectory',
    roles: ['unit_staff'],
  },
```

### 2.3 Add the English labels

In `navigationLabels.en`, insert after `messages: 'Messages',`:

```ts
    staffMail: 'Staff mail',
    staffDirectory: 'Staff directory',
```

### 2.4 Add the Arabic labels

In `navigationLabels.ar`, insert after `messages: 'الرسائل',`:

```ts
    staffMail: 'بريد الموظفين',
    staffDirectory: 'دليل الموظفين',
```

Both bundles must carry the same keys; a missing key is a build failure. The
rail mirrors locale onto `dir` and `lang`, so no component sets its own direction.

## 3. The one barrel and its mount points

Import every component from the single barrel; prop and domain types come from
the owning sub-module because this barrel intentionally exports components only:

```ts
import {
  DirectoryPage,
  MailPage,
  MailScreen,
  MessengerPanel,
  PatientReferenceChip,
  RecipientPicker,
  ReferenceInserter,
  SettingsPage,
  UnreadBadge,
} from '../staffmsg';

import type { MailScreenProps } from '../staffmsg/mail';
import type { MessengerPanelProps } from '../staffmsg/messenger/MessengerPanel';
import type { RecipientPickerProps } from '../staffmsg/directory';
import type { PatientReferenceChipProps, ReferenceInserterProps } from '../staffmsg/references';
import type { SettingsPageProps, UnreadBadgeProps } from '../staffmsg/settings';
```

The path above is for a screen directly under `src/features/<feature>/`; from a
nested screen use the relative path to `features/staffmsg/index.ts` instead.

### Routed pages

`MailPage` and `DirectoryPage` are mounted by the router through `routes.ts`.
When embedding one inside another shell, mount it directly. `DirectoryPage`
is a static synthetic picker with no asynchronous states; `MailPage` loads
tenant-scoped mail through its own command flow inside the providers supplied by
the shell and shows the WEB-09 loading, empty, error and conflict states:

```tsx
<MailPage />
<DirectoryPage />
```

### Messenger panel — mounted by the owning screen

```tsx
<MessengerPanel {...messengerPanelProps} />
```

`messengerPanelProps` is a `MessengerPanelProps` value supplied by the owning
shell: the tenant and facility identifiers, the narrowed conversation view,
draft and staged files, the realtime stream/poll/schedule functions, and the
refresh, select, draft, stage, remove, send, retry, reload, sign-in, compare and
copy callbacks. The panel never fetches, and a failed send keeps the typed text
for retry. Pass every required prop from the type; never invent an identifier.

### Mail screen — presentational part

```tsx
<MailScreen {...mailScreenProps} />
```

`mailScreenProps` is a `MailScreenProps` value from the owning mail command
flow. The page (`MailPage`) already composes it; mount `MailScreen` directly only
in a host that owns the versioned draft and the send/confirm commands.

### Recipient picker — search and selection part

```tsx
<RecipientPicker
  contacts={tenantContacts}
  actor={activeActor}
  policy={messagingPolicy}
  selectedSubject={selectedSubject}
  onSelect={selectRecipient}
/>
```

Every result is labelled with its facility, a crossing is marked even when the
policy refuses the recipient, and the combobox keeps keyboard focus.

### Patient reference chip and subject inserter — mounted in a composer

```tsx
<PatientReferenceChip label={referenceLabel} patientId={opaqueId} access={access} />
<ReferenceInserter onInsert={insertSubject} />
```

The chip links to the normal chart route only for an `ALLOWED` access outcome;
otherwise it renders a blocked state that reveals neither label nor identifier.
The inserter warns when the subject looks like an identifier, without echoing the
raw subject.

### Notification settings and unread badge — mounted in the shell

```tsx
<SettingsPage onSave={saveNotificationSettings} />
<UnreadBadge count={unreadCount} />
```

`onSave` must be a real command supplied by the owning shell. A no-op save that
reports success while persisting nothing is forbidden: the settings page would
show a false state transition. The badge hides itself at zero and isolates the
digits so they do not reorder under right-to-left layout.

## 4. What this feature does not do

It does not register `/messages`, edit `navigation.ts` or `routes.tsx`, call a
network API from this barrel, or expose real patient data. Adding the navigation
lines above is the owner's separate step; until they merge the two routes are
open to every signed-in role.
