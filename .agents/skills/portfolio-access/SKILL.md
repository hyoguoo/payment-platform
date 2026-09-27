---
name: portfolio-access
description: 별도 blog 저장소의 payment-platform 포트폴리오 페이지를 찾고 확인·수정할 때 사용한다. 이 저장소의 결제 UI나 일반 index.html 수정에는 사용하지 않는다.
---

# 포트폴리오 접근

포트폴리오는 별도 Astro blog 저장소에 있다. 현재 저장소 기준 `../../notes/blog` 또는 사용자가
제공한 위치를 확인한다. 없으면 알려진 상위 저장소 디렉토리 안에서 `rg --files`로 페이지를
찾고, 여전히 없으면 시도한 경로를 알린 뒤 위치를 요청한다.

blog의 적용 지침과 Git 상태를 먼저 확인한다. 다음 구조를 실제 파일과 대조한다.

| 역할 | blog 기준 경로 |
|---|---|
| 페이지 셸 | `src/pages/payment-platform-portfolio/index.astro` |
| 콘텐츠 | `src/data/paymentPortfolio/*.ts` |
| 스타일 | `src/styles/payment-portfolio.css` |
| 렌더·상호작용 | `src/scripts/portfolio/*.ts` |

문구·수치 수정은 콘텐츠 모듈을 중심으로 수행한다. 디자인·동작 변경이 요청되었으면 해당
스타일과 렌더 경로까지 수정한다. 긴 산문은 기존 페이지의 명사형 불릿 톤에 맞춰 간결하게 쓴다.
데이터의 줄바꿈은 실제 렌더가 `escBr`를 쓰는지 확인하고 적용한다. 사용자 입력 이스케이프를
제거하지 않는다. 검증된 사실·수치를 유지하고 외부 이미지·폰트·스크립트 추가는 요청과 맞는지 확인한다.

blog의 실제 package scripts에 따라 빌드하고, 화면 변경은 사용 가능한 브라우저에서 확인한다.
개발 전용 도구는 공개되는 `public` 아래에 넣지 않는다. 완료 보고는 blog에서 바뀐 파일과
검증 결과를 전달한다. 게시까지 요청되었으면 해당 저장소의 배포 절차로 이어간다.
