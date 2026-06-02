#!/usr/bin/env node
// allium-workspace.mjs — zero-dependency workspace-mode wrapper around the
// `allium` CLI.
//
// Runs both `allium check specs/` and `allium analyse specs/` over the whole
// spec set in one pass. Passing the entire `specs/` directory (rather than
// individual files) lets the CLI resolve cross-spec `use` paths and triggers,
// so per-file `allium.use.unresolvedPath` warnings disappear.
//
// What it does:
//   (a) invokes `allium check specs/`   — JSON diagnostics per spec file.
//   (b) invokes `allium analyse specs/` — JSON diagnostics + process-level
//       `findings` per spec file (unreachable triggers etc.).
//   (c) parses the output (the CLI emits ONE JSON document per spec file,
//       concatenated — NDJSON-style — so a streaming brace scanner is used).
//   (d) prints a readable baseline summary: diagnostics by severity, the
//       `unreachableTrigger` count, the `field.unused` / `definition.unused`
//       counts, and a PASS/FAIL line.
//
// Exit behaviour:
//   * Exits 1 ONLY when at least one `error`-severity diagnostic is present
//     (from either `check` or `analyse`). Warnings, info and `findings` are
//     reported but never gate.
//   * Exits non-zero (loudly) if the `allium` binary is missing, if a command
//     exits with code 2 (no inputs), or if its output cannot be parsed.
//
// This script is the acceptance command for the spec checkboxes in Phases 2,
// 4 and 5 of docs/plans/alium-v6.md — its summary is the drift baseline later
// checkboxes compare against. Uses only Node.js built-ins.
//
// --- unreachableTrigger baseline (Allium v6, recorded 2026-05-21) ------------
//   * Pre-sweep baseline:  226 unreachableTrigger findings — the count before
//     any `surface RestApi` block existed, i.e. before the Phase 5 surface-
//     block sweep (checkboxes 5.1–5.5).
//   * The Phase 5 sweep drove the count down in five batches:
//       226 → 202 → 181 → 152 → 137 → 85.
//   * Post-sweep count:    85 unreachableTrigger findings (verified by this
//     script on 2026-05-21 — see the Baseline summary line below).
//   * error-severity diagnostics: 0 across all specs as of the v6 sweep
//     completion (2026-05-21) — the script exits 0.
//   * The residual 85 unreachableTrigger findings are the deliberately-omitted
//     event-driven / scheduled / internal / cross-spec triggers (not HTTP-
//     origin), which by design have no `surface RestApi` entry — per the
//     Phase 5 design.

import { spawnSync } from 'node:child_process';
import path from 'node:path';
import process from 'node:process';

// Resolve repo root relative to this script (docs/plans/scripts/ → repo root).
const SCRIPT_DIR = path.dirname(new URL(import.meta.url).pathname);
const REPO_ROOT = path.resolve(SCRIPT_DIR, '..', '..');

const SPECS_DIR = 'specs/';
const ALLIUM_BIN = 'allium';

// --- Multi-document JSON parsing ---------------------------------------------
// `allium check`/`analyse` print one JSON object per spec file, concatenated
// with no separator. JSON.parse only consumes the first; this scanner walks the
// string tracking brace depth (and string state, so braces inside string
// literals are ignored) and yields each top-level {...} document.
function parseConcatenatedJson(text) {
  const docs = [];
  let depth = 0;
  let start = -1;
  let inString = false;
  let escaped = false;
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (inString) {
      if (escaped) {
        escaped = false;
      } else if (ch === '\\') {
        escaped = true;
      } else if (ch === '"') {
        inString = false;
      }
      continue;
    }
    if (ch === '"') {
      inString = true;
    } else if (ch === '{') {
      if (depth === 0) start = i;
      depth++;
    } else if (ch === '}') {
      depth--;
      if (depth < 0) {
        throw new Error('unbalanced "}" in allium output');
      }
      if (depth === 0) {
        docs.push(JSON.parse(text.slice(start, i + 1)));
        start = -1;
      }
    }
  }
  if (depth !== 0) {
    throw new Error('unterminated JSON object in allium output');
  }
  return docs;
}

// --- Run one allium subcommand and parse its documents -----------------------
// Returns { docs: [...] }. Fails loudly (process.exit) on a missing binary,
// exit code 2 (no inputs), or unparseable output.
function runAllium(subcommand) {
  const result = spawnSync(ALLIUM_BIN, [subcommand, SPECS_DIR], {
    cwd: REPO_ROOT,
    encoding: 'utf8',
    maxBuffer: 64 * 1024 * 1024,
  });

  if (result.error) {
    if (result.error.code === 'ENOENT') {
      fail(
        `the \`${ALLIUM_BIN}\` CLI was not found on PATH. Install it (the ` +
          'expected location is /opt/homebrew/bin/allium) and retry.',
      );
    }
    fail(`failed to run \`${ALLIUM_BIN} ${subcommand}\`: ${result.error.message}`);
  }

  // Exit code 2 means "no inputs" — a real failure (e.g. specs/ went missing).
  if (result.status === 2) {
    fail(
      `\`${ALLIUM_BIN} ${subcommand} ${SPECS_DIR}\` exited with code 2 ` +
        `(no inputs). The \`${SPECS_DIR}\` directory has no .allium files, ` +
        'or does not exist relative to the repo root.',
    );
  }

  // Exit code 1 fires on warnings too — NOT a parse failure. We deliberately
  // ignore the CLI exit code beyond 2 and gate purely on parsed severities.
  // Any other non-{0,1,2} status is unexpected and treated as fatal.
  if (result.status !== 0 && result.status !== 1) {
    const stderr = (result.stderr || '').trim();
    fail(
      `\`${ALLIUM_BIN} ${subcommand} ${SPECS_DIR}\` exited with unexpected ` +
        `code ${result.status}` + (stderr ? `:\n${stderr}` : '.'),
    );
  }

  const stdout = result.stdout || '';
  if (stdout.trim() === '') {
    fail(
      `\`${ALLIUM_BIN} ${subcommand} ${SPECS_DIR}\` produced no output.` +
        ((result.stderr || '').trim()
          ? `\n${result.stderr.trim()}`
          : ''),
    );
  }

  let docs;
  try {
    docs = parseConcatenatedJson(stdout);
  } catch (e) {
    fail(
      `could not parse \`${ALLIUM_BIN} ${subcommand}\` output as JSON: ` +
        e.message,
    );
  }
  if (docs.length === 0) {
    fail(`\`${ALLIUM_BIN} ${subcommand}\` returned zero JSON documents.`);
  }
  return { docs };
}

function fail(message) {
  console.error(`\nFAILED — ${message}`);
  process.exit(1);
}

// --- Aggregate diagnostics + findings across all per-file documents ----------
function aggregate(docs) {
  const bySeverity = { error: 0, warning: 0, info: 0 };
  const byCode = new Map();
  const errors = [];
  let findingsTotal = 0;
  const findingsByKind = new Map();

  for (const doc of docs) {
    for (const d of doc.diagnostics || []) {
      const severity = d.severity || 'unknown';
      bySeverity[severity] = (bySeverity[severity] || 0) + 1;
      const code = d.code || 'unknown';
      byCode.set(code, (byCode.get(code) || 0) + 1);
      if (severity === 'error') errors.push({ ...d, specFile: doc.spec_file });
    }
    // `analyse` may surface process-level results in a `findings` array.
    for (const f of doc.findings || []) {
      findingsTotal++;
      const kind = f.type || f.code || f.kind || 'unknown';
      findingsByKind.set(kind, (findingsByKind.get(kind) || 0) + 1);
    }
  }

  return { bySeverity, byCode, errors, findingsTotal, findingsByKind };
}

function countMatching(byCode, ...needles) {
  let total = 0;
  for (const [code, n] of byCode) {
    if (needles.some((needle) => code.includes(needle))) total += n;
  }
  return total;
}

// --- Reporting ---------------------------------------------------------------
function printAggregate(label, agg, docCount) {
  console.log(`\n${label}`);
  console.log('-'.repeat(label.length));
  console.log(`  Spec files analysed: ${docCount}`);
  console.log(
    '  Diagnostics by severity: ' +
      `error=${agg.bySeverity.error || 0}, ` +
      `warning=${agg.bySeverity.warning || 0}, ` +
      `info=${agg.bySeverity.info || 0}`,
  );
  if (agg.byCode.size > 0) {
    console.log('  Diagnostics by code:');
    for (const [code, n] of [...agg.byCode].sort((a, b) => b[1] - a[1])) {
      console.log(`    ${String(n).padStart(4)}  ${code}`);
    }
  } else {
    console.log('  Diagnostics by code: (none)');
  }
  if (agg.findingsTotal > 0) {
    console.log(`  Findings: ${agg.findingsTotal}`);
    for (const [kind, n] of [...agg.findingsByKind].sort((a, b) => b[1] - a[1])) {
      console.log(`    ${String(n).padStart(4)}  ${kind}`);
    }
  } else {
    console.log('  Findings: (none)');
  }
}

// --- Main --------------------------------------------------------------------
function main() {
  console.log('Allium workspace validator');
  console.log('==========================');
  console.log(`Repo root: ${REPO_ROOT}`);
  console.log(`Running \`allium check ${SPECS_DIR}\` and ` +
    `\`allium analyse ${SPECS_DIR}\` over the full spec set.`);

  const check = runAllium('check');
  const analyse = runAllium('analyse');

  const checkAgg = aggregate(check.docs);
  const analyseAgg = aggregate(analyse.docs);

  printAggregate('allium check', checkAgg, check.docs.length);
  printAggregate('allium analyse', analyseAgg, analyse.docs.length);

  // --- Baseline summary ------------------------------------------------------
  // `unreachableTrigger` is reported both as a diagnostic code
  // (allium.rule.unreachableTrigger) and, when `analyse` populates it, in the
  // `findings` array. Count both so the baseline is stable regardless of which
  // channel the CLI uses.
  const unreachableCheck =
    countMatching(checkAgg.byCode, 'unreachableTrigger') +
    (checkAgg.findingsByKind.get('unreachableTrigger') || 0);
  const unreachableAnalyse =
    countMatching(analyseAgg.byCode, 'unreachableTrigger') +
    (analyseAgg.findingsByKind.get('unreachableTrigger') || 0);

  const fieldUnused =
    countMatching(checkAgg.byCode, 'field.unused') ||
    countMatching(analyseAgg.byCode, 'field.unused');
  const definitionUnused =
    countMatching(checkAgg.byCode, 'definition.unused') ||
    countMatching(analyseAgg.byCode, 'definition.unused');
  const entityUnused =
    countMatching(checkAgg.byCode, 'entity.unused') ||
    countMatching(analyseAgg.byCode, 'entity.unused');

  const totalErrors =
    (checkAgg.bySeverity.error || 0) + (analyseAgg.bySeverity.error || 0);

  console.log('\nBaseline summary');
  console.log('----------------');
  console.log(`  error-severity diagnostics:   ${totalErrors}`);
  console.log(
    '  warning-severity diagnostics: ' +
      `${(checkAgg.bySeverity.warning || 0) +
        (analyseAgg.bySeverity.warning || 0)} ` +
      '(check + analyse, non-gating)',
  );
  console.log(
    '  info-severity diagnostics:    ' +
      `${(checkAgg.bySeverity.info || 0) + (analyseAgg.bySeverity.info || 0)} ` +
      '(check + analyse, non-gating)',
  );
  console.log(
    `  unreachableTrigger findings:  check=${unreachableCheck}, ` +
      `analyse=${unreachableAnalyse} (non-gating)`,
  );
  console.log(`  field.unused diagnostics:     ${fieldUnused} (non-gating)`);
  console.log(
    `  definition.unused diagnostics: ${definitionUnused} (non-gating)`,
  );
  console.log(`  entity.unused diagnostics:    ${entityUnused} (non-gating)`);

  // --- Gate ------------------------------------------------------------------
  if (totalErrors > 0) {
    console.log(`\nError-severity diagnostics (${totalErrors})`);
    console.log('--------------------------------');
    for (const [label, agg] of [
      ['check', checkAgg],
      ['analyse', analyseAgg],
    ]) {
      for (const e of agg.errors) {
        const loc = e.location
          ? `${e.location.file}:${e.location.line}:${e.location.col}`
          : e.specFile || '(unknown location)';
        console.log(`  ERROR [${label}] ${loc}`);
        console.log(`        ${e.code}: ${e.message}`);
      }
    }
    console.log('\nFAIL — error-severity diagnostics present in the spec set.');
    process.exit(1);
  }

  console.log('\nPASS — no error-severity diagnostics in the spec set.');
  process.exit(0);
}

main();
