---
layout: page
title: Authentication
parent: User Guide
nav_order: 1
---

# Authentication
{: .no_toc }

Configure how clients and users authenticate to RAG in a Box.
{: .fs-6 .fw-300 }

## Table of Contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Overview

Every request, except a few public endpoints, goes through a single authenticator. It turns
the request's credentials into an authenticated **principal** (who is calling, whether they are an
administrator, and for access tokens their scopes and collection restrictions) and rejects the
request with `401 Unauthorized` if there is no valid credential.

`AUTH_MODE` selects the mode; `API_KEY` can be added to any mode.

| Mode | Interactive login | Machine credentials | Use case |
|------|-------------------|---------------------|----------|
| `open` (no `API_KEY`) | none: every request is an anonymous administrator | none needed | Local development only |
| `open` + `API_KEY` | API key | `API_KEY`, `rat_` access tokens | Single-tenant service behind a gateway |
| `basic` | username / password (JWT) | `rat_` tokens, `API_KEY` if set | Small teams |
| `oauth` | SSO via OAuth2/OIDC (session cookie) | `rat_` tokens, `API_KEY` if set | Enterprise SSO |

Credentials accepted by each mode:

| Credential | open | open + `API_KEY` | basic | oauth |
|------------|------|------------------|-------|-------|
| none | yes (anonymous admin) | | | |
| static `API_KEY` | | yes | if set | if set |
| `rat_` [access token](access-tokens.md) | | yes | yes | yes |
| JWT from `POST /api/v1/auth/login` | | | yes | |
| OIDC session cookie | | | | yes |

JWTs are only accepted in `basic` mode: in the other modes the bootstrap admin user may still
have its development password, so `POST /api/v1/auth/login` answers `400` there.

### Sending credentials

The authenticator reads the first credential it finds, in this order:

1. `X-API-Key: <API_KEY>`
2. `Authorization: Bearer <API_KEY | rat_token | JWT>`
3. `?api_key=<API_KEY>` (legacy; prefer a header, query strings end up in access logs)
4. `?access_token=<API_KEY | rat_token | JWT>` on the WebSocket endpoint `/ws` only, because
   browsers cannot set headers on a WebSocket handshake
5. The OIDC session cookie (oauth mode)

A credential that is present but invalid (wrong key, expired or revoked token, bad signature,
deleted user) is rejected with `401`, even if another credential would have been valid.

### Public endpoints

These never require authentication:

- `GET /health`, `/health/ready`, `/health/live`
- `GET /metrics` (when `METRICS_ENABLED=true`)
- `POST /api/v1/auth/login`, `GET /api/v1/auth/mode`
- `/api/v1/oauth/*` (login, callback, logout, userinfo; oauth mode)
- `/admin/*` (the admin UI's static files; the UI itself then logs in)

Everything else, including unknown paths, answers `401` without a valid credential.

### Who is an administrator

| Principal | Administrator |
|-----------|---------------|
| Anonymous (open mode without `API_KEY`) | yes |
| `API_KEY` holder | yes |
| Password user (basic) | if the user's role is `admin` |
| Access token | if it has the `admin` scope |
| SSO user (oauth) | if they are in one of `OAUTH_ADMIN_GROUPS` |

### What administrators can do that users cannot

Administrative endpoints answer `403` to everyone else: collections (create, delete,
permissions), principals, runtime and collection configuration, experiments, ingestion, clearing
all documents, users, tokens, the sync API, `/visibility/*` and `/analytics/*`. Non-admin users
only see collections, documents and chat sessions they are allowed to; see
[Permissions](permissions.md#what-non-admin-users-can-see-and-do).

### Acting for end users behind a gateway

`X-User-Id` / `X-Group-Ids` are ignored (stripped) unless `AUTH_TRUSTED_PROXY_HEADERS=true`, and
even then only honoured on requests authenticated as an administrator. See
[trusted proxy headers](permissions.md#trusted-proxy-headers).

### Discovering the mode

`GET /api/v1/auth/mode` (public) tells clients how to log in. The admin UI uses it to choose
its login form.

```json
{
  "mode": "basic",
  "authRequired": true,
  "methods": ["password", "api-key"],
  "oauthLoginUrl": null
}
```

`methods` lists `password` (basic), `sso` (oauth) and `api-key` (when `API_KEY` is set).
`authRequired` is `false` only in open mode without an API key.

### The current principal

`GET /api/v1/auth/me` returns the authenticated principal, whatever the credential:

```json
{
  "id": "user:alice",
  "username": "alice",
  "role": "user",
  "isAdmin": false,
  "authMethod": "password",
  "groups": [],
  "scopes": null,
  "collections": null,
  "userId": 2
}
```

`authMethod` is one of `open`, `api-key`, `password`, `access-token`, `oidc`. `scopes` and
`collections` are only set for access tokens.

## Configuration

```hocon
security {
  api-key = ${?API_KEY}            # optional, any mode

  auth {
    mode = "open"                  # open | basic | oauth
    mode = ${?AUTH_MODE}

    basic {
      admin-username = "admin"
      admin-password = ${?ADMIN_PASSWORD}  # required in basic mode
    }

    jwt-secret = ${?JWT_SECRET}    # required in basic mode, >= 32 characters
    jwt-expiration = 86400         # seconds
  }
}
```

## Open Mode

The default. Without `API_KEY` no credentials are checked and **every request is treated as an
anonymous administrator**: all endpoints are usable, exactly as before authentication was
enforced. It is meant for local development.

{: .warning }
Never expose open mode without `API_KEY` to an untrusted network.

Setting `API_KEY` in open mode turns authentication on: requests then need the key (or a `rat_`
access token).

```bash
export API_KEY=$(openssl rand -base64 32)
curl -H "X-API-Key: $API_KEY" http://localhost:8080/api/v1/stats
```

## Basic Authentication Mode

Username/password login that returns a JWT. `API_KEY` is not required.

### Setup

```bash
export AUTH_MODE=basic
export ADMIN_PASSWORD=your-secure-password
export JWT_SECRET=$(openssl rand -base64 32)
```

On first start an `admin` user is created with `ADMIN_PASSWORD`.

### Login

```bash
curl -X POST "http://localhost:8080/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username": "admin", "password": "your-secure-password"}'
```

```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "username": "admin",
  "role": "admin",
  "expiresIn": 86400
}
```

Use the token as `Authorization: Bearer <token>`. Wrong usernames and wrong passwords both
answer `401 Invalid username or password`.

Each request re-reads the user record, so deleting a user or changing their role takes effect
immediately, even for tokens already issued.

### Change Password

```bash
curl -X PUT "http://localhost:8080/api/v1/auth/password" \
  -H "Authorization: Bearer your-jwt-token" \
  -H "Content-Type: application/json" \
  -d '{"currentPassword": "old-password", "newPassword": "new-secure-password"}'
```

### User Management (administrators)

```bash
# Create
curl -X POST "http://localhost:8080/api/v1/users" \
  -H "Authorization: Bearer admin-jwt-token" -H "Content-Type: application/json" \
  -d '{"username": "alice", "password": "alice-password", "role": "user"}'

# List
curl "http://localhost:8080/api/v1/users" -H "Authorization: Bearer admin-jwt-token"

# Reset a password
curl -X PUT "http://localhost:8080/api/v1/users/2/password" \
  -H "Authorization: Bearer admin-jwt-token" -H "Content-Type: application/json" \
  -d '{"password": "new-password"}'

# Delete
curl -X DELETE "http://localhost:8080/api/v1/users/2" -H "Authorization: Bearer admin-jwt-token"
```

Non-administrators get `403 Forbidden`.

### JWT Details

JWTs are HS256-signed with `JWT_SECRET` and contain `sub` (user id), `username`, `role`, `iat`
and `exp`. Signatures are compared in constant time.

## OAuth Mode

SSO through an OAuth2/OIDC provider (Google, Azure AD, Okta, Keycloak or a custom provider).
See [OAuth configuration](../oauth-configuration.md) for provider setup and
[OAuth security](../oauth-security.md) for the flow.

The browser goes to `GET /api/v1/oauth/login?redirect_after=/admin/documents`; after the
provider's callback the server sets an HttpOnly session cookie and redirects back to
`redirect_after` if it is a relative path on this server (otherwise to `/admin/`). Requests then
authenticate with that cookie. `POST /api/v1/oauth/logout` ends the session.

Administrators are the members of the groups in `OAUTH_ADMIN_GROUPS` (comma-separated, matched
against the groups claim). With no admin groups configured no SSO user is an administrator; use
`API_KEY` or an admin-scoped access token for administration.

Sessions live in memory or, with `OAUTH_SESSION_STORAGE=postgres`, in the database; expired
sessions and login states are removed periodically.

## WebSocket

`GET /ws` streams `ingestion_progress` and `stats_update` messages. It needs the same
authentication as the API: the session cookie works in oauth mode; otherwise pass the JWT, API
key or access token as `?access_token=` (only accepted on `/ws`).

```javascript
const ws = new WebSocket(`wss://ragbox.example.com/ws?access_token=${encodeURIComponent(token)}`)
```

## Admin UI

The admin UI asks `GET /api/v1/auth/mode` and shows a login page with the methods the server
accepts: a username/password form (basic), "Sign in with SSO" (oauth) and an API key field (when
`API_KEY` is set). A `401` from any request sends the user back to the login page. In open mode
without an API key there is no login.

## Security Best Practices

- Always use HTTPS in production.
- Generate `JWT_SECRET` and `API_KEY` with `openssl rand -base64 32` and keep them in a secrets
  manager.
- Prefer scoped, collection-restricted [access tokens](access-tokens.md) over `API_KEY` for
  integrations; `API_KEY` is a master key.
- Passwords are hashed with PBKDF2-HMAC-SHA256 and a random salt.

## Troubleshooting

### `401 Authentication required`

No credential was sent. Add `Authorization: Bearer <token>` or `X-API-Key`.

### `401 Invalid credentials` / `Invalid token`

The bearer value is not the API key, a valid access token, or (basic mode) a valid JWT. JWTs are
not accepted outside basic mode.

### `401 Token expired`

Log in again, or raise `JWT_EXPIRATION`.

### `400 Password login is only available when AUTH_MODE=basic`

`POST /api/v1/auth/login` is disabled in open and oauth mode.

### Admin password not working

`ADMIN_PASSWORD` is only used to create the admin user on first start. Reset it with
`PUT /api/v1/users/{id}/password` as another administrator, or in the database.
