package org.llm4s.ragbox.routes

import org.llm4s.ragbox.testkit.TestAuth.*

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import fs2.Stream
import org.apache.poi.xwpf.usermodel.XWPFDocument
import org.http4s._
import org.http4s.headers.`Content-Type`
import org.http4s.implicits._
import org.http4s.multipart.{Multiparts, Part}
import org.llm4s.ragbox.testkit.MockRAGService
import org.scalatest.LoneElement
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.io.ByteArrayOutputStream
import scala.util.Using

/** POST /api/v1/documents/upload: multipart upload with text extracted by llm4s's Tika extractor. */
class DocumentUploadRoutesSpec extends AnyFlatSpec with Matchers with LoneElement {

  private def docx(text: String): Array[Byte] =
    Using.resource(new XWPFDocument()) { doc =>
      doc.createParagraph().createRun().setText(text)
      val out = new ByteArrayOutputStream()
      doc.write(out)
      out.toByteArray
    }

  private def upload(service: MockRAGService, parts: Part[IO]*): Response[IO] = {
    val routes = DocumentRoutes.routes(service).asAdmin
    Multiparts.forSync[IO].flatMap { multiparts =>
      multiparts.multipart(Vector(parts*)).flatMap { multipart =>
        val request = Request[IO](Method.POST, uri"/api/v1/documents/upload")
          .withEntity(multipart)
          .withHeaders(multipart.headers)
        routes.orNotFound.run(request)
      }
    }.unsafeRunSync()
  }

  private def filePart(name: String, bytes: Array[Byte], mediaType: MediaType): Part[IO] =
    Part.fileData[IO]("file", name, Stream.emits(bytes.toSeq), `Content-Type`(mediaType))

  "POST /api/v1/documents/upload" should "extract the text of a DOCX file and ingest it" in {
    val service = MockRAGService()
    val response = upload(
      service,
      filePart("handbook.docx", docx("Employees accrue twenty days of leave per year."),
        new MediaType("application", "vnd.openxmlformats-officedocument.wordprocessingml.document")),
      Part.formData[IO]("collection", "hr"),
      Part.formData[IO]("metadata", """{"team":"people"}""")
    )

    response.status shouldBe Status.Created
    val (_, content, metadata) = service.getIngestCalls.loneElement
    content should include("Employees accrue twenty days of leave per year.")
    metadata("filename") shouldBe "handbook.docx"
    metadata("collection") shouldBe "hr"
    metadata("team") shouldBe "people"
    metadata("format") shouldBe "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
  }

  it should "ingest a plain text file" in {
    val service = MockRAGService()
    val response = upload(service, filePart("notes.txt", "Plain notes about zebras.".getBytes("UTF-8"), MediaType.text.plain))

    response.status shouldBe Status.Created
    service.getIngestCalls.loneElement._2 should include("Plain notes about zebras.")
  }

  it should "reject a request without a file part" in {
    val service = MockRAGService()
    upload(service, Part.formData[IO]("collection", "hr")).status shouldBe Status.BadRequest
    service.getIngestCalls shouldBe empty
  }

  it should "reject metadata that is not a JSON object of strings" in {
    val service = MockRAGService()
    val response = upload(
      service,
      filePart("notes.txt", "text".getBytes("UTF-8"), MediaType.text.plain),
      Part.formData[IO]("metadata", "[1, 2]")
    )
    response.status shouldBe Status.BadRequest
  }

  it should "reject a file with no extractable text" in {
    val service = MockRAGService()
    upload(service, filePart("empty.docx", docx(""),
      new MediaType("application", "vnd.openxmlformats-officedocument.wordprocessingml.document"))).status shouldBe Status.BadRequest
    service.getIngestCalls shouldBe empty
  }
}
