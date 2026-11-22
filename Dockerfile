# RAG in a Box - Docker build
# Multi-stage build: Admin UI + Scala Backend
#
# The build stages run on the native build platform ($BUILDPLATFORM): their
# outputs (static assets and a JVM fat JAR) are platform independent, so a
# multi-arch build only needs to emulate the small runtime stage.

# Stage 1: Build Admin UI
FROM --platform=$BUILDPLATFORM node:20-alpine AS admin-builder

WORKDIR /admin-ui

# Copy package files and install dependencies
COPY admin-ui/package*.json ./
RUN npm ci

# Copy source and build
COPY admin-ui/ ./
RUN npm run build

# Stage 2: Build Scala Backend
FROM --platform=$BUILDPLATFORM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /app

# Install sbt from the official repository, trusting its key only for that repository
RUN apt-get update && apt-get install -y --no-install-recommends curl gnupg ca-certificates && \
    curl -fsSL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" \
      | gpg --dearmor -o /usr/share/keyrings/sbt-archive-keyring.gpg && \
    echo "deb [signed-by=/usr/share/keyrings/sbt-archive-keyring.gpg] https://repo.scala-sbt.org/scalasbt/debian all main" \
      > /etc/apt/sources.list.d/sbt.list && \
    apt-get update && apt-get install -y --no-install-recommends sbt && \
    rm -rf /var/lib/apt/lists/*

# Copy project files
COPY project /app/project
COPY build.sbt /app/

# Download dependencies (cached layer)
RUN sbt update

# Copy source code
COPY src /app/src

# Copy built admin-ui from first stage (SBT will bundle it into JAR)
COPY --from=admin-builder /admin-ui/dist /app/admin-ui/dist

# The version /health and /metrics report (the build context has no .git for sbt-dynver)
ARG RAGBOX_VERSION=""

# Build the fat JAR (includes admin-ui via resourceGenerators)
RUN sbt assembly

# Stage 3: Runtime image
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# Install curl for healthcheck
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*

# Create non-root user with a fixed numeric UID/GID (lets Kubernetes verify runAsNonRoot)
RUN groupadd -r -g 10001 ragbox && useradd -r -u 10001 -g ragbox ragbox

# Copy the built JAR
COPY --from=builder --chown=10001:10001 /app/target/scala-*/ragbox-assembly.jar /app/app.jar

# Switch to non-root user
USER 10001:10001

# Expose the port
EXPOSE 8080

# Size the heap relative to the container memory limit. Extra JVM flags can be
# supplied at runtime via JAVA_OPTS (e.g. -e JAVA_OPTS="-XX:MaxRAMPercentage=60 -Dfoo=bar").
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75"

# Health check (liveness: the process is up and serving HTTP)
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -fsS http://localhost:8080/health/live || exit 1

# Run the application (exec so java is PID 1 and receives SIGTERM)
ENTRYPOINT ["sh", "-c", "exec java $JAVA_OPTS -jar /app/app.jar \"$@\"", "--"]
