-- Widen intraday_pnl_snapshots numeric columns from (20,8) to (30,8).
--
-- Why: precision(20, 8) caps the integer portion at 12 digits, so any P&L
-- value with absolute magnitude >= 1e12 (one trillion) overflows and
-- crashes the INSERT with
--   "ERROR: numeric field overflow. A field with precision 20, scale 8 must
--    round to an absolute value less than 10^12."
-- For a firm aggregating ~$5.6B of positions, intraday P&L attributions and
-- the high_water_mark can briefly exceed 1e12 in synthetic-data scenarios
-- (gamma * (large priceChange)^2 dominates the Taylor expansion). When the
-- INSERT throws, the TradeEventConsumer's coroutine dies and never restarts,
-- so the intraday P&L chart goes permanently empty for the rest of the day.
--
-- Widening to (30, 8) gives 22 integer digits of headroom — comfortable for
-- any plausible P&L number while keeping cent precision.
--
-- TimescaleDB note: the `minute_pnl_summary` continuous aggregate references
-- `total_pnl`, `high_water_mark`, `realised_pnl`, and `unrealised_pnl`, which
-- blocks ALTER COLUMN TYPE. We drop the materialized view (and its refresh
-- policy, which is dropped implicitly) before the ALTERs and recreate it
-- afterwards. The CAGG definition mirrors V35 exactly so behaviour is
-- preserved; historical aggregated data is lost and will rebuild on the next
-- refresh tick (which fires every 30 seconds per the policy below).

-- 1) Tear down the dependent continuous aggregate so the ALTER TYPEs can run.
DROP MATERIALIZED VIEW IF EXISTS minute_pnl_summary;

-- 2) Widen the columns.
ALTER TABLE intraday_pnl_snapshots
    ALTER COLUMN total_pnl       TYPE numeric(30, 8),
    ALTER COLUMN realised_pnl    TYPE numeric(30, 8),
    ALTER COLUMN unrealised_pnl  TYPE numeric(30, 8),
    ALTER COLUMN delta_pnl       TYPE numeric(30, 8),
    ALTER COLUMN gamma_pnl       TYPE numeric(30, 8),
    ALTER COLUMN vega_pnl        TYPE numeric(30, 8),
    ALTER COLUMN theta_pnl       TYPE numeric(30, 8),
    ALTER COLUMN rho_pnl         TYPE numeric(30, 8),
    ALTER COLUMN unexplained_pnl TYPE numeric(30, 8),
    ALTER COLUMN high_water_mark TYPE numeric(30, 8),
    ALTER COLUMN vanna_pnl       TYPE numeric(30, 8),
    ALTER COLUMN volga_pnl       TYPE numeric(30, 8),
    ALTER COLUMN charm_pnl       TYPE numeric(30, 8),
    ALTER COLUMN cross_gamma_pnl TYPE numeric(30, 8);

-- 3) Recreate the continuous aggregate with the same definition as V35.
CREATE MATERIALIZED VIEW minute_pnl_summary
WITH (timescaledb.continuous) AS
SELECT
    time_bucket('1 minute', snapshot_at) AS bucket,
    book_id,
    base_currency,
    AVG(total_pnl)      AS avg_total_pnl,
    MIN(total_pnl)      AS min_total_pnl,
    MAX(total_pnl)      AS max_total_pnl,
    MAX(high_water_mark) AS high_water_mark,
    AVG(realised_pnl)   AS avg_realised_pnl,
    AVG(unrealised_pnl) AS avg_unrealised_pnl,
    COUNT(*)            AS snapshot_count
FROM intraday_pnl_snapshots
GROUP BY bucket, book_id, base_currency
WITH NO DATA;

-- 4) Restore the refresh policy from V35.
SELECT add_continuous_aggregate_policy('minute_pnl_summary',
    start_offset      => INTERVAL '2 hours',
    end_offset        => INTERVAL '30 seconds',
    schedule_interval => INTERVAL '30 seconds'
);
