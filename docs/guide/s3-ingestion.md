---
layout: page
title: S3 Ingestion
parent: User Guide
nav_order: 6
---

# S3 Ingestion Guide

Ingest documents from AWS S3 buckets into RAG in a Box.

## Overview

S3 ingestion allows you to automatically pull documents from AWS S3 buckets for indexing. It supports:

- **Standard S3 access** via AWS credentials or IAM roles
- **Cross-account access** via role assumption
- **Pattern-based filtering** to select specific file types
- **Prefix filtering** to target specific bucket paths

## Quick Start

Add an S3 source to your configuration:

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

## Configuration Options

| Option | Type | Default | Description |
|--------|------|---------|-------------|
| `type` | string | - | Must be `"s3"` |
| `name` | string | - | Unique name for this source |
| `bucket` | string | - | S3 bucket name (required) |
| `prefix` | string | `""` | Key prefix to filter objects |
| `region` | string | `"us-east-1"` | AWS region |
| `patterns` | list | `["*.md", "*.txt", "*.pdf"]` | File patterns to include |
| `max-keys` | int | `1000` | Max objects per request |
| `access-key-id` | string | - | AWS access key (optional) |
| `secret-access-key` | string | - | AWS secret key (optional) |
| `role-arn` | string | - | IAM role for cross-account |
| `enabled` | boolean | `true` | Enable this source |

## Authentication Methods

### Method 1: Environment Variables (Recommended)

```bash
export AWS_ACCESS_KEY_ID=AKIAIOSFODNN7EXAMPLE
export AWS_SECRET_ACCESS_KEY=wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY
export AWS_REGION=us-east-1
```

### Method 2: IAM Instance Role

When running on EC2, ECS, or EKS, use IAM instance/task roles. No credentials needed in configuration.

### Method 3: Cross-Account Role Assumption

```hocon
{
  type = "s3"
  name = "cross-account-docs"
  bucket = "other-account-bucket"
  role-arn = "arn:aws:iam::123456789012:role/s3-reader-role"
}
```

## IAM Permissions

Minimum required permissions:

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

## Filtering Documents

### By Prefix

```hocon
{
  type = "s3"
  bucket = "docs-bucket"
  prefix = "published/2024/"
}
```

### By File Type

```hocon
{
  type = "s3"
  bucket = "docs-bucket"
  patterns = ["*.md", "*.txt"]
}
```

## Document Metadata

S3-ingested documents automatically include metadata:

| Metadata Key | Description |
|--------------|-------------|
| `source` | Source name |
| `source_type` | Always `"s3"` |
| `s3_bucket` | Bucket name |
| `s3_key` | Full object key |
| `s3_size` | Object size |
| `s3_last_modified` | Last modification |

## Scheduling

### Run on Startup

```hocon
ingestion {
  enabled = true
  run-on-startup = true
}
```

### Cron Schedule

```hocon
ingestion {
  enabled = true
  schedule = "0 */6 * * *"  # Every 6 hours
}
```

### Manual Trigger

```bash
curl -X POST http://localhost:8080/api/v1/ingest/run
```

## Troubleshooting

**"Access Denied" errors**
- Verify IAM permissions include `s3:GetObject` and `s3:ListBucket`
- Check bucket policy allows access

**No documents ingested**
- Verify `prefix` matches actual paths
- Check `patterns` include your file extensions

**Credential errors**
- Verify credentials are valid
- Check environment variables are set
