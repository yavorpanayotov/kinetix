#!/usr/bin/env python3
"""Acceptance harness for Grafana dashboard data.

For each dashboard JSON it extracts every panel's datasource queries
(PromQL for Prometheus panels, LogQL for Loki panels), runs them against
the live datasources, and reports which panels return no data.

It also distinguishes the two root-cause classes the observability-fixes
loop cares about:

  * QUERY  - the metric/stream EXISTS in the datasource but the panel's
             query (labels, template vars, functions) yields nothing.
             => dashboard-JSON fix.
  * SOURCE - the underlying metric/stream is NEVER emitted at all.
             => instrumentation / backend code fix.

Usage:
  scripts/check_dashboard_data.py [DASHBOARD_JSON ...]      # specific files
  scripts/check_dashboard_data.py --all                     # every dashboard
  scripts/check_dashboard_data.py --all --json              # machine output

Exit code is non-zero if any panel in the checked set returns no data,
so it can back a /work-plan acceptance line per dashboard.

Env:
  PROM_URL  (default http://localhost:9090)
  LOKI_URL  (default http://localhost:3100)
"""
from __future__ import annotations

import argparse
import glob
import json
import os
import re
import sys
import urllib.parse
import urllib.request

PROM_URL = os.environ.get("PROM_URL", "http://localhost:9090")
LOKI_URL = os.environ.get("LOKI_URL", "http://localhost:3100")
DASH_GLOB = "infra/grafana/provisioning/dashboards/**/*.json"

# Grafana global macros -> concrete values for an instant query.
MACROS = {
    "$__rate_interval": "5m",
    "$__interval": "1m",
    "$__range": "1h",
    "$__range_s": "3600",
    "$__range_ms": "3600000",
    "${__rate_interval}": "5m",
    "${__interval}": "1m",
}

PROM_METRIC_RE = re.compile(r"\b([a-zA-Z_:][a-zA-Z0-9_:]*)\s*(?:\{|\(|\[| by| without|$|\s)")
# Reserved PromQL words that look like metrics but aren't.
PROM_RESERVED = {
    "by", "without", "on", "ignoring", "group_left", "group_right", "offset",
    "bool", "and", "or", "unless", "sum", "rate", "irate", "increase", "avg",
    "min", "max", "count", "count_values", "stddev", "stdvar", "topk", "bottomk",
    "quantile", "histogram_quantile", "label_replace", "label_join", "abs",
    "ceil", "floor", "round", "clamp_max", "clamp_min", "delta", "idelta",
    "deriv", "predict_linear", "time", "vector", "scalar", "absent", "le",
    "avg_over_time", "sum_over_time", "max_over_time", "min_over_time",
    "count_over_time", "last_over_time", "present_over_time", "changes",
    "resets", "Inf", "NaN", "e",
}


def http_get_json(url: str) -> dict:
    req = urllib.request.Request(url, headers={"Accept": "application/json"})
    with urllib.request.urlopen(req, timeout=20) as resp:
        return json.loads(resp.read().decode())


def strip_template_vars(expr: str) -> str:
    """Neutralise Grafana template vars so an instant query still parses.

    A label matcher like `job="$service"` becomes `job=~".+"`; bare `$var`
    tokens elsewhere become `.+`. Good enough to test data existence.
    """
    for macro, val in MACROS.items():
        expr = expr.replace(macro, val)
    # label="$var" or label="${var}"  -> label=~".+"
    expr = re.sub(r'(\w+)\s*=\s*"\$\{?\w+\}?"', r'\1=~".+"', expr)
    expr = re.sub(r'(\w+)\s*=~\s*"\$\{?\w+\}?"', r'\1=~".+"', expr)
    # any remaining $var / ${var}
    expr = re.sub(r"\$\{?\w+\}?", ".+", expr)
    return expr


def prom_metric_names(expr: str) -> list[str]:
    names = []
    for m in PROM_METRIC_RE.finditer(expr):
        name = m.group(1)
        if name in PROM_RESERVED or name.isdigit():
            continue
        if name not in names:
            names.append(name)
    return names


def prom_instant(expr: str) -> tuple[bool, str]:
    q = urllib.parse.urlencode({"query": expr})
    try:
        data = http_get_json(f"{PROM_URL}/api/v1/query?{q}")
    except Exception as e:  # noqa: BLE001
        return False, f"error: {e}"
    if data.get("status") != "success":
        return False, f"prom error: {data.get('error', 'unknown')}"
    res = data.get("data", {}).get("result", [])
    return (len(res) > 0), f"{len(res)} series"


def prom_metric_exists(metric: str) -> bool:
    ok, _ = prom_instant(f"count({metric})")
    return ok


def loki_has_data(expr: str) -> tuple[bool, str]:
    # Use a wide window instant-ish query via query_range count.
    expr_c = strip_template_vars(expr)
    params = urllib.parse.urlencode(
        {"query": f"count_over_time(({expr_c})[1h])", "limit": "1"}
    )
    try:
        data = http_get_json(f"{LOKI_URL}/loki/api/v1/query?{params}")
    except Exception as e:  # noqa: BLE001
        return False, f"error: {e}"
    res = data.get("data", {}).get("result", [])
    return (len(res) > 0), f"{len(res)} streams"


def iter_panels(dash: dict):
    stack = list(dash.get("panels", []))
    while stack:
        p = stack.pop()
        if p.get("type") == "row" and p.get("panels"):
            stack.extend(p["panels"])
        yield p


def panel_ds_type(panel: dict, target: dict) -> str:
    ds = target.get("datasource") or panel.get("datasource") or {}
    if isinstance(ds, dict):
        t = (ds.get("type") or "").lower()
        uid = (ds.get("uid") or "").lower()
    else:
        t = uid = str(ds).lower()
    if "loki" in t or "loki" in uid:
        return "loki"
    if "prometheus" in t or "prometheus" in uid:
        return "prometheus"
    return "prometheus"  # default for this stack


def check_dashboard(path: str) -> dict:
    with open(path) as f:
        dash = json.load(f)
    findings = []
    for panel in iter_panels(dash):
        title = panel.get("title", "(untitled)")
        for target in panel.get("targets", []) or []:
            expr = target.get("expr")
            if not expr:
                continue
            dst = panel_ds_type(panel, target)
            if dst == "loki":
                ok, detail = loki_has_data(expr)
                cls = "OK" if ok else "SOURCE"
            else:
                ok, detail = prom_instant(strip_template_vars(expr))
                if ok:
                    cls = "OK"
                else:
                    metrics = prom_metric_names(expr)
                    any_exists = any(prom_metric_exists(m) for m in metrics)
                    cls = "QUERY" if any_exists else "SOURCE"
                    detail += f" | metrics={metrics}"
            if not ok:
                findings.append(
                    {"panel": title, "ds": dst, "class": cls,
                     "expr": expr, "detail": detail}
                )
    return {
        "dashboard": os.path.relpath(path),
        "uid": dash.get("uid"),
        "empty_panels": findings,
    }


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("files", nargs="*")
    ap.add_argument("--all", action="store_true")
    ap.add_argument("--json", action="store_true", dest="as_json")
    args = ap.parse_args()

    paths = args.files
    if args.all or not paths:
        paths = sorted(glob.glob(DASH_GLOB, recursive=True))

    results = [check_dashboard(p) for p in paths]
    total_empty = sum(len(r["empty_panels"]) for r in results)

    if args.as_json:
        print(json.dumps(results, indent=2))
    else:
        for r in results:
            n = len(r["empty_panels"])
            mark = "OK " if n == 0 else "BAD"
            print(f"[{mark}] {r['dashboard']}  ({n} empty panel(s))")
            for f in r["empty_panels"]:
                print(f"      - [{f['class']}] {f['panel']}: {f['detail']}")
        print(f"\nTotal empty panels: {total_empty} across {len(results)} dashboard(s)")

    return 1 if total_empty else 0


if __name__ == "__main__":
    sys.exit(main())
