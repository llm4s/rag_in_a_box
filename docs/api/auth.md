---
layout: page
title: Auth API
parent: API Reference
nav_order: 1
---

# Auth API
{: .no_toc }

Authentication and user management endpoints.
{: .fs-6 .fw-300 }

## Table of Contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Login

Authenticate with username and password to receive a JWT token.

```
POST /api/v1/auth/login
```

### Request Body

```json
{
  "username": "admin",
  "password": "your-password"
}
```

### Response

**200 OK**
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

**401 Unauthorized**
```json
{
  "error": "unauthorized",
  "message": "Invalid credentials"
}
```

---

## Get Current User

Get information about the authenticated user.

```
GET /api/v1/auth/me
```

### Headers

```
Authorization: Bearer <jwt-token>
```

### Response

**200 OK**
```json
{
  "id": 1,
  "username": "admin",
  "role": "admin"
}
```

**401 Unauthorized**
```json
{
  "error": "unauthorized",
  "message": "Missing authorization token"
}
```

---

## Change Password

Update the current user's password.

```
PUT /api/v1/auth/password
```

### Headers

```
Authorization: Bearer <jwt-token>
```

### Request Body

```json
{
  "currentPassword": "old-password",
  "newPassword": "new-secure-password"
}
```

### Response

**200 OK**
```json
{
  "message": "Password updated successfully"
}
```

**400 Bad Request**
```json
{
  "error": "bad_request",
  "message": "Current password is incorrect"
}
```

---

## Create User

Create a new user account. Requires admin role.

```
POST /api/v1/users
```

### Headers

```
Authorization: Bearer <admin-jwt-token>
```

### Request Body

```json
{
  "username": "alice",
  "password": "alice-password",
  "role": "user"
}
```

| Field | Type | Required | Description |
|-------|------|----------|-------------|
| `username` | string | Yes | Unique username |
| `password` | string | Yes | Initial password |
| `role` | string | No | `admin` or `user` (default: `user`) |

### Response

**201 Created**
```json
{
  "id": 2,
  "username": "alice",
  "role": "user",
  "createdAt": "2024-01-15T10:30:00Z"
}
```

**400 Bad Request**
```json
{
  "error": "bad_request",
  "message": "Username already exists"
}
```

**403 Forbidden**
```json
{
  "error": "forbidden",
  "message": "Admin access required"
}
```

---

## List Users

List all user accounts. Requires admin role.

```
GET /api/v1/users
```

### Headers

```
Authorization: Bearer <admin-jwt-token>
```

### Response

**200 OK**
```json
{
  "users": [
    {
      "id": 1,
      "username": "admin",
      "role": "admin",
      "createdAt": "2024-01-01T00:00:00Z"
    },
    {
      "id": 2,
      "username": "alice",
      "role": "user",
      "createdAt": "2024-01-15T10:30:00Z"
    }
  ]
}
```

---

## Delete User

Delete a user account. Requires admin role.

```
DELETE /api/v1/users/{id}
```

### Headers

```
Authorization: Bearer <admin-jwt-token>
```

### Path Parameters

| Parameter | Type | Description |
|-----------|------|-------------|
| `id` | integer | User ID |

### Response

**204 No Content**

**404 Not Found**
```json
{
  "error": "not_found",
  "message": "User not found"
}
```

---

## Authentication Errors

All authenticated endpoints may return:

**401 Unauthorized**
```json
{
  "error": "unauthorized",
  "message": "Missing authorization token"
}
```

```json
{
  "error": "unauthorized",
  "message": "Token expired"
}
```

```json
{
  "error": "unauthorized",
  "message": "Invalid token"
}
```

**403 Forbidden**
```json
{
  "error": "forbidden",
  "message": "Admin access required"
}
```
