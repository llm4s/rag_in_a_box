# S3 Ingestion Guide

This guide covers configuring document ingestion from AWS S3 buckets in RAG in a Box.

## Overview

S3 ingestion allows you to automatically pull documents from AWS S3 buckets for indexing. It supports:

- **Standard S3 access** via AWS credentials or IAM roles
- **Cross-account access** via role assumption
- **Pattern-based filtering** to select specific file types
- **Prefix filtering** to target specific bucket paths

> **Important**: S3 ingestion only supports **text-based file formats** (markdown, plain text, JSON, XML, HTML, CSV, etc.). Binary formats like PDF, DOCX, images, and archives are **not supported** and will produce garbage content if included. For binary format support, use the directory ingestion source with local files.

---

## Quick Start

### Basic Configuration

Add an S3 source to your `application.conf`:

```hocon
ingestion {
  enabled = true
  sources = [
    {
      type = "s3"
      name = "company-docs"
      bucket = "my-document-bucket"
      region = "us-east-1"
    }
  ]
}
```

### Environment Variables

Alternatively, configure via environment variables for credentials (recommended for production):

```bash
export AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE
export AWS_SECRET_ACCESS_KEY=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY
export AWS_REGION=us-east-1
```

---

## Configuration Reference

### S3 Source Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `type` | string | - | Must be `"s3"` |
| `name` | string | - | Unique name for this source |
| `bucket` | string | - | S3 bucket name (required) |
| `prefix` | string | `""` | Key prefix to filter objects |
| `region` | string | `"us-east-1"` | AWS region |
| `patterns` | list | `["*.md", "*.txt", "*.json", "*.xml", "*.html", "*.csv"]` | File patterns to include (text formats only) |
| `max-keys` | int | `1000` | Max objects per pagination request |
| `access-key-id` | string | - | AWS access key (optional) |
| `secret-access-key` | string | - | AWS secret key (optional) |
| `role-arn` | string | - | IAM role ARN for cross-account access |
| `enabled` | boolean | `true` | Whether this source is active |
| `metadata` | object | `{}` | Additional metadata for documents |

### Full Example

```hocon
ingestion {
  enabled = true
  run-on-startup = true
  schedule = "0 0 * * *"  # Daily at midnight

  sources = [
    {
      type = "s3"
      name = "engineering-docs"
      bucket = "engineering-documentation"
      prefix = "docs/published/"
      region = "us-west-2"
      patterns = ["*.md", "*.txt", "*.rst", "*.html"]  # Text formats only
      max-keys = 500
      metadata {
        department = "engineering"
        classification = "internal"
      }
    }
  ]
}
```

---

## Authentication Methods

S3 ingestion supports multiple authentication methods. The system uses the following priority:

1. **Explicit credentials** (access-key-id/secret-access-key in config)
2. **Role assumption** (role-arn in config)
3. **AWS default credential chain** (environment, config files, IAM roles)

### Method 1: Explicit Credentials

Specify credentials directly in configuration:

```hocon
{
  type = "s3"
  name = "s3-source"
  bucket = "my-bucket"
  access-key-id = "AKIAIOSFODNN7EXAMPLE"
  secret-access-key = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
}
```

> **Warning**: Avoid committing credentials to version control. Use environment variables or secret management for production.

### Method 2: Environment Variables

Use AWS standard environment variables:

```bash
export AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE
export AWS_SECRET_ACCESS_KEY=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY
export AWS_REGION=us-east-1
```

### Method 3: IAM Instance Role

When running on EC2, ECS, or EKS, use IAM instance/task roles:

1. Create an IAM role with S3 read permissions
2. Attach the role to your instance/task
3. No credentials needed in configuration

```hocon
{
  type = "s3"
  name = "s3-source"
  bucket = "my-bucket"
  # No credentials needed - uses instance role
}
```

### Method 4: Cross-Account Role Assumption

For accessing buckets in other AWS accounts:

```hocon
{
  type = "s3"
  name = "cross-account-docs"
  bucket = "other-account-bucket"
  role-arn = "arn:aws:iam::123456789012:role/s3-reader-role"
}
```

The role must:
- Trust your source account/role
- Have S3 read permissions on the target bucket

---

## IAM Permissions

### Minimum Required Permissions

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "s3:GetObject",
        "s3:ListBucket"
      ],
      "Resource": [
        "arn:aws:s3:::my-document-bucket",
        "arn:aws:s3:::my-document-bucket/*"
      ]
    }
  ]
}
```

### Cross-Account Trust Policy

For the target role in cross-account access:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Principal": {
        "AWS": "arn:aws:iam::SOURCE_ACCOUNT:role/ragbox-role"
      },
      "Action": "sts:AssumeRole"
    }
  ]
}
```

---

## Filtering Documents

### By Prefix

Target specific paths in the bucket:

```hocon
{
  type = "s3"
  bucket = "docs-bucket"
  prefix = "published/2024/"  # Only objects under this path
}
```

### By File Type

Use patterns to filter by extension:

```hocon
{
  type = "s3"
  bucket = "docs-bucket"
  patterns = ["*.md", "*.txt"]  # Only markdown and text files
}
```

### Combining Filters

```hocon
{
  type = "s3"
  bucket = "docs-bucket"
  prefix = "engineering/"
  patterns = ["*.md", "*.txt", "*.html"]  # Engineering docs, text files only
}
```

---

## Document Metadata

S3-ingested documents automatically include metadata:

| Metadata Key | Description |
|--------------|-------------|
| `source` | Source name from configuration |
| `source_type` | Always `"s3"` |
| `s3_bucket` | S3 bucket name |
| `s3_key` | Full object key |
| `s3_size` | Object size in bytes |
| `s3_last_modified` | Last modification timestamp |

You can add custom metadata:

```hocon
{
  type = "s3"
  bucket = "docs-bucket"
  metadata {
    department = "engineering"
    access_level = "internal"
  }
}
```

---

## Scheduling

### Run on Startup

```hocon
ingestion {
  enabled = true
  run-on-startup = true
  sources = [...]
}
```

### Cron Schedule

```hocon
ingestion {
  enabled = true
  schedule = "0 */6 * * *"  # Every 6 hours
  sources = [...]
}
```

### Manual Trigger

Trigger ingestion via API:

```bash
# Run all sources
curl -X POST http://localhost:8080/api/v1/ingest/run

# Run specific source
curl -X POST http://localhost:8080/api/v1/ingest/run/engineering-docs
```

---

## Monitoring

### Check Ingestion Status

```bash
curl http://localhost:8080/api/v1/ingest/status
```

Response:

```json
{
  "running": false,
  "lastRun": "2024-01-15T10:30:00Z",
  "lastResults": [
    {
      "sourceName": "engineering-docs",
      "sourceType": "s3",
      "documentsAdded": 45,
      "documentsUpdated": 0,
      "documentsFailed": 2,
      "durationMs": 15234
    }
  ]
}
```

### Logs

S3 ingestion logs key events:

```
INFO  Ingestion started for source 'engineering-docs'
INFO  Listed 150 objects from s3://engineering-documentation/docs/
INFO  Processing document: docs/published/guide.md
INFO  Ingestion completed: added=45, failed=2
```

---

## Troubleshooting

### Common Issues

**"Access Denied" errors**
- Verify IAM permissions include `s3:GetObject` and `s3:ListBucket`
- Check bucket policy allows access from your account/role
- Ensure the bucket name is correct (case-sensitive)

**No documents ingested**
- Check `prefix` matches actual object paths
- Verify `patterns` include the file extensions you want
- Ensure objects exist in the bucket

**Credential errors**
- Verify credentials are valid and not expired
- Check environment variables are set correctly
- For role assumption, verify trust policy allows your identity

### Debug Mode

Enable debug logging:

```bash
export LOG_LEVEL=DEBUG
```

---

## Limitations

### Text-Only Format Support

S3 ingestion reads files as UTF-8 text. This means:

- **Supported formats**: `.md`, `.txt`, `.json`, `.xml`, `.html`, `.csv`, `.rst`, `.yaml`, `.yml`, `.log`
- **Not supported**: `.pdf`, `.docx`, `.xlsx`, `.pptx`, `.png`, `.jpg`, `.zip`, and other binary formats

If you need to ingest binary formats like PDFs:
1. Download files locally first
2. Use directory ingestion with the DirectoryLoader, which supports binary format parsing

### No Incremental Sync

S3 ingestion currently performs full ingestion on each run (not incremental sync). All matched files are re-processed. For large buckets, consider:
- Using prefix filtering to limit scope
- Running ingestion less frequently
- Splitting into multiple sources by prefix

---

## Best Practices

1. **Use IAM roles** instead of explicit credentials when possible
2. **Limit permissions** to read-only access on specific buckets
3. **Use prefix filtering** to avoid indexing unnecessary objects
4. **Use text-format patterns only** - avoid binary formats like PDF, DOCX
5. **Monitor ingestion** status to catch failures early
6. **Use metadata** to tag documents for filtering and analytics

---

## Examples

### Corporate Document Repository

```hocon
ingestion {
  enabled = true
  schedule = "0 6 * * *"  # Daily at 6 AM

  sources = [
    {
      type = "s3"
      name = "hr-policies"
      bucket = "corporate-docs"
      prefix = "hr/policies/"
      region = "us-east-1"
      patterns = ["*.md", "*.txt", "*.html"]  # Text formats only
      metadata {
        department = "hr"
        document_type = "policy"
      }
    },
    {
      type = "s3"
      name = "engineering-wiki"
      bucket = "corporate-docs"
      prefix = "engineering/wiki/"
      patterns = ["*.md"]
      metadata {
        department = "engineering"
        document_type = "wiki"
      }
    }
  ]
}
```

### Multi-Account Setup

```hocon
ingestion {
  enabled = true

  sources = [
    {
      type = "s3"
      name = "production-logs"
      bucket = "prod-logs-bucket"
      role-arn = "arn:aws:iam::111111111111:role/log-reader"
      prefix = "app-logs/"
      patterns = ["*.log", "*.txt"]
    },
    {
      type = "s3"
      name = "staging-logs"
      bucket = "staging-logs-bucket"
      role-arn = "arn:aws:iam::222222222222:role/log-reader"
      prefix = "app-logs/"
      patterns = ["*.log", "*.txt"]
    }
  ]
}
```
