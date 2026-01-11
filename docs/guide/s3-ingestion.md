---
layout: page
title: S3 Ingestion
parent: User Guide
nav_order: 3
---

# S3 Ingestion
{: .no_toc }

Ingest documents from Amazon S3 with multi-format support.
{: .fs-6 .fw-300 }

## Table of Contents
{: .no_toc .text-delta }

1. TOC
{:toc}

---

## Overview

S3 ingestion allows you to automatically pull documents from AWS S3 buckets for indexing. It supports:

- **Multi-format extraction**: PDF, DOCX, DOC, HTML, Markdown, and more
- **Incremental sync**: Only re-index changed files (via S3 ETags)
- **Flexible authentication**: IAM roles, environment variables, or explicit credentials
- **Pattern filtering**: Include only specific file types

## Supported File Formats

The S3 ingester extracts text from a variety of document formats:

| Format | Extensions | Extraction |
|--------|------------|------------|
| **PDF** | `.pdf` | Apache PDFBox |
| **Word (Modern)** | `.docx` | Apache POI |
| **Word (Legacy)** | `.doc` | Apache Tika |
| **Markdown** | `.md`, `.markdown` | Native |
| **Plain Text** | `.txt` | Native |
| **HTML** | `.html`, `.htm` | Tika |
| **JSON** | `.json` | Native |
| **XML** | `.xml` | Native |
| **CSV** | `.csv` | Native |
| **RST** | `.rst` | Native |

## Quick Start

### Basic Configuration

Add an S3 source to your `application.conf`:

```hocon
ingestion {
  enabled = true
  run-on-startup = true

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

### With Prefix Filtering

Target a specific folder in your bucket:

```hocon
{
  type = "s3"
  name = "engineering-docs"
  bucket = "company-data"
  prefix = "docs/engineering/"
  region = "us-west-2"
}
```

## Authentication Methods

S3 ingestion uses the AWS credential chain by default:

1. **Environment variables**: `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`
2. **System properties**: `aws.accessKeyId`, `aws.secretAccessKey`
3. **AWS credentials file**: `~/.aws/credentials`
4. **IAM role**: For EC2, Lambda, or EKS

### Explicit Credentials (Not Recommended for Production)

```hocon
{
  type = "s3"
  name = "external-bucket"
  bucket = "partner-docs"
  region = "eu-west-1"
  access-key-id = "AKIAIOSFODNN7EXAMPLE"
  secret-access-key = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
}
```

{: .warning }
> Prefer IAM roles or environment variables over hardcoded credentials.

### Environment Variables (Recommended)

```bash
export AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE
export AWS_SECRET_ACCESS_KEY=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY
export AWS_REGION=us-east-1
```

## Configuration Reference

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `type` | string | - | Must be `"s3"` |
| `name` | string | - | Unique name for this source |
| `bucket` | string | - | S3 bucket name (required) |
| `prefix` | string | `""` | Key prefix to filter objects |
| `region` | string | `"us-east-1"` | AWS region |
| `patterns` | list | See below | File patterns to include |
| `access-key-id` | string | - | AWS access key (optional) |
| `secret-access-key` | string | - | AWS secret key (optional) |
| `enabled` | boolean | `true` | Whether this source is active |
| `metadata` | object | `{}` | Additional metadata for documents |

### Default Patterns

If not specified, the following patterns are used:
```hocon
patterns = ["*.md", "*.txt", "*.pdf", "*.docx", "*.doc", "*.json", "*.xml", "*.html", "*.csv"]
```

## Full Example

```hocon
ingestion {
  enabled = true
  run-on-startup = true
  schedule = "0 0 * * *"  # Daily at midnight

  sources = [
    {
      type = "s3"
      name = "engineering-docs"
      bucket = "company-documentation"
      prefix = "docs/engineering/"
      region = "us-west-2"
      patterns = ["*.md", "*.pdf", "*.docx"]
      metadata {
        department = "engineering"
        classification = "internal"
      }
    },
    {
      type = "s3"
      name = "legal-contracts"
      bucket = "legal-documents"
      prefix = "contracts/2024/"
      region = "us-east-1"
      patterns = ["*.pdf", "*.docx"]
      metadata {
        department = "legal"
        confidential = "true"
      }
    }
  ]
}
```

## Manual Trigger

Trigger S3 ingestion via the API:

```bash
# Trigger all sources
curl -X POST "http://localhost:8080/api/v1/ingestion/run"

# Trigger specific source
curl -X POST "http://localhost:8080/api/v1/ingestion/run/engineering-docs"
```

## Monitoring

Check ingestion status:

```bash
curl "http://localhost:8080/api/v1/ingestion/status"
```

Response:
```json
{
  "running": false,
  "lastRun": "2024-01-15T00:00:00Z",
  "lastResults": [
    {
      "sourceName": "engineering-docs",
      "sourceType": "s3",
      "documentsAdded": 25,
      "documentsUpdated": 3,
      "documentsDeleted": 1,
      "documentsUnchanged": 150,
      "documentsFailed": 0,
      "durationMs": 45000
    }
  ],
  "nextScheduledRun": "2024-01-16T00:00:00Z"
}
```

## Best Practices

### Use Prefixes for Large Buckets

If your bucket contains millions of objects, use prefixes to limit scope:

```hocon
prefix = "docs/published/2024/"
```

### Set Up Lifecycle Policies

Configure S3 lifecycle rules to archive old versions and manage storage costs.

### Use IAM Roles in Production

For EKS deployments, use [IAM Roles for Service Accounts (IRSA)](https://docs.aws.amazon.com/eks/latest/userguide/iam-roles-for-service-accounts.html):

```yaml
# In your Helm values.yaml
serviceAccount:
  annotations:
    eks.amazonaws.com/role-arn: arn:aws:iam::123456789:role/ragbox-s3-access
```

### Monitor Object Sizes

Very large files (>100MB) may cause memory issues. Consider filtering or pre-processing large documents.

## Troubleshooting

### Access Denied Errors

Ensure your IAM user/role has the required permissions:

```json
{
  "Version": "2012-10-17",
  "Statement": [
    {
      "Effect": "Allow",
      "Action": [
        "s3:GetObject",
        "s3:ListBucket",
        "s3:HeadObject"
      ],
      "Resource": [
        "arn:aws:s3:::your-bucket",
        "arn:aws:s3:::your-bucket/*"
      ]
    }
  ]
}
```

### Document Extraction Failures

If specific documents fail to extract:

1. Check file format is supported
2. Ensure file is not corrupted or password-protected
3. Check server logs for extraction errors

### Slow Ingestion

For large datasets:

1. Use prefixes to narrow scope
2. Consider scheduling during off-peak hours
3. Monitor memory usage and adjust heap size if needed
