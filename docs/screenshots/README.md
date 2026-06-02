# UI Screenshots

Captured from the running Kinetix UI with deterministic mocked routes (the
same fixtures the Playwright e2e suite uses), so the numbers are stable and
no live backend is required. Regenerate with:

```bash
cd ui && npx playwright test e2e/screenshots/capture.spec.ts --project=chromium
```

Capture spec: [`ui/e2e/screenshots/capture.spec.ts`](../../ui/e2e/screenshots/capture.spec.ts).
Last captured against commit `9da77e7c`.

| Screenshot | Description |
| --- | --- |
| ![Counterparty Risk tab](counterparty-risk-tab.png) | The Counterparty Risk tab — per-counterparty net exposure and peak PFE. Subject of the [flagship case study](../case-studies/counterparty-risk.md). |
| ![Copilot narrative](copilot-narrative.png) | The AI copilot answering "Why did my VaR move?" — a citation-backed narrative with the `get_book_var` provenance (source, value, freshness) and the demo-mode badge. The citation enforcement here is what the [eval harness](../governance/copilot-eval-scorecard.md) measures. |
