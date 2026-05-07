-- Per-service databases for Kinetix
-- Uses SELECT ... \gexec so the script is idempotent (safe to re-run).
SELECT 'CREATE DATABASE kinetix_gateway' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'kinetix_gateway')\gexec
SELECT 'CREATE DATABASE kinetix_position' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'kinetix_position')\gexec
SELECT 'CREATE DATABASE kinetix_price' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'kinetix_price')\gexec
SELECT 'CREATE DATABASE kinetix_risk' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'kinetix_risk')\gexec
SELECT 'CREATE DATABASE kinetix_audit' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'kinetix_audit')\gexec
SELECT 'CREATE DATABASE kinetix_notification' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'kinetix_notification')\gexec
SELECT 'CREATE DATABASE kinetix_regulatory' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'kinetix_regulatory')\gexec
SELECT 'CREATE DATABASE kinetix_rates' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'kinetix_rates')\gexec
SELECT 'CREATE DATABASE kinetix_reference_data' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'kinetix_reference_data')\gexec
SELECT 'CREATE DATABASE kinetix_volatility' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'kinetix_volatility')\gexec
SELECT 'CREATE DATABASE kinetix_correlation' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'kinetix_correlation')\gexec
SELECT 'CREATE DATABASE kinetix_fix_gateway' WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'kinetix_fix_gateway')\gexec

-- Enable TimescaleDB on all databases that use hypertables
\c kinetix_price
CREATE EXTENSION IF NOT EXISTS timescaledb;
\c kinetix_risk
CREATE EXTENSION IF NOT EXISTS timescaledb;
\c kinetix_audit
CREATE EXTENSION IF NOT EXISTS timescaledb;
\c kinetix_notification
CREATE EXTENSION IF NOT EXISTS timescaledb;
\c kinetix_regulatory
CREATE EXTENSION IF NOT EXISTS timescaledb;
