# RAG in a Box - Docker build
# Multi-stage build: Admin UI + Scala Backend

# Stage 1: Build Admin UI
FROM node:20-alpine AS admin-builder

WORKDIR /admin-ui

# Copy package files and install dependencies
COPY admin-ui/package*.json ./
RUN npm ci

# Copy source and build
COPY admin-ui/ ./
RUN npm run build

# Stage 2: Build Scala Backend
FROM eclipse-temurin:21-jdk-jammy AS builder

WORKDIR /app

# Install sbt
RUN apt-get update && apt-get install -y curl gnupg && \
    echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" | tee /etc/apt/sources.list.d/sbt.list && \
    curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | apt-key add && \
    apt-get update && apt-get install -y sbt && \
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

# Build the fat JAR (includes admin-ui via resourceGenerators)
RUN sbt assembly

# Stage 3: Runtime image
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# Install curl for healthcheck
RUN apt-get update && apt-get install -y curl && rm -rf /var/lib/apt/lists/*

# Create non-root user
RUN groupadd -r ragbox && useradd -r -g ragbox ragbox

# Copy the built JAR
COPY --from=builder /app/target/scala-*/ragbox-assembly.jar /app/app.jar

# Set ownership
RUN chown -R ragbox:ragbox /app

# Switch to non-root user
USER ragbox

# Expose the port
EXPOSE 8080

# Health check
HEALTHCHECK --interval=30s --timeout=10s --start-period=60s --retries=3 \
    CMD curl -f http://localhost:8080/health || exit 1

# Run the application
ENTRYPOINT ["java", "-Xmx512m", "-jar", "/app/app.jar"]
