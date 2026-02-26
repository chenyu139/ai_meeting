from __future__ import annotations

import time

from app.config import get_settings
from app.database import Base, engine
from app.services.worker_service import WorkerService


def main() -> None:
    settings = get_settings()
    Base.metadata.create_all(bind=engine)
    worker = WorkerService()

    while True:
        worker.run_once()
        time.sleep(settings.worker_poll_interval_sec)


if __name__ == "__main__":
    main()
