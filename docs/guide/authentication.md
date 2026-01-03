---
layout: page
title: Authentication
parent: User Guide
nav_order: 1
---

# Authentication
{: .no_toc }

Configure authentication modes for secure access to RAG in a Box.
{: .fs-6 .fw-300 }

## Table of Contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Overview

RAG in a Box supports three authentication modes to fit different deployment scenarios:

| Mode | Description | Use Case |
|------|-------------|----------|
| `open` | No authentication required | Local development, trusted networks |
| `basic` | Username/password with JWT tokens | Small teams, simple deployments |
| `oauth` | OAuth2/OIDC integration | Enterprise SSO (coming soon) |

## Configuration

Authentication is configured in `application.conf`:

```hocon
auth {
  # Authentication mode: open, basic, or oauth
  mode = "open"
  mode = ${?AUTH_MODE}

  # Basic auth settings
  basic {
    admin-username = "admin"
    admin-password = ${?ADMIN_PASSWORD}  # Required if mode=basic
  }

  # JWT settings
  jwt-secret = ${?JWT_SECRET}     # Required for basic/oauth modes
  jwt-expiration = 3600           # Token lifetime in seconds (default: 1 hour)
}
```

## Open Mode (No Authentication)

Default mode for development. All API endpoints are accessible without authentication.

```hocon
auth {
  mode = "open"
}
```

{: .warning }
Do not use open mode in production with sensitive data.

## Basic Authentication Mode

Username/password authentication with JWT tokens for API access.

### Setup

1. Set the authentication mode and admin password:

```bash
export AUTH_MODE=basic
export ADMIN_PASSWORD=your-secure-password
export JWT_SECRET=your-jwt-secret-at-least-32-chars
```

2. Start RAG in a Box:

```bash
docker run -d \
  -e AUTH_MODE=basic \
  -e ADMIN_PASSWORD=your-secure-password \
  -e JWT_SECRET=your-jwt-secret-at-least-32-chars \
  -p 8080:8080 \
  ragbox:latest
```

### User Roles

Two roles are available:

| Role | Permissions |
|------|-------------|
| `admin` | Full access: manage users, tokens, all collections |
| `user` | Query accessible collections, upload to permitted collections |

### API Endpoints

#### Login

Authenticate and receive a JWT token:

```bash
curl -X POST "http://localhost:8080/api/v1/auth/login" \
  -H "Content-Type: application/json" \
  -d '{"username": "admin", "password": "your-password"}'
```

Response:
```json
{
  "token": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
  "expiresIn": 3600,
  "user": {
    "id": 1,
    "username": "admin",
    "role": "admin"
  }
}
```

#### Get Current User

Check the authenticated user:

```bash
curl "http://localhost:8080/api/v1/auth/me" \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
```

Response:
```json
{
  "id": 1,
  "username": "admin",
  "role": "admin"
}
```

#### Change Password

Update your password:

```bash
curl -X PUT "http://localhost:8080/api/v1/auth/password" \
  -H "Authorization: Bearer your-jwt-token" \
  -H "Content-Type: application/json" \
  -d '{
    "currentPassword": "old-password",
    "newPassword": "new-secure-password"
  }'
```

### User Management (Admin Only)

#### Create User

```bash
curl -X POST "http://localhost:8080/api/v1/users" \
  -H "Authorization: Bearer admin-jwt-token" \
  -H "Content-Type: application/json" \
  -d '{
    "username": "alice",
    "password": "alice-password",
    "role": "user"
  }'
```

Response:
```json
{
  "id": 2,
  "username": "alice",
  "role": "user",
  "createdAt": "2024-01-15T10:30:00Z"
}
```

#### List Users

```bash
curl "http://localhost:8080/api/v1/users" \
  -H "Authorization: Bearer admin-jwt-token"
```

Response:
```json
{
  "users": [
    {"id": 1, "username": "admin", "role": "admin", "createdAt": "..."},
    {"id": 2, "username": "alice", "role": "user", "createdAt": "..."}
  ]
}
```

#### Delete User

```bash
curl -X DELETE "http://localhost:8080/api/v1/users/2" \
  -H "Authorization: Bearer admin-jwt-token"
```

## JWT Token Details

JWT tokens contain:

```json
{
  "sub": "1",           // User ID
  "username": "admin",
  "role": "admin",
  "iat": 1705312200,    // Issued at
  "exp": 1705315800     // Expiration
}
```

Tokens are signed using HMAC-SHA256 with your `JWT_SECRET`.

### Token Expiration

Default expiration is 1 hour (3600 seconds). Configure via:

```hocon
auth {
  jwt-expiration = 86400  # 24 hours
}
```

Or environment variable:

```bash
export JWT_EXPIRATION=86400
```

## Security Best Practices

### Password Requirements

- Use strong passwords (minimum 12 characters recommended)
- Passwords are hashed using PBKDF2 with SHA-256 and random salt
- Never log or expose plain-text passwords

### JWT Secret

- Use a cryptographically random secret (minimum 32 characters)
- Generate with: `openssl rand -base64 32`
- Store securely (environment variable, secrets manager)

### Production Deployment

1. **Always use HTTPS** in production
2. **Set strong secrets** via environment variables
3. **Rotate JWT secrets** periodically
4. **Monitor login attempts** for suspicious activity
5. **Use OAuth mode** for enterprise SSO integration

## Migrating Between Modes

### Open to Basic

1. Set environment variables:
   ```bash
   export AUTH_MODE=basic
   export ADMIN_PASSWORD=secure-password
   export JWT_SECRET=your-secret
   ```

2. Restart RAG in a Box

3. Update all API clients to authenticate

### Basic to OAuth

Coming soon in a future release.

## Troubleshooting

### "Invalid credentials" Error

- Verify username and password are correct
- Check that AUTH_MODE is set to "basic"
- Ensure ADMIN_PASSWORD is set on first startup

### "Token expired" Error

- Login again to get a fresh token
- Consider increasing jwt-expiration for long-running scripts

### "Missing authorization token" Error

- Include `Authorization: Bearer <token>` header
- Verify token was obtained from login endpoint

### Admin Password Not Working

- Admin password is only set on first startup
- To reset: delete the users table and restart
- Or use the database directly to reset the password hash
