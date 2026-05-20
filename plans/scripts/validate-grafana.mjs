#!/usr/bin/env node
// validate-grafana.mjs — zero-dependency Grafana dashboard validator.
//
// Validates the provisioned Grafana dashboards under
// infra/grafana/provisioning/dashboards against the datasources declared in
// infra/grafana/provisioning/datasources/datasources.yaml, and (when present)
// structurally validates deploy/observability/alert-rules.yml.
//
// Checks:
//   (a) every dashboard JSON parses — JSON syntax errors are hard errors.
//   (b) every panel datasource (uid or name) resolves to a provisioned
//       datasource. A datasource that references a Grafana template variable
//       ($foo / ${foo}) is valid only when the dashboard's templating.list
//       declares a "type": "datasource" variable of that name.
//   (c) warns when a timeseries/stat/gauge/bargauge panel declares no unit.
//   (d) prints every PromQL/LogQL metric name referenced, grouped by dashboard.
//   (e) if alert-rules.yml exists, checks every "- alert:" block has expr,
//       for, labels and annotations.
//
// Exits 1 on any hard error, 0 otherwise. Uses only Node.js built-ins.

import fs from 'node:fs';
import path from 'node:path';
import process from 'node:process';

// Resolve repo root relative to this script (plans/scripts/ → repo root).
const SCRIPT_DIR = path.dirname(new URL(import.meta.url).pathname);
const REPO_ROOT = path.resolve(SCRIPT_DIR, '..', '..');

const DASHBOARD_DIR = path.join(
  REPO_ROOT,
  'infra/grafana/provisioning/dashboards',
);
const DATASOURCES_FILE = path.join(
  REPO_ROOT,
  'infra/grafana/provisioning/datasources/datasources.yaml',
);
const ALERT_RULES_FILE = path.join(
  REPO_ROOT,
  'deploy/observability/alert-rules.yml',
);

const errors = [];
const warnings = [];

function error(msg) {
  errors.push(msg);
}
function warn(msg) {
  warnings.push(msg);
}

// --- Recursively collect *.json files under a directory ----------------------
function walkJson(dir) {
  const out = [];
  let entries;
  try {
    entries = fs.readdirSync(dir, { withFileTypes: true });
  } catch {
    return out;
  }
  for (const entry of entries) {
    const full = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      out.push(...walkJson(full));
    } else if (entry.isFile() && entry.name.endsWith('.json')) {
      out.push(full);
    }
  }
  return out.sort();
}

// --- Parse datasources.yaml (line-based, tolerant) ---------------------------
// Returns { uids: Set, names: Set }. Only the `uid:`/`name:` keys belonging to
// a top-level datasource list item are collected — nested keys (derivedFields,
// exemplarTraceIdDestinations) sit at a deeper indentation and are ignored.
function parseDatasources(file) {
  const uids = new Set();
  const names = new Set();
  let text;
  try {
    text = fs.readFileSync(file, 'utf8');
  } catch {
    error(`datasources file not found: ${rel(file)}`);
    return { uids, names };
  }
  let inDatasources = false;
  // Indentation of a top-level datasource list item ("- name: ..."), learned
  // from the first "- " entry encountered inside the datasources block. Only
  // list items at this exact indent are datasources; deeper "- " entries
  // (derivedFields, exemplarTraceIdDestinations) are nested config.
  let dsListIndent = null;
  // Whether the most recent list item we entered is a datasource (vs nested).
  let inDatasourceItem = false;
  for (const rawLine of text.split('\n')) {
    const line = rawLine.replace(/\r$/, '');
    // Strip comments (best effort — values here never contain '#').
    const noComment = line.replace(/\s+#.*$/, '');
    const trimmed = noComment.trim();
    if (trimmed === '') continue;
    if (/^datasources:\s*$/.test(trimmed)) {
      inDatasources = true;
      continue;
    }
    if (!inDatasources) continue;
    // A non-indented, non-list key ends the datasources block.
    if (!/^\s/.test(noComment) && !trimmed.startsWith('-')) {
      inDatasources = false;
      continue;
    }
    const indent = noComment.match(/^(\s*)/)[1].length;
    const listItemMatch = noComment.match(/^(\s*)-\s+/);
    if (listItemMatch) {
      // The first "- " inside the block establishes the datasource list
      // indentation. A later "- " at that same indent is a new datasource;
      // a deeper one is nested config and is ignored.
      if (dsListIndent == null) dsListIndent = indent;
      inDatasourceItem = indent === dsListIndent;
    }
    if (!inDatasourceItem) continue;
    // Within a datasource item, keys live either on the "- " line itself or
    // indented two spaces past the "- ". Anything deeper is nested config.
    const keyIndent = dsListIndent + 2;
    if (!listItemMatch && indent !== keyIndent) continue;
    const uidMatch = trimmed.match(/^-?\s*uid:\s*["']?([^"'#]+?)["']?\s*$/);
    if (uidMatch) {
      uids.add(uidMatch[1].trim());
      continue;
    }
    const nameMatch = trimmed.match(/^-?\s*name:\s*["']?([^"'#]+?)["']?\s*$/);
    if (nameMatch) {
      names.add(nameMatch[1].trim());
    }
  }
  return { uids, names };
}

function rel(file) {
  return path.relative(REPO_ROOT, file);
}

// --- Datasource reference checking -------------------------------------------
const PANEL_TYPES_NEEDING_UNIT = new Set([
  'timeseries',
  'stat',
  'gauge',
  'bargauge',
]);

// True when a datasource reference is a Grafana template variable
// (e.g. "$datasource" or "${datasource}") which resolves at runtime.
function isTemplateVariable(value) {
  return typeof value === 'string' && /\$\{?\w+\}?/.test(value);
}

// Extracts the variable name from a template-variable reference string
// ("$foo" / "${foo}" → "foo"). Returns null when not a template reference.
function templateVariableName(value) {
  if (typeof value !== 'string') return null;
  const m = value.match(/\$\{?(\w+)\}?/);
  return m ? m[1] : null;
}

// Collects the set of template-variable names a dashboard declares with
// "type": "datasource" — these are the variables a panel may legitimately
// point its datasource at.
function datasourceVariableNames(dashboard) {
  const names = new Set();
  const list = dashboard?.templating?.list;
  if (!Array.isArray(list)) return names;
  for (const variable of list) {
    if (variable == null || typeof variable !== 'object') continue;
    if (variable.type === 'datasource' && typeof variable.name === 'string') {
      names.add(variable.name);
    }
  }
  return names;
}

// Resolve a datasource reference (string or {type,uid}) against provisioned
// datasources and the dashboard's own datasource-type template variables.
// Returns true if valid, false if it is a concrete unresolved ref or a
// template-variable reference with no matching datasource variable defined.
//
// `dsVariables` is the Set of datasource-type template-variable names declared
// by the dashboard; a "${foo}" reference is valid only when "foo" is in it.
function datasourceResolves(ds, sources, dsVariables) {
  const vars = dsVariables || new Set();
  if (ds == null) return true; // panel inherits dashboard/default datasource
  if (typeof ds === 'string') {
    if (ds === '' || ds === 'default' || ds === '-- Mixed --') return true;
    if (isTemplateVariable(ds)) {
      // A template-variable datasource is valid only when the dashboard
      // defines a matching "type": "datasource" template variable.
      return vars.has(templateVariableName(ds));
    }
    return sources.uids.has(ds) || sources.names.has(ds);
  }
  if (typeof ds === 'object') {
    const uid = ds.uid;
    if (uid == null || uid === '') return true; // type-only ref, runtime picks
    if (isTemplateVariable(uid)) {
      return vars.has(templateVariableName(uid));
    }
    if (uid === 'default' || uid === '-- Mixed --' || uid === 'grafana') {
      return true;
    }
    return sources.uids.has(uid) || sources.names.has(uid);
  }
  return true;
}

function describeDatasource(ds) {
  if (ds == null) return '(none)';
  if (typeof ds === 'string') return `"${ds}"`;
  if (typeof ds === 'object') return `{type:${ds.type}, uid:${ds.uid}}`;
  return String(ds);
}

// --- Metric-name extraction (best effort) ------------------------------------
// PromQL/LogQL reserved words / functions we never want to report as metrics.
const PROMQL_KEYWORDS = new Set([
  'by', 'without', 'on', 'ignoring', 'group_left', 'group_right', 'offset',
  'bool', 'and', 'or', 'unless', 'rate', 'irate', 'increase', 'sum', 'avg',
  'min', 'max', 'count', 'count_values', 'stddev', 'stdvar', 'topk', 'bottomk',
  'quantile', 'histogram_quantile', 'delta', 'idelta', 'deriv', 'predict_linear',
  'abs', 'absent', 'absent_over_time', 'ceil', 'floor', 'round', 'clamp',
  'clamp_min', 'clamp_max', 'exp', 'ln', 'log2', 'log10', 'sqrt', 'sgn',
  'time', 'timestamp', 'vector', 'scalar', 'label_replace', 'label_join',
  'sort', 'sort_desc', 'sort_by_label', 'sort_by_label_desc',
  'avg_over_time', 'min_over_time', 'max_over_time', 'sum_over_time',
  'count_over_time', 'quantile_over_time', 'stddev_over_time', 'stdvar_over_time',
  'last_over_time', 'present_over_time', 'changes', 'resets', 'day_of_month',
  'day_of_week', 'day_of_year', 'days_in_month', 'hour', 'minute', 'month',
  'year', 'pi', 'rad', 'deg', 'acos', 'asin', 'atan', 'cos', 'sin', 'tan',
  // LogQL specifics
  'json', 'logfmt', 'regexp', 'pattern', 'unwrap', 'line_format',
  'label_format', 'bytes_rate', 'bytes_over_time', 'first_over_time',
  'detected_level', 'service_name', 'rate_counter',
  'Inf', 'NaN', 'nan', 'inf',
]);

// Extracts candidate Prometheus metric names from a single expr string.
function extractMetrics(expr) {
  const found = new Set();
  if (typeof expr !== 'string') return found;
  // Strip double-quoted string literals so label values etc. are not scanned.
  const cleaned = expr.replace(/"(\\.|[^"\\])*"/g, ' ');
  // A metric name is a bare identifier. We treat an identifier as a metric
  // when it is NOT immediately followed by '(' (that would be a function).
  const idRe = /[a-zA-Z_][a-zA-Z0-9_]*/g;
  let m;
  while ((m = idRe.exec(cleaned)) !== null) {
    const name = m[0];
    const after = cleaned.slice(idRe.lastIndex);
    const before = cleaned.slice(0, m.index);
    if (PROMQL_KEYWORDS.has(name)) continue;
    // Skip function calls: identifier directly followed by '('.
    if (/^\s*\(/.test(after)) continue;
    // Skip label keys: identifier followed by '=' (e.g. job="x").
    if (/^\s*(=~|!~|!=|=)/.test(after)) continue;
    // Skip Grafana template-variable names (preceded by '$' or '${').
    if (/[$]\{?$/.test(before)) continue;
    // Heuristic: real Prometheus metrics conventionally contain an underscore.
    if (!name.includes('_')) continue;
    found.add(name);
  }
  return found;
}

// --- Dashboard validation ----------------------------------------------------
function collectPanels(dashboard) {
  const panels = [];
  function recurse(list) {
    if (!Array.isArray(list)) return;
    for (const panel of list) {
      if (panel == null || typeof panel !== 'object') continue;
      panels.push(panel);
      if (Array.isArray(panel.panels)) recurse(panel.panels); // row sub-panels
    }
  }
  recurse(dashboard.panels);
  return panels;
}

function validateDashboard(file, sources) {
  let raw;
  try {
    raw = fs.readFileSync(file, 'utf8');
  } catch (e) {
    error(`${rel(file)}: cannot read file — ${e.message}`);
    return null;
  }
  let dashboard;
  try {
    dashboard = JSON.parse(raw);
  } catch (e) {
    error(`${rel(file)}: JSON syntax error — ${e.message}`);
    return null;
  }

  const panels = collectPanels(dashboard);
  const metrics = new Set();

  // Datasource-type template variables this dashboard declares; a panel may
  // legitimately point its datasource at any of these via "${name}".
  const dsVariables = datasourceVariableNames(dashboard);

  // (b) template-variable datasource resolution — a query-type template
  // variable that itself targets a datasource (e.g. label_values(...)).
  const templateList = dashboard?.templating?.list;
  if (Array.isArray(templateList)) {
    for (const variable of templateList) {
      if (variable == null || typeof variable !== 'object') continue;
      if (!datasourceResolves(variable.datasource, sources, dsVariables)) {
        error(
          `${rel(file)}: template variable "${variable.name}" references ` +
            `unknown datasource ${describeDatasource(variable.datasource)}`,
        );
      }
    }
  }

  for (const panel of panels) {
    const panelType = panel.type;
    const panelTitle = panel.title || `#${panel.id ?? '?'}`;

    // Row panels carry no datasource/unit — skip those checks.
    if (panelType === 'row') continue;

    // (b) panel datasource resolution.
    if (!datasourceResolves(panel.datasource, sources, dsVariables)) {
      error(
        `${rel(file)}: panel "${panelTitle}" references unknown datasource ` +
          `${describeDatasource(panel.datasource)}`,
      );
    }

    // Targets may also carry their own datasource.
    if (Array.isArray(panel.targets)) {
      for (const target of panel.targets) {
        if (target == null || typeof target !== 'object') continue;
        if (!datasourceResolves(target.datasource, sources, dsVariables)) {
          error(
            `${rel(file)}: panel "${panelTitle}" target references unknown ` +
              `datasource ${describeDatasource(target.datasource)}`,
          );
        }
        // (d) metric extraction.
        for (const name of extractMetrics(target.expr)) {
          metrics.add(name);
        }
      }
    }

    // (c) missing-unit warning for graph/stat/gauge panels.
    if (PANEL_TYPES_NEEDING_UNIT.has(panelType)) {
      const unit = panel?.fieldConfig?.defaults?.unit;
      if (unit == null || unit === '') {
        warn(
          `${rel(file)}: ${panelType} panel "${panelTitle}" declares no unit`,
        );
      }
    }
  }

  return {
    file,
    title: dashboard.title || rel(file),
    panelCount: panels.length,
    metrics: [...metrics].sort(),
  };
}

// --- alert-rules.yml structural validation -----------------------------------
function validateAlertRules(file) {
  if (!fs.existsSync(file)) {
    return { present: false, alertCount: 0 };
  }
  let text;
  try {
    text = fs.readFileSync(file, 'utf8');
  } catch (e) {
    error(`${rel(file)}: cannot read file — ${e.message}`);
    return { present: true, alertCount: 0 };
  }

  const lines = text.split('\n').map((l) => l.replace(/\r$/, ''));
  // Each alert block starts at a "- alert:" line. The block extends until the
  // next line at the same-or-lower indentation that starts a new list item
  // ("- ") or a non-indented key.
  const required = ['expr', 'for', 'labels', 'annotations'];
  let alertCount = 0;

  function indentOf(line) {
    const m = line.match(/^(\s*)/);
    return m ? m[1].length : 0;
  }

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i];
    const alertMatch = line.match(/^(\s*)-\s+alert:\s*(.*)$/);
    if (!alertMatch) continue;
    alertCount++;
    const blockIndent = alertMatch[1].length;
    const alertName = alertMatch[2].trim() || `(unnamed @ line ${i + 1})`;

    // Keys within this block sit at blockIndent + 2 (aligned with "alert:").
    const keys = new Set(['alert']);
    for (let j = i + 1; j < lines.length; j++) {
      const next = lines[j];
      const trimmed = next.trim();
      if (trimmed === '' || trimmed.startsWith('#')) continue;
      const ind = indentOf(next);
      // A new "- " item or a dedent at/below blockIndent ends this block.
      if (ind <= blockIndent) break;
      if (trimmed.startsWith('- ')) {
        // Nested list item belonging to this block's value — skip it.
        continue;
      }
      const keyMatch = trimmed.match(/^([A-Za-z_][\w]*)\s*:/);
      if (keyMatch && ind === blockIndent + 2) {
        keys.add(keyMatch[1]);
      }
    }

    const missing = required.filter((k) => !keys.has(k));
    if (missing.length > 0) {
      error(
        `${rel(file)}: alert "${alertName}" is missing required ` +
          `key(s): ${missing.join(', ')}`,
      );
    }
  }

  return { present: true, alertCount };
}

// --- Main --------------------------------------------------------------------
function main() {
  console.log('Grafana dashboard validator');
  console.log('===========================');

  const sources = parseDatasources(DATASOURCES_FILE);
  console.log(
    `\nProvisioned datasources (${rel(DATASOURCES_FILE)}):` +
      `\n  uids:  ${[...sources.uids].sort().join(', ') || '(none)'}` +
      `\n  names: ${[...sources.names].sort().join(', ') || '(none)'}`,
  );

  const dashboardFiles = walkJson(DASHBOARD_DIR);
  if (dashboardFiles.length === 0) {
    error(`no dashboard JSON files found under ${rel(DASHBOARD_DIR)}`);
  }

  console.log(`\nChecking ${dashboardFiles.length} dashboard file(s)...`);
  const results = [];
  for (const file of dashboardFiles) {
    const result = validateDashboard(file, sources);
    if (result) results.push(result);
  }

  // (d) Print metrics referenced, grouped by dashboard.
  console.log('\nMetrics referenced by dashboard');
  console.log('-------------------------------');
  for (const result of results) {
    console.log(`\n${result.title}  (${rel(result.file)})`);
    if (result.metrics.length === 0) {
      console.log('  (no Prometheus metric names detected)');
    } else {
      for (const metric of result.metrics) {
        console.log(`  - ${metric}`);
      }
    }
  }

  // (e) alert-rules.yml.
  console.log('\nAlert rules');
  console.log('-----------');
  const alertResult = validateAlertRules(ALERT_RULES_FILE);
  if (!alertResult.present) {
    console.log(
      `  ${rel(ALERT_RULES_FILE)} not present — skipping (alert pipeline ` +
        'inert until it is created).',
    );
  } else {
    console.log(
      `  ${rel(ALERT_RULES_FILE)}: ${alertResult.alertCount} alert rule(s) ` +
        'structurally checked.',
    );
  }

  // Warnings.
  if (warnings.length > 0) {
    console.log(`\nWarnings (${warnings.length})`);
    console.log('--------');
    for (const w of warnings) console.log(`  WARN  ${w}`);
  }

  // Errors.
  if (errors.length > 0) {
    console.log(`\nErrors (${errors.length})`);
    console.log('------');
    for (const e of errors) console.log(`  ERROR ${e}`);
  }

  // Summary.
  console.log('\nSummary');
  console.log('-------');
  console.log(`  Dashboards checked: ${results.length}`);
  console.log(`  Warnings:           ${warnings.length}`);
  console.log(`  Errors:             ${errors.length}`);

  if (errors.length > 0) {
    console.log('\nFAILED — hard errors present.');
    process.exit(1);
  }
  console.log('\nOK — no hard errors.');
  process.exit(0);
}

main();
