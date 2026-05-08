-- V27: Backfill any 'UNKNOWN' instrument_type rows in positions and
-- trade_events to a real type code, drop the 'UNKNOWN' DEFAULT that V12
-- introduced, and add a CHECK constraint forbidding 'UNKNOWN' from now
-- on. Companion to V26 — same principle: every instrument has a type.
--
-- Background:
--   • V9 backfilled DERIVATIVE rows as CASH_EQUITY (wrong).
--   • V12 corrected those to 'UNKNOWN' and made the column NOT NULL
--     DEFAULT 'UNKNOWN' as a stopgap.
--   • This migration finishes the job: real types for the rows that
--     still say 'UNKNOWN', and structural enforcement that no future
--     row may carry that placeholder.
--
-- Mapping below mirrors the reference-data and position-service
-- seeders. Any 'UNKNOWN' row whose instrument_id is not in this map
-- causes the migration to fail rather than silently default.

-- ── trade_events ───────────────────────────────────────────────────
UPDATE trade_events
SET instrument_type = CASE
        WHEN instrument_id = 'AAPL' THEN 'CASH_EQUITY'
        WHEN instrument_id = 'AAPL-BOND-2030' THEN 'CORPORATE_BOND'
        WHEN instrument_id = 'AAPL-C-200-20260620' THEN 'EQUITY_OPTION'
        WHEN instrument_id = 'AAPL-P-180-20260620' THEN 'EQUITY_OPTION'
        WHEN instrument_id = 'ADBE' THEN 'CASH_EQUITY'
        WHEN instrument_id = 'AMD' THEN 'CASH_EQUITY'
        WHEN instrument_id = 'AMZN' THEN 'CASH_EQUITY'
        WHEN instrument_id = 'AMZN-C-220-20260620' THEN 'EQUITY_OPTION'
        WHEN instrument_id = 'AMZN-P-190-20260620' THEN 'EQUITY_OPTION'
        WHEN instrument_id = 'AUDUSD' THEN 'FX_SPOT'
        WHEN instrument_id = 'BABA' THEN 'CASH_EQUITY'
        WHEN instrument_id = 'BAC' THEN 'CASH_EQUITY'
        WHEN instrument_id = 'CL' THEN 'COMMODITY_FUTURE'
        WHEN instrument_id = 'CL-P-70-DEC26' THEN 'COMMODITY_OPTION'
        WHEN instrument_id = 'CRM' THEN 'CASH_EQUITY'
        WHEN instrument_id = 'CVX' THEN 'CASH_EQUITY'
        WHEN instrument_id = 'DE10Y' THEN 'GOVERNMENT_BOND'
        WHEN instrument_id = 'DE2Y' THEN 'GOVERNMENT_BOND'
        WHEN instrument_id = 'DIS' THEN 'CASH_EQUITY'
        WHEN instrument_id = 'EUR-ESTR-5Y' THEN 'INTEREST_RATE_SWAP'
        WHEN instrument_id = 'EURGBP' THEN 'FX_SPOT'
        WHEN instrument_id = 'EURUSD' THEN 'FX_SPOT'
        WHEN instrument_id = 'EURUSD-6M' THEN 'FX_FORWARD'
        WHEN instrument_id = 'EURUSD-P-1.08-SEP26' THEN 'FX_OPTION'
        WHEN instrument_id = 'GBPUSD' THEN 'FX_SPOT'
        WHEN instrument_id = 'GBPUSD-3M' THEN 'FX_FORWARD'
        WHEN instrument_id = 'GC' THEN 'COMMODITY_FUTURE'
        WHEN instrument_id = 'GC-C-2200-DEC26' THEN 'COMMODITY_OPTION'
        WHEN instrument_id = 'GOOGL' THEN 'CASH_EQUITY'
        WHEN instrument_id = 'GOOGL-C-190-20260620' THEN 'EQUITY_OPTION'
        WHEN instrument_id = 'GOOGL-P-160-20260620' THEN 'EQUITY_OPTION'
        WHEN instrument_id = 'GS' THEN 'CASH_EQUITY'
        WHEN instrument_id = 'GS-BOND-2029' THEN 'CORPORATE_BOND'
        WHEN instrument_id = 'HG' THEN 'COMMODITY_FUTURE'
        WHEN instrument_id = 'IDX-SPX' THEN 'CASH_EQUITY'
        WHEN instrument_id = 'IDX-VIX' THEN 'CASH_EQUITY'
        WHEN instrument_id = 'INTC' THEN 'CASH_EQUITY'
        WHEN instrument_id = 'JNJ' THEN 'CASH_EQUITY'
        WHEN instrument_id = 'JP10Y' THEN 'GOVERNMENT_BOND'
        WHEN instrument_id = 'JPM' THEN 'CASH_EQUITY'
        WHEN instrument_id = 'JPM-BOND-2031' THEN 'CORPORATE_BOND'
        WHEN instrument_id = 'KO' THEN 'CASH_EQUITY'
        WHEN instrument_id = 'META' THEN 'CASH_EQUITY'
        WHEN instrument_id = 'MS' THEN 'CASH_EQUITY'
        WHEN instrument_id = 'MSFT' THEN 'CASH_EQUITY'
        WHEN instrument_id = 'MSFT-BOND-2032' THEN 'CORPORATE_BOND'
        WHEN instrument_id = 'MSFT-C-450-20260620' THEN 'EQUITY_OPTION'
        WHEN instrument_id = 'MSFT-P-400-20260620' THEN 'EQUITY_OPTION'
        WHEN instrument_id = 'NDX-SEP26' THEN 'EQUITY_FUTURE'
        WHEN instrument_id = 'NG' THEN 'COMMODITY_FUTURE'
        WHEN instrument_id = 'NVDA' THEN 'CASH_EQUITY'
        WHEN instrument_id = 'NVDA-C-950-20260620' THEN 'EQUITY_OPTION'
        WHEN instrument_id = 'NVDA-P-800-20260620' THEN 'EQUITY_OPTION'
        WHEN instrument_id = 'NZDUSD' THEN 'FX_SPOT'
        WHEN instrument_id = 'ORCL' THEN 'CASH_EQUITY'
        WHEN instrument_id = 'PFE' THEN 'CASH_EQUITY'
        WHEN instrument_id = 'PL' THEN 'COMMODITY_FUTURE'
        WHEN instrument_id = 'RTY-SEP26' THEN 'EQUITY_FUTURE'
        WHEN instrument_id = 'SI' THEN 'COMMODITY_FUTURE'
        WHEN instrument_id = 'SPX-CALL-5000' THEN 'EQUITY_OPTION'
        WHEN instrument_id = 'SPX-CALL-5200' THEN 'EQUITY_OPTION'
        WHEN instrument_id = 'SPX-PUT-4500' THEN 'EQUITY_OPTION'
        WHEN instrument_id = 'SPX-PUT-4800' THEN 'EQUITY_OPTION'
        WHEN instrument_id = 'SPX-SEP26' THEN 'EQUITY_FUTURE'
        WHEN instrument_id = 'TSLA' THEN 'CASH_EQUITY'
        WHEN instrument_id = 'TSLA-C-280-20260620' THEN 'EQUITY_OPTION'
        WHEN instrument_id = 'TSLA-P-220-20260620' THEN 'EQUITY_OPTION'
        WHEN instrument_id = 'UK10Y' THEN 'GOVERNMENT_BOND'
        WHEN instrument_id = 'UNH' THEN 'CASH_EQUITY'
        WHEN instrument_id = 'US10Y' THEN 'GOVERNMENT_BOND'
        WHEN instrument_id = 'US2Y' THEN 'GOVERNMENT_BOND'
        WHEN instrument_id = 'US30Y' THEN 'GOVERNMENT_BOND'
        WHEN instrument_id = 'US5Y' THEN 'GOVERNMENT_BOND'
        WHEN instrument_id = 'USD-SOFR-10Y' THEN 'INTEREST_RATE_SWAP'
        WHEN instrument_id = 'USD-SOFR-5Y' THEN 'INTEREST_RATE_SWAP'
        WHEN instrument_id = 'USDCAD' THEN 'FX_SPOT'
        WHEN instrument_id = 'USDCHF' THEN 'FX_SPOT'
        WHEN instrument_id = 'USDJPY' THEN 'FX_SPOT'
        WHEN instrument_id = 'USDJPY-3M' THEN 'FX_FORWARD'
        WHEN instrument_id = 'USDJPY-C-155-SEP26' THEN 'FX_OPTION'
        WHEN instrument_id = 'VIX-PUT-15' THEN 'EQUITY_OPTION'
        WHEN instrument_id = 'WMT' THEN 'CASH_EQUITY'
        WHEN instrument_id = 'WTI-AUG26' THEN 'COMMODITY_FUTURE'
        WHEN instrument_id = 'XOM' THEN 'CASH_EQUITY'
        WHEN instrument_id = 'ZC' THEN 'COMMODITY_FUTURE'
        ELSE instrument_type
    END
WHERE instrument_type = 'UNKNOWN';

-- ── positions ──────────────────────────────────────────────────────
UPDATE positions
SET instrument_type = CASE
        WHEN instrument_id = 'AAPL' THEN 'CASH_EQUITY'
        WHEN instrument_id = 'AAPL-BOND-2030' THEN 'CORPORATE_BOND'
        WHEN instrument_id = 'AAPL-C-200-20260620' THEN 'EQUITY_OPTION'
        WHEN instrument_id = 'AAPL-P-180-20260620' THEN 'EQUITY_OPTION'
        WHEN instrument_id = 'ADBE' THEN 'CASH_EQUITY'
        WHEN instrument_id = 'AMD' THEN 'CASH_EQUITY'
        WHEN instrument_id = 'AMZN' THEN 'CASH_EQUITY'
        WHEN instrument_id = 'AMZN-C-220-20260620' THEN 'EQUITY_OPTION'
        WHEN instrument_id = 'AMZN-P-190-20260620' THEN 'EQUITY_OPTION'
        WHEN instrument_id = 'AUDUSD' THEN 'FX_SPOT'
        WHEN instrument_id = 'BABA' THEN 'CASH_EQUITY'
        WHEN instrument_id = 'BAC' THEN 'CASH_EQUITY'
        WHEN instrument_id = 'CL' THEN 'COMMODITY_FUTURE'
        WHEN instrument_id = 'CL-P-70-DEC26' THEN 'COMMODITY_OPTION'
        WHEN instrument_id = 'CRM' THEN 'CASH_EQUITY'
        WHEN instrument_id = 'CVX' THEN 'CASH_EQUITY'
        WHEN instrument_id = 'DE10Y' THEN 'GOVERNMENT_BOND'
        WHEN instrument_id = 'DE2Y' THEN 'GOVERNMENT_BOND'
        WHEN instrument_id = 'DIS' THEN 'CASH_EQUITY'
        WHEN instrument_id = 'EUR-ESTR-5Y' THEN 'INTEREST_RATE_SWAP'
        WHEN instrument_id = 'EURGBP' THEN 'FX_SPOT'
        WHEN instrument_id = 'EURUSD' THEN 'FX_SPOT'
        WHEN instrument_id = 'EURUSD-6M' THEN 'FX_FORWARD'
        WHEN instrument_id = 'EURUSD-P-1.08-SEP26' THEN 'FX_OPTION'
        WHEN instrument_id = 'GBPUSD' THEN 'FX_SPOT'
        WHEN instrument_id = 'GBPUSD-3M' THEN 'FX_FORWARD'
        WHEN instrument_id = 'GC' THEN 'COMMODITY_FUTURE'
        WHEN instrument_id = 'GC-C-2200-DEC26' THEN 'COMMODITY_OPTION'
        WHEN instrument_id = 'GOOGL' THEN 'CASH_EQUITY'
        WHEN instrument_id = 'GOOGL-C-190-20260620' THEN 'EQUITY_OPTION'
        WHEN instrument_id = 'GOOGL-P-160-20260620' THEN 'EQUITY_OPTION'
        WHEN instrument_id = 'GS' THEN 'CASH_EQUITY'
        WHEN instrument_id = 'GS-BOND-2029' THEN 'CORPORATE_BOND'
        WHEN instrument_id = 'HG' THEN 'COMMODITY_FUTURE'
        WHEN instrument_id = 'IDX-SPX' THEN 'CASH_EQUITY'
        WHEN instrument_id = 'IDX-VIX' THEN 'CASH_EQUITY'
        WHEN instrument_id = 'INTC' THEN 'CASH_EQUITY'
        WHEN instrument_id = 'JNJ' THEN 'CASH_EQUITY'
        WHEN instrument_id = 'JP10Y' THEN 'GOVERNMENT_BOND'
        WHEN instrument_id = 'JPM' THEN 'CASH_EQUITY'
        WHEN instrument_id = 'JPM-BOND-2031' THEN 'CORPORATE_BOND'
        WHEN instrument_id = 'KO' THEN 'CASH_EQUITY'
        WHEN instrument_id = 'META' THEN 'CASH_EQUITY'
        WHEN instrument_id = 'MS' THEN 'CASH_EQUITY'
        WHEN instrument_id = 'MSFT' THEN 'CASH_EQUITY'
        WHEN instrument_id = 'MSFT-BOND-2032' THEN 'CORPORATE_BOND'
        WHEN instrument_id = 'MSFT-C-450-20260620' THEN 'EQUITY_OPTION'
        WHEN instrument_id = 'MSFT-P-400-20260620' THEN 'EQUITY_OPTION'
        WHEN instrument_id = 'NDX-SEP26' THEN 'EQUITY_FUTURE'
        WHEN instrument_id = 'NG' THEN 'COMMODITY_FUTURE'
        WHEN instrument_id = 'NVDA' THEN 'CASH_EQUITY'
        WHEN instrument_id = 'NVDA-C-950-20260620' THEN 'EQUITY_OPTION'
        WHEN instrument_id = 'NVDA-P-800-20260620' THEN 'EQUITY_OPTION'
        WHEN instrument_id = 'NZDUSD' THEN 'FX_SPOT'
        WHEN instrument_id = 'ORCL' THEN 'CASH_EQUITY'
        WHEN instrument_id = 'PFE' THEN 'CASH_EQUITY'
        WHEN instrument_id = 'PL' THEN 'COMMODITY_FUTURE'
        WHEN instrument_id = 'RTY-SEP26' THEN 'EQUITY_FUTURE'
        WHEN instrument_id = 'SI' THEN 'COMMODITY_FUTURE'
        WHEN instrument_id = 'SPX-CALL-5000' THEN 'EQUITY_OPTION'
        WHEN instrument_id = 'SPX-CALL-5200' THEN 'EQUITY_OPTION'
        WHEN instrument_id = 'SPX-PUT-4500' THEN 'EQUITY_OPTION'
        WHEN instrument_id = 'SPX-PUT-4800' THEN 'EQUITY_OPTION'
        WHEN instrument_id = 'SPX-SEP26' THEN 'EQUITY_FUTURE'
        WHEN instrument_id = 'TSLA' THEN 'CASH_EQUITY'
        WHEN instrument_id = 'TSLA-C-280-20260620' THEN 'EQUITY_OPTION'
        WHEN instrument_id = 'TSLA-P-220-20260620' THEN 'EQUITY_OPTION'
        WHEN instrument_id = 'UK10Y' THEN 'GOVERNMENT_BOND'
        WHEN instrument_id = 'UNH' THEN 'CASH_EQUITY'
        WHEN instrument_id = 'US10Y' THEN 'GOVERNMENT_BOND'
        WHEN instrument_id = 'US2Y' THEN 'GOVERNMENT_BOND'
        WHEN instrument_id = 'US30Y' THEN 'GOVERNMENT_BOND'
        WHEN instrument_id = 'US5Y' THEN 'GOVERNMENT_BOND'
        WHEN instrument_id = 'USD-SOFR-10Y' THEN 'INTEREST_RATE_SWAP'
        WHEN instrument_id = 'USD-SOFR-5Y' THEN 'INTEREST_RATE_SWAP'
        WHEN instrument_id = 'USDCAD' THEN 'FX_SPOT'
        WHEN instrument_id = 'USDCHF' THEN 'FX_SPOT'
        WHEN instrument_id = 'USDJPY' THEN 'FX_SPOT'
        WHEN instrument_id = 'USDJPY-3M' THEN 'FX_FORWARD'
        WHEN instrument_id = 'USDJPY-C-155-SEP26' THEN 'FX_OPTION'
        WHEN instrument_id = 'VIX-PUT-15' THEN 'EQUITY_OPTION'
        WHEN instrument_id = 'WMT' THEN 'CASH_EQUITY'
        WHEN instrument_id = 'WTI-AUG26' THEN 'COMMODITY_FUTURE'
        WHEN instrument_id = 'XOM' THEN 'CASH_EQUITY'
        WHEN instrument_id = 'ZC' THEN 'COMMODITY_FUTURE'
        ELSE instrument_type
    END
WHERE instrument_type = 'UNKNOWN';

-- Fail loudly if anything is still 'UNKNOWN' after backfill.
DO $$
DECLARE
    pos_unknown INT;
    trd_unknown INT;
BEGIN
    SELECT COUNT(*) INTO pos_unknown FROM positions WHERE instrument_type = 'UNKNOWN';
    SELECT COUNT(*) INTO trd_unknown FROM trade_events WHERE instrument_type = 'UNKNOWN';
    IF pos_unknown > 0 OR trd_unknown > 0 THEN
        RAISE EXCEPTION
            'V27 backfill left UNKNOWN rows: positions=%, trade_events=%. Add the missing instrument mapping before retrying.',
            pos_unknown, trd_unknown;
    END IF;
END
$$;

-- Drop the 'UNKNOWN' DEFAULT introduced in V12 — every insert must now
-- carry a real type from the application layer.
ALTER TABLE positions     ALTER COLUMN instrument_type DROP DEFAULT;
ALTER TABLE trade_events  ALTER COLUMN instrument_type DROP DEFAULT;

-- Structural guard: no future row may store 'UNKNOWN'. The application
-- layer is being tightened in the same change-set so this constraint is
-- never expected to fire — it exists to catch any path that bypasses
-- the domain-model validation.
ALTER TABLE positions     ADD CONSTRAINT positions_instrument_type_not_unknown    CHECK (instrument_type <> 'UNKNOWN');
ALTER TABLE trade_events  ADD CONSTRAINT trade_events_instrument_type_not_unknown CHECK (instrument_type <> 'UNKNOWN');
