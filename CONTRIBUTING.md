# Contributing to RAG in a Box

Thank you for your interest in contributing to RAG in a Box! This document provides guidelines and instructions for contributing.

## Development Setup

### Prerequisites

- Java 21 (Temurin/Eclipse recommended)
- sbt 1.9+
- Node.js 20+
- Docker and Docker Compose
- PostgreSQL with pgvector (or use Docker)

### Getting Started

1. **Clone the repository**
   ```bash
   git clone https://github.com/llm4s/rag_in_a_box.git
   cd rag_in_a_box
   ```

2. **Start PostgreSQL with pgvector**
   ```bash
   docker run -d --name ragbox-postgres \
     -e POSTGRES_USER=rag \
     -e POSTGRES_PASSWORD=rag \
     -e POSTGRES_DB=ragdb \
     -v $(pwd)/scripts/init-db.sql:/docker-entrypoint-initdb.d/01-init.sql \
     -p 15432:5432 \
     pgvector/pgvector:pg15
   ```

3. **Configure environment**
   ```bash
   cp .env.example .env
   # Edit .env and add your OPENAI_API_KEY
   ```

4. **Run the backend**
   ```bash
   sbt run
   ```

5. **Run the admin UI (development mode)**
   ```bash
   cd admin-ui
   npm install
   npm run dev
   ```

## Project Structure

```
rag_in_a_box/
├── src/                    # Scala backend source
│   └── main/scala/ragbox/
│       ├── Main.scala      # Application entry point
│       ├── config/         # Configuration handling
│       ├── routes/         # HTTP route definitions
│       └── service/        # Business logic
├── admin-ui/               # Vue.js admin interface
│   ├── src/
│   │   ├── api/           # API client functions
│   │   ├── components/    # Reusable Vue components
│   │   ├── composables/   # Vue composables (hooks)
│   │   ├── stores/        # Pinia state stores
│   │   ├── types/         # TypeScript type definitions
│   │   └── views/         # Page components
│   └── package.json
├── scripts/                # Database init scripts
├── sdk/python/            # Python SDK
├── docker-compose.yml     # Docker orchestration
├── Dockerfile             # Multi-stage Docker build
└── build.sbt              # Scala build configuration
```

## Making Changes

### Backend (Scala)

1. **Compile and check**
   ```bash
   sbt compile
   ```

2. **Run tests**
   ```bash
   sbt test
   ```

3. **Build fat JAR**
   ```bash
   sbt assembly
   ```

### Admin UI (Vue/TypeScript)

1. **Install dependencies**
   ```bash
   cd admin-ui
   npm install
   ```

2. **Run linting**
   ```bash
   npm run lint        # Fix issues automatically
   npm run lint:check  # Check only (used in CI)
   ```

3. **Type check**
   ```bash
   npx vue-tsc --noEmit
   ```

4. **Build for production**
   ```bash
   npm run build
   ```

### Docker

Build the complete Docker image:
```bash
docker compose build
```

### Database migrations

ragbox's schema is managed by [Flyway](https://documentation.red-gate.com/flyway). Components
never run DDL themselves; every table, column, index and constraint comes from the SQL scripts
in `src/main/resources/db/migration`, which `Main` applies at startup (and `--migrate-only`
applies on its own). Tables owned by llm4s (the vector/keyword table, `llm4s_collections`,
`llm4s_principals`) are not in the migrations.

To change the schema:

1. Add a new file `V<next>__<what_it_does>.sql` (e.g. `V3__add_documents_language.sql`, two
   underscores), one higher than the latest version. **Never edit a migration that has been
   merged**: deployed databases have already run it, and Flyway rejects a changed checksum.
2. Write plain PostgreSQL. Each migration runs in one transaction, so a failure leaves the
   database unchanged. Avoid `CREATE INDEX CONCURRENTLY` (it cannot run in a transaction) unless
   the migration is split out and configured for it. Do not use `${...}`: Flyway treats it as a
   placeholder.
3. Think about existing data: give new `NOT NULL` columns a default, and backfill in the same
   migration where needed.
4. Update the code that reads and writes the table, and extend `MigrationSpec` (it checks that
   a fresh database and an upgraded legacy one end with the same schema) or the integration
   spec of the component you changed. Every Postgres integration test runs against a database
   built by the real migrations (`PgVectorFixture.freshDatabase`).

`V1__baseline.sql` is special: it also upgrades databases created before Flyway (which are
baselined at version 0), so it is idempotent. Later migrations only ever run on databases
that have completed every earlier one, so they need not be.

## Code Style

### Scala
- Follow standard Scala conventions
- Use meaningful names for variables and functions
- Add comments for complex logic

### TypeScript/Vue
- Use TypeScript strict mode
- Follow Vue 3 Composition API patterns
- Use Pinia for state management
- Keep components focused and small

## Pull Request Process

1. **Create a feature branch**
   ```bash
   git checkout -b feature/your-feature-name
   ```

2. **Make your changes**
   - Write clear, focused commits
   - Include tests for new functionality
   - Update documentation as needed

3. **Ensure CI passes**
   - All linting checks pass
   - TypeScript compiles without errors
   - Tests pass
   - Docker build succeeds

4. **Submit a pull request**
   - Provide a clear description of changes
   - Reference any related issues
   - Request review from maintainers

## Reporting Issues

When reporting issues, please include:

- Clear description of the problem
- Steps to reproduce
- Expected vs actual behavior
- Environment details (OS, versions)
- Relevant logs or error messages

## License

By contributing, you agree that your contributions will be licensed under the MIT License.
