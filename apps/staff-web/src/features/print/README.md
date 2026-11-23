# Print buttons

The print feature owns three reusable, bilingual buttons (WEB-09). It owns **no
screens**: each button is mounted by the feature that owns the screen, and that
screen performs the protected print call and records the audit reason. This
module exports the buttons from one barrel and keeps the preview and eligibility
rules in one place.

Import every button from the single barrel:

```ts
import { PrintChartButton, PrintOrderButton, PrintPrescriptionButton } from '../print';
```

The lines below are from a screen directly under `src/features/<feature>/`.
From a nested screen (for example `src/features/orderentry/entry/`) use
`'../../print'` instead. Only the three components are exported from this
barrel; if a screen needs a prop type, import it from the button's own
sub-module (for example `../print/orders/PrintOrderButton`).

## Prescription screen

Owner: the clinician prescription screen (RX-02). The pharmacy item page shows
the same prescription after signing. Mount the button where the signed
prescription and its immutable handout URL are known — after the state/attestation
block, not on the draft form. The button shows a disabled, explained state for a
draft, an unattested Rx or an unapproved channel.

```tsx
import { PrintPrescriptionButton } from '../print';
```

```tsx
<PrintPrescriptionButton
  prescription={prescriptionForPrint}
  handoutUrl={handoutUrl}
  onRecord={recordPrint}
/>
```

- `prescriptionForPrint`: the read-only `PrescriptionForPrint` snapshot
  (`id`, `version`, `state`, `attested`, `printChannel`, `handoutVersion`).
- `handoutUrl`: the immutable handout PDF URL bound to that exact source version.
- `onRecord(record)`: the owner records the reason, copy, language and version
  through the protected print API. A non-null `handoutVersion` is a reprint and
  must be audited again.

## Order screen

Owner: the lab/imaging order or radiology order screen. Mount the button beside
the placed-order list on the order sheet; several orders from one visit may be
combined or printed as separate sheets, and drafts are refused with a reason.

```tsx
import { PrintOrderButton } from '../print';
```

```tsx
<PrintOrderButton orders={ordersForPrint} onPrint={printOrders} />
```

- `ordersForPrint`: the placed orders from one visit (`readonly PrintableOrder[]`).
- `printOrders(sheets, reason, language)`: the owner renders/prints the chosen
  sheets and records the audit reason. `reason` is required by the preview.

## Chart screen and signed-report screen

Owner: the chart feature. One button serves two sources — a chart summary and a
signed report. Mount it on the longitudinal chart (or chart page) and on the
signed-report screen; pass the right `target` each time. The preview lists
unsigned and withheld content with the reason it was left out, and a draft
report is never printable.

```tsx
import { PrintChartButton } from '../print';
```

Chart summary placement:

```tsx
<PrintChartButton
  locale={locale}
  target={{
    kind: 'chart',
    patientName,
    counts,
    notes,
    results,
    visits,
  }}
  onPrint={printChartSummary}
/>
```

Signed-report placement:

```tsx
<PrintChartButton locale={locale} target={{ kind: 'report', report }} onPrint={printSignedReport} />
```

- `locale`: current interface locale; English and Arabic are supported.
- `target`: a `ChartPrintChartTarget` (`kind: 'chart'`) or a
  `ChartPrintReportTarget` (`kind: 'report'`).
- `onPrint(model, reason)`: the owner prints the previewed handout and records
  the audit reason.

## What this feature does not do

It does not add routes, edit the prescription/pharmacy, order or chart features,
call a print API itself, or expose real patient data. Every example above uses
synthetic identifiers. The owning screen wires the button to its own API and
audit; adding the button to that screen is a separate task owned by that screen.
