---
description: View Docker service logs
---

View logs for Docker services.

If a specific service is requested, show logs for that service only.
Otherwise, prompt the user which service logs to view:

Available services:
- app (Spring Boot application)
- mysql
- elasticsearch
- logstash
- kibana
- prometheus
- grafana

Commands:
```bash
# View logs for specific service (follow mode)
cd docker/compose && docker-compose logs -f <service-name>

# View last 100 lines
cd docker/compose && docker-compose logs --tail=100 <service-name>

# View all services
cd docker/compose && docker-compose logs
```

After showing logs:
- Highlight any ERROR or WARN messages
- Suggest next steps if issues are found