from functools import lru_cache

from pydantic import Field
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", env_file_encoding="utf-8", extra="ignore")

    app_env: str = Field(default="dev", alias="APP_ENV")
    app_name: str = Field(default="ai-meeting-backend", alias="APP_NAME")
    api_prefix: str = Field(default="/api/v1", alias="API_PREFIX")
    public_base_url: str = Field(default="http://localhost:8000", alias="PUBLIC_BASE_URL")

    database_url: str = Field(default="sqlite:///./ai_meeting.db", alias="DATABASE_URL")
    redis_url: str = Field(default="redis://localhost:6379/0", alias="REDIS_URL")

    storage_mode: str = Field(default="local", alias="STORAGE_MODE")
    local_storage_root: str = Field(default="./storage", alias="LOCAL_STORAGE_ROOT")
    oss_endpoint: str = Field(default="", alias="OSS_ENDPOINT")
    oss_bucket: str = Field(default="", alias="OSS_BUCKET")
    oss_access_key_id: str = Field(default="", alias="OSS_ACCESS_KEY_ID")
    oss_access_key_secret: str = Field(default="", alias="OSS_ACCESS_KEY_SECRET")
    oss_sts_token: str = Field(default="", alias="OSS_STS_TOKEN")
    oss_public_base_url: str = Field(default="", alias="OSS_PUBLIC_BASE_URL")

    tingwu_mode: str = Field(default="sdk", alias="TINGWU_MODE")
    tingwu_region: str = Field(default="cn-beijing", alias="TINGWU_REGION")
    tingwu_endpoint: str = Field(default="", alias="TINGWU_ENDPOINT")
    tingwu_access_key_id: str = Field(default="", alias="TINGWU_ACCESS_KEY_ID")
    tingwu_access_key_secret: str = Field(default="", alias="TINGWU_ACCESS_KEY_SECRET")
    tingwu_app_key: str = Field(default="", alias="TINGWU_APP_KEY")
    tingwu_webhook_signing_key: str = Field(default="", alias="TINGWU_WEBHOOK_SIGNING_KEY")
    tingwu_callback_base_url: str = Field(default="http://localhost:8000", alias="TINGWU_CALLBACK_BASE_URL")

    knowledge_search_url: str = Field(default="", alias="KNOWLEDGE_SEARCH_URL")
    knowledge_search_timeout_sec: int = Field(default=5, alias="KNOWLEDGE_SEARCH_TIMEOUT_SEC")

    worker_poll_interval_sec: int = Field(default=10, alias="WORKER_POLL_INTERVAL_SEC")
    worker_processing_scan_interval_sec: int = Field(default=30, alias="WORKER_PROCESSING_SCAN_INTERVAL_SEC")
    worker_task_timeout_sec: int = Field(default=120, alias="WORKER_TASK_TIMEOUT_SEC")


@lru_cache
def get_settings() -> Settings:
    return Settings()
