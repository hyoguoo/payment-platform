---
description: 빌드 산출물과 Docker 환경을 정리합니다
---

Clean the project and Docker environments:

1. Clean Gradle build artifacts:
```bash
./gradlew clean
```

2. Stop and remove Docker containers (local):
```bash
cd docker/local && docker-compose down -v
```

3. Stop and remove Docker containers (full-stack):
```bash
cd docker/full-stack && docker-compose down -v
```

After cleaning, confirm all resources are removed and provide status summary.
