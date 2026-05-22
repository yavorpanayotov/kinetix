from prometheus_client import Counter, Gauge, Histogram

risk_var_calculation_duration_seconds = Histogram(
    "risk_var_calculation_duration_seconds",
    "Duration of VaR calculation in seconds",
)

risk_var_calculation_total = Counter(
    "risk_var_calculation_total",
    "Total number of VaR calculations",
    ["calculation_type", "confidence_level"],
)

risk_var_value = Gauge(
    "risk_var_value",
    "Current VaR value for a portfolio",
    ["book_id", "calculation_type", "confidence_level"],
)

risk_var_expected_shortfall = Gauge(
    "risk_var_expected_shortfall",
    "Current Expected Shortfall (CVaR) value for a portfolio",
    ["book_id", "calculation_type", "confidence_level"],
)

risk_var_component_contribution = Gauge(
    "risk_var_component_contribution",
    "VaR contribution by asset class for a portfolio",
    ["book_id", "asset_class"],
)

ml_prediction_duration_seconds = Histogram(
    "ml_prediction_duration_seconds",
    "Duration of ML model prediction in seconds",
    ["model_type"],
)

ml_prediction_total = Counter(
    "ml_prediction_total",
    "Total number of ML model predictions",
    ["model_type"],
)

ml_anomaly_detected_total = Counter(
    "ml_anomaly_detected_total",
    "Total number of anomalies detected",
    ["metric_name"],
)

stress_test_duration_seconds = Histogram(
    "stress_test_duration_seconds",
    "Duration of stress test calculation in seconds",
    ["scenario_name"],
)

stress_test_total = Counter(
    "stress_test_total",
    "Total number of stress tests run",
    ["scenario_name"],
)

stress_test_loss = Gauge(
    "stress_test_loss",
    "P&L impact of a stress scenario for a book (negative is a loss)",
    ["scenario_name", "book_id"],
)

greeks_delta = Gauge(
    "greeks_delta",
    "Current net delta by book and asset class",
    ["book_id", "asset_class"],
)

greeks_gamma = Gauge(
    "greeks_gamma",
    "Current net gamma by book and asset class",
    ["book_id", "asset_class"],
)

greeks_vega = Gauge(
    "greeks_vega",
    "Current net vega by book and asset class",
    ["book_id", "asset_class"],
)

greeks_theta = Gauge(
    "greeks_theta",
    "Current net theta by book",
    ["book_id"],
)

greeks_rho = Gauge(
    "greeks_rho",
    "Current net rho by book",
    ["book_id"],
)

greeks_calculation_duration_seconds = Histogram(
    "greeks_calculation_duration_seconds",
    "Duration of Greeks calculation in seconds",
)

greeks_calculation_total = Counter(
    "greeks_calculation_total",
    "Total number of Greeks calculations",
)

frtb_calculation_duration_seconds = Histogram(
    "frtb_calculation_duration_seconds",
    "Duration of FRTB calculation in seconds",
)

frtb_calculation_total = Counter(
    "frtb_calculation_total",
    "Total number of FRTB calculations",
)

pnl_attribution_total_pnl = Gauge(
    "pnl_attribution_total_pnl",
    "Total P&L for a book from factor attribution",
    ["book_id"],
)

pnl_attribution_unexplained_pnl = Gauge(
    "pnl_attribution_unexplained_pnl",
    "Unexplained (idiosyncratic) P&L for a book from factor attribution",
    ["book_id"],
)

pnl_attribution_greek_pnl = Gauge(
    "pnl_attribution_greek_pnl",
    "P&L contribution of a single Greek component for a book "
    "(delta / gamma / vega / theta / rho) — the Taylor-expansion P&L decomposition",
    ["book_id", "greek"],
)

pnl_attribution_dollar_delta = Gauge(
    "pnl_attribution_dollar_delta",
    "Dollar-delta (delta x spot) — cash exposure of a book to a 100% spot move",
    ["book_id"],
)

pnl_attribution_dollar_gamma = Gauge(
    "pnl_attribution_dollar_gamma",
    "Dollar-gamma (gamma x spot^2) — cash convexity of a book",
    ["book_id"],
)

cross_book_diversification_benefit = Gauge(
    "cross_book_diversification_benefit",
    "VaR diversification benefit across books (total standalone VaR minus portfolio VaR)",
    ["portfolio_group_id"],
)

regulatory_report_total = Counter(
    "regulatory_report_total",
    "Total number of regulatory reports generated",
    ["format"],
)
