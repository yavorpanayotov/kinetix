-- V34: Add base_currency to book_hierarchy.
-- Each book reports P&L in a base currency. risk-orchestrator's
-- IntradayPnlService resolves this per book instead of assuming "USD"
-- for every book (see intraday-pnl.allium:168 book_base_currency).
-- Plain ALTER TABLE — transaction-safe for Flyway.

ALTER TABLE book_hierarchy
    ADD COLUMN base_currency VARCHAR(3) NOT NULL DEFAULT 'USD';
