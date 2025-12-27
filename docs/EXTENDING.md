# Extending RAG in a Box

This guide covers how to customize and extend RAG in a Box for advanced use cases.

## Architecture Overview

RAG in a Box is structured as follows:

```
rag-in-a-box/
├── src/main/scala/ragbox/
│   ├── Main.scala                 # Entry point
│   ├── config/
│   │   └── AppConfig.scala        # Configuration loading
│   ├── ingestion/
│   │   ├── IngestionConfig.scala  # Source configurations
│   │   ├── IngestionService.scala # Ingestion logic
│   │   └── IngestionScheduler.scala
│   ├── middleware/
│   │   └── AuthMiddleware.scala   # Authentication
│   ├── model/
│   │   ├── ApiModels.scala        # Request/response types
│   │   └── Codecs.scala           # JSON serialization
│   ├── registry/
│   │   ├── DocumentRegistry.scala # Registry trait
│   │   └── PgDocumentRegistry.scala # PostgreSQL implementation
│   ├── routes/
│   │   ├── DocumentRoutes.scala
│   │   ├── QueryRoutes.scala
│   │   ├── IngestionRoutes.scala
│   │   ├── ConfigRoutes.scala
│   │   ├── HealthRoutes.scala
│   │   └── MetricsRoutes.scala
│   └── service/
│       └── RAGService.scala       # Core RAG wrapper
└── src/main/resources/
    └── application.conf           # Default configuration
```

## Extension Points

### 1. Adding Custom Ingestion Sources

To add a new ingestion source:

1. **Define the source configuration** in `IngestionConfig.scala`:

```scala
final case class MyCustomSourceConfig(
  name: String,
  myOption: String,
  metadata: Map[String, String] = Map.empty,
  enabled: Boolean = true
) extends SourceConfig
```

2. **Add parsing logic** in `IngestionConfig.parseSource`:

```scala
case "custom" =>
  val myOption = Try(config.getString("my-option")).getOrElse("")
  Some(MyCustomSourceConfig(
    name = name,
    myOption = myOption,
    metadata = metadata,
    enabled = enabled
  ))
```

3. **Implement the ingestion method** in `IngestionService`:

```scala
private def runCustomIngestion(
  config: MyCustomSourceConfig,
  startTime: Instant
): IO[IngestionResult] = {
  rag.flatMap { r =>
    IO.blocking {
      // Your custom ingestion logic here
      var added = 0

      // Fetch documents from your source
      val documents = fetchFromCustomSource(config.myOption)

      documents.foreach { doc =>
        r.ingestText(doc.content, doc.id, config.metadata) match {
          case Right(_) => added += 1
          case Left(_) => // Handle error
        }
      }

      IngestionResult(
        sourceName = config.name,
        sourceType = "custom",
        documentsAdded = added,
        // ... other fields
      )
    }
  }
}
```

4. **Add the case** to `runSourceConfig`:

```scala
case custom: MyCustomSourceConfig =>
  runCustomIngestion(custom, startTime)
```

### 2. Custom Document Loaders

llm4s provides the `DocumentLoader` trait for custom loading logic:

```scala
import org.llm4s.rag.loader.{DocumentLoader, DocumentData, LoadStats}

class MyCustomLoader extends DocumentLoader {
  override def load(): Either[LLMError, LoadStats] = {
    // Return documents and stats
    Right(LoadStats(successful = 10, failed = 0))
  }

  override def loadWithDocuments(): Either[LLMError, (Seq[DocumentData], LoadStats)] = {
    val documents = Seq(
      DocumentData("doc1", "content...", Map("source" -> "custom"))
    )
    Right((documents, LoadStats(successful = documents.size, failed = 0)))
  }
}
```

Use with RAG:

```scala
val loader = new MyCustomLoader()
rag.sync(loader) // For incremental sync
// or
rag.ingest(loader) // For full ingestion
```

### 3. Adding Custom API Endpoints

Create a new routes file in `routes/`:

```scala
package ragbox.routes

import cats.effect.IO
import org.http4s._
import org.http4s.dsl.io._
import ragbox.service.RAGService

object MyCustomRoutes {

  def routes(ragService: RAGService): HttpRoutes[IO] = HttpRoutes.of[IO] {

    case GET -> Root / "api" / "v1" / "custom" / "endpoint" =>
      for {
        // Your logic here
        result <- ragService.someMethod()
        response <- Ok(result.asJson)
      } yield response
  }
}
```

Wire it in `Main.scala`:

```scala
baseRoutes = Seq(
  // ... existing routes
  "/" -> MyCustomRoutes.routes(ragService)
)
```

### 4. Custom Middleware

Add middleware in `middleware/`:

```scala
package ragbox.middleware

import cats.data.Kleisli
import cats.effect.IO
import org.http4s._

object MyMiddleware {

  def apply(routes: HttpRoutes[IO]): HttpRoutes[IO] = {
    Kleisli { (request: Request[IO]) =>
      // Pre-processing
      routes(request).map { response =>
        // Post-processing
        response.putHeaders(
          Header.Raw(CIString("X-Custom-Header"), "value")
        )
      }
    }
  }
}
```

### 5. Custom Authentication

Replace or extend `AuthMiddleware.scala`:

```scala
// Add JWT support
private def extractJwtToken(request: Request[IO]): Option[Claims] = {
  request.headers.get[Authorization].flatMap { auth =>
    auth.credentials match {
      case Credentials.Token(AuthScheme.Bearer, token) =>
        validateJwt(token) // Your JWT validation
      case _ => None
    }
  }
}
```

### 6. Custom Vector Store

llm4s supports multiple vector stores. To use a different one:

```scala
// In RAGService or custom builder
val ragConfig = RAGConfig.default
  .withEmbeddings(EmbeddingProvider.OpenAI, "text-embedding-3-small")
  .withQdrant(
    url = "http://localhost:6333",
    collectionName = "my_docs"
  )
  // or
  .withSQLite("/path/to/vectors.db")
  .build(resolveEmbeddingProvider)
```

## Configuration Extension

### Adding New Config Sections

1. **Define case class** in `AppConfig.scala`:

```scala
final case class MyCustomConfig(
  option1: String,
  option2: Int
)
```

2. **Add to AppConfig**:

```scala
final case class AppConfig(
  // ... existing fields
  myCustom: MyCustomConfig
)
```

3. **Add parsing**:

```scala
myCustom = MyCustomConfig(
  option1 = config.getString("my-custom.option1"),
  option2 = config.getInt("my-custom.option2")
)
```

4. **Add to application.conf**:

```hocon
my-custom {
  option1 = "default"
  option1 = ${?MY_OPTION1}
  option2 = 42
  option2 = ${?MY_OPTION2}
}
```

## Building Custom Docker Images

```dockerfile
FROM eclipse-temurin:21-jdk AS builder
WORKDIR /app

# Copy source and build
COPY . .
RUN sbt assembly

FROM eclipse-temurin:21-jre
WORKDIR /app

COPY --from=builder /app/target/scala-*/ragbox-assembly.jar ./app.jar

# Add any custom files
COPY custom-config.conf /app/custom-config.conf

EXPOSE 8080

ENTRYPOINT ["java", "-Dconfig.file=/app/custom-config.conf", "-jar", "app.jar"]
```

## Upgrade Guide

When upgrading from a base RAG in a Box version:

1. **Merge upstream changes**:
   ```bash
   git remote add upstream https://github.com/org/rag-in-a-box.git
   git fetch upstream
   git merge upstream/main
   ```

2. **Check for breaking changes** in CHANGELOG.md

3. **Update database schema** if needed:
   ```sql
   -- Run any migration scripts
   ALTER TABLE document_registry ADD COLUMN new_field TEXT;
   ```

4. **Test your extensions** against the new version

## Best Practices

1. **Keep extensions modular** - Create separate files/packages for custom code
2. **Use configuration** - Make custom behavior configurable via env vars
3. **Test incrementally** - Test each extension point before combining
4. **Document your changes** - Maintain a local CHANGES.md for your customizations
5. **Pin dependencies** - Lock llm4s and other dependency versions

## Troubleshooting

### Common Issues

**Dependency conflicts**: If you add new dependencies, check for version conflicts with the merge strategy in `build.sbt`.

**Type mismatches after upgrade**: The llm4s API may change between versions. Check the llm4s CHANGELOG for breaking changes.

**Database schema mismatch**: The registry adds columns automatically, but if you've customized the schema, you may need manual migration.

## Getting Help

- Open an issue on GitHub
- Check the llm4s documentation for RAG API details
- Review the example implementations in this codebase
