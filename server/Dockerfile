ARG SERVER_BASE_IMAGE=eclipse-temurin:17-jre-jammy@sha256:e17d77fb030dd4b642dc078d048a5fb9efcb3676ee20305d905949105a6ccd5a
FROM ${SERVER_BASE_IMAGE}

RUN apt-get update && apt-get install -y --no-install-recommends wget ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && groupadd --gid 10001 nimbus \
    && useradd --uid 10001 --gid nimbus --home-dir /app --shell /usr/sbin/nologin nimbus
WORKDIR /app
COPY server/build/libs/nimbus-finance-server.jar /app/server.jar
RUN chown nimbus:nimbus /app/server.jar

USER nimbus
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-XX:+ExitOnOutOfMemoryError", "-Djansi.passthrough=true", "-jar", "/app/server.jar"]
