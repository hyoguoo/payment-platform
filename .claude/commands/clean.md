---
description: Clean build artifacts and Docker environment
---

Clean the project and Docker environments:

1. Clean Gradle build artifacts:
```bash
./gradlew clean
```

2. Stop and remove Docker containers (from compose directory):
```bash
cd docker/compose && docker-compose down -v
```

3. Verify cleanup:
```bash
docker ps -a | grep payment
```

After cleaning:
- Confirm Gradle build directory is removed
- Confirm all Docker containers are stopped and removed
- Report remaining Docker volumes if any
- Provide status summary
