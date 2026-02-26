from __future__ import annotations

from pathlib import Path

from fastapi import FastAPI, HTTPException
from fastapi.responses import FileResponse

from app.api import admin, callbacks, meetings, shares
from app.config import get_settings
from app.database import Base, engine
from app.services.storage_service import StorageService

settings = get_settings()
app = FastAPI(title=settings.app_name)


@app.on_event("startup")
def on_startup() -> None:
    Base.metadata.create_all(bind=engine)


app.include_router(meetings.router, prefix=settings.api_prefix)
app.include_router(shares.router, prefix=settings.api_prefix)
app.include_router(callbacks.router, prefix=settings.api_prefix)
app.include_router(admin.router, prefix=settings.api_prefix)


@app.get("/healthz")
def healthz() -> dict[str, str]:
    return {"status": "ok"}


@app.get(f"{settings.api_prefix}/files/{{file_path:path}}", include_in_schema=False)
def serve_local_file(file_path: str) -> FileResponse:
    storage = StorageService()
    path = storage.local_file_path(file_path)
    if not path.exists() or not path.is_file():
        raise HTTPException(status_code=404, detail="file not found")
    return FileResponse(path)
