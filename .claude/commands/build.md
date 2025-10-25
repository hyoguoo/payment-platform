---
description: Build executable JAR file
---

Build the project without running tests:

```bash
./gradlew clean bootJar
```

After build completes:
1. Confirm JAR file location in `build/libs/`
2. Display JAR file name and size
3. Suggest next steps (e.g., running with `java -jar` or using Docker)
