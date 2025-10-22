---
description: Port-Adapter 패턴 준수 여부와 아키텍처 품질을 검토합니다
---

Review the project architecture with focus on:

1. **Port-Adapter Pattern Compliance**
   - Check if external dependencies are properly abstracted through Ports
   - Verify Infrastructure implements Ports correctly
   - Ensure Domain layer has no external technology dependencies

2. **Dependency Direction**
   - Verify dependencies flow inward (Presentation → Application → Domain)
   - Check that Infrastructure depends on Application (not vice versa)

3. **Package Structure**
   - ServiceImpl: orchestrates multiple use cases
   - UseCase: single responsibility units
   - Port: interface abstractions for external dependencies
   - Domain: pure business logic

4. **Naming Conventions**
   - *ServiceImpl for services
   - *UseCase for use cases
   - *Port for external abstractions
   - *Repository (interface) and *RepositoryImpl (implementation)

Search key packages and analyze structure, then provide a summary report with any violations or recommendations.
