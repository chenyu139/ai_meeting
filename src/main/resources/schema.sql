CREATE TABLE IF NOT EXISTS meeting (
    id VARCHAR(36) PRIMARY KEY,
    owner_id VARCHAR(64) NOT NULL,
    title VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    task_id VARCHAR(128) UNIQUE,
    meeting_join_url TEXT,
    processing_progress INTEGER NOT NULL DEFAULT 0,
    started_at TIMESTAMP NOT NULL,
    ended_at TIMESTAMP,
    duration_sec INTEGER NOT NULL DEFAULT 0,
    deleted_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_meeting_owner ON meeting(owner_id);
CREATE INDEX IF NOT EXISTS idx_meeting_status ON meeting(status);
CREATE INDEX IF NOT EXISTS idx_meeting_started_at ON meeting(started_at);

CREATE TABLE IF NOT EXISTS meeting_transcript_final (
    id VARCHAR(36) PRIMARY KEY,
    meeting_id VARCHAR(36) NOT NULL,
    segment_order INTEGER NOT NULL,
    speaker VARCHAR(64),
    start_ms INTEGER,
    end_ms INTEGER,
    confidence INTEGER,
    text TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_transcript_meeting_segment UNIQUE (meeting_id, segment_order)
);
CREATE INDEX IF NOT EXISTS idx_transcript_meeting_id ON meeting_transcript_final(meeting_id);

CREATE TABLE IF NOT EXISTS meeting_summary (
    id VARCHAR(36) PRIMARY KEY,
    meeting_id VARCHAR(36) NOT NULL,
    overview TEXT NOT NULL,
    chapters TEXT NOT NULL,
    decisions TEXT NOT NULL,
    highlights TEXT NOT NULL,
    todos TEXT NOT NULL,
    raw_result_refs TEXT NOT NULL,
    knowledge_links TEXT NOT NULL,
    version INTEGER NOT NULL DEFAULT 1,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_summary_meeting UNIQUE (meeting_id)
);
CREATE INDEX IF NOT EXISTS idx_summary_meeting_id ON meeting_summary(meeting_id);

CREATE TABLE IF NOT EXISTS meeting_audio_asset (
    id VARCHAR(36) PRIMARY KEY,
    meeting_id VARCHAR(36) NOT NULL,
    chunk_index INTEGER NOT NULL,
    object_key VARCHAR(512) NOT NULL,
    content_type VARCHAR(128) NOT NULL,
    size_bytes INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_audio_meeting_id ON meeting_audio_asset(meeting_id);

CREATE TABLE IF NOT EXISTS share_link (
    id VARCHAR(36) PRIMARY KEY,
    meeting_id VARCHAR(36) NOT NULL,
    created_by VARCHAR(64) NOT NULL,
    token VARCHAR(128) NOT NULL UNIQUE,
    passcode_hash VARCHAR(255),
    expire_at TIMESTAMP,
    status VARCHAR(32) NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_share_meeting_id ON share_link(meeting_id);
CREATE INDEX IF NOT EXISTS idx_share_created_by ON share_link(created_by);

CREATE TABLE IF NOT EXISTS share_access_log (
    id VARCHAR(36) PRIMARY KEY,
    share_link_id VARCHAR(36) NOT NULL,
    meeting_id VARCHAR(36) NOT NULL,
    viewer_id VARCHAR(64),
    ip VARCHAR(64),
    user_agent VARCHAR(255),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_access_meeting_id ON share_access_log(meeting_id);
CREATE INDEX IF NOT EXISTS idx_access_viewer_id ON share_access_log(viewer_id);

CREATE TABLE IF NOT EXISTS parse_job (
    id VARCHAR(36) PRIMARY KEY,
    meeting_id VARCHAR(36) NOT NULL,
    task_id VARCHAR(128) NOT NULL,
    stage VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    retry_count INTEGER NOT NULL DEFAULT 0,
    max_retries INTEGER NOT NULL DEFAULT 3,
    next_retry_at TIMESTAMP NOT NULL,
    locked_by VARCHAR(64),
    locked_at TIMESTAMP,
    last_error TEXT,
    payload TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uq_parse_job_meeting_stage UNIQUE (meeting_id, stage)
);
CREATE INDEX IF NOT EXISTS idx_parse_job_status_next_retry ON parse_job(status, next_retry_at);
CREATE INDEX IF NOT EXISTS idx_parse_job_meeting_id ON parse_job(meeting_id);

CREATE TABLE IF NOT EXISTS task_event_log (
    id VARCHAR(36) PRIMARY KEY,
    meeting_id VARCHAR(36),
    source VARCHAR(32) NOT NULL,
    event_type VARCHAR(128) NOT NULL,
    payload TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_event_meeting_id ON task_event_log(meeting_id);

CREATE TABLE IF NOT EXISTS idempotency_record (
    idempotency_key VARCHAR(128) PRIMARY KEY,
    endpoint VARCHAR(128) NOT NULL,
    user_id VARCHAR(64) NOT NULL,
    request_hash VARCHAR(128) NOT NULL,
    response_body TEXT NOT NULL,
    status_code INTEGER NOT NULL,
    created_at TIMESTAMP NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_idempotency_user_id ON idempotency_record(user_id);
