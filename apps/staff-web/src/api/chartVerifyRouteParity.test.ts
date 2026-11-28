/**
 * Route-parity tripwire for chart verification. The authoritative contract
 * declares the required If-Match precondition and its 428 outcome; the generated
 * client must mirror both. Reads committed files as text only: no endpoint is
 * contacted and no credential or patient data is used.
 */
import { readFileSync } from 'node:fs';
import { dirname, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { describe, expect, expectTypeOf, it } from 'vitest';
import type { components, operations } from './generated/openapi';

/** Directory of this test. */
const here = dirname(fileURLToPath(import.meta.url));
/** Staff-web module root. */
const moduleRoot = resolve(here, '..', '..');
/** Repository root, two levels above the staff-web module. */
const repoRoot = resolve(moduleRoot, '..', '..');

/** Return the text between two markers, naming the marker that was not found. */
function sliceBetween(text: string, start: string, end: string): string {
  const from = text.indexOf(start);
  if (from < 0) {
    throw new Error(`missing start marker: ${start}`);
  }
  const to = text.indexOf(end, from + start.length);
  if (to < 0) {
    throw new Error(`missing end marker: ${end}`);
  }
  return text.slice(from, to);
}

const contract = readFileSync(resolve(repoRoot, 'contracts', 'openapi.yaml'), 'utf8');
const generated = readFileSync(
  resolve(moduleRoot, 'src', 'api', 'generated', 'openapi.d.ts'),
  'utf8',
);
const contractChartverify = sliceBetween(
  contract,
  '      operationId: chartverify',
  '\n  /',
);
const generatedChartverify = sliceBetween(generated, '    chartverify: {\n', '\n    };');
const ifMatchParameter = sliceBetween(contract, '    IfMatch:\n', '    IdempotencyKey:\n');

type ChartVerifyHeader = operations['chartverify']['parameters']['header'];
type ChartVerifyResponses = operations['chartverify']['responses'];

// Compile-time only. An absent or optional key widens the indexed access and
// fails one of these assertions before the client is regenerated.
expectTypeOf<ChartVerifyHeader['If-Match']>().toEqualTypeOf<
  components['parameters']['IfMatch']
>();
expectTypeOf<428>().toExtend<keyof ChartVerifyResponses>();

describe('chartverify route parity', () => {
  it('declares a required If-Match header on the authoritative operation', () => {
    expect(ifMatchParameter).toContain('required: true');
    expect(contractChartverify).toContain("- $ref: '#/components/parameters/IfMatch'");
  });

  it('documents the 428 If-Match precondition response', () => {
    expect(contractChartverify).toContain(
      "'428': { description: If-Match precondition required }",
    );
  });

  it('mirrors the contract header and response in the generated client', () => {
    expect(generatedChartverify).toContain(
      '"If-Match": components["parameters"]["IfMatch"];',
    );
    expect(generatedChartverify).toMatch(/^\s+428:/m);
    expect(generatedChartverify).toContain(
      '"Idempotency-Key"?: components["parameters"]["RetryKey"];',
    );
  });

  it('names a missing marker instead of silently slicing an empty block', () => {
    expect(() => sliceBetween('synthetic text', 'missing-start', 'end')).toThrow(
      'missing start marker: missing-start',
    );
    expect(() => sliceBetween('synthetic text', 'synthetic', 'missing-end')).toThrow(
      'missing end marker: missing-end',
    );
  });
});
