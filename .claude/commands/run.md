---
description: Start application with Docker Compose (all services)
---

Execute the run script to start all services:

```bash
./scripts/run.sh
```

This will start:
- MySQL database
- Spring Boot application
- Elasticsearch, Logstash, Kibana (ELK Stack)
- Prometheus, Grafana (Monitoring)

After starting:
1. Check service status
2. Report any failed services
3. Show access URLs for each service
