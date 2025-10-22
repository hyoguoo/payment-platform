---
description: 프로젝트를 빌드하여 실행 가능한 JAR 파일을 생성합니다
---

Build the project without running tests:

```bash
./gradlew clean bootJar
```

After build completes:
1. Confirm JAR file location in `build/libs/`
2. Display JAR file name and size
3. Suggest next steps (e.g., running with `java -jar` or using Docker)
