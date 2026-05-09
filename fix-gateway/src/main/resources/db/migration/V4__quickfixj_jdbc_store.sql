-- QuickFIX/J JdbcStore tables (sessions + messages).
--
-- These mirror the standard QuickFIX/J PostgreSQL DDL distributed at
-- https://github.com/quickfix-j/quickfixj/blob/master/quickfixj-core/src/main/resources/sql/postgresql/sessions_table.sql
-- and corresponding messages_table.sql, layered into our Flyway-managed schema
-- so the durability anchor (sequence numbers + unacked messages) survives a
-- fix-gateway restart without manual DBA intervention.
--
-- The composite primary keys match the SessionID identity QuickFIX/J derives
-- from BeginString + SenderCompID + SenderSubID + SenderLocationID +
-- TargetCompID + TargetSubID + TargetLocationID + SessionQualifier.

CREATE TABLE IF NOT EXISTS sessions (
    beginstring       CHAR(8)      NOT NULL,
    sendercompid      VARCHAR(64)  NOT NULL,
    sendersubid       VARCHAR(64)  NOT NULL,
    senderlocid       VARCHAR(64)  NOT NULL,
    targetcompid      VARCHAR(64)  NOT NULL,
    targetsubid       VARCHAR(64)  NOT NULL,
    targetlocid       VARCHAR(64)  NOT NULL,
    session_qualifier VARCHAR(64)  NOT NULL,
    creation_time     TIMESTAMP    NOT NULL,
    incoming_seqnum   INTEGER      NOT NULL,
    outgoing_seqnum   INTEGER      NOT NULL,
    PRIMARY KEY (
        beginstring, sendercompid, sendersubid, senderlocid,
        targetcompid, targetsubid, targetlocid, session_qualifier
    )
);

CREATE TABLE IF NOT EXISTS messages (
    beginstring       CHAR(8)      NOT NULL,
    sendercompid      VARCHAR(64)  NOT NULL,
    sendersubid       VARCHAR(64)  NOT NULL,
    senderlocid       VARCHAR(64)  NOT NULL,
    targetcompid      VARCHAR(64)  NOT NULL,
    targetsubid       VARCHAR(64)  NOT NULL,
    targetlocid       VARCHAR(64)  NOT NULL,
    session_qualifier VARCHAR(64)  NOT NULL,
    msgseqnum         INTEGER      NOT NULL,
    message           TEXT         NOT NULL,
    PRIMARY KEY (
        beginstring, sendercompid, sendersubid, senderlocid,
        targetcompid, targetsubid, targetlocid, session_qualifier, msgseqnum
    )
);
