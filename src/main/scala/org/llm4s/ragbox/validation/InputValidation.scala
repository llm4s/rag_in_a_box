package org.llm4s.ragbox.validation

import cats.effect.IO
import org.http4s.Response
import org.http4s.dsl.io._
import io.circe.syntax._
import org.http4s.circe._
import org.llm4s.ragbox.model.ErrorResponse
import org.llm4s.ragbox.model.Codecs._

/**
 * Input validation for API requests.
 *
 * Provides configurable limits and validation helpers for document content,
 * query text, and other input fields to prevent abuse and ensure data quality.
 */
object InputValidation {

  /**
   * Validation configuration with sensible defaults.
   */
  final case class Config(
    maxDocumentSizeBytes: Long = 10 * 1024 * 1024,      // 10 MB default
    maxQueryLength: Int = 10000,                         // 10,000 chars
    maxFilenameLength: Int = 500,                        // 500 chars
    maxMetadataKeys: Int = 50,                           // 50 keys
    maxMetadataValueLength: Int = 10000,                 // 10,000 chars per value
    maxDocumentIdLength: Int = 500,                      // 500 chars
    maxBatchSize: Int = 1000                             // 1000 items per batch
  )

  object Config {
    val default: Config = Config()
  }

  /**
   * Validation result.
   */
  sealed trait ValidationResult
  case object Valid extends ValidationResult
  final case class Invalid(message: String) extends ValidationResult

  /**
   * Validate document content size.
   */
  def validateDocumentContent(content: String, config: Config = Config.default): ValidationResult = {
    val sizeBytes = content.getBytes("UTF-8").length
    if (sizeBytes > config.maxDocumentSizeBytes) {
      Invalid(s"Document content too large: ${sizeBytes} bytes exceeds maximum of ${config.maxDocumentSizeBytes} bytes")
    } else if (content.isEmpty) {
      Invalid("Document content cannot be empty")
    } else {
      Valid
    }
  }

  /**
   * Validate query text.
   */
  def validateQuery(query: String, config: Config = Config.default): ValidationResult = {
    if (query.isEmpty || query.trim.isEmpty) {
      Invalid("Query cannot be empty")
    } else if (query.length > config.maxQueryLength) {
      Invalid(s"Query too long: ${query.length} characters exceeds maximum of ${config.maxQueryLength}")
    } else {
      Valid
    }
  }

  /**
   * Validate filename.
   */
  def validateFilename(filename: String, config: Config = Config.default): ValidationResult = {
    if (filename.isEmpty) {
      Invalid("Filename cannot be empty")
    } else if (filename.length > config.maxFilenameLength) {
      Invalid(s"Filename too long: ${filename.length} characters exceeds maximum of ${config.maxFilenameLength}")
    } else {
      Valid
    }
  }

  /**
   * Validate document ID.
   */
  def validateDocumentId(id: String, config: Config = Config.default): ValidationResult = {
    if (id.isEmpty) {
      Invalid("Document ID cannot be empty")
    } else if (id.length > config.maxDocumentIdLength) {
      Invalid(s"Document ID too long: ${id.length} characters exceeds maximum of ${config.maxDocumentIdLength}")
    } else if (!id.matches("^[a-zA-Z0-9_\\-\\./:]+$")) {
      Invalid("Document ID contains invalid characters. Use only alphanumeric, underscore, hyphen, period, slash, or colon")
    } else {
      Valid
    }
  }

  /**
   * Validate metadata map.
   */
  def validateMetadata(metadata: Map[String, String], config: Config = Config.default): ValidationResult = {
    if (metadata.size > config.maxMetadataKeys) {
      Invalid(s"Too many metadata keys: ${metadata.size} exceeds maximum of ${config.maxMetadataKeys}")
    } else {
      metadata.find { case (_, v) => v.length > config.maxMetadataValueLength } match {
        case Some((k, v)) =>
          Invalid(s"Metadata value for key '$k' too long: ${v.length} characters exceeds maximum of ${config.maxMetadataValueLength}")
        case None =>
          Valid
      }
    }
  }

  /**
   * Validate batch size (for batch operations).
   */
  def validateBatchSize(size: Int, config: Config = Config.default): ValidationResult = {
    if (size <= 0) {
      Invalid("Batch size must be positive")
    } else if (size > config.maxBatchSize) {
      Invalid(s"Batch size too large: $size exceeds maximum of ${config.maxBatchSize}")
    } else {
      Valid
    }
  }

  /**
   * Convert validation result to HTTP response.
   * Returns None if valid, Some(BadRequest) if invalid.
   */
  def toResponse(result: ValidationResult): Option[IO[Response[IO]]] = result match {
    case Valid => None
    case Invalid(message) =>
      Some(BadRequest(ErrorResponse.badRequest(message).asJson))
  }

  /**
   * Run validation and continue with success action, or return error response.
   */
  def withValidation(result: ValidationResult)(onValid: => IO[Response[IO]]): IO[Response[IO]] = {
    result match {
      case Valid => onValid
      case Invalid(message) =>
        BadRequest(ErrorResponse.badRequest(message).asJson)
    }
  }

  /**
   * Combine multiple validations - returns first error or Valid.
   */
  def all(results: ValidationResult*): ValidationResult = {
    results.find(_ != Valid).getOrElse(Valid)
  }
}
