---
description: 애플리케이션을 Docker Compose로 실행합니다 (모든 서비스 포함)
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
