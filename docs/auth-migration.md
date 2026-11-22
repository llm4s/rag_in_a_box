---
layout: page
title: Auth Migration
parent: User Guide
nav_order: 5
---

# Authentication Migration Guide

This guide covers moving a RAG in a Box deployment from one authentication mode to another.
For the modes themselves see [Authentication](guide/authentication.md).

## Authentication Modes

| Mode | `AUTH_MODE` | Who can log in | Use case |
|------|-------------|----------------|----------|
| Open | `open` (default) | Nobody needs to: every request is an anonymous administrator, unless `API_KEY` is set | Local development only |
| Basic | `basic` | ragbox users with a password (`POST /api/v1/auth/login` returns a JWT) | Small deployments without an identity provider |
| OAuth | `oauth` | Users of an OIDC provider (Google, Azure AD, Okta, Keycloak, custom); session cookie | Enterprise SSO |

Credentials accepted in each mode:

| Credential | open | open + `API_KEY` | basic | oauth |
|------------|------|------------------|-------|-------|
| none (anonymous administrator) | yes | | | |
| static `API_KEY` (`X-API-Key` or `Authorization: Bearer`) | | yes | if set | if set |
| `rat_` [access token](guide/access-tokens.md) (`Authorization: Bearer`) | | yes | yes | yes |
| JWT from `POST /api/v1/auth/login` | | | yes | |
| OIDC session cookie | | | | yes |

So `API_KEY` and `rat_` access tokens keep working across every mode change (except that in
open mode without `API_KEY` no credential is checked at all). They are the way to keep
integrations (ingesters, scripts, the Python SDK) running while you migrate. There is no
separate switch to enable them: setting `API_KEY` is enough, and access tokens are created by
an administrator with `POST /api/v1/tokens`.

---

## What Carries Over, and What Does Not

| Data | Effect of changing mode |
|------|-------------------------|
| Documents, collections, chunking configs, analytics | Unaffected |
| `rat_` access tokens | Keep working (stored in the database, independent of the mode) |
| `API_KEY` | Keeps working while set |
| Password users (`users` table) | Kept, but they can only log in in basic mode |
| JWTs issued in basic mode | Rejected in every other mode; also invalid after `JWT_SECRET` changes |
| OAuth sessions | Rejected outside oauth mode; in-memory sessions are lost on restart |
| Collection permissions | Kept, but they name user identities, which differ between modes (below) |

### Identities and collection permissions

Collection permissions are granted to external user ids and groups. The id a request is
checked against depends on how it authenticated:

- **Basic mode**: the ragbox username (e.g. `alice`).
- **OAuth mode**: by default `<issuer>|<sub>` (e.g. `https://accounts.google.com|1234567890`).
  With `OAUTH_CLAIM_USER_ID=email` it is the (verified) e-mail address; with any other claim,
  that claim's value. Groups come from the groups claim (`OAUTH_CLAIM_GROUPS`).
- **API key / admin-scoped tokens / open mode**: administrators, who see every collection.

When the identity changes, re-grant permissions to the new identifiers with
`PUT /api/v1/collections/{path}/permissions`, or grant them to groups, which are usually
stable across a provider change. Administrators in oauth mode are the members of
`OAUTH_ADMIN_GROUPS`; with none set, no SSO user is an administrator.

---

## Migration Paths

### Open → Basic

```bash
export AUTH_MODE=basic
export ADMIN_PASSWORD='a-strong-password-of-12+-chars'   # creates the admin user on first start
export JWT_SECRET="$(openssl rand -base64 48)"            # 32+ characters, not a placeholder
# optional: export ADMIN_USERNAME=admin
```

Startup fails if `ADMIN_PASSWORD` or `JWT_SECRET` is missing or too weak. After the restart,
log in to the admin UI (or `POST /api/v1/auth/login`), create users with `POST /api/v1/users`,
and give integrations an `API_KEY` or a `rat_` token.

### Open → OAuth

```bash
export AUTH_MODE=oauth
export OAUTH_PROVIDER=google
export OAUTH_CLIENT_ID=your-client-id
export OAUTH_CLIENT_SECRET=your-client-secret
export OAUTH_REDIRECT_URI=https://app.example.com/api/v1/oauth/callback
export OAUTH_ADMIN_GROUPS=ragbox-admins          # who administers ragbox
export OAUTH_SESSION_STORAGE=postgres            # for more than one replica
```

1. Register the application with the provider and set the redirect URI exactly as above
   (see [OAuth Configuration](oauth-configuration.md)).
2. OAuth mode needs permission-based RAG (the PostgreSQL search index); the server refuses
   to start in oauth mode without it.
3. Set `API_KEY` (or create admin-scoped `rat_` tokens first, while still in open mode) if
   anything needs administrative API access and no SSO group is an admin group.
4. Deploy. Browser users log in through `/api/v1/oauth/login`.

**Rollback**: set `AUTH_MODE=open` and restart.

### Basic → OAuth

```bash
export AUTH_MODE=oauth
export OAUTH_PROVIDER=azure
export OAUTH_CLIENT_ID=your-client-id
export OAUTH_CLIENT_SECRET=your-client-secret
export OAUTH_AZURE_TENANT_ID=your-tenant-id
export OAUTH_REDIRECT_URI=https://app.example.com/api/v1/oauth/callback
export OAUTH_ADMIN_GROUPS=ragbox-admins
```

1. Integrations: keep `API_KEY` set and keep using existing `rat_` tokens; they work in oauth
   mode unchanged.
2. Password logins stop working (`POST /api/v1/auth/login` answers `400` outside basic mode),
   and outstanding JWTs are rejected.
3. Re-grant collection permissions to the users' OIDC identities or to groups (see
   [Identities and collection permissions](#identities-and-collection-permissions)).
4. The `users` table is left as it is; it is used again if you switch back.

**Rollback**: set `AUTH_MODE=basic` (with the same `JWT_SECRET` and `ADMIN_PASSWORD`).

### OAuth → OAuth (provider change)

**Before** (Google):
```bash
export OAUTH_PROVIDER=google
export OAUTH_CLIENT_ID=old-google-client-id
export OAUTH_CLIENT_SECRET=old-google-secret
```

**After** (Azure AD):
```bash
export OAUTH_PROVIDER=azure
export OAUTH_CLIENT_ID=new-azure-client-id
export OAUTH_CLIENT_SECRET=new-azure-secret
export OAUTH_AZURE_TENANT_ID=your-tenant-id
```

With the default `OAUTH_CLAIM_USER_ID=sub`, identities include the issuer, so every user gets
a new identity after a provider change and per-user permissions must be granted again.
Alternatives:

- Grant permissions to groups, and make the new provider emit the same group names.
- Use `OAUTH_CLAIM_USER_ID=email`, if both providers assert verified e-mail addresses
  (logins without `email_verified: true` are then rejected with `403`).

Existing sessions keep the old identity until they expire; to force everyone to log in again
with `OAUTH_SESSION_STORAGE=postgres`, run `DELETE FROM oauth_sessions;` (with in-memory
storage, a restart does it).

---

## Zero-Downtime Migration

A rolling update briefly runs old and new configurations side by side, so during the
rollout a request may reach either. Plan for that:

1. Test the new configuration in staging, including the login flow.
2. Make sure integrations authenticate with `API_KEY` or `rat_` tokens, which both modes accept.
3. Roll out; users whose session or JWT the new mode does not accept are asked to log in again.
4. With several replicas in oauth mode, use `OAUTH_SESSION_STORAGE=postgres`, or a session
   created by one replica is unknown to the others.

---

## Troubleshooting

### Users can't log in after migration

1. Check the server logs; configuration errors fail startup with a message naming the
   missing setting (`OAUTH_CLIENT_ID required when auth mode is 'oauth'`, ...).
2. Check the redirect URI matches the provider's configuration exactly.
3. A `400 Missing login cookie` on the callback: see
   [OAuth Configuration - Troubleshooting](oauth-configuration.md#troubleshooting).

### Users can log in but see no collections

Their identity changed and the permissions name the old one. `GET /api/v1/auth/me` shows
the current principal; grant permissions to that identity or to one of its groups.

### API clients failing with 401

- `rat_` tokens: check the token has not been revoked or expired (`GET /api/v1/tokens`).
- `API_KEY`: check it is set on every replica.
- A JWT from basic mode is not accepted in any other mode; switch the client to a `rat_`
  token.

### API clients failing with 403

An access token without the `admin` scope may only use the endpoints its scopes are for
(documents, sync, query/search, chat, `/api/v1/auth/me`, `/ws`). See
[Access Tokens](guide/access-tokens.md).

---

## Migration Checklist

Before migration:
- [ ] Identity provider application created with the exact redirect URI (oauth)
- [ ] `JWT_SECRET` and `ADMIN_PASSWORD` generated (basic)
- [ ] Integrations moved to `API_KEY` or `rat_` tokens
- [ ] Plan for re-granting collection permissions to the new identities
- [ ] Staging tested, rollback plan written down

After migration:
- [ ] Administrators can log in (`GET /api/v1/auth/me` shows `isAdmin: true`)
- [ ] Regular users see the collections they should
- [ ] Integrations still work
