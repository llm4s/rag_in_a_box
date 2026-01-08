# OAuth Security Guide

This document covers security features and best practices for OAuth2/OIDC authentication in RAG in a Box.

## Security Features

### PKCE (Proof Key for Code Exchange)

RAG in a Box enforces PKCE for all OAuth flows. PKCE prevents authorization code interception attacks by:

1. **Code Verifier**: A cryptographically random string generated per login attempt
2. **Code Challenge**: SHA-256 hash of the verifier, sent with the authorization request
3. **Verification**: Provider validates the original verifier when exchanging the code

PKCE is always enabled and cannot be disabled.

### State Parameter

The OAuth state parameter prevents CSRF attacks:

- Random 32-byte value generated per login attempt
- Stored server-side with 5-minute TTL (configurable via `OAUTH_STATE_TTL`)
- Verified on callback before processing authorization code
- Single-use: removed from storage after verification

### Token Validation

ID tokens are validated using industry-standard checks:

| Check | Description |
|-------|-------------|
| Signature | Verified against provider's JWKS public keys |
| Issuer | Must match configured `OAUTH_ISSUER` |
| Audience | Must include configured `OAUTH_CLIENT_ID` |
| Expiration | Token must not be expired |
| Not Before | Token must be valid (if `nbf` claim present) |

JWKS keys are cached and refreshed periodically to handle key rotation.

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

**In-Memory Storage (Development)**

- Sessions stored in application memory
- Lost on application restart
- Not suitable for multi-instance deployments

**PostgreSQL Storage (Production)**

- Sessions persisted to database
- Survives application restarts
- Supports horizontal scaling
- Enable with: `OAUTH_SESSION_STORAGE=postgres`

### Session Lifecycle

```
Login Request → Create PKCE State → Redirect to Provider
                    ↓
Provider Callback → Validate State → Exchange Code → Validate Token
                    ↓
Create Session → Set Cookie → Redirect to App
                    ↓
Subsequent Requests → Validate Session Cookie → Allow/Deny
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
| CSRF attacks | State parameter + SameSite cookies |
| Session hijacking | HttpOnly + Secure cookies |
| Token tampering | Cryptographic signature validation |
| Replay attacks | Token expiration + single-use state |
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

The application logs these security-relevant events:

- Login attempts (success/failure)
- Session creation/deletion
- Token validation failures
- Invalid state parameter attempts
- JWKS refresh operations

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

1. Caching JWKS keys for performance
2. Refreshing cache on signature verification failure
3. Supporting multiple keys during rotation period

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
2. Invalidate all sessions (clear session table)
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
