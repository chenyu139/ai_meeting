from __future__ import annotations

import os
from pathlib import Path

import oss2
from fastapi import UploadFile

from app.config import get_settings


class StorageService:
    def __init__(self) -> None:
        self.settings = get_settings()
        self.local_root = Path(self.settings.local_storage_root)
        self.local_root.mkdir(parents=True, exist_ok=True)

        self.bucket = None
        if self.settings.storage_mode == "oss":
            auth = oss2.Auth(self.settings.oss_access_key_id, self.settings.oss_access_key_secret)
            self.bucket = oss2.Bucket(auth, self.settings.oss_endpoint, self.settings.oss_bucket)

    def upload_audio_chunk(self, meeting_id: str, chunk_index: int, upload: UploadFile) -> tuple[str, int]:
        ext = ".m4a"
        object_key = f"meetings/{meeting_id}/chunks/{chunk_index}{ext}"
        data = upload.file.read()
        size = len(data)

        if self.settings.storage_mode == "oss" and self.bucket is not None:
            self.bucket.put_object(object_key, data)
            return object_key, size

        local_path = self.local_root / object_key
        local_path.parent.mkdir(parents=True, exist_ok=True)
        local_path.write_bytes(data)
        return object_key, size

    def get_object_url(self, object_key: str, expires_seconds: int = 3600) -> str:
        if self.settings.storage_mode == "oss" and self.bucket is not None:
            return self.bucket.sign_url("GET", object_key, expires_seconds)

        base = self.settings.public_base_url.rstrip("/")
        return f"{base}/api/v1/files/{object_key}"

    def local_file_path(self, object_key: str) -> Path:
        return self.local_root / object_key

    def list_meeting_chunk_keys(self, meeting_id: str) -> list[str]:
        prefix = f"meetings/{meeting_id}/chunks/"
        if self.settings.storage_mode == "oss" and self.bucket is not None:
            keys = [obj.key for obj in oss2.ObjectIterator(self.bucket, prefix=prefix)]
            return sorted(keys)

        base = self.local_root / prefix
        if not base.exists():
            return []
        keys = [f"{prefix}{name}" for name in os.listdir(base)]
        return sorted(keys)
