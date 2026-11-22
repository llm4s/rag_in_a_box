---
layout: page
title: WebSocket API
parent: API Reference
nav_order: 14
---

# WebSocket API
{: .no_toc }

Real-time server-to-client updates: ingestion progress and index statistics. The admin UI's
dashboard uses it.
{: .fs-6 .fw-300 }

```
GET /ws   (WebSocket upgrade)
```

**Auth:** Authenticated, like every API route. Browsers cannot set headers on a WebSocket
handshake, so besides the usual headers and the OIDC session cookie this path (and only this
path) also accepts the credential as a query parameter:

```
ws://localhost:8080/ws?access_token=<API_KEY | rat_ token | JWT>
```

In `open` mode without an `API_KEY` no credential is needed.

## Messages

Every message is a JSON text frame:

```json
{ "type": "stats_update", "payload": { ... }, "timestamp": "2026-01-15T10:30:00Z" }
```

| `type` | `payload` | Sent |
|--------|-----------|------|
| `ingestion_progress` | `{"source", "status", "progress", "message"}` | While a source is ingested (`status` is `running` until the run ends) |
| `stats_update` | `{"documentCount", "chunkCount", "collectionCount"}` | When an ingestion run ends |

The server also sends a WebSocket ping every 30 seconds. Messages from the client are ignored.
A client that falls behind by more than 256 messages misses the newer ones.

## Example

```javascript
const ws = new WebSocket(`ws://${location.host}/ws?access_token=${token}`);
ws.onmessage = (event) => {
  const msg = JSON.parse(event.data);
  if (msg.type === "ingestion_progress") console.log(msg.payload.source, msg.payload.status);
};
```
