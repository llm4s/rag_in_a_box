---
layout: page
title: Access Tokens
parent: User Guide
nav_order: 3
---

# Access Tokens
{: .no_toc }

Create and manage API tokens for external ingesters and automated systems.
{: .fs-6 .fw-300 }

## Table of Contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Overview

Access tokens provide secure API access for external systems like:

- Custom ingesters (Confluence, SharePoint, databases)
- CI/CD pipelines
- Automated scripts
- Third-party integrations

Tokens are:
- Long-lived (optional expiration)
- Scoped to specific permissions
- Optionally restricted to collections
- Revocable at any time

## Token Format

Tokens use the format `rat_XXXXXXXX...` where:
- `rat_` prefix identifies RAG Access Tokens
- Followed by 43 characters of URL-safe base64

Example: `rat_dGVzdC10b2tlbi1oZXJlLWZvci1leGFtcGxl`

## Token Scopes

Scopes control what operations a token can perform:

| Scope | Description |
|-------|-------------|
| `documents:read` | Read document metadata |
| `documents:write` | Create, update, delete documents |
| `sync:read` | Read sync status and document list |
| `sync:write` | Mark sync complete, prune documents |
| `query` | Execute RAG queries |
| `admin` | Full administrative access |

### Common Scope Combinations

| Use Case | Scopes |
|----------|--------|
| External ingester | `documents:write`, `sync:read`, `sync:write` |
| Read-only integration | `documents:read`, `query` |
| Query-only client | `query` |
| Full automation | `admin` |

## Managing Tokens

### Create a Token

Requires admin authentication:

```bash
curl -X POST "http://localhost:8080/api/v1/tokens" \
  -H "Authorization: Bearer admin-jwt-token" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "confluence-ingester",
    "scopes": ["documents:write", "sync:read", "sync:write"]
  }'
```

Response:
```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "name": "confluence-ingester",
  "token": "rat_dGVzdC10b2tlbi1oZXJlLWZvci1leGFtcGxl",
  "scopes": ["documents:write", "sync:read", "sync:write"],
  "collections": null,
  "expiresAt": null
}
```

{: .warning }
The full token is only returned once at creation time. Store it securely!

### Create Token with Expiration

Set an expiration date for temporary access:

```bash
curl -X POST "http://localhost:8080/api/v1/tokens" \
  -H "Authorization: Bearer admin-jwt-token" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "temp-migration-token",
    "scopes": ["documents:write"],
    "expiresAt": "2024-12-31T23:59:59Z"
  }'
```

### Create Token with Collection Restrictions

Limit token access to specific collections:

```bash
curl -X POST "http://localhost:8080/api/v1/tokens" \
  -H "Authorization: Bearer admin-jwt-token" \
  -H "Content-Type: application/json" \
  -d '{
    "name": "engineering-docs-ingester",
    "scopes": ["documents:write", "sync:write"],
    "collections": ["confluence/ENG", "confluence/PLATFORM"]
  }'
```

This token can only write to documents in the `confluence/ENG` and `confluence/PLATFORM` collections.

### List Tokens

View all tokens (token values are hidden):

```bash
curl "http://localhost:8080/api/v1/tokens" \
  -H "Authorization: Bearer admin-jwt-token"
```

Response:
```json
{
  "tokens": [
    {
      "id": "550e8400-e29b-41d4-a716-446655440000",
      "name": "confluence-ingester",
      "tokenPrefix": "rat_dGVzdC10",
      "scopes": ["documents:write", "sync:read", "sync:write"],
      "collections": null,
      "expiresAt": null,
      "lastUsedAt": "2024-01-15T10:30:00Z",
      "createdAt": "2024-01-01T00:00:00Z"
    }
  ],
  "total": 1
}
```

### Get Token Details

```bash
curl "http://localhost:8080/api/v1/tokens/550e8400-e29b-41d4-a716-446655440000" \
  -H "Authorization: Bearer admin-jwt-token"
```

### Revoke a Token

Delete a token immediately:

```bash
curl -X DELETE "http://localhost:8080/api/v1/tokens/550e8400-e29b-41d4-a716-446655440000" \
  -H "Authorization: Bearer admin-jwt-token"
```

Response: `204 No Content`

## Using Tokens

### In API Requests

Include the token in the `Authorization` header:

```bash
curl -X PUT "http://localhost:8080/api/v1/documents/doc-123" \
  -H "Authorization: Bearer rat_dGVzdC10b2tlbi1oZXJlLWZvci1leGFtcGxl" \
  -H "Content-Type: application/json" \
  -d '{"content": "Document content..."}'
```

### Python Example

```python
import requests

class RAGBoxClient:
    def __init__(self, base_url, access_token):
        self.base_url = base_url
        self.headers = {
            "Authorization": f"Bearer {access_token}",
            "Content-Type": "application/json"
        }

    def upsert_document(self, doc_id, content, collection, metadata=None):
        response = requests.put(
            f"{self.base_url}/api/v1/documents/{doc_id}",
            headers=self.headers,
            json={
                "content": content,
                "collection": collection,
                "metadata": metadata or {}
            }
        )
        response.raise_for_status()
        return response.json()

    def sync_complete(self, keep_doc_ids):
        response = requests.post(
            f"{self.base_url}/api/v1/sync",
            headers=self.headers,
            json={"keepDocumentIds": keep_doc_ids}
        )
        response.raise_for_status()
        return response.json()

# Usage
client = RAGBoxClient(
    base_url="http://localhost:8080",
    access_token="rat_dGVzdC10b2tlbi1oZXJlLWZvci1leGFtcGxl"
)

client.upsert_document(
    doc_id="confluence-123",
    content="Document content from Confluence...",
    collection="confluence/ENG",
    metadata={"title": "API Guidelines", "author": "alice@example.com"}
)
```

### Node.js Example

```javascript
class RAGBoxClient {
  constructor(baseUrl, accessToken) {
    this.baseUrl = baseUrl;
    this.headers = {
      Authorization: `Bearer ${accessToken}`,
      "Content-Type": "application/json",
    };
  }

  async upsertDocument(docId, content, collection, metadata = {}) {
    const response = await fetch(
      `${this.baseUrl}/api/v1/documents/${docId}`,
      {
        method: "PUT",
        headers: this.headers,
        body: JSON.stringify({ content, collection, metadata }),
      }
    );
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    return response.json();
  }

  async syncComplete(keepDocIds) {
    const response = await fetch(`${this.baseUrl}/api/v1/sync`, {
      method: "POST",
      headers: this.headers,
      body: JSON.stringify({ keepDocumentIds: keepDocIds }),
    });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    return response.json();
  }
}

// Usage
const client = new RAGBoxClient(
  "http://localhost:8080",
  "rat_dGVzdC10b2tlbi1oZXJlLWZvci1leGFtcGxl"
);

await client.upsertDocument(
  "sharepoint-456",
  "Document content from SharePoint...",
  "sharepoint/HR",
  { filename: "handbook.docx" }
);
```

## Security Best Practices

### Token Storage

1. **Environment variables**: Store tokens in environment variables
   ```bash
   export RAG_ACCESS_TOKEN=rat_xxx...
   ```

2. **Secrets managers**: Use AWS Secrets Manager, HashiCorp Vault, etc.

3. **Never commit tokens**: Add to `.gitignore`:
   ```
   .env
   *.token
   ```

### Principle of Least Privilege

- Grant only required scopes
- Restrict to specific collections when possible
- Use expiring tokens for temporary access

### Token Rotation

1. Create a new token with the same permissions
2. Update clients to use new token
3. Revoke the old token

### Monitoring

- Check `lastUsedAt` to identify unused tokens
- Review token list regularly
- Revoke tokens for departed team members

## Error Handling

### "Token not found" (404)

Token ID doesn't exist or was revoked.

### "Invalid scopes" (400)

One or more scopes in the request are invalid. Valid scopes:
- `documents:read`
- `documents:write`
- `sync:read`
- `sync:write`
- `query`
- `admin`

### "Forbidden" (403)

Token doesn't have the required scope for the operation.

### "Token expired" (401)

Token has passed its `expiresAt` time. Create a new token.

### "Missing authorization token" (401)

Request is missing the `Authorization: Bearer` header.
