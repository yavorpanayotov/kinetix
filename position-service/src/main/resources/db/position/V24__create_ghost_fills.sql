-- ADR-0035 phase 2: ghost_fills persistence.
--
-- Records every FIX 35=8 fill that arrives against an already-terminal order
-- (EXPIRED / CANCELLED / REJECTED). The Position is intentionally NOT
-- auto-updated for these — manual ops resolution decides whether the fill
-- is real (and the venue is authoritative) or whether the venue made a
-- mistake. The accompanying RiskBreak event publishes to risk.breaks so
-- ops + the trader-facing RiskAlertBanner surface the break.
--
-- One row per ghost fill (not per order); the (order_id, fix_exec_id)
-- composite index defends against duplicate-event ingestion when FIX
-- replays the message on session reconnect.
CREATE TABLE IF NOT EXISTS ghost_fills (
    id              BIGSERIAL PRIMARY KEY,
    order_id        VARCHAR(255) NOT NULL,
    prior_status    VARCHAR(30)  NOT NULL,
    venue           VARCHAR(32)  NOT NULL,
    fix_exec_id     VARCHAR(255) NOT NULL,
    fill_qty        NUMERIC(28, 12) NOT NULL,
    fill_price      NUMERIC(28, 12) NOT NULL,
    cumulative_qty  NUMERIC(28, 12) NOT NULL,
    detected_at     TIMESTAMPTZ  NOT NULL,
    raw_event       TEXT         NOT NULL,
    CONSTRAINT chk_ghost_fills_prior_status CHECK (prior_status IN (
        'EXPIRED', 'CANCELLED', 'REJECTED'
    )),
    CONSTRAINT uq_ghost_fills_order_exec UNIQUE (order_id, fix_exec_id)
);

CREATE INDEX IF NOT EXISTS idx_ghost_fills_order_id
    ON ghost_fills (order_id, detected_at DESC);

CREATE INDEX IF NOT EXISTS idx_ghost_fills_venue_detected_at
    ON ghost_fills (venue, detected_at DESC);
