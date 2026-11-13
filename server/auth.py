import hashlib
import secrets
import time
from functools import lru_cache

from email_validator import validate_email
from pwdlib import PasswordHash

PASSWORDS = PasswordHash.recommended()
SESSION_SECONDS = 12 * 60 * 60


@lru_cache(maxsize=1)
def dummy_password_hash():
    return PASSWORDS.hash(secrets.token_urlsafe(32))


def normalize_email(email):
    return validate_email(email.strip(), check_deliverability=False).normalized.casefold()


def create_user(db, email, password, display_name, role):
    if not 12 <= len(password) <= 128:
        raise ValueError("Пароль должен содержать от 12 до 128 символов.")
    if not display_name.strip():
        raise ValueError("Укажите имя пользователя.")
    db.execute(
        "INSERT INTO users (email, password_hash, display_name, role) VALUES (?, ?, ?, ?)",
        (normalize_email(email), PASSWORDS.hash(password), display_name.strip(), role),
    )


def token_hash(token):
    return hashlib.sha256(token.encode("utf-8")).hexdigest()


def authenticate(db, email, password):
    user = db.execute("SELECT * FROM users WHERE email = ?", (email,)).fetchone()
    password_hash = user["password_hash"] if user else dummy_password_hash()
    if not PASSWORDS.verify(password, password_hash) or not user or not user["active"]:
        return None

    token = secrets.token_urlsafe(32)
    now = int(time.time())
    db.execute("DELETE FROM sessions WHERE expires_at <= ?", (now,))
    db.execute(
        "INSERT INTO sessions (token_hash, user_id, expires_at) VALUES (?, ?, ?)",
        (token_hash(token), user["id"], now + SESSION_SECONDS),
    )
    return token


def session_user(db, token):
    user = db.execute(
        """
        SELECT u.id, u.email, u.display_name, u.role FROM sessions s
        JOIN users u ON u.id = s.user_id
        WHERE s.token_hash = ? AND s.expires_at > ? AND u.active = 1
        """,
        (token_hash(token), int(time.time())),
    ).fetchone()
    return dict(user) if user else None
