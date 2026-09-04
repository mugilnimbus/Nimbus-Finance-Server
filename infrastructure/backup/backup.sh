#!/bin/sh
set -eu

BACKUP_DIR="/backups"
INTERVAL="${BACKUP_INTERVAL_SECONDS:-86400}"
RETENTION_DAYS="${BACKUP_RETENTION_DAYS:-30}"
mkdir -p "$BACKUP_DIR"
export GNUPGHOME="/tmp/gnupg"
mkdir -p -m 0700 "$GNUPGHOME"

run_backup() {
  stamp="$(date -u +%Y%m%dT%H%M%SZ)"
  target="$BACKUP_DIR/nimbus-$stamp.dump.gpg"
  encrypted="$target.tmp"
  dump="$(mktemp /tmp/nimbus-backup.dump.XXXXXX)"
  verified_dump="$(mktemp /tmp/nimbus-verify.dump.XXXXXX)"
  cleanup() {
    rm -f "$dump" "$verified_dump" "$encrypted"
  }
  trap cleanup EXIT INT TERM

  export PGPASSWORD="$(cat "$POSTGRES_PASSWORD_FILE")"
  schema_ready="$(psql --host="$PGHOST" --username="$PGUSER" --dbname="$PGDATABASE" \
      --tuples-only --no-align --command="SELECT to_regclass('public.schema_migrations') IS NOT NULL AND to_regclass('public.users') IS NOT NULL")"
  if [ "$schema_ready" != "t" ]; then
    echo "Database backup failed: application migrations are not ready" >&2
    return 1
  fi
  if ! pg_dump --host="$PGHOST" --username="$PGUSER" --dbname="$PGDATABASE" \
      --format=custom --compress=6 --file="$dump"; then
    echo "Database backup failed: pg_dump did not complete" >&2
    return 1
  fi
  if ! pg_restore --list "$dump" >/dev/null; then
    echo "Database backup failed: PostgreSQL rejected the generated dump" >&2
    return 1
  fi

  if ! gpg --batch --yes --pinentry-mode loopback --passphrase-file "$BACKUP_PASSPHRASE_FILE" \
      --symmetric --cipher-algo AES256 --force-aead --aead-algo OCB --output "$encrypted" "$dump"; then
    echo "Database backup failed: authenticated encryption did not complete" >&2
    return 1
  fi
  if ! gpg --batch --yes --pinentry-mode loopback --passphrase-file "$BACKUP_PASSPHRASE_FILE" \
      --decrypt --output "$verified_dump" "$encrypted" || ! pg_restore --list "$verified_dump" >/dev/null; then
    echo "Database backup failed: encrypted backup could not be verified" >&2
    return 1
  fi
  unset PGPASSWORD

  mv "$encrypted" "$target"
  sha256sum "$target" > "$target.sha256"
  printf 'format=openpgp-aes256-ocb-aead\ncreated_utc=%s\nsize_bytes=%s\n' \
    "$stamp" "$(wc -c < "$target" | tr -d ' ')" > "$target.verified"
  find "$BACKUP_DIR" -type f \( -name 'nimbus-*.dump.gpg' -o -name 'nimbus-*.dump.gpg.sha256' -o -name 'nimbus-*.dump.gpg.verified' -o -name 'nimbus-*.dump.gz.enc' \) \
    -mtime "+$RETENTION_DAYS" -delete
  echo "Verified encrypted database backup completed: $(basename "$target")"
  date -u +%s > "$BACKUP_DIR/.last-success-epoch"
  rm -f "$BACKUP_DIR/.last-error"
  trap - EXIT INT TERM
  cleanup
}

if [ "${1:-}" = "--once" ]; then
  run_backup
  exit $?
fi

while true; do
  run_backup || {
    date -u +%FT%TZ > "$BACKUP_DIR/.last-error"
    echo "Automatic database backup failed; the previous verified backup remains active" >&2
  }
  sleep "$INTERVAL"
done
