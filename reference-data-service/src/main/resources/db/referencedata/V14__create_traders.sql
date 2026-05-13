-- Adds a `traders` table so traders can be modelled as proper
-- reference-data entities rather than a string placeholder.
--
-- Each trader belongs to a desk (FK to desks). Used at trade-booking
-- time for traderId validation, and as the source of named drill-down
-- in per-trader P&L / limit views.

CREATE TABLE traders (
    id VARCHAR(64) PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    desk_id VARCHAR(255) NOT NULL REFERENCES desks(id),
    email VARCHAR(255),
    notional_limit_usd NUMERIC(38, 4),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_traders_notional_limit_non_negative
        CHECK (notional_limit_usd IS NULL OR notional_limit_usd >= 0)
);

CREATE INDEX idx_traders_desk ON traders(desk_id);
