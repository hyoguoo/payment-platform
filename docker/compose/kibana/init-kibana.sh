#!/bin/bash
set -e

echo "Waiting for Kibana to be ready..."

# Wait for Kibana to be healthy
until curl -s -f "http://kibana:5601/api/status" > /dev/null 2>&1; do
  echo "Kibana is unavailable - sleeping"
  sleep 5
done

echo "Kibana is up - importing saved objects"

# Import saved objects
curl -X POST "http://kibana:5601/api/saved_objects/_import?overwrite=true" \
  -H "kbn-xsrf: true" \
  --form file=@/usr/share/kibana/saved_objects/payment-platform.ndjson

echo "Kibana initialization completed!"
