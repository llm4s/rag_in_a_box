#!/usr/bin/env python3
"""
Example: Ingest all markdown/text files from a directory using the Python SDK.

This script demonstrates incremental ingestion:
- Only new or changed files are indexed
- Deleted files are automatically pruned

Usage:
    python ingest_directory.py /path/to/docs

Requirements:
    pip install ragbox-client
"""

import argparse
import hashlib
import sys
from pathlib import Path

# Add parent directory to path for development
sys.path.insert(0, str(Path(__file__).parent.parent))

from ragbox import RagBoxClient, Document


def file_to_document(file_path: Path, base_path: Path) -> Document:
    """Convert a file to a Document object."""
    content = file_path.read_text(encoding='utf-8', errors='replace')

    # Use relative path as document ID
    relative_path = file_path.relative_to(base_path)
    doc_id = str(relative_path).replace('/', '_').replace('\\', '_')

    return Document(
        id=doc_id,
        content=content,
        metadata={
            "filename": file_path.name,
            "path": str(relative_path),
            "extension": file_path.suffix,
        }
    )


def main():
    parser = argparse.ArgumentParser(
        description="Ingest files from a directory into RAG in a Box"
    )
    parser.add_argument(
        "directory",
        help="Directory containing files to ingest"
    )
    parser.add_argument(
        "--url",
        default="http://localhost:8080",
        help="RAG in a Box API URL (default: http://localhost:8080)"
    )
    parser.add_argument(
        "--extensions",
        default=".md,.txt,.rst",
        help="Comma-separated file extensions to include (default: .md,.txt,.rst)"
    )
    parser.add_argument(
        "--recursive",
        action="store_true",
        default=True,
        help="Recursively scan subdirectories (default: true)"
    )
    parser.add_argument(
        "--no-prune",
        action="store_true",
        help="Don't prune documents not found in directory"
    )
    args = parser.parse_args()

    # Parse extensions
    extensions = set(ext.strip() for ext in args.extensions.split(','))
    if not all(ext.startswith('.') for ext in extensions):
        extensions = {f'.{ext}' if not ext.startswith('.') else ext for ext in extensions}

    # Find files
    base_path = Path(args.directory).resolve()
    if not base_path.exists():
        print(f"Error: Directory not found: {base_path}")
        sys.exit(1)

    pattern = "**/*" if args.recursive else "*"
    files = [
        f for f in base_path.glob(pattern)
        if f.is_file() and f.suffix in extensions
    ]

    if not files:
        print(f"No files found matching extensions: {extensions}")
        sys.exit(0)

    print(f"Found {len(files)} files to process")
    print(f"Connecting to: {args.url}")

    # Connect to RAG in a Box
    client = RagBoxClient(args.url)

    # Check health
    try:
        health = client.health()
        print(f"Server status: {health['status']}")
    except Exception as e:
        print(f"Error connecting to server: {e}")
        sys.exit(1)

    # Ingest files
    doc_ids = []
    stats = {"created": 0, "updated": 0, "unchanged": 0, "failed": 0}

    for file_path in files:
        try:
            doc = file_to_document(file_path, base_path)
            result = client.upsert(doc)
            doc_ids.append(doc.id)
            stats[result.action] = stats.get(result.action, 0) + 1

            if result.action != "unchanged":
                print(f"  [{result.action}] {doc.id}")
        except Exception as e:
            print(f"  [error] {file_path}: {e}")
            stats["failed"] += 1

    # Print summary
    print(f"\nIngestion complete:")
    print(f"  Created:   {stats['created']}")
    print(f"  Updated:   {stats['updated']}")
    print(f"  Unchanged: {stats['unchanged']}")
    print(f"  Failed:    {stats['failed']}")

    # Prune deleted files
    if not args.no_prune:
        print("\nSyncing and pruning orphaned documents...")
        sync_result = client.sync(keep_ids=doc_ids)
        pruned = sync_result.get('prunedCount', 0)
        print(f"  Pruned: {pruned} documents")

    # Final stats
    final_status = client.sync_status()
    print(f"\nFinal state:")
    print(f"  Documents: {final_status.document_count}")
    print(f"  Chunks:    {final_status.chunk_count}")


if __name__ == "__main__":
    main()
