import { useState } from 'react';
import { Button } from '../../../components/Button';
import { MixedScriptText } from '../../../components/MixedScriptText';
import { ConflictState, ForbiddenState } from '../../../components/StateViews';
import { displayLocalName } from '../../../i18n/format';
import { useI18n } from '../../../i18n/I18nProvider';
import { useTheme } from '../../../theme/ThemeProvider';
import type { Role } from '../../../app/navigation';
import type { CodedEntry } from '../../chart/domain';
import {
  canOrder,
  contentFromDraft,
  duplicateActiveOrders,
  emptyContent,
  hasImaging,
  OrderConflict,
  removeSelectedItem,
  searchCatalog,
  selectCatalogItem,
  validateContent,
} from './domain';
import type {
  ActiveOrder,
  CatalogItem,
  DeliveryMode,
  OrderDraftContent,
  OrderPriority,
  SavedOrderDraft,
} from './domain';
import { entryText } from './messages';
import type { EntryMessageKey } from './messages';

/** Props for the chart-embedded order entry panel. All data is synthetic. */
export interface OrderEntryPanelProps {
  organizationId: string;
  patientLocalId: string;
  encounterLocalId: string;
  facilityId: string;
  /** Role of the signed-in clinician; ordering privilege is checked here. */
  role: Role;
  catalog: readonly CatalogItem[];
  allergies: readonly CodedEntry[];
  activeOrders: readonly ActiveOrder[];
  /** Saved draft opened from the chart; the baseline version for If-Match. */
  savedDraft?: SavedOrderDraft | undefined;
  onSaveDraft: (content: OrderDraftContent, expectedVersion: number) => Promise<SavedOrderDraft>;
  onReload: () => Promise<SavedOrderDraft>;
}

/** Local UI state; typed work lives here and is never cleared by a conflict. */
interface PanelState {
  organizationId: string;
  content: OrderDraftContent;
  version: number;
  query: string;
  conflict: OrderConflict | undefined;
  errorKey: EntryMessageKey | '';
  announcement: EntryMessageKey | '';
  comparing: boolean;
}

/** Seed panel state from the saved draft, or an empty draft for this tenant. */
function initialState(props: OrderEntryPanelProps): PanelState {
  const seed =
    props.savedDraft?.organizationId === props.organizationId ? props.savedDraft : undefined;
  return {
    organizationId: props.organizationId,
    content:
      seed === undefined
        ? emptyContent(props.patientLocalId, props.encounterLocalId, props.facilityId)
        : contentFromDraft(seed),
    version: seed?.version ?? 0,
    query: '',
    conflict: undefined,
    errorKey: '',
    announcement: '',
    comparing: false,
  };
}

const controlStyle = {
  minHeight: '32px',
  padding: '8px',
  border: '1px solid currentColor',
  background: 'transparent',
  color: 'inherit',
  font: 'inherit',
} as const;

interface PanelHeaderProps {
  patientLocalId: string;
  encounterLocalId: string;
  facilityId: string;
}

interface ActiveOrdersSectionProps {
  activeOrders: readonly ActiveOrder[];
  duplicates: readonly ActiveOrder[];
}

interface CatalogResultsProps {
  results: readonly CatalogItem[];
  content: OrderDraftContent;
  onAdd: (item: CatalogItem) => void;
}

interface SelectedItemsProps {
  content: OrderDraftContent;
  onRemove: (itemId: string) => void;
}

interface OrderFormFieldsProps {
  content: OrderDraftContent;
  imagingRequired: boolean;
  onChange: (patch: Partial<OrderDraftContent>) => void;
}

interface CompareSectionProps {
  conflict: OrderConflict;
  content: OrderDraftContent;
}

/** Shared locale, tokens and translator for the panel's sections. */
function useEntryView() {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const text = (key: EntryMessageKey): string => entryText(locale, key);
  return { locale, tokens, text };
}

function PanelHeader({ patientLocalId, encounterLocalId, facilityId }: PanelHeaderProps) {
  const { tokens, text } = useEntryView();
  return (
    <header style={{ display: 'grid', gap: tokens.spacing.sm }}>
      <h2>{text('panelTitle')}</h2>
      <p style={{ marginBlock: 0 }}>{text('syntheticNotice')}</p>
      <p style={{ marginBlock: 0 }}>
        <b>{text('patient')}:</b> <code dir="ltr">{patientLocalId}</code>
        {' · '}
        <b>{text('encounter')}:</b> <code dir="ltr">{encounterLocalId}</code>
        {' · '}
        <b>{text('facility')}:</b> <code dir="ltr">{facilityId}</code>
      </p>
    </header>
  );
}

function AllergiesSection({ allergies }: { allergies: readonly CodedEntry[] }) {
  const { locale, tokens, text } = useEntryView();
  return (
    <section aria-label={text('allergies')} style={{ display: 'grid', gap: tokens.spacing.sm }}>
      <h3>{text('allergies')}</h3>
      {allergies.length === 0 ? (
        <p>{text('noAllergies')}</p>
      ) : (
        <ul>
          {allergies.map((allergy) => (
            <li key={allergy.id}>
              <MixedScriptText
                text={displayLocalName(
                  {
                    latinName: allergy.display,
                    ...(allergy.displayLocal === undefined
                      ? {}
                      : { localName: allergy.displayLocal }),
                  },
                  locale,
                )}
                lang={locale}
              />
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function ActiveOrdersSection({ activeOrders, duplicates }: ActiveOrdersSectionProps) {
  const { locale, tokens, text } = useEntryView();
  return (
    <section aria-label={text('activeOrders')} style={{ display: 'grid', gap: tokens.spacing.sm }}>
      <h3>{text('activeOrders')}</h3>
      {activeOrders.length === 0 ? (
        <p>{text('noActiveOrders')}</p>
      ) : (
        <ul>
          {activeOrders.map((order) => (
            <li key={order.id}>
              <MixedScriptText text={order.display} lang={locale} />
            </li>
          ))}
        </ul>
      )}
      {duplicates.length > 0 ? (
        <p role="status" data-testid="entry-duplicate">
          {text('duplicateWarning')} {duplicates.map((order) => order.display).join(', ')}
        </p>
      ) : null}
    </section>
  );
}

function CatalogResults({ results, content, onAdd }: CatalogResultsProps) {
  const { tokens, text } = useEntryView();
  return (
    <div style={{ display: 'grid', gap: tokens.spacing.sm }}>
      {results.length === 0 ? (
        <p>{text('catalogEmpty')}</p>
      ) : (
        <ul>
          {results.map((item) => {
            const alreadySelected = content.items.some((selected) => selected.itemId === item.id);
            return (
              <li key={item.id}>
                <code dir="ltr">{item.code}</code> {item.name}
                {' · '}
                {item.category === 'imaging' ? text('categoryImaging') : text('categoryLab')}
                {' · '}
                <button
                  type="button"
                  disabled={alreadySelected}
                  aria-label={`${text('add')}: ${item.name}`}
                  style={controlStyle}
                  onClick={() => onAdd(item)}
                >
                  {text('add')}
                </button>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}

function SelectedItems({ content, onRemove }: SelectedItemsProps) {
  const { tokens, text } = useEntryView();
  return (
    <section aria-label={text('selectedItems')} style={{ display: 'grid', gap: tokens.spacing.sm }}>
      <h3>{text('selectedItems')}</h3>
      {content.items.length === 0 ? (
        <p>{text('noSelectedItems')}</p>
      ) : (
        <ul>
          {content.items.map((item) => (
            <li key={item.itemId}>
              <code dir="ltr">{item.code}</code> {item.name}
              <button
                type="button"
                aria-label={`${text('remove')}: ${item.name}`}
                style={controlStyle}
                onClick={() => onRemove(item.itemId)}
              >
                {text('remove')}
              </button>
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

function OrderFormFields({ content, imagingRequired, onChange }: OrderFormFieldsProps) {
  const { tokens, text } = useEntryView();
  return (
    <>
      <div style={{ display: 'grid', gap: tokens.spacing.sm }}>
        <label htmlFor="entry-question">
          {text('clinicalQuestion')} {imagingRequired ? `(${text('imagingNotice')})` : ''}
        </label>
        <textarea
          id="entry-question"
          value={content.clinicalQuestion}
          aria-describedby="entry-question-hint"
          style={controlStyle}
          onChange={(event) => onChange({ clinicalQuestion: event.target.value })}
        />
        <p id="entry-question-hint" style={{ marginBlock: 0 }}>
          {text('clinicalQuestionHint')}
        </p>
      </div>

      <div style={{ display: 'grid', gap: tokens.spacing.sm }}>
        <label htmlFor="entry-priority">{text('priority')}</label>
        <select
          id="entry-priority"
          value={content.priority}
          style={controlStyle}
          onChange={(event) => onChange({ priority: event.target.value as OrderPriority })}
        >
          <option value="routine">{text('routine')}</option>
          <option value="urgent">{text('urgent')}</option>
          <option value="stat">{text('stat')}</option>
        </select>
      </div>

      <div style={{ display: 'grid', gap: tokens.spacing.sm }}>
        <label htmlFor="entry-preparation">{text('preparation')}</label>
        <textarea
          id="entry-preparation"
          value={content.preparationInstructions}
          aria-describedby="entry-preparation-hint"
          style={controlStyle}
          onChange={(event) => onChange({ preparationInstructions: event.target.value })}
        />
        <p id="entry-preparation-hint" style={{ marginBlock: 0 }}>
          {text('preparationHint')}
        </p>
      </div>

      <div style={{ display: 'grid', gap: tokens.spacing.sm }}>
        <label htmlFor="entry-delivery">{text('deliveryMode')}</label>
        <select
          id="entry-delivery"
          value={content.deliveryMode}
          style={controlStyle}
          onChange={(event) => onChange({ deliveryMode: event.target.value as DeliveryMode })}
        >
          <option value="electronic">{text('electronic')}</option>
          <option value="print">{text('print')}</option>
          <option value="both">{text('both')}</option>
        </select>
      </div>
    </>
  );
}

function CompareSection({ conflict, content }: CompareSectionProps) {
  const { tokens, text } = useEntryView();
  return (
    <section aria-label={text('compareTitle')} style={{ display: 'grid', gap: tokens.spacing.sm }}>
      <h3>{text('compareTitle')}</h3>
      <b>{text('savedContent')}</b>
      <pre>{JSON.stringify(conflict.server.content, null, 2)}</pre>
      <b>{text('yourContent')}</b>
      <pre>{JSON.stringify(content, null, 2)}</pre>
    </section>
  );
}

interface CatalogSearchProps {
  query: string;
  onChange: (next: string) => void;
}

function CatalogSearch({ query, onChange }: CatalogSearchProps) {
  const { tokens, text } = useEntryView();
  return (
    <div style={{ display: 'grid', gap: tokens.spacing.sm }}>
      <label htmlFor="entry-catalog-search">{text('catalogSearch')}</label>
      <input
        id="entry-catalog-search"
        type="text"
        value={query}
        aria-describedby="entry-catalog-hint"
        style={controlStyle}
        onChange={(event) => onChange(event.target.value)}
      />
      <p id="entry-catalog-hint" style={{ marginBlock: 0 }}>
        {text('catalogHint')}
      </p>
    </div>
  );
}

/**
 * Order entry surface embedded in the patient chart (LAB-01, RAD-01, WEB-09).
 *
 * The clinician searches a versioned catalog, selects items, answers the
 * clinical question required for imaging, adjusts preparation instructions and
 * saves a draft under the version it opened. A stale write keeps every typed
 * value and offers compare, reload or copy instead of discarding work.
 */
export function OrderEntryPanel(props: OrderEntryPanelProps) {
  const { locale } = useI18n();
  const { tokens } = useTheme();
  const [state, setState] = useState<PanelState>(() => initialState(props));
  const { organizationId, catalog, allergies, activeOrders, onSaveDraft, onReload } = props;

  // A tenant switch resets every field synchronously during render so the
  // previous tenant's typed order can never be painted under the new one.
  if (state.organizationId !== organizationId) {
    setState(initialState(props));
  }

  if (!canOrder(props.role)) {
    return <ForbiddenState />;
  }

  const { content, query, conflict, errorKey, announcement, comparing } = state;
  const text = (key: EntryMessageKey): string => entryText(locale, key);
  const results = searchCatalog(catalog, organizationId, query);
  const duplicates = duplicateActiveOrders(content, activeOrders, organizationId);
  const imagingRequired = hasImaging(content);

  function updateContent(patch: Partial<OrderDraftContent>): void {
    setState((current) => ({
      ...current,
      content: { ...current.content, ...patch },
      errorKey: '',
      announcement: '',
    }));
  }

  function handleSave(): void {
    const findings = validateContent(state.content);
    if (findings.length > 0) {
      setState((current) => ({
        ...current,
        errorKey: findings[0] === 'noItems' ? 'noItems' : 'clinicalQuestionRequired',
        announcement: '',
        conflict: undefined,
        comparing: false,
      }));
      return;
    }
    void (async () => {
      try {
        const saved = await onSaveDraft(state.content, state.version);
        setState((current) => ({
          ...current,
          version: saved.version,
          announcement: 'savedNotice',
          errorKey: '',
          conflict: undefined,
          comparing: false,
        }));
      } catch (failure: unknown) {
        if (failure instanceof OrderConflict) {
          setState((current) => ({
            ...current,
            conflict: failure,
            errorKey: '',
            announcement: '',
            comparing: false,
          }));
        } else {
          setState((current) => ({
            ...current,
            errorKey: 'saveError',
            announcement: '',
            conflict: undefined,
            comparing: false,
          }));
        }
      }
    })();
  }

  async function handleReload(): Promise<void> {
    const saved = await onReload();
    setState((current) => ({
      ...current,
      content: contentFromDraft(saved),
      version: saved.version,
      conflict: undefined,
      errorKey: '',
      announcement: 'reloadNotice',
      comparing: false,
    }));
  }

  function handleCopy(): void {
    void navigator.clipboard.writeText(JSON.stringify(state.content));
    setState((current) => ({ ...current, announcement: 'copiedNotice' }));
  }

  return (
    <section aria-label={text('panelTitle')} style={{ display: 'grid', gap: tokens.spacing.lg }}>
      <PanelHeader
        patientLocalId={props.patientLocalId}
        encounterLocalId={props.encounterLocalId}
        facilityId={props.facilityId}
      />

      {announcement !== '' ? (
        <p role="status" data-testid="entry-status">
          {text(announcement)}
        </p>
      ) : null}
      {errorKey !== '' ? (
        <p role="alert" data-testid="entry-error">
          {text(errorKey)}
        </p>
      ) : null}

      <AllergiesSection allergies={allergies} />

      <ActiveOrdersSection activeOrders={activeOrders} duplicates={duplicates} />

      <form
        aria-label={text('panelTitle')}
        style={{ display: 'grid', gap: tokens.spacing.md }}
        onSubmit={(event) => {
          event.preventDefault();
          handleSave();
        }}
      >
        <CatalogSearch
          query={query}
          onChange={(next) => setState((current) => ({ ...current, query: next }))}
        />

        <CatalogResults
          results={results}
          content={content}
          onAdd={(item) => updateContent(selectCatalogItem(content, item))}
        />

        <SelectedItems
          content={content}
          onRemove={(itemId) => updateContent(removeSelectedItem(content, itemId))}
        />

        <OrderFormFields
          content={content}
          imagingRequired={imagingRequired}
          onChange={updateContent}
        />

        <Button type="submit">{text('saveDraft')}</Button>
      </form>

      {conflict !== undefined ? (
        <ConflictState
          onCompare={() => setState((current) => ({ ...current, comparing: !current.comparing }))}
          onReload={() => void handleReload()}
          onCopy={handleCopy}
        />
      ) : null}

      {conflict !== undefined && comparing ? (
        <CompareSection conflict={conflict} content={state.content} />
      ) : null}
    </section>
  );
}
