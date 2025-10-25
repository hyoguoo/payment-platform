---
description: Check Docker services status
---

Check the status of all running services:

```bash
cd docker/compose && docker-compose ps
```

After checking status:
1. Report which services are running (✅) and which are stopped (❌)
2. Show port mappings for running services
3. Provide service URLs:
   - Application: http://localhost:8080
   - Kibana: http://localhost:5601
   - Grafana: http://localhost:3000
   - Prometheus: http://localhost:9090

4. If any service is not running, suggest troubleshooting steps:
   - Check logs: `docker-compose logs <service-name>`
   - Restart service: `docker-compose restart <service-name>`