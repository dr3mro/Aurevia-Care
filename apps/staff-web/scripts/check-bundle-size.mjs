import console from 'node:console';
import { readFileSync, readdirSync } from 'node:fs';
import { join, resolve } from 'node:path';
import process from 'node:process';
import { fileURLToPath, pathToFileURL, URL } from 'node:url';
import { gzipSync } from 'node:zlib';

/**
 * Maximum allowed gzipped size of one built JavaScript chunk, in bytes.
 *
 * WEB-08 sets a 250 KB budget; the budget is expressed in decimal bytes so the
 * gate is deterministic and does not depend on a binary-kilobyte convention.
 */
export const MAX_CHUNK_GZIP_BYTES = 256_000;

/**
 * Measure the gzipped size of a built asset.
 *
 * @param contents raw bytes of a built JavaScript file
 * @returns the gzipped byte length
 */
export function gzipSize(contents) {
  return gzipSync(contents).byteLength;
}

/**
 * Assert that a single built asset stays within the WEB-08 gzip budget.
 *
 * @param contents raw bytes of a built JavaScript file
 * @param label asset name included in the failure message
 * @returns the gzipped byte length
 * @throws when the gzipped size exceeds {@link MAX_CHUNK_GZIP_BYTES}
 */
export function assertWithinBudget(contents, label) {
  const size = gzipSize(contents);
  if (size > MAX_CHUNK_GZIP_BYTES) {
    throw new Error(
      `${label}: ${size} bytes gzipped exceeds the ${MAX_CHUNK_GZIP_BYTES} byte budget (WEB-08)`,
    );
  }
  return size;
}

/**
 * Measure every built JavaScript chunk in a directory.
 *
 * @param directory path or file URL of Vite's `dist/assets` output
 * @returns the measured rows and whether any row exceeds the budget
 */
export function measureAssets(directory) {
  const path = typeof directory === 'string' ? directory : fileURLToPath(directory);
  const names = readdirSync(path)
    .filter((name) => name.endsWith('.js'))
    .sort();
  const rows = names.map((name) => {
    const size = gzipSize(readFileSync(join(path, name)));
    return { name, size, withinBudget: size <= MAX_CHUNK_GZIP_BYTES };
  });
  return { rows, failed: rows.some((row) => !row.withinBudget) };
}

/** Print the measured sizes and fail the process when any chunk is over budget. */
function main() {
  const { rows, failed } = measureAssets(new URL('../dist/assets/', import.meta.url));

  console.log('chunk                                          gzip bytes  budget');
  console.log('---------------------------------------------------------------');
  for (const row of rows) {
    console.log(
      `${row.name.padEnd(46)} ${String(row.size).padStart(10)}  ${row.withinBudget ? 'ok' : 'OVER'}`,
    );
  }

  if (rows.length === 0) {
    console.error('No built JavaScript assets found; run the build before checking sizes.');
    process.exitCode = 1;
    return;
  }
  if (failed) {
    console.error(`One or more chunks exceed ${MAX_CHUNK_GZIP_BYTES} bytes gzipped (WEB-08).`);
    process.exitCode = 1;
  }
}

const invokedDirectly =
  process.argv[1] !== undefined && import.meta.url === pathToFileURL(resolve(process.argv[1])).href;
if (invokedDirectly) {
  main();
}
