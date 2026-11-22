---
layout: page
title: OAuth Security
parent: User Guide
nav_order: 4
---

# OAuth Security Guide

This document covers security features and best practices for OAuth2/OIDC authentication in RAG in a Box.

## Security Features

### PKCE (Proof Key for Code Exchange)

RAG in a Box enforces PKCE for all OAuth flows. PKCE prevents authorization code interception attacks by:

1. **Code Verifier**: A cryptographically random string generated per login attempt
2. **Code Challenge**: SHA-256 hash of the verifier, sent with the authorization request
3. **Verification**: Provider validates the original verifier when exchanging the code

PKCE (`S256`) is always used and cannot be disabled (the `OAUTH_PKCE_ENABLED` setting is parsed but has no effect).

### State Parameter and Browser Binding (Login CSRF Protection)

The OAuth `state` parameter on its own only proves that *some* browser started a login.
To stop an attacker from completing their own login in a victim's browser (login CSRF /
session fixation), each login is also bound to the browser that started it:

- `GET /api/v1/oauth/login` generates a random 32-byte `state`, PKCE verifier, `nonce`
  and a separate random *browser-binding* value
- The binding value is set in a pre-auth cookie (`ragbox_oauth_state`); only its SHA-256
  hash is stored server-side with the state
- The endpoint responds with a `302` redirect to the provider; the state is never returned
  to the client in a response body
- `GET /api/v1/oauth/callback` requires the pre-auth cookie, and rejects the callback
  (`400`) unless the cookie's hash matches the binding stored for that `state`
- State is single-use (removed atomically on first use) and expires after
  `OAUTH_STATE_TTL` seconds (default 300)
- The pre-auth cookie is cleared on every callback response, success or failure

Pre-auth cookie attributes:

| Attribute | Value |
|-----------|-------|
| `HttpOnly` | `true` |
| `Secure` | Follows `OAUTH_SESSION_COOKIE_SECURE` (default `true`) |
| `SameSite` | `Lax` (required so the cookie accompanies the top-level redirect back from the provider) |
| `Path` | `/api/v1/oauth` |
| `Max-Age` | `OAUTH_STATE_TTL` |

### Nonce

A random `nonce` is sent in every authorization request and stored with the state. The ID
token returned by the token endpoint must carry an identical `nonce` claim, otherwise the
login is rejected (`401`). This binds the ID token to the login request and prevents ID
token replay/injection.

### Session Rotation

On a successful callback a brand-new random session id is issued. If the browser already
presented a session cookie, that session is deleted server-side, so a session id planted
before login (session fixation) can never become authenticated.

### Token Validation

ID tokens are validated using industry-standard checks:

| Check | Description |
|-------|-------------|
| Signature | Verified against provider's JWKS public keys |
| Issuer | Must match the provider preset's issuer (or `OAUTH_ISSUER` for `custom`) |
| Audience | Must include configured `OAUTH_CLIENT_ID` |
| Expiration | Token must not be expired |
| Not Before | Token must be valid (if `nbf` claim present) |
| Nonce | Must equal the nonce sent in the authorization request (browser logins) |

JWKS keys are cached (up to 10 keys for 24 hours, with at most 10 JWKS fetches per minute); a token signed with a key id that is not in the cache triggers a fetch, which is how key rotation is picked up.

### User Identity

The principal a login maps to is controlled by `OAUTH_CLAIM_USER_ID` (default `sub`):

| `OAUTH_CLAIM_USER_ID` | Principal | Notes |
|-----------------------|-----------|-------|
| `sub` (default) | `<iss>\|<sub>`, e.g. `https://accounts.google.com\|1234567890` | `sub` is only unique per issuer, so it is qualified with the issuer |
| The email claim (`email`, or whatever `OAUTH_CLAIM_EMAIL` names) | The email address | Accepted **only** when the token has `email_verified: true`; otherwise the login is rejected with `403` |
| Any other claim (e.g. `oid`, `preferred_username`) | The claim's value | The login is rejected (`403`) if the claim is missing. Only use claims your provider guarantees are unique and immutable |

The `email` claim is never used as identity implicitly: two accounts at a provider that
lets users set an unverified email cannot collide on the same principal.

### Groups

Group names from the groups claim are stored as a PostgreSQL `TEXT[]`, so a group name
containing a comma (e.g. `x,admins`) remains a single group.

---

## Session Security

### Session Cookies

Sessions are managed via HTTP cookies with these security settings:

| Setting | Default | Description |
|---------|---------|-------------|
| `HttpOnly` | `true` | Cookie not accessible via JavaScript |
| `Secure` | `true` | Cookie only sent over HTTPS |
| `SameSite` | `Lax` | CSRF protection |
| `Path` | `/` | Cookie valid for entire application |
| `Max-Age` | 24 hours | Session lifetime |

### Session Storage

Selected with `OAUTH_SESSION_STORAGE` (`memory` | `postgres`, default `memory`).

**In-Memory Storage (Development)** - `OAUTH_SESSION_STORAGE=memory`

- Sessions stored in application memory
- Lost on application restart
- Not suitable for multi-instance deployments

**PostgreSQL Storage (Production)** - `OAUTH_SESSION_STORAGE=postgres`

- Sessions and in-flight login states persisted to the application database
- Survives application restarts
- Supports horizontal scaling
- Tables (`oauth_sessions`, `oauth_auth_state`) are part of the Flyway-managed schema (`V1__baseline.sql`), applied at startup unless `DB_MIGRATE_ON_START=false`

**Upgrading an existing `oauth_sessions` table**: older releases stored groups as a
comma-joined `TEXT` column. The baseline migration converts the column in place to `TEXT[]`,
splitting existing values on commas exactly as they were previously read, so live sessions
keep working. Because those legacy rows may already contain a comma-injected group split,
operators who want to be strict can force everyone to log in again with
`DELETE FROM oauth_sessions;`. In-flight login states from before the upgrade (no nonce /
browser binding) are deleted automatically; affected users simply start the login again.

### Session Lifecycle

```
Login Request → Create state + PKCE + nonce + browser binding
             → Set pre-auth cookie → 302 to Provider
                    ↓
Provider Callback → Require pre-auth cookie → Validate state (single-use, TTL)
                 → Check cookie matches state → Exchange code (PKCE)
                 → Validate ID token (signature, iss, aud, exp, nonce)
                    ↓
Resolve identity (iss|sub, or verified email / configured claim)
                    ↓
Delete any pre-existing session → Create new session → Set session cookie
                 → Clear pre-auth cookie → Redirect to App
                    ↓
Subsequent Requests → Validate session cookie → Resolve authorization
                 → Allow (or 401 no/invalid session, 403 authorization unavailable)
                    ↓
Logout → Delete Session → Clear Cookie
```

---

## Logout

### Local Logout

POST to `/api/v1/oauth/logout`:
- Deletes session from server-side storage
- Clears session cookie

### Provider Logout (SSO)

For complete single sign-out, redirect users to provider's logout endpoint after local logout:

| Provider | Logout URL |
|----------|------------|
| Google | `https://accounts.google.com/Logout` |
| Azure AD | `https://login.microsoftonline.com/{tenant}/oauth2/v2.0/logout` |
| Okta | `https://{domain}/oauth2/default/v1/logout` |
| Keycloak | `{issuer}/protocol/openid-connect/logout` |

---

## Best Practices

### Production Deployment

1. **Always use HTTPS**
   ```bash
   export OAUTH_SESSION_COOKIE_SECURE=true
   ```

2. **Use PostgreSQL session storage**
   ```bash
   export OAUTH_SESSION_STORAGE=postgres
   ```

3. **Restrict redirect URIs** in provider configuration to exact match

4. **Rotate client secrets** periodically

5. **Configure session timeouts** appropriately
   ```bash
   export OAUTH_SESSION_MAX_AGE=3600  # 1 hour for sensitive apps
   ```

### Secret Management

Never commit OAuth credentials to version control:

```bash
# BAD - Don't do this
OAUTH_CLIENT_SECRET=secret123  # in .env file committed to git

# GOOD - Use environment variables or secret managers
export OAUTH_CLIENT_SECRET=$(vault kv get -field=secret oauth/ragbox)
```

Recommended approaches:
- AWS Secrets Manager
- HashiCorp Vault
- Kubernetes Secrets
- Environment variables from secure CI/CD

### Claim Validation

Validate user claims match expected values:

- **Email domain restriction**: Configure at provider level (Google Workspace, Azure AD tenant)
- **Group membership**: Map provider groups to internal permissions
- **Required claims**: Application fails if expected claims are missing

---

## Threat Model

### Mitigated Threats

| Threat | Mitigation |
|--------|------------|
| Authorization code interception | PKCE |
| Login CSRF | State bound to the initiating browser via HttpOnly pre-auth cookie |
| CSRF attacks | SameSite cookies |
| Session fixation | Session id rotated (old session deleted) on every successful login |
| Session hijacking | HttpOnly + Secure cookies |
| Token tampering | Cryptographic signature validation |
| ID token injection/replay | Nonce verification |
| Replay attacks | Token expiration + single-use state |
| Account takeover via unverified email | Identity keyed on `iss`+`sub`; email only used when `email_verified` |
| Group name injection | Groups stored as `TEXT[]`, never split on delimiters |
| Man-in-the-middle | HTTPS enforcement |

### Residual Risks

| Risk | Mitigation Strategy |
|------|---------------------|
| Compromised client secret | Regular rotation, secret detection in CI |
| Session theft via XSS | Content Security Policy, input sanitization |
| Provider compromise | Trust established OIDC providers |

---

## Monitoring

### Security Events to Log

The OIDC components log (logger `org.llm4s.ragbox.auth.*`) login initiation, callback
handling, token validation and state failures, and session cleanup. Every HTTP request is
also logged by the request logger (`org.llm4s.ragbox.http`) with credentials redacted; set
`LOG_FORMAT=json` for structured logs.

### Alerting Recommendations

Configure alerts for:
- High rate of failed login attempts (potential brute force)
- Token validation failures (potential attack or misconfiguration)
- Session creation from unusual locations (if tracking IP)

---

## Compliance Considerations

### Session Management

For compliance with security standards (SOC 2, ISO 27001):

- **Idle timeout**: Implement client-side activity tracking
- **Absolute timeout**: Configure `OAUTH_SESSION_MAX_AGE` appropriately
- **Concurrent sessions**: Track sessions per user if required

### Audit Logging

Consider logging:
- Who authenticated
- When they authenticated
- What resources they accessed
- When they logged out

---

## Key Rotation

### Provider Key Rotation

Identity providers rotate signing keys periodically. RAG in a Box handles this by:

1. Caching JWKS keys by key id (`kid`) for performance (up to 10 keys, 24 hours)
2. Fetching the JWKS again when a token names a key id that is not cached (rate limited to 10 fetches a minute)
3. Accepting any key the provider currently publishes, so old and new keys both work during a rotation

### Client Secret Rotation

To rotate client secrets without downtime:

1. Generate new secret in provider console
2. Update `OAUTH_CLIENT_SECRET` in deployment
3. Restart application (rolling restart for zero downtime)
4. Delete old secret in provider console

---

## Incident Response

### Suspected Token Compromise

1. Rotate client secret immediately
2. Invalidate all sessions (with `OAUTH_SESSION_STORAGE=postgres`: `DELETE FROM oauth_sessions;`; with in-memory storage, restart the server)
3. Review access logs for unauthorized activity
4. Notify affected users

### Suspected Session Hijacking

1. Delete affected session(s)
2. Force re-authentication
3. Review session creation patterns
4. Consider reducing session lifetime

### Provider Security Incident

1. Monitor provider status page
2. Consider temporarily disabling OAuth login
3. Switch to alternative auth mode if needed
4. Re-enable after provider confirms resolution
