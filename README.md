# AI Meeting Backend

Backend service for AI meeting workflow using Alibaba Tingwu realtime tasks.

## Quick Start

1. Create a Python 3.12 virtual environment and install dependencies.
   ```bash
   python3.12 -m venv .venv312
   ./.venv312/bin/pip install -r requirements.txt
   ```
2. Configure environment variables (copy `.env.example` if needed).
3. Start API service:
   ```bash
   ./.venv312/bin/uvicorn app.main:app --reload
   ```
4. Start worker service:
   ```bash
   ./.venv312/bin/python scripts/run_worker.py
   ```

## Architecture docs

- `docs/architecture.md`
- `docs/api-data-model.md`
- `docs/testing.md`
- `docs/deployment.md`

## Real Tingwu integration test

```bash
TINGWU_MODE=sdk \\
TINGWU_ACCESS_KEY_ID=xxx \\
TINGWU_ACCESS_KEY_SECRET=xxx \\
TINGWU_APP_KEY=NUZKS8AveuPWMwn6 \\
./.venv312/bin/pytest -m integration -q
```
