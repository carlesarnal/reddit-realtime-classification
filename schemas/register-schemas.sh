#!/bin/bash
set -e

REGISTRY_URL="${REGISTRY_URL:-http://localhost:8080}"
GROUP="reddit-realtime"

echo "Registering schemas with Apicurio Registry at $REGISTRY_URL..."

# Register reddit-stream value schema
curl -s -X POST "$REGISTRY_URL/apis/registry/v3/groups/$GROUP/artifacts" \
  -H "Content-Type: application/json" \
  -d @"$(dirname "$0")/reddit-stream-value.json"
echo ""
echo "Registered: reddit-stream-value"

# Register kafka-predictions value schema
curl -s -X POST "$REGISTRY_URL/apis/registry/v3/groups/$GROUP/artifacts" \
  -H "Content-Type: application/json" \
  -d @"$(dirname "$0")/kafka-predictions-value.json"
echo ""
echo "Registered: kafka-predictions-value"

echo "All schemas registered successfully."
