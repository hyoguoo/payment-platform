package com.hyoguoo.paymentplatform.product;

import com.hyoguoo.paymentplatform.buildtools.archunit.HexagonalRules;
import com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * hexagonal 레이어 규칙 검증. 규칙 본문은
 * {@link com.hyoguoo.paymentplatform.buildtools.archunit.HexagonalRules} 한 곳에만 있고
 * 이 클래스는 검사 대상 패키지만 지정한다 — 규칙을 고칠 때는 그쪽만 고친다.
 */
@AnalyzeClasses(
        packages = "com.hyoguoo.paymentplatform.product",
        importOptions = DoNotIncludeTests.class
)
class ArchitectureTest {

    @ArchTest
    static final ArchRule DOMAIN_IS_FRAMEWORK_FREE =
            HexagonalRules.DOMAIN_IS_FRAMEWORK_FREE;

    @ArchTest
    static final ArchRule DOMAIN_DOES_NOT_DEPEND_ON_OUTER_LAYERS =
            HexagonalRules.DOMAIN_DOES_NOT_DEPEND_ON_OUTER_LAYERS;

    @ArchTest
    static final ArchRule PRESENTATION_DOES_NOT_DEPEND_ON_INFRASTRUCTURE =
            HexagonalRules.PRESENTATION_DOES_NOT_DEPEND_ON_INFRASTRUCTURE;

    @ArchTest
    static final ArchRule TRANSACTIONAL_NOT_ON_PRESENTATION_OR_DOMAIN_CLASSES =
            HexagonalRules.TRANSACTIONAL_NOT_ON_PRESENTATION_OR_DOMAIN_CLASSES;

    @ArchTest
    static final ArchRule TRANSACTIONAL_NOT_ON_PRESENTATION_OR_DOMAIN_METHODS =
            HexagonalRules.TRANSACTIONAL_NOT_ON_PRESENTATION_OR_DOMAIN_METHODS;

    @ArchTest
    static final ArchRule FEIGN_CLIENTS_LIVE_IN_INFRASTRUCTURE =
            HexagonalRules.FEIGN_CLIENTS_LIVE_IN_INFRASTRUCTURE;

    @ArchTest
    static final ArchRule PORT_NAMED_TYPES_ARE_INTERFACES =
            HexagonalRules.PORT_NAMED_TYPES_ARE_INTERFACES;
}
