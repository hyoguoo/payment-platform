package com.hyoguoo.paymentplatform.buildtools.archunit;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

import com.tngtech.archunit.lang.ArchRule;

/**
 * 4개 비즈니스 서비스(payment / pg / product / user)가 공유하는 hexagonal 레이어 규칙.
 *
 * <p>패키지 패턴을 서비스 이름 없이 상대 표기(`..domain..`)로 쓰기 때문에 규칙 정의는 한 벌만
 * 두고 각 서비스의 {@code ArchitectureTest} 가 자기 루트 패키지를 대상으로 재사용한다. 규칙을
 * 서비스마다 복제하면 시간이 지나며 서로 갈라지므로 이 클래스가 유일한 정의처다.
 *
 * <p>규칙 추가 기준: 실제 코드에서 위반이 0이거나, 위반을 전부 정리한 뒤에만 켠다. 정당한 코드를
 * 지적하는 규칙이 하나라도 섞이면 규칙 세트 전체가 무시되기 시작한다.
 *
 * <p>어노테이션은 타입 대신 FQCN 문자열로 지정한다 — 이 모듈이 Spring / Feign 에 컴파일 의존하지
 * 않게 하기 위함이다.
 */
public final class HexagonalRules {

    private static final String TRANSACTIONAL = "org.springframework.transaction.annotation.Transactional";
    private static final String FEIGN_CLIENT = "org.springframework.cloud.openfeign.FeignClient";

    /** 도메인은 프레임워크를 모른다 — 순수 자바로만 표현되어야 단위 테스트와 이식이 쉬워진다. */
    public static final ArchRule DOMAIN_IS_FRAMEWORK_FREE = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework..",
                    "jakarta.persistence..",
                    "javax.persistence..")
            .because("도메인이 프레임워크에 묶이면 규칙 검증이 컨테이너 없이는 불가능해진다");

    /** 의존은 안쪽으로만 흐른다 — 도메인이 바깥 레이어를 올려다보면 방향이 뒤집힌다. */
    public static final ArchRule DOMAIN_DOES_NOT_DEPEND_ON_OUTER_LAYERS = noClasses()
            .that().resideInAPackage("..domain..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..application..",
                    "..infrastructure..",
                    "..presentation..")
            .because("도메인이 필요로 하는 타입은 도메인 안에 있어야 한다");

    /** 표현 계층은 포트만 본다 — 어댑터 구현을 직접 잡으면 교체가 막힌다. */
    public static final ArchRule PRESENTATION_DOES_NOT_DEPEND_ON_INFRASTRUCTURE = noClasses()
            .that().resideInAPackage("..presentation..")
            .should().dependOnClassesThat().resideInAPackage("..infrastructure..")
            .because("컨트롤러는 포트 인터페이스를 통해서만 안쪽에 닿아야 한다");

    /**
     * 트랜잭션 경계는 표현 계층과 도메인에 두지 않는다.
     *
     * <p>application 만 허용하는 형태로 좁히지 않는 이유: outbox 상태 전이나 SKIP LOCKED 조회처럼
     * 저장소 구현이 스스로 경계를 여는 것이 정당한 자리가 실제로 존재한다. 그쪽까지 막으면 규칙이
     * 정당한 코드를 지적하게 된다.
     */
    public static final ArchRule TRANSACTIONAL_NOT_ON_PRESENTATION_OR_DOMAIN_CLASSES = noClasses()
            .that().resideInAnyPackage("..presentation..", "..domain..")
            .should().beAnnotatedWith(TRANSACTIONAL)
            .because("트랜잭션 경계는 유스케이스나 저장소 구현이 연다");

    public static final ArchRule TRANSACTIONAL_NOT_ON_PRESENTATION_OR_DOMAIN_METHODS = noMethods()
            .that().areDeclaredInClassesThat().resideInAnyPackage("..presentation..", "..domain..")
            .should().beAnnotatedWith(TRANSACTIONAL)
            .because("트랜잭션 경계는 유스케이스나 저장소 구현이 연다");

    /** 외부 벤더 호출 통로는 어댑터 안에만 존재한다. */
    public static final ArchRule FEIGN_CLIENTS_LIVE_IN_INFRASTRUCTURE = classes()
            .that().areAnnotatedWith(FEIGN_CLIENT)
            .should().resideInAPackage("..infrastructure..")
            .allowEmptyShould(true)
            .because("외부 연동 세부는 안쪽 레이어로 새면 안 된다");

    /** 포트는 구현이 아니라 계약이다. */
    public static final ArchRule PORT_NAMED_TYPES_ARE_INTERFACES = classes()
            .that().haveSimpleNameEndingWith("Port")
            .should().beInterfaces()
            .allowEmptyShould(true)
            .because("포트가 클래스면 어댑터 교체 지점이 사라진다");

    private HexagonalRules() {
    }
}
