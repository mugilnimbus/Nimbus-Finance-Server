#!/bin/sh
set -eu

if [ "${1#-}" != "$1" ]; then
  set -- postgres "$@"
fi

if [ "$1" = "postgres" ]; then
  : "${POSTGRES_USER:=nimbus}"
  : "${POSTGRES_DB:=$POSTGRES_USER}"
  : "${POSTGRES_PASSWORD_FILE:?POSTGRES_PASSWORD_FILE is required}"
  test -s "$POSTGRES_PASSWORD_FILE" || { echo "PostgreSQL password file is empty" >&2; exit 1; }

  mkdir -p "$PGDATA" /run/postgresql
  chown -R postgres:postgres "$PGDATA" /run/postgresql
  chmod 0700 "$PGDATA"

  if [ ! -s "$PGDATA/PG_VERSION" ]; then
    su-exec postgres initdb \
      --pgdata="$PGDATA" \
      --username="$POSTGRES_USER" \
      --pwfile="$POSTGRES_PASSWORD_FILE" \
      --auth-host=scram-sha-256 \
      --auth-local=trust

    # Compose does not publish PostgreSQL to the host. Permit peers on the
    # isolated internal network and require SCRAM password authentication.
    printf '\nhost all all all scram-sha-256\n' >> "$PGDATA/pg_hba.conf"

    su-exec postgres pg_ctl --pgdata="$PGDATA" \
      --options="-c listen_addresses=''" --wait start
    if [ "$POSTGRES_DB" != "postgres" ]; then
      su-exec postgres createdb --username="$POSTGRES_USER" "$POSTGRES_DB"
    fi
    su-exec postgres pg_ctl --pgdata="$PGDATA" --mode=fast --wait stop
  fi

  # The database has no published host port and is reachable only on the
  # internal Compose network. Listen on that network so the API and backup
  # containers can connect to a genuinely fresh PostgreSQL instance.
  exec su-exec postgres "$@" -c "listen_addresses=*"
fi

exec "$@"
