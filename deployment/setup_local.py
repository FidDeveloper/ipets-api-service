"""Create local secrets once; never overwrite an existing value. Missing values are appended for upgrades."""
from pathlib import Path
import base64
import os
import secrets
import subprocess

PASSWORDS = ["MYSQL_ROOT_PASSWORD", "MYSQL_AUTH_PASSWORD", "MYSQL_CORE_PASSWORD", "MYSQL_DOCUMENT_PASSWORD", "MYSQL_NOTIFICATION_PASSWORD",
             "RABBITMQ_PASSWORD", "MYSQL_AUTH_MIGRATOR_PASSWORD", "MYSQL_CORE_MIGRATOR_PASSWORD", "MYSQL_DOCUMENT_MIGRATOR_PASSWORD",
             "MYSQL_NOTIFICATION_MIGRATOR_PASSWORD", "IPETS_INTERNAL_TOKEN"]


def rsa_private_key() -> str:
    """RS256 signing key for auth-service as single-line base64 PKCS#8 DER (never printed)."""
    pem = subprocess.run(["openssl", "genrsa", "2048"], check=True, capture_output=True).stdout
    # Some OpenSSL/LibreSSL builds emit PKCS#1; convert explicitly to unencrypted PKCS#8 DER for Java.
    der = subprocess.run(["openssl", "pkcs8", "-topk8", "-nocrypt", "-outform", "DER"], input=pem, check=True, capture_output=True).stdout
    return base64.b64encode(der).decode()


def generate(name: str) -> str:
    return rsa_private_key() if name == "IPETS_JWT_PRIVATE_KEY" else secrets.token_hex(24)


NAMES = PASSWORDS + ["IPETS_JWT_PRIVATE_KEY"]


def create_env(path):
    fd = os.open(path, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
    with os.fdopen(fd, "w") as stream:
        for name in NAMES:
            stream.write(f"{name}={generate(name)}\n")


def complete_env(path) -> list[str]:
    """Append only the names that are missing; existing values are left untouched."""
    present = {line.split("=", 1)[0] for line in Path(path).read_text().splitlines() if "=" in line}
    missing = [name for name in NAMES if name not in present]
    if missing:
        with open(path, "a") as stream:
            for name in missing:
                stream.write(f"{name}={generate(name)}\n")
    return missing


if __name__ == "__main__":
    target = Path(__file__).parent / ".env"
    try:
        create_env(target)
        print("Local secrets generated in .env (mode 0600); no secrets printed.")
    except FileExistsError:
        added = complete_env(target)
        print(f"Existing .env retained; added missing: {', '.join(added) if added else 'none'}.")
