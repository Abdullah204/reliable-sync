# syntax=docker/dockerfile:1.7
# One Dockerfile, one build stage, one small runtime image per deployable.
# Build with ./scripts/build-images.sh, which supplies the connector-foundations library either
# from a sibling checkout (--build-context foundations=../connector-foundations) or from GitHub
# Packages (a PACKAGES_TOKEN build secret).

# Empty unless --build-context foundations=<dir> replaces it.
FROM scratch AS foundations

FROM eclipse-temurin:21-jdk AS build
WORKDIR /src
# Copy build definition first so dependency resolution is cached across source edits.
COPY gradlew settings.gradle.kts ./
COPY gradle gradle
COPY build-logic build-logic
COPY --from=foundations / /connector-foundations/
COPY mocks mocks
COPY services services
RUN --mount=type=secret,id=packages_token \
    PACKAGES_TOKEN="$(cat /run/secrets/packages_token 2>/dev/null || true)" \
    ./gradlew --no-daemon --console=plain -x test \
        :mocks:acme-crm-mock:installDist :services:reliable-sync:installDist

FROM eclipse-temurin:21-jre AS acme-crm-mock
LABEL org.opencontainers.image.source="https://github.com/Abdullah204/reliable-sync" \
      org.opencontainers.image.description="Fake 'Acme CRM' API with pagination, rate limits and injected failures" \
      org.opencontainers.image.licenses="MIT"
RUN useradd --system --uid 10001 app
COPY --from=build /src/mocks/acme-crm-mock/build/install/acme-crm-mock /app
USER app
EXPOSE 8081
ENTRYPOINT ["/app/bin/acme-crm-mock"]

FROM eclipse-temurin:21-jre AS reliable-sync
LABEL org.opencontainers.image.source="https://github.com/Abdullah204/reliable-sync" \
      org.opencontainers.image.description="Crash-safe incremental API to PostgreSQL sync" \
      org.opencontainers.image.licenses="MIT"
RUN useradd --system --uid 10001 app
COPY --from=build /src/services/reliable-sync/build/install/reliable-sync /app
USER app
ENTRYPOINT ["/app/bin/reliable-sync"]
CMD ["sync"]
