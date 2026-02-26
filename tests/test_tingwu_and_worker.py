import os
import time
import uuid

import pytest

from app.services.tingwu_client import TingwuClient


REQUIRED_ENV = [
    "TINGWU_MODE",
    "TINGWU_ACCESS_KEY_ID",
    "TINGWU_ACCESS_KEY_SECRET",
]


def _ensure_real_tingwu_env() -> None:
    missing = [key for key in REQUIRED_ENV if not os.getenv(key)]
    if missing:
        pytest.skip(f"Missing Tingwu env vars for real integration test: {', '.join(missing)}")

    os.environ.setdefault("TINGWU_APP_KEY", "NUZKS8AveuPWMwn6")

    mode = os.getenv("TINGWU_MODE", "").lower()
    if mode not in {"sdk", "real"}:
        pytest.skip("Set TINGWU_MODE=sdk (or real) for real-key test")


@pytest.mark.integration
def test_tingwu_real_create_stop_and_query() -> None:
    _ensure_real_tingwu_env()

    client = TingwuClient()
    task_key = f"it-{uuid.uuid4()}"
    created = client.create_realtime_task(task_key=task_key, title="integration", webhook_url="")

    assert created["task_id"]
    assert created.get("meeting_join_url")

    stop_result = client.stop_task(created["task_id"])
    assert stop_result.get("ok") is True

    task_status = None
    for _ in range(20):
        info = client.get_task_info(created["task_id"])
        task_status = client.extract_task_status(info)
        if task_status in {"ONGOING", "COMPLETED", "FAILED"}:
            break
        time.sleep(3)

    assert task_status in {"ONGOING", "COMPLETED", "FAILED"}
