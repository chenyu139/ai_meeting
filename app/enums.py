from enum import Enum


class MeetingStatus(str, Enum):
    RECORDING = "RECORDING"
    PAUSED = "PAUSED"
    PROCESSING = "PROCESSING"
    COMPLETED = "COMPLETED"
    FAILED = "FAILED"
    DELETED = "DELETED"


class ParseJobStatus(str, Enum):
    PENDING = "PENDING"
    RUNNING = "RUNNING"
    SUCCESS = "SUCCESS"
    FAILED = "FAILED"


class ParseJobStage(str, Enum):
    RESULT_PARSE = "RESULT_PARSE"
    KNOWLEDGE_LINK = "KNOWLEDGE_LINK"


class ShareStatus(str, Enum):
    ACTIVE = "ACTIVE"
    REVOKED = "REVOKED"
