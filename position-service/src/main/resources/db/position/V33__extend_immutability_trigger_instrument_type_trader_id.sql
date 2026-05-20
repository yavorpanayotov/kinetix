-- trading.allium:252-256 lists instrument_type and trader_id among the
-- immutable trade identity fields, but V14's prevent_trade_event_core_mutation
-- only guards 11 columns. V9 added instrument_type and V30 added trader_id
-- without extending the trigger, so manual SQL could silently mutate either.
-- Recreate the function to also reject changes to instrument_type and trader_id.
-- IS DISTINCT FROM is NULL-safe; both new columns are nullable.
CREATE OR REPLACE FUNCTION prevent_trade_event_core_mutation() RETURNS trigger AS $$
BEGIN
    IF (OLD.trade_id     IS DISTINCT FROM NEW.trade_id     OR
        OLD.book_id      IS DISTINCT FROM NEW.book_id      OR
        OLD.instrument_id IS DISTINCT FROM NEW.instrument_id OR
        OLD.instrument_type IS DISTINCT FROM NEW.instrument_type OR
        OLD.asset_class  IS DISTINCT FROM NEW.asset_class  OR
        OLD.side         IS DISTINCT FROM NEW.side         OR
        OLD.quantity     IS DISTINCT FROM NEW.quantity     OR
        OLD.price_amount IS DISTINCT FROM NEW.price_amount OR
        OLD.price_currency IS DISTINCT FROM NEW.price_currency OR
        OLD.traded_at    IS DISTINCT FROM NEW.traded_at    OR
        OLD.created_at   IS DISTINCT FROM NEW.created_at   OR
        OLD.event_type   IS DISTINCT FROM NEW.event_type   OR
        OLD.trader_id    IS DISTINCT FROM NEW.trader_id) THEN
        RAISE EXCEPTION 'Core trade event fields are immutable';
    END IF;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;
