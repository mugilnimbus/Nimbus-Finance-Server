ARG BACKUP_BASE_IMAGE=alpine:3.23.3@sha256:25109184c71bdad752c8312a8623239686a9a2071e8825f20acb8f2198c3f659
FROM ${BACKUP_BASE_IMAGE}
RUN apk upgrade --no-cache && apk add --no-cache gnupg openssl postgresql17-client tzdata
COPY infrastructure/backup/backup.sh /usr/local/bin/nimbus-backup
RUN chmod 0555 /usr/local/bin/nimbus-backup
ENTRYPOINT ["/usr/local/bin/nimbus-backup"]
