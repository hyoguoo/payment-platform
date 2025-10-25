---
description: Run specific test class or method
---

Run a single test class or method.

Ask the user which test to run if not specified in the request.

Commands:

For a single test class:
```bash
./gradlew test --tests "com.hyoguoo.paymentplatform.payment.domain.PaymentEventTest"
```

For a specific test method:
```bash
./gradlew test --tests "com.hyoguoo.paymentplatform.payment.domain.PaymentEventTest.testMethodName"
```

For pattern matching:
```bash
./gradlew test --tests "*PaymentEvent*"
```

After running:
1. Report test results (passed/failed)
2. Show execution time
3. If failed, display error details and suggest fixes
4. Provide coverage info if available

Tip: Use pattern matching with wildcards to run related tests together