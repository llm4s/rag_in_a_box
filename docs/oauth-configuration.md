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
| `OAUTH_OKTA_AUTH_SERVER_ID` | Okta (optional) | Custom auth server ID (default: `default`) |
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
| `OAUTH_SESSION_COOKIE_SECURE` | `true` | Require HTTPS for cookies |
| `OAUTH_SESSION_MAX_AGE` | `86400` | Session duration in seconds (24 hours) |
| `OAUTH_STATE_TTL` | `300` | Auth state lifetime in seconds (5 minutes) |
| `OAUTH_SCOPES` | `openid profile email` | OAuth scopes to request |

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

3. **Optional: Use custom authorization server:**
   ```bash
   export OAUTH_OKTA_AUTH_SERVER_ID=your-auth-server-id
   ```

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
| User ID | `sub` | Unique user identifier |
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

---

## API Endpoints

OAuth authentication exposes these endpoints:

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/v1/oauth/login` | GET | Initiates OAuth flow, returns redirect URL |
| `/api/v1/oauth/callback` | GET | Handles provider callback |
| `/api/v1/oauth/logout` | POST | Ends session |
| `/api/v1/oauth/userinfo` | GET | Returns current user information |

### Login Flow

1. Client calls `GET /api/v1/oauth/login?redirect=/dashboard`
2. Server returns JSON with authorization URL:
   ```json
   {"authorizationUrl": "https://provider.example.com/authorize?..."}
   ```
3. Client redirects user to authorization URL
4. User authenticates with provider
5. Provider redirects to callback URL with authorization code
6. Server exchanges code for tokens, creates session
7. Server redirects user to original destination (`/dashboard`)

---

## Session Storage

### In-Memory (Development)

By default, sessions are stored in memory. This works for single-instance deployments but sessions are lost on restart.

### PostgreSQL (Production)

For production multi-instance deployments, use PostgreSQL session storage:

```bash
export OAUTH_SESSION_STORAGE=postgres
```

This uses the same database connection pool as the main application. Required tables are created automatically on startup.

---

## Troubleshooting

### "Invalid redirect_uri" error from provider

- Verify `OAUTH_REDIRECT_URI` exactly matches what's configured in your provider
- Check for trailing slashes
- Ensure the protocol matches (https vs http)

### "Invalid token issuer" error

- Verify `OAUTH_ISSUER` matches the issuer claim in tokens from your provider
- For multi-tenant Azure AD, ensure tenant ID is correct

### Session not persisting across requests

- Check that `OAUTH_SESSION_COOKIE_SECURE=false` for HTTP (development only)
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
