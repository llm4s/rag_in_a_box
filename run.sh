#!/bin/bash
# Run RAG in a Box with environment from .env file

# Load environment from .env if it exists
if [ -f .env ]; then
    echo "Loading environment from .env..."
    set -a
    source .env
    set +a
fi

# Check for API key
if [ -z "$OPENAI_API_KEY" ] && [ -z "$OPEN_AI_KEY" ]; then
    echo "Warning: No OpenAI API key found. Set OPENAI_API_KEY in .env"
fi

# Export OPEN_AI_KEY as OPENAI_API_KEY if only OPEN_AI_KEY is set
if [ -n "$OPEN_AI_KEY" ] && [ -z "$OPENAI_API_KEY" ]; then
    export OPENAI_API_KEY="$OPEN_AI_KEY"
fi

echo "Starting RAG in a Box..."
sbt run
