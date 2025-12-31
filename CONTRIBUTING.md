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
