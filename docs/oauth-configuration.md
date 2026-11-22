---
layout: page
title: OAuth Configuration
parent: User Guide
nav_order: 3
---

# OAuth2/OIDC Configuration Guide

This guide covers configuring OAuth2/OIDC authentication for RAG in a Box with various identity providers.

## Quick Start

1. Set auth mode to OAuth:
   ```bash
   export AUTH_MODE=oauth
   ```

2. Configure your provider (example with Google):
   ```bash
   export OAUTH_PROVIDER=google
   export OAUTH_CLIENT_ID=your-client-id
   export OAUTH_CLIENT_SECRET=your-client-secret
   export OAUTH_REDIRECT_URI=https://your-app.example.com/api/v1/oauth/callback
   ```

3. Start the application.

OAuth mode needs permission-based RAG (the PostgreSQL search index with its permission
schema), which is the default with PostgreSQL; the server refuses to start in oauth mode
without it. `OAUTH_CLIENT_ID`, `OAUTH_CLIENT_SECRET` and `OAUTH_REDIRECT_URI` are required,
and startup fails with a configuration error if any of them is missing.

---

## Supported Providers

RAG in a Box includes pre-configured settings for major identity providers:

| Provider | `OAUTH_PROVIDER` value | Notes |
|----------|------------------------|-------|
| Google | `google` | Google Workspace or personal accounts |
| Azure AD | `azure` | Microsoft Entra ID (formerly Azure AD) |
| Okta | `okta` | Okta Identity Cloud |
| Keycloak | `keycloak` | Self-hosted Keycloak |
| Custom | `custom` | Any OIDC-compliant provider |

---

## Environment Variables Reference

### Required Variables

| Variable | Description | Example |
|----------|-------------|---------|
| `AUTH_MODE` | Must be `oauth` | `oauth` |
| `OAUTH_PROVIDER` | Provider preset | `google`, `azure`, `okta`, `keycloak`, `custom` |
| `OAUTH_CLIENT_ID` | OAuth client ID from provider | `abc123.apps.googleusercontent.com` |
| `OAUTH_CLIENT_SECRET` | OAuth client secret | `GOCSPX-xxxxx` |
| `OAUTH_REDIRECT_URI` | Callback URL (must match provider config) | `https://app.example.com/api/v1/oauth/callback` |

### Provider-Specific Variables

| Variable | Required For | Description |
|----------|--------------|-------------|
| `OAUTH_AZURE_TENANT_ID` | Azure | Azure AD tenant ID |
| `OAUTH_OKTA_DOMAIN` | Okta | Okta org domain (e.g., `company.okta.com`) |
| `OAUTH_KEYCLOAK_BASE_URL` | Keycloak | Keycloak server URL |
| `OAUTH_KEYCLOAK_REALM` | Keycloak | Keycloak realm name |

### Custom Provider Variables

| Variable | Required For | Description |
|----------|--------------|-------------|
| `OAUTH_ISSUER` | Custom | OIDC issuer URL |
| `OAUTH_AUTHORIZATION_ENDPOINT` | Custom | Authorization endpoint URL |
| `OAUTH_TOKEN_ENDPOINT` | Custom | Token endpoint URL |
| `OAUTH_USERINFO_ENDPOINT` | Custom | Userinfo endpoint URL |
| `OAUTH_JWKS_URI` | Custom | JWKS (public keys) endpoint URL |

### Optional Configuration

| Variable | Default | Description |
|----------|---------|-------------|
| `OAUTH_SESSION_COOKIE_NAME` | `ragbox_session` | Session cookie name |
| `OAUTH_SESSION_COOKIE_SECURE` | `true` | Require HTTPS for the session and pre-auth (login) cookies. Set `false` only for local HTTP development |
| `OAUTH_SESSION_MAX_AGE` | `86400` | Session duration in seconds (24 hours) |
| `OAUTH_PKCE_ENABLED` | `true` | Parsed but currently has no effect: PKCE (S256) is always used |
| `OAUTH_STATE_TTL` | `300` | Auth state and pre-auth cookie lifetime in seconds (5 minutes) |
| `OAUTH_SESSION_STORAGE` | `memory` | Session and login-state storage: `memory` (single instance) or `postgres` (`postgresql` is also accepted) |
| `OAUTH_ADMIN_GROUPS` | (none) | Comma-separated groups (from the groups claim) whose members are ragbox administrators. With none, no SSO user is an administrator |
| `OAUTH_SCOPES` | the provider preset's (e.g. `openid profile email`) | Scopes to request, space- or comma-separated; must include `openid` |

---

## Provider Setup Guides

### Google

1. **Create OAuth credentials in Google Cloud Console:**
   - Go to [Google Cloud Console](https://console.cloud.google.com/)
   - Navigate to APIs & Services > Credentials
   - Click "Create Credentials" > "OAuth client ID"
   - Select "Web application"
   - Add authorized redirect URI: `https://your-app.example.com/api/v1/oauth/callback`
   - Copy the Client ID and Client Secret

2. **Configure environment:**
   ```bash
   export AUTH_MODE=oauth
   export OAUTH_PROVIDER=google
   export OAUTH_CLIENT_ID=your-client-id.apps.googleusercontent.com
   export OAUTH_CLIENT_SECRET=GOCSPX-your-secret
   export OAUTH_REDIRECT_URI=https://your-app.example.com/api/v1/oauth/callback
   ```

3. **Optional: Restrict to your domain:**
   In Google Cloud Console, configure the OAuth consent screen to restrict access to users in your Google Workspace domain.

### Azure AD (Microsoft Entra ID)

1. **Register application in Azure Portal:**
   - Go to [Azure Portal](https://portal.azure.com/)
   - Navigate to Azure Active Directory > App registrations
   - Click "New registration"
   - Set redirect URI to: `https://your-app.example.com/api/v1/oauth/callback`
   - Note the Application (client) ID and Directory (tenant) ID

2. **Create client secret:**
   - In your app registration, go to Certificates & secrets
   - Create a new client secret
   - Copy the secret value immediately (it won't be shown again)

3. **Configure environment:**
   ```bash
   export AUTH_MODE=oauth
   export OAUTH_PROVIDER=azure
   export OAUTH_CLIENT_ID=your-application-id
   export OAUTH_CLIENT_SECRET=your-secret-value
   export OAUTH_AZURE_TENANT_ID=your-tenant-id
   export OAUTH_REDIRECT_URI=https://your-app.example.com/api/v1/oauth/callback
   ```

4. **Optional: Configure group claims:**
   - In Azure Portal > App registrations > Token configuration
   - Add "groups" claim to include group membership in tokens

### Okta

1. **Create OIDC application in Okta Admin Console:**
   - Go to Applications > Create App Integration
   - Select "OIDC - OpenID Connect" and "Web Application"
   - Set sign-in redirect URI: `https://your-app.example.com/api/v1/oauth/callback`
   - Note the Client ID and Client Secret

2. **Configure environment:**
   ```bash
   export AUTH_MODE=oauth
   export OAUTH_PROVIDER=okta
   export OAUTH_CLIENT_ID=your-client-id
   export OAUTH_CLIENT_SECRET=your-client-secret
   export OAUTH_OKTA_DOMAIN=your-company.okta.com
   export OAUTH_REDIRECT_URI=https://your-app.example.com/api/v1/oauth/callback
   ```

The Okta preset uses the `default` custom authorization server
(`https://<OAUTH_OKTA_DOMAIN>/oauth2/default`) and requests the scopes
`openid profile email groups`. There is no setting for another authorization server; to use
one, configure Okta as a `custom` provider with that server's endpoints.

### Keycloak

1. **Create client in Keycloak Admin Console:**
   - Go to your realm > Clients > Create client
   - Set Client ID (e.g., `ragbox`)
   - Enable "Client authentication" for confidential client
   - Add Valid redirect URI: `https://your-app.example.com/api/v1/oauth/callback`
   - Copy the Client Secret from the Credentials tab

2. **Configure environment:**
   ```bash
   export AUTH_MODE=oauth
   export OAUTH_PROVIDER=keycloak
   export OAUTH_CLIENT_ID=ragbox
   export OAUTH_CLIENT_SECRET=your-client-secret
   export OAUTH_KEYCLOAK_BASE_URL=https://keycloak.example.com
   export OAUTH_KEYCLOAK_REALM=your-realm
   export OAUTH_REDIRECT_URI=https://your-app.example.com/api/v1/oauth/callback
   ```

### Custom OIDC Provider

For any OIDC-compliant provider not listed above:

```bash
export AUTH_MODE=oauth
export OAUTH_PROVIDER=custom
export OAUTH_CLIENT_ID=your-client-id
export OAUTH_CLIENT_SECRET=your-client-secret
export OAUTH_REDIRECT_URI=https://your-app.example.com/api/v1/oauth/callback
export OAUTH_ISSUER=https://your-provider.example.com
export OAUTH_AUTHORIZATION_ENDPOINT=https://your-provider.example.com/authorize
export OAUTH_TOKEN_ENDPOINT=https://your-provider.example.com/token
export OAUTH_USERINFO_ENDPOINT=https://your-provider.example.com/userinfo
export OAUTH_JWKS_URI=https://your-provider.example.com/.well-known/jwks.json
```

---

## Claim Mapping

RAG in a Box maps OIDC token claims to internal user attributes. Default mappings:

| Internal Attribute | Default Claim | Description |
|--------------------|---------------|-------------|
| User ID | `sub` | Unique user identifier (see below) |
| Email | `email` | User's email address |
| Name | `name` | Display name |
| Groups | `groups` | Group/role membership |

### Custom Claim Mapping

Override claim names via environment variables:

```bash
export OAUTH_CLAIM_USER_ID=preferred_username
export OAUTH_CLAIM_EMAIL=mail
export OAUTH_CLAIM_NAME=display_name
export OAUTH_CLAIM_GROUPS=roles
```

This is useful when your provider uses non-standard claim names.

### User Identity

- With the default `OAUTH_CLAIM_USER_ID=sub`, users are identified as `<issuer>|<sub>`
  (for example `https://accounts.google.com|1234567890`), because `sub` is only unique
  within one issuer. Grant permissions to that identifier.
- `OAUTH_CLAIM_USER_ID=email` identifies users by email address, but a login is only
  accepted when the ID token has `email_verified: true`; otherwise it is rejected with
  `403 Forbidden`.
- Any other claim (e.g. `oid` for Azure AD) is used verbatim; a token without it is
  rejected. Only choose claims your provider guarantees to be unique and immutable.

---

## API Endpoints

OAuth authentication exposes these endpoints:

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/v1/oauth/login` | GET | Initiates OAuth flow: sets the pre-auth cookie and redirects (302) to the provider |
| `/api/v1/oauth/callback` | GET | Handles provider callback |
| `/api/v1/oauth/logout` | POST | Ends session |
| `/api/v1/oauth/userinfo` | GET | Returns current user information |

### Login Flow

1. The browser navigates to `GET /api/v1/oauth/login` (a link or `window.location`, not
   `fetch`/XHR - the response is a redirect that must be followed by the browser)
2. Server responds `302 Found` to the provider's authorization URL (including `state`,
   `nonce` and the PKCE challenge) and sets a short-lived HttpOnly `ragbox_oauth_state`
   cookie that binds the login to this browser
3. User authenticates with provider
4. Provider redirects to `OAUTH_REDIRECT_URI` (`/api/v1/oauth/callback`) with `code` and `state`
5. Server checks the pre-auth cookie matches `state`, exchanges the code (PKCE), validates
   the ID token (including `nonce`), invalidates any previous session, creates a new session
6. Server clears the pre-auth cookie, sets the session cookie and redirects to the
   `redirect_after` passed to `/login` if it is a relative path on this server (starts with a
   single `/`, no scheme, host or backslash), otherwise to `/admin/`
7. Every later request authenticates with the session cookie; `GET /api/v1/auth/me` returns the
   principal (`authMethod: "oidc"`, `isAdmin` from `OAUTH_ADMIN_GROUPS`)

In oauth mode `API_KEY` (if set) and `rat_` [access tokens](guide/access-tokens.md) are accepted
as well, for integrations; password login (`/api/v1/auth/login`) is disabled. Expired sessions
and login states are removed every `max(60, OAUTH_STATE_TTL)` seconds.

A callback without the pre-auth cookie (for example, a link opened in a different browser,
or after `OAUTH_STATE_TTL` has elapsed) is rejected with `400`; start the login again.

---

## Session Storage

### In-Memory (Development)

By default, sessions are stored in memory. This works for single-instance deployments but sessions are lost on restart.

### PostgreSQL (Production)

For production multi-instance deployments, use PostgreSQL session storage:

```bash
export OAUTH_SESSION_STORAGE=postgres
```

This uses the same database connection pool as the main application. The session tables are part of the schema managed by the Flyway migrations (`src/main/resources/db/migration`), which run at startup unless `DB_MIGRATE_ON_START=false`; see [OAuth Security](oauth-security.md#session-storage).

---

## Troubleshooting

### "Invalid redirect_uri" error from provider

- Verify `OAUTH_REDIRECT_URI` exactly matches what's configured in your provider
- Check for trailing slashes
- Ensure the protocol matches (https vs http)

### "Invalid token issuer" error

- The ID token's `iss` must equal the preset's issuer (or `OAUTH_ISSUER` for `custom`)
- For Azure AD the expected issuer is `https://login.microsoftonline.com/<tenant>/v2.0`; ensure the tenant ID is correct (multi-tenant `common`/`organizations` endpoints are not supported, because their tokens carry the user's tenant as issuer)

### Session not persisting across requests

- Check that `OAUTH_SESSION_COOKIE_SECURE=false` for HTTP (development only)
- A `400 Missing login cookie` on the callback means the pre-auth cookie was not sent:
  check the cookie `Secure` setting matches your scheme, that the login and callback use the
  same host, and that the login completed within `OAUTH_STATE_TTL`
- Verify cookies are being set in browser
- For multi-instance deployments, use PostgreSQL session storage

### Group claims not appearing

- Ensure your provider is configured to include groups in tokens
- Azure AD: Add "groups" claim in Token configuration
- Okta: Add "groups" scope to your application
- Keycloak: Configure group mapper for your client

### JWKS fetch timeout

- Verify the JWKS URI is accessible from your server
- Check firewall rules allow outbound HTTPS
- For Keycloak, ensure the realm is correctly configured
