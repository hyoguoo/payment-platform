package com.hyoguoo.paymentplatform.product

import com.hyoguoo.paymentplatform.buildtools.archunit.HexagonalRules
import com.tngtech.archunit.core.importer.ImportOption.DoNotIncludeTests
import com.tngtech.archunit.junit.AnalyzeClasses
import com.tngtech.archunit.junit.ArchTest
import com.tngtech.archunit.lang.ArchRule

/**
 * hexagonal 레이어 규칙 검증. 규칙 본문은
 * [com.hyoguoo.paymentplatform.buildtools.archunit.HexagonalRules] 한 곳에만 있고
 * 이 클래스는 검사 대상 패키지만 지정한다 — 규칙을 고칠 때는 그쪽만 고친다.
 */
@AnalyzeClasses(
    packages = ["com.hyoguoo.paymentplatform.product"],
    importOptions = [DoNotIncludeTests::class],
)
// ArchUnit 은 규칙을 클래스의 static 필드로 찾으므로 companion 의 @JvmField 로 둬야 한다. object 로 바꿀 수 없다
@Suppress("UtilityClassWithPublicConstructor")
class ArchitectureTest {
    companion object {
        @JvmField
        @ArchTest
        val DOMAIN_IS_FRAMEWORK_FREE: ArchRule = HexagonalRules.DOMAIN_IS_FRAMEWORK_FREE

        @JvmField
        @ArchTest
        val DOMAIN_DOES_NOT_DEPEND_ON_OUTER_LAYERS: ArchRule = HexagonalRules.DOMAIN_DOES_NOT_DEPEND_ON_OUTER_LAYERS

        @JvmField
        @ArchTest
        val PRESENTATION_DOES_NOT_DEPEND_ON_INFRASTRUCTURE: ArchRule =
            HexagonalRules.PRESENTATION_DOES_NOT_DEPEND_ON_INFRASTRUCTURE

        @JvmField
        @ArchTest
        val TRANSACTIONAL_NOT_ON_PRESENTATION_OR_DOMAIN_CLASSES: ArchRule =
            HexagonalRules.TRANSACTIONAL_NOT_ON_PRESENTATION_OR_DOMAIN_CLASSES

        @JvmField
        @ArchTest
        val TRANSACTIONAL_NOT_ON_PRESENTATION_OR_DOMAIN_METHODS: ArchRule =
            HexagonalRules.TRANSACTIONAL_NOT_ON_PRESENTATION_OR_DOMAIN_METHODS

        @JvmField
        @ArchTest
        val FEIGN_CLIENTS_LIVE_IN_INFRASTRUCTURE: ArchRule = HexagonalRules.FEIGN_CLIENTS_LIVE_IN_INFRASTRUCTURE

        @JvmField
        @ArchTest
        val PORT_NAMED_TYPES_ARE_INTERFACES: ArchRule = HexagonalRules.PORT_NAMED_TYPES_ARE_INTERFACES
    }
}
