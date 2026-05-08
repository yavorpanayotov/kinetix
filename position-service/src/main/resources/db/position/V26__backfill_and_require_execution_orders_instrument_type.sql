-- V26: Backfill the instrument_type column added in V25 from a known
-- instrument-id → type mapping (sourced from the reference-data and
-- position-service seeders), then make the column NOT NULL.
--
-- Principle: every instrument has a type. We do NOT use 'UNKNOWN' as a
-- placeholder. Any row whose instrument_id we cannot resolve causes the
-- migration to fail rather than silently default — that surfaces real
-- data drift instead of hiding it.
--
-- After this migration:
--   • execution_orders.instrument_type is NOT NULL with no default.
--   • Every existing row carries a real type code from InstrumentTypeCode.
--   • New inserts must carry an explicit type — the application layer is
--     being tightened in the same change-set.

UPDATE execution_orders
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
        ELSE NULL
    END
WHERE instrument_type IS NULL;

-- Fail loudly on unresolved rows. If this raises, add the missing
-- (instrument_id → type) row to the CASE above and re-run the migration
-- on a fresh DB. Do not weaken to a default.
DO $$
DECLARE
    unresolved_count INT;
BEGIN
    SELECT COUNT(*) INTO unresolved_count FROM execution_orders WHERE instrument_type IS NULL;
    IF unresolved_count > 0 THEN
        RAISE EXCEPTION
            'V26 backfill left % execution_orders rows with NULL instrument_type. Add the missing instrument mapping before retrying.',
            unresolved_count;
    END IF;
END
$$;

ALTER TABLE execution_orders ALTER COLUMN instrument_type SET NOT NULL;
