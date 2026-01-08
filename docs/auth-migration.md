# Authentication Migration Guide

This guide covers migrating RAG in a Box from one authentication mode to another.

## Authentication Modes

RAG in a Box supports three authentication modes:

| Mode | `AUTH_MODE` | Use Case |
|------|-------------|----------|
| Open | `open` | Development, internal tools |
| Basic | `basic` | Simple deployments with API keys |
| OAuth | `oauth` | Enterprise with SSO integration |

---

## Migration Paths

### Open → OAuth

**Scenario**: Moving from unauthenticated access to enterprise SSO.

**Before** (no authentication):
```bash
export AUTH_MODE=open
```

**After** (OAuth with Google):
```bash
export AUTH_MODE=oauth
export OAUTH_PROVIDER=google
export OAUTH_CLIENT_ID=your-client-id
export OAUTH_CLIENT_SECRET=your-client-secret
export OAUTH_REDIRECT_URI=https://app.example.com/api/v1/oauth/callback
```

**Migration Steps**:

1. **Configure identity provider** (Google, Azure AD, Okta, or Keycloak)
   - Create OAuth application
   - Configure redirect URI
   - Note client credentials

2. **Update deployment configuration**
   - Set environment variables as shown above
   - Ensure PostgreSQL is available for session storage

3. **Deploy with new configuration**
   - All existing sessions will be invalid
   - Users will need to log in via OAuth

4. **Update client applications**
   - API clients must obtain tokens via OAuth flow
   - Or use API keys (if enabled alongside OAuth)

**Rollback**: Set `AUTH_MODE=open` to revert.

---

### Basic → OAuth

**Scenario**: Upgrading from API key authentication to enterprise SSO.

**Before** (basic auth):
```bash
export AUTH_MODE=basic
export AUTH_USERNAME=admin
export AUTH_PASSWORD=secret123
```

**After** (OAuth):
```bash
export AUTH_MODE=oauth
export OAUTH_PROVIDER=azure
export OAUTH_CLIENT_ID=your-client-id
export OAUTH_CLIENT_SECRET=your-client-secret
export OAUTH_AZURE_TENANT_ID=your-tenant-id
export OAUTH_REDIRECT_URI=https://app.example.com/api/v1/oauth/callback
```

**Migration Steps**:

1. **Preserve API key access** (optional)
   If you have API clients that can't use OAuth, keep API key authentication:
   ```bash
   export API_KEY_ENABLED=true
   export API_KEY=your-secure-api-key
   ```
   API clients can continue using the `X-API-Key` header.

2. **Configure identity provider**
   - Set up OAuth application
   - Map user groups if needed

3. **Communicate to users**
   - Notify users of authentication change
   - Provide login instructions

4. **Deploy and monitor**
   - Watch for authentication failures
   - Assist users with first-time OAuth login

**Rollback**: Set `AUTH_MODE=basic` and restore basic auth credentials.

---

### OAuth → OAuth (Provider Change)

**Scenario**: Switching identity providers (e.g., Google to Azure AD).

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

**Migration Steps**:

1. **Set up new provider** alongside existing
   - Create OAuth application in new provider
   - Test with a staging environment

2. **User identity mapping**
   - Ensure email addresses match between providers
   - Or plan for new user records

3. **Deploy with new provider**
   - All existing sessions will be invalid
   - Users log in with new provider

4. **Clean up old provider**
   - Revoke old OAuth application
   - Remove old configuration

**Note**: User IDs may change between providers. If using `sub` claim (default), users may appear as new users. Consider mapping by email instead:
```bash
export OAUTH_CLAIM_USER_ID=email
```

---

## User Data Considerations

### Session Migration

Sessions cannot be migrated between authentication modes. All users will need to re-authenticate after switching modes.

### User Records

User records persist across auth mode changes. However:

- **User ID matching**: Ensure the claim used for user ID maps to existing users
- **Email-based matching**: Consider using email as user ID for consistency:
  ```bash
  export OAUTH_CLAIM_USER_ID=email
  ```

### API Keys and Tokens

- **JWT tokens**: Become invalid when switching modes
- **API keys**: Can be preserved if `API_KEY_ENABLED=true`

---

## Hybrid Authentication

For gradual migration, you can enable multiple authentication methods:

```bash
# Primary: OAuth
export AUTH_MODE=oauth
export OAUTH_PROVIDER=google
export OAUTH_CLIENT_ID=...

# Secondary: API keys for programmatic access
export API_KEY_ENABLED=true
export API_KEY=your-secure-api-key
```

This allows:
- Human users to authenticate via OAuth
- API clients to continue using API keys

---

## Zero-Downtime Migration

For production systems requiring zero downtime:

1. **Prepare new configuration**
   - Test in staging environment
   - Verify OAuth flow works

2. **Rolling deployment**
   - Deploy new configuration to one instance
   - Verify it works
   - Roll out to remaining instances

3. **Session handling**
   - Users with old sessions will be logged out
   - New sessions use OAuth

4. **Monitor**
   - Watch for authentication errors
   - Check user feedback

---

## Rollback Procedures

### Quick Rollback

If issues arise after migration:

```bash
# Revert to previous auth mode
export AUTH_MODE=open  # or basic

# Restart application
```

### Rollback with Data Preservation

1. Stop application
2. Revert environment variables
3. (Optional) Clear invalid sessions from database:
   ```sql
   DELETE FROM oauth_sessions WHERE provider = 'new-provider';
   ```
4. Start application

---

## Troubleshooting Migration Issues

### Users Can't Log In After Migration

1. Verify OAuth configuration is correct
2. Check provider application settings
3. Ensure redirect URI matches exactly
4. Check server logs for detailed errors

### Existing Users Not Recognized

1. Verify claim mapping:
   ```bash
   export OAUTH_CLAIM_USER_ID=email  # Try email instead of sub
   ```
2. Check if email addresses match between old and new auth

### API Clients Failing

1. Ensure API key authentication is enabled:
   ```bash
   export API_KEY_ENABLED=true
   ```
2. Update clients to use correct authentication method

### Session Issues

1. For multi-instance deployments, ensure PostgreSQL session storage:
   ```bash
   export OAUTH_SESSION_STORAGE=postgres
   ```
2. Clear any stale sessions:
   ```sql
   DELETE FROM oauth_sessions WHERE expires_at < NOW();
   ```

---

## Migration Checklist

Before migration:
- [ ] Identity provider configured
- [ ] OAuth application created with correct redirect URI
- [ ] Client credentials saved securely
- [ ] Staging environment tested
- [ ] Rollback plan documented
- [ ] Users notified of change

During migration:
- [ ] Environment variables updated
- [ ] Application deployed
- [ ] Authentication flow verified
- [ ] Monitor for errors

After migration:
- [ ] Verify all users can log in
- [ ] Check API access still works
- [ ] Review authentication logs
- [ ] Clean up old configuration
- [ ] Update documentation
