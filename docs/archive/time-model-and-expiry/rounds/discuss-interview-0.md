# discuss-interview-0 — TIME-MODEL-AND-EXPIRY

## Ambiguity Ledger (4트랙)

### scope (범위)
- **확정**: 시간 추상화를 4서비스(payment / pg / product / user) 전부에 적용. user는 직접 호출 0건이라 사실상 무변경.
- **확정**: 적용 깊이 — 도메인 내부 직접 `now()` 호출까지 전부 제거(pg 도메인 16곳, product 4곳, pg Toss 전략 1곳, payment 포트 구현 1곳).
- **non-goal**: 만료 메커니즘 자체(스케줄러 구조, 두 스케줄러 연쇄)는 변경하지 않는다 — 현행 동작을 의도로 확정·문서화만.
- **non-goal**: 만료 대상 상태 확장(IN_PROGRESS 직접 만료 등)은 하지 않는다.

### constraints (제약)
- **확정**: 시간 표준 = **Clock + Instant**. payment의 `LocalDateTimeProvider` 포트를 JDK `Clock` 빈 주입으로 교체, 시각 타입을 `LocalDateTime` → `Instant`로 수렴.
- **확정**: 만료 임계 `EXPIRATION_MINUTES=30` 하드코딩을 환경 프로퍼티로 외부화, **기본값 30분 유지**.
- **확정**: 만료 스케줄러 프로퍼티 키 `scheduler.payment-status-sync.*`를 만료 의미에 맞게 정정. 기본값 보존하여 운영 yml 무중단.
- **트레이드오프(설계에서 해소 필요)**: payment 시각 컬럼이 MySQL `DATETIME(6)`(시간대 없음) + 엔티티 `LocalDateTime` 매핑. `Instant` 통일 시 (a) 엔티티 매핑을 `Instant`로 바꾸고 DB는 UTC 저장 규약 고정, (b) 컬럼 타입을 `TIMESTAMP`로 전환할지 여부 — DB 마이그레이션 비용·기존 데이터 해석 영향이 큼. plan에서 컬럼 전환 vs 매핑만 전환 결정.

### outputs (산출물)
- `docs/topics/TIME-MODEL-AND-EXPIRY.md` 결정 사항 섹션
- 4서비스 시간 추상화 통일 코드 + 만료 임계 외부화 코드

### verification (검증) — Path 1 코드 기반 제안, 가정으로 기록
- **단위 테스트**: 고정 `Clock` 주입으로 시간 의존 도메인/서비스 테스트 결정성 확보. 만료 임계 경계(29분 vs 31분), 만료 전이 가드(READY만 허용) 파라미터 테스트.
- **통합 테스트**: 기존 회귀(`./gradlew test`) 무손상 확인. 새 통합 시나리오는 불필요(메커니즘 무변경).
- **k6**: 불필요 — 측정 무관 작업.

## 3-Path Routing 기록
- Path 1 (code): EXPIRED 사용처/스케줄러/임계 상수/시각 컬럼 타입 조사 (다수)
- Path 2 (user): 시간 표준 / 적용 범위 / 임계 외부화 / 만료 대상 정책 — 4문항 AskUserQuestion
- Dialectic Rhythm Guard: Path 1 누적 후 Path 2 묶음으로 사용자 판단 수렴 — 충족

## 확정된 가정
1. payment 포트 javadoc의 "UTC 기준"은 의도였고 현재 시스템 시간대 구현은 버그에 가깝다 → 표준화 시 UTC 고정.
2. 만료 임계 30분은 운영 조정값으로 본다(외부화 대상). 비즈니스 불변이 아님.
3. "READY만 직접 만료 + IN_PROGRESS 정체분은 정합 스캐너가 READY 복원 후 만료"라는 두 스케줄러 연쇄는 의도된 정책으로 확정·문서화.
4. verification은 고정 Clock 단위테스트 + 기존 통합 회귀로 충분.

## 종료 조건 충족
- 4트랙(scope/constraints/outputs/verification) 모두 커버 ✓
- 핵심 가정 사용자 확인 거침(4문항) ✓
