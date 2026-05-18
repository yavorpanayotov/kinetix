.PHONY: install test test-unit test-integration run clean

install:
	uv sync

test:
	uv run pytest

test-unit:
	uv run pytest -m unit

test-integration:
	uv run pytest -m integration

run:
	uv run uvicorn kinetix_insights.app:app --host 0.0.0.0 --port 8000

clean:
	rm -rf .pytest_cache reports __pycache__
	find . -type d -name __pycache__ -exec rm -rf {} +
