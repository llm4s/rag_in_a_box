package org.llm4s.ragbox.routes

import org.llm4s.ragbox.model.ChatMessageRecord
import org.llm4s.ragbox.service.Llm4sPipeline.ChatTurn
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.time.Instant

class ChatHistorySpec extends AnyFlatSpec with Matchers {

  private def message(index: Int, role: String, content: String): ChatMessageRecord =
    ChatMessageRecord(
      id = s"msg-$index",
      sessionId = "chat-1",
      role = role,
      content = content,
      messageIndex = index,
      createdAt = Instant.EPOCH
    )

  "historyTurns" should "keep answered exchanges, oldest first" in {
    val messages = Seq(
      message(0, "user", "q1"),
      message(1, "assistant", "a1"),
      message(2, "user", "q2"),
      message(3, "assistant", "a2")
    )
    ChatRoutes.historyTurns(messages) shouldBe Seq(
      ChatTurn("user", "q1"), ChatTurn("assistant", "a1"), ChatTurn("user", "q2"), ChatTurn("assistant", "a2")
    )
  }

  it should "drop a question whose answer failed, with its error reply, and an unanswered question" in {
    val messages = Seq(
      message(0, "assistant", "a0"), // the window starts mid-exchange
      message(1, "user", "q1"),
      message(2, "assistant", s"${ChatRoutes.ErrorReplyPrefix} timeout"),
      message(3, "user", "q2"),
      message(4, "assistant", "a2"),
      message(5, "user", "q3")
    )
    ChatRoutes.historyTurns(messages) shouldBe Seq(ChatTurn("user", "q2"), ChatTurn("assistant", "a2"))
  }
}
