---
layout: page
title: Auth API
parent: API Reference
nav_order: 1
---

# Auth API
{: .no_toc }

Login, the current principal, user management and the OAuth/OIDC flow. See the
[Authentication guide](/guide/authentication) for choosing and configuring a mode.
{: .fs-6 .fw-300 }

## Table of Contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Auth Mode {#mode}

Which login methods the server accepts. The admin UI calls this first.

```
GET /api/v1/auth/mode
```

**Auth:** Public.

```json
{
  "mode": "basic",
  "authRequired": true,
  "methods": ["password", "api-key"],
  "oauthLoginUrl": null
}
```

| Field | Description |
|-------|-------------|
| `mode` | `open`, `basic` or `oauth` (`AUTH_MODE`) |
| `authRequired` | `false` only in `open` mode without an `API_KEY` |
| `methods` | `password` (basic mode), `sso` (oauth mode), `api-key` (when `API_KEY` is set) |
| `oauthLoginUrl` | `/api/v1/oauth/login` in oauth mode, otherwise `null` |

---

## Login {#login}

Exchange a username and password for a JWT.

```
POST /api/v1/auth/login
```

**Auth:** Public. Only available when `AUTH_MODE=basic`; in other modes it answers `400`.
Failed attempts are throttled per client IP and per username
(`RATE_LIMIT_LOGIN_MAX_ATTEMPTS`, default 10 per `RATE_LIMIT_LOGIN_WINDOW_SECONDS`, default
300), even when general rate limiting is off.

```json
{ "username": "admin", "password": "your-password" }
```

**200 OK**

```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "username": "admin",
  "role": "admin",
  "expiresIn": 86400
}
```

`expiresIn` is in seconds (`JWT_EXPIRATION`, default 86400 = 24 hours). Send the token as
`Authorization: Bearer <token>`. There is no logout endpoint for JWTs: discard the token (it
stops working when it expires, or immediately if the user is deleted or demoted).

**401 Unauthorized** (whether the user exists is not revealed)

```json
{ "error": "unauthorized", "message": "Invalid username or password" }
```

---

## Current Principal {#me}

```
GET /api/v1/auth/me
```

**Auth:** Authenticated (any credential, including access tokens of any scope).

```json
{
  "id": "user:admin",
  "username": "admin",
  "role": "admin",
  "isAdmin": true,
  "authMethod": "password",
  "groups": [],
  "scopes": null,
  "collections": null,
  "userId": 1
}
```

| Field | Description |
|-------|-------------|
| `authMethod` | `open`, `api-key`, `password`, `access-token` or `oidc` |
| `role` | `admin` or `user` |
| `groups` | Group memberships (OIDC groups, or `X-Group-Ids` from a trusted proxy) |
| `scopes` | Access-token scopes; `null` for other credentials |
| `collections` | Access-token collection restriction; `null` when unrestricted |
| `userId` | Numeric user id for password users; `null` otherwise |

---

## Change Password {#change-password}

```
PUT /api/v1/auth/password
```

**Auth:** Password users (basic mode). Other principals get `400`.

```json
{ "currentPassword": "old-password", "newPassword": "new-secure-password" }
```

**200 OK**

```json
{ "message": "Password changed successfully" }
```

**400 Bad Request** when the current password is wrong or the new one breaks the password
policy (at least `PASSWORD_MIN_LENGTH` characters, default 12).

---

## Create User {#create-user}

```
POST /api/v1/users
```

**Auth:** Admin.

```json
{ "username": "alice", "password": "a-long-password", "role": "user" }
```

| Field | Required | Description |
|-------|----------|-------------|
| `username` | Yes | Unique username |
| `password` | Yes | Initial password (password policy applies) |
| `role` | No | `admin` or `user` (default `user`) |

**201 Created**

```json
{ "id": 2, "username": "alice", "role": "user" }
```

**400 Bad Request** e.g. when the username exists or the password is too short.

---

## List Users {#list-users}

```
GET /api/v1/users
```

**Auth:** Admin.

```json
{
  "users": [
    { "id": 1, "username": "admin", "role": "admin" },
    { "id": 2, "username": "alice", "role": "user" }
  ],
  "total": 2
}
```

---

## Delete User {#delete-user}

```
DELETE /api/v1/users/{id}
```

**Auth:** Admin. **204 No Content**, or **404** when the user does not exist. The user's
JWTs stop working immediately.

---

## Reset Password {#reset-password}

```
PUT /api/v1/users/{id}/password
```

**Auth:** Admin.

```json
{ "password": "a-new-long-password" }
```

**200 OK** `{"message": "Password reset successfully"}`, or **400** when the password is
missing or breaks the policy.

---

## OAuth / OIDC {#oauth}

These endpoints exist only when `AUTH_MODE=oauth` (see
[OAuth configuration](/oauth-configuration)). They are public: they establish or end the
session that the browser then presents as an HttpOnly session cookie (`ragbox_session` by
default, `OAUTH_SESSION_COOKIE_NAME`; `OAUTH_SESSION_COOKIE_SECURE` controls `Secure`).

| Method | Path | Description |
|--------|------|-------------|
| GET | `/api/v1/oauth/login?redirect_after=/admin/` | Redirects (`302`) to the identity provider (authorization code flow with state, nonce and PKCE) and sets a short-lived pre-auth cookie binding the login to this browser. `redirect_after` must be a relative path; anything else falls back to `/admin/` |
| GET | `/api/v1/oauth/callback?code=...&state=...` | Provider callback. On success: `303` to the `redirect_after` target with the session cookie set (any previous session is invalidated). `400` on a missing pre-auth cookie, invalid or expired state, or a provider `error`; `401` on an invalid ID token; `403` when the identity is rejected |
| POST | `/api/v1/oauth/logout` | Ends the session and clears the cookie. `200 {"message": "Logged out successfully"}` (or `"No active session"`) |
| GET | `/api/v1/oauth/userinfo` | The session's user: `{"userId", "email", "name", "groups"}`; `401` without a valid session |

Members of the groups in `OAUTH_ADMIN_GROUPS` are administrators.

---

## Authentication Errors

Every authenticated endpoint may answer:

**401 Unauthorized** (with `WWW-Authenticate: Bearer realm="ragbox"`), e.g.

```json
{ "error": "unauthorized", "message": "Authentication required" }
```

Other messages: `Invalid API key`, `Invalid, revoked or expired access token`,
`Token expired`, `Invalid token`, `User no longer exists`,
`Session expired or invalid. Please log in again.`

**403 Forbidden**, e.g.

```json
{ "error": "forbidden", "message": "Admin access required" }
```

Other messages: `Access token is missing the '<scope>' scope`,
`Access token needs the 'admin' scope for this endpoint`,
`Not allowed to use collection '<name>'`.
