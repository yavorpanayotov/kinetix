#!/usr/bin/env python3
"""Benchmark script: profile gRPC ThreadPoolExecutor sizing for the risk-engine.

Fires N concurrent Valuate RPCs against an in-process gRPC server with pool
sizes [4, 8, 16, 32] and reports throughput (requests/second) and mean latency.

Usage:
    cd risk-engine
    uv run python3 scripts/profile_thread_pool.py

The server uses a minimal RiskCalculationServicer. Requests have no positions,
so calculate_valuation returns immediately — this stresses the gRPC dispatch
path, not calculation logic. That is intentional: the bottleneck we are sizing
is thread dispatch, not computation.

For production sizing, also run with realistic payloads (see the NOTES section
at the bottom of this script).
"""

from __future__ import annotations

import os
import sys
import time
from concurrent import futures
from pathlib import Path

# Ensure the project source is importable when running from the scripts/ dir.
_ROOT = Path(__file__).parent.parent
sys.path.insert(0, str(_ROOT / "src"))

import grpc

from kinetix.common import types_pb2
from kinetix.risk import risk_calculation_pb2, risk_calculation_pb2_grpc
from kinetix_risk.server import RiskCalculationServicer

# Number of concurrent requests per pool size. Chosen to be a multiple of the
# largest pool size (32) and large enough to produce stable throughput numbers.
_N_REQUESTS = 320


def _build_request() -> risk_calculation_pb2.ValuationRequest:
    return risk_calculation_pb2.ValuationRequest(
        book_id=types_pb2.BookId(value="BENCH-BOOK"),
        calculation_type=risk_calculation_pb2.MONTE_CARLO,
        confidence_level=risk_calculation_pb2.CL_95,
        time_horizon_days=1,
        num_simulations=100,
    )


def _start_server(pool_size: int) -> tuple[grpc.Server, int]:
    server = grpc.server(
        futures.ThreadPoolExecutor(max_workers=pool_size),
        options=[
            ("grpc.max_send_message_length", 10 * 1024 * 1024),
            ("grpc.max_receive_message_length", 10 * 1024 * 1024),
        ],
    )
    risk_calculation_pb2_grpc.add_RiskCalculationServiceServicer_to_server(
        RiskCalculationServicer(), server
    )
    port = server.add_insecure_port("[::]:0")
    server.start()
    return server, port


def _run_concurrent(stub: risk_calculation_pb2_grpc.RiskCalculationServiceStub, n: int) -> float:
    """Fire n concurrent RPCs via the thread pool and return elapsed seconds."""
    request = _build_request()

    def do_call(_: int) -> None:
        stub.Valuate(request)

    t0 = time.perf_counter()
    with futures.ThreadPoolExecutor(max_workers=n) as pool:
        futs = [pool.submit(do_call, i) for i in range(n)]
        for f in futures.as_completed(futs):
            f.result()  # propagate any exception
    return time.perf_counter() - t0


def benchmark(pool_size: int, n_requests: int) -> dict:
    server, port = _start_server(pool_size)
    channel = grpc.insecure_channel(f"localhost:{port}")
    stub = risk_calculation_pb2_grpc.RiskCalculationServiceStub(channel)

    # Warm-up: one pass to avoid measuring JIT/import overhead.
    _run_concurrent(stub, min(pool_size, 8))

    elapsed = _run_concurrent(stub, n_requests)
    throughput = n_requests / elapsed
    mean_latency_ms = (elapsed / n_requests) * 1000

    channel.close()
    server.stop(grace=None)

    return {
        "pool_size": pool_size,
        "n_requests": n_requests,
        "elapsed_s": round(elapsed, 3),
        "throughput_rps": round(throughput, 1),
        "mean_latency_ms": round(mean_latency_ms, 2),
    }


def main() -> None:
    cpu_count = os.cpu_count() or 4
    pool_sizes = [4, 8, 16, 32]

    print(f"CPU count: {cpu_count}")
    print(f"Benchmarking {_N_REQUESTS} concurrent Valuate RPCs per pool size")
    print(f"Pool sizes: {pool_sizes}")
    print()

    results = []
    for size in pool_sizes:
        print(f"  pool_size={size:>3} ...", end=" ", flush=True)
        r = benchmark(size, _N_REQUESTS)
        results.append(r)
        print(f"{r['throughput_rps']:>8.1f} rps  mean_latency={r['mean_latency_ms']:.2f}ms  elapsed={r['elapsed_s']}s")

    print()
    print("=" * 60)
    print("RESULTS SUMMARY")
    print("=" * 60)
    print(f"{'pool_size':>12}  {'rps':>10}  {'mean_latency_ms':>18}  {'elapsed_s':>10}")
    print("-" * 60)
    for r in results:
        print(f"{r['pool_size']:>12}  {r['throughput_rps']:>10.1f}  {r['mean_latency_ms']:>18.2f}  {r['elapsed_s']:>10.3f}")

    best = max(results, key=lambda r: r["throughput_rps"])
    print()
    print(f"Best throughput at pool_size={best['pool_size']}: {best['throughput_rps']} rps")
    print(f"CPU count: {cpu_count}  => 2*cpu_count = {2 * cpu_count}")
    print()

    # Recommend: if best size is <= 2*cpu_count, use 2*cpu_count; cap at 32.
    recommended = min(max(best["pool_size"], 2 * cpu_count), 32)
    print(f"Recommended GRPC_THREAD_POOL_SIZE={recommended}")


if __name__ == "__main__":
    main()

# ---------------------------------------------------------------------------
# NOTES on production sizing
# ---------------------------------------------------------------------------
# This benchmark stresses empty-payload RPCs to isolate gRPC dispatch overhead.
# For production, replace _build_request() with a realistic payload (10-50
# positions, full market data) and rerun. The compute-heavy path (Monte Carlo,
# Greeks) will dominate and the optimal pool size will be lower — typically
# cpu_count rather than 2*cpu_count — because threads will be CPU-bound rather
# than I/O-waiting. The env var GRPC_THREAD_POOL_SIZE lets ops tune without a
# code change.
