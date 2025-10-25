---
description: Run all tests with JaCoCo coverage
---

Execute Gradle test command to run all tests with JaCoCo coverage:

```bash
time ./gradlew test
```

After tests complete:
1. Report test results summary (passed/failed/skipped counts)
2. Report JaCoCo coverage results if available
3. Highlight any test failures with error details
4. If there are failures, suggest next steps (e.g., run single test with `./gradlew test --tests "ClassName"`)

Coverage report location: `build/reports/jacoco/test/html/index.html`

Notes:
- Tests use Testcontainers (requires Docker running)
- Environment variables loaded from `.env.secret` in root directory
- JaCoCo excludes: Q* (QueryDSL), dto, entity, exception, infrastructure, enums
