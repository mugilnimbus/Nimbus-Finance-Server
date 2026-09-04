ARG POSTGRES_BASE_IMAGE=alpine:3.23.3@sha256:25109184c71bdad752c8312a8623239686a9a2071e8825f20acb8f2198c3f659
FROM ${POSTGRES_BASE_IMAGE}

RUN apk upgrade --no-cache \
    && apk add --no-cache postgresql17 postgresql17-contrib su-exec tzdata

ENV PGDATA=/var/lib/postgresql/data
COPY infrastructure/postgres/entrypoint.sh /usr/local/bin/nimbus-postgres-entrypoint
RUN chmod 0555 /usr/local/bin/nimbus-postgres-entrypoint \
    && mkdir -p /var/lib/postgresql/data /run/postgresql \
    && chown -R postgres:postgres /var/lib/postgresql/data /run/postgresql

VOLUME ["/var/lib/postgresql/data"]
ENTRYPOINT ["/usr/local/bin/nimbus-postgres-entrypoint"]
CMD ["postgres"]
