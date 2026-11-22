---
layout: page
title: Chat API
parent: API Reference
nav_order: 5
---

# Chat API
{: .no_toc }

Persistent multi-turn chat sessions. Each question is answered with RAG over the session's
collection pattern, with the session's recent messages sent to the LLM as conversation
history (`RAG_CHAT_HISTORY_MESSAGES`, default 10).
{: .fs-6 .fw-300 }

## Table of Contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

**Auth (all endpoints):** Authenticated; access tokens need the `query` scope and may only
use collection patterns inside their restriction. Every session belongs to the principal that
created it: only that principal and administrators can list, read, change, delete, post to or
rate it. Other callers get `404` (session ids are not confirmed to exist).

## Session Object

```json
{
  "id": "c0a8...",
  "title": "Onboarding questions",
  "collectionPattern": "hr/**",
  "messageCount": 4,
  "createdAt": "2026-01-15T10:30:00Z",
  "updatedAt": "2026-01-15T10:35:00Z",
  "isArchived": false,
  "ownerId": "user:alice"
}
```

## Message Object

```json
{
  "id": "m-123",
  "sessionId": "c0a8...",
  "role": "assistant",
  "content": "You get 25 days of leave...",
  "contexts": [ { "content": "...", "score": 0.9, "metadata": {}, "documentId": "doc-1", "chunkIndex": 0 } ],
  "usage": { "promptTokens": 400, "completionTokens": 60, "totalTokens": 460 },
  "rating": null,
  "queryLogId": "q-456",
  "createdAt": "2026-01-15T10:35:00Z",
  "messageIndex": 3
}
```

`role` is `user` or `assistant`. `contexts`, `usage`, `rating` and `queryLogId` are `null`
when not applicable.

---

## Create Session {#create-session}

```
POST /api/v1/chat/sessions
```

```json
{ "title": "Onboarding questions", "collectionPattern": "hr/**" }
```

Both fields are optional (`collectionPattern` defaults to `*`). **201 Created**:

```json
{ "session": { ...session... }, "message": "Session created" }
```

---

## List Sessions {#list-sessions}

```
GET /api/v1/chat/sessions?includeArchived=false&limit=100&offset=0
```

Most recently updated first. Administrators see every session, others only their own.
`limit` defaults to 100 (max 1000); `total` counts all matching sessions across pages.

```json
{ "sessions": [ { ...session... } ], "total": 12 }
```

---

## Get Session {#get-session}

```
GET /api/v1/chat/sessions/{id}?limit=1000&offset=0
```

The session and its messages in order. `limit` defaults to 1000 (also the maximum).

```json
{ "session": { ...session... }, "messages": [ { ...message... } ] }
```

---

## Update Session {#update-session}

```
PUT /api/v1/chat/sessions/{id}
```

```json
{ "title": "New title", "collectionPattern": "hr/policies", "isArchived": true }
```

All fields optional. Returns the updated session.

---

## Delete Session {#delete-session}

```
DELETE /api/v1/chat/sessions/{id}
```

```json
{ "success": true, "message": "Session deleted" }
```

---

## Send Message {#send-message}

```
POST /api/v1/chat/sessions/{id}/messages
```

```json
{ "content": "How many days of leave do I get?" }
```

**200 OK** with both stored messages:

```json
{
  "userMessage": { ...message... },
  "assistantMessage": { ...message... }
}
```

If answering fails, the response is still `200`: `assistantMessage` holds an error reply and a
top-level `"error"` string explains why.

---

## Send Message (Streaming) {#stream-message}

```
POST /api/v1/chat/sessions/{id}/messages/stream
```

Same body. The response is `text/event-stream` with the same events as the
[streaming query](/api/query#stream) (`start`, `context`, `answer`, `usage`, `complete`,
`error`); the `start` event's data is `{"queryId", "userMessageId"}`. The answer arrives as one
`answer` event once complete ([llm4s/llm4s#2717](https://github.com/llm4s/llm4s/issues/2717)).
The assistant message is stored when the stream completes. `queryId` is the id of the answer's
query-log entry, which the stored assistant message links to (`queryLogId`).

A failed answer (either endpoint) is stored as an error reply and logged in the query log as a
failed query, as `/query` failures are, so analytics error rates include chat; the error reply's
`queryLogId` points at that entry.

---

## Rate Message {#rate-message}

```
PUT /api/v1/chat/messages/{id}/rate
```

```json
{ "rating": 5 }
```

`rating` is 1 to 5. The rating is also recorded on the linked query-log entry, so it shows up
in [analytics](/api/analytics) and [experiment results](/api/experiments#results).

```json
{ "success": true, "message": "Rating saved" }
```
