import secrets

from passlib.context import CryptContext

pwd_context = CryptContext(schemes=["bcrypt"], deprecated="auto")


def hash_passcode(passcode: str) -> str:
    return pwd_context.hash(passcode)


def verify_passcode(passcode: str, hashed: str) -> bool:
    return pwd_context.verify(passcode, hashed)


def generate_share_token() -> str:
    return secrets.token_urlsafe(24)
