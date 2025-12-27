# RAG in a Box - Docker build

# Stage 1: Build the application
FROM eclipse-temurin:17-jdk-jammy AS builder

WORKDIR /app

# Install sbt
RUN apt-get update && apt-get install -y curl gnupg && \
    echo "deb https://repo.scala-sbt.org/scalasbt/debian all main" | tee /etc/apt/sources.list.d/sbt.list && \
    curl -sL "https://keyserver.ubuntu.com/pks/lookup?op=get&search=0x2EE0EA64E40A89B84B2DF73499E82A75642AC823" | apt-key add && \
    apt-get update && apt-get install -y sbt && \
    rm -rf /var/lib/apt/lists/*

# Copy llm4s dependency (for local build)
# Note: In production, this would be a published artifact
COPY ../llm4s /llm4s

# Copy project files
COPY project /app/project
COPY build.sbt /app/

# Download dependencies (cached layer)
RUN sbt update

# Copy source code
COPY src /app/src

# Build the fat JAR
RUN sbt assembly

# Stage 2: Runtime image
FROM eclipse-temurin:17-jre-jammy

WORKDIR /app

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
