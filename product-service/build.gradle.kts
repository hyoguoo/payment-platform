import io.gitlab.arturbosch.detekt.getSupportedKotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType

plugins {
    id("org.springframework.boot")
    id("io.spring.dependency-management")
    id("org.gradle.test-retry")
    kotlin("jvm") version "2.4.21"
    kotlin("plugin.spring") version "2.4.21"
    kotlin("plugin.jpa") version "2.4.21"
    id("io.gitlab.arturbosch.detekt") version "1.23.8"
    id("org.jlleitschuh.gradle.ktlint") version "14.2.0"
}

extra["springCloudVersion"] = "2024.0.0"
extra["testcontainersVersion"] = "1.20.4"
// 단위 커버리지 기준 게이트. 통합테스트 정합성은 integration-test job 통과(pass/fail)로 보호.
// 자바 시절 단위 test exec 실측 100%(46/46 라인) 기준, 안전마진 3%p 적용.
// 코틀린 이관 뒤 실측 98.6%(68/69 라인, 2026-11-23). 남은 1줄은 ProductQueryUseCase.getPage.
extra["jacoco.lineCoverageMinimum"] = "0.97"
// Spring Boot 3.4.4 BOM 은 Kotlin 을 1.9.25 로 관리한다. 프로젝트 속성으로 BOM 의 kotlin.version 을 덮어써
// 표준 라이브러리·리플렉션 버전을 컴파일러(위 플러그인)와 맞춘다.
extra["kotlin.version"] = "2.4.21"

val testcontainersVersion: String by extra

// Gradle ktlint 플러그인과 편집 직후 훅의 ktlint CLI 가 같은 버전으로 검사하게 한 곳에서 정한다.
val ktlintVersion = "1.8.0"

dependencies {
    // Spring Boot Core (MVC + Virtual Threads — pg-service 구성 대칭)
    implementation("org.springframework.boot:spring-boot-starter-web")

    // Kotlin — Spring 이 코틀린 클래스를 JSON 으로 읽고 쓰는 데 필요하다
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")

    // Actuator / Micrometer
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("io.micrometer:micrometer-tracing-bridge-otel")
    implementation("io.opentelemetry:opentelemetry-exporter-otlp")

    // Service Discovery (Eureka 등록 — docker 프로필에서만 활성화)
    implementation("org.springframework.cloud:spring-cloud-starter-netflix-eureka-client")

    // Kafka (payment.events.stock-committed consumer)
    implementation("org.springframework.kafka:spring-kafka")

    // JPA + Flyway + MySQL
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("com.mysql:mysql-connector-j")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-mysql")

    // 코틀린 테스트의 목 객체와 Spring 빈 목
    testImplementation("io.mockk:mockk-jvm:1.14.12")
    testImplementation("com.ninja-squad:springmockk:4.0.2")

    // Testcontainers — CBA-7 FlywayDockerProfileTest docker profile seed 차단 검증 (MySQL)
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation(platform("org.testcontainers:testcontainers-bom:$testcontainersVersion"))
    testImplementation("org.testcontainers:mysql")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:testcontainers")

    // STOCK-GATE-PER-PRODUCT Task 13 — 격리 토픽 도달 통합 테스트(@EmbeddedKafka) 용.
    // payment-service 와 동일 좌표, 버전은 루트 BOM 관리값(spring-kafka 3.3.4 계열)을 그대로 따른다.
    testImplementation("org.springframework.kafka:spring-kafka-test")
}

dependencyManagement {
    imports {
        mavenBom("org.springframework.cloud:spring-cloud-dependencies:${property("springCloudVersion")}")
    }
}

// 루트 subprojects 가 모든 모듈에 Lombok 을 넣는다. 코틀린 모듈인 product-service 는 쓰지 않으므로 뺀다.
listOf("compileOnly", "annotationProcessor").forEach { name ->
    configurations.named(name) {
        exclude(group = "org.projectlombok", module = "lombok")
    }
}

configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.testcontainers") {
            useVersion(testcontainersVersion)
            because(
                "Override Spring Boot BOM strict constraint to use newer Testcontainers with Docker Desktop 4.65+ support",
            )
        }
    }
}

// detekt·ktlint 는 자기 Kotlin 컴파일러로 소스를 읽는다. Spring 의존성 관리 플러그인이 위 kotlin.version 을
// 이 도구 classpath 에도 적용하면 컴파일러 버전이 어긋나 detekt 는 실행을 거부하고 ktlint 는 파싱에 실패한다.
// 도구 classpath 는 각 도구가 요구한 버전을 그대로 쓰게 한다.
configurations.matching { it.name == "detekt" }.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.jetbrains.kotlin") {
            useVersion(getSupportedKotlinVersion())
        }
    }
}
configurations.matching { it.name.startsWith("ktlint") }.configureEach {
    resolutionStrategy.eachDependency {
        val requestedVersion = requested.version
        if (requested.group == "org.jetbrains.kotlin" && requestedVersion != null) {
            useVersion(requestedVersion)
        }
    }
}

kotlin {
    compilerOptions {
        // Spring 의 null 표기(@Nullable 등)를 코틀린 타입에 그대로 반영한다
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

// main 은 경고를 남기지 않는다(루트 Checkstyle maxWarnings = 0 과 같은 원칙)
tasks.named<KotlinCompile>("compileKotlin") {
    compilerOptions.allWarningsAsErrors.set(true)
}

// 코틀린 플러그인은 테스트 컴파일에 main jar 를 연결해, 테스트만 돌려도 plain jar 가 만들어진다.
// Dockerfile 이 build/libs/*.jar 하나만 있다고 보고 복사하므로 plain jar 는 build/libs 밖에 둔다.
tasks.named<Jar>("jar") {
    destinationDirectory.set(layout.buildDirectory.dir("libs-plain"))
}

// 루트 빌드는 커버리지 리포트의 클래스 디렉토리를 설정 시점의 자바 출력 경로로 고정한다. 자바 소스가 없는 이 모듈은
// 그대로 두면 리포트가 비므로, 루트가 afterEvaluate 에서 main 출력 전체(코틀린 포함)로 잡는 검증 태스크와
// 같은 목록을 실행 시점에 쓴다. 검증 태스크가 리포트에 의존하므로 태스크가 아닌 목록만 읽어 순환을 피한다.
tasks.named<JacocoReport>("jacocoTestReport") {
    classDirectories.setFrom(
        provider { tasks.getByName<JacocoCoverageVerification>("jacocoTestCoverageVerification").classDirectories },
    )
}

// 루트 제외 패턴 **/*Application.class 는 진입점 main 을 커버리지에서 뺀다. 코틀린은 최상위 main 을
// <파일명>Kt 클래스로 컴파일해 이 패턴에 걸리지 않으므로, 같은 이유로 *ApplicationKt 도 뺀다(루트 afterEvaluate 뒤에 적용).
afterEvaluate {
    tasks.named<JacocoCoverageVerification>("jacocoTestCoverageVerification") {
        // 루트가 넣은 목록을 먼저 떼어 낸다. classDirectories 자신을 원본으로 쓰면 자기 참조가 된다.
        val rootMeasured = objects.fileCollection().from(classDirectories.from.toList())
        classDirectories.setFrom(rootMeasured.asFileTree.matching { exclude("**/*ApplicationKt.class") })
    }
}

// 지연 로딩 프록시가 엔티티를 상속할 수 있게 연다. kotlin-spring 이 Spring 애노테이션 클래스를 여는 것과 같은 방식이다.
allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    reports {
        xml.required.set(true)
        html.required.set(true)
        txt.required.set(false)
        sarif.required.set(false)
        md.required.set(false)
    }
}

ktlint {
    version.set(ktlintVersion)
    reporters {
        reporter(ReporterType.PLAIN)
        reporter(ReporterType.CHECKSTYLE)
    }
}

// CI 의 빌드·단위 테스트 단계는 lint 를 빼고 돌린 뒤 lint 를 전용 단계에서 따로 돌린다(.github/workflows/_service-ci.yml).
// ktlint 플러그인은 소스 셋별 검사 태스크를 check 에 직접 붙여 -x ktlintCheck 만으로는 빠지지 않으므로 속성 하나로 끈다.
// 실제 검사는 runKtlintCheckOver… 태스크가 하므로 이름에 ktlint 가 들어간 태스크를 모두 끈다(편집 hook 용 classpath 태스크는 남긴다).
if (providers.gradleProperty("skipKotlinLint").isPresent) {
    tasks
        .matching {
            (it.name.contains("ktlint", ignoreCase = true) && it.name != "writeKtlintCliClasspath") ||
                it.name == "detekt"
        }.configureEach { enabled = false }
}

// 편집 직후 훅(.agents/hooks/lint-file.sh → config/ktlint/check-file.sh)이 쓰는 ktlint CLI classpath 를 파일로 떨군다.
// Gradle ktlintCheck 는 모듈 전체를 돌아 편집 직후 피드백으로 쓰기엔 느려 훅은 CLI 를 파일 하나에만 물린다.
// 위 ktlint 설정 고정 규칙(이름이 ktlint 로 시작하는 configuration)이 이 classpath 에도 적용된다.
val ktlintCli: Configuration by configurations.creating

dependencies {
    ktlintCli("com.pinterest.ktlint:ktlint-cli:$ktlintVersion")
}

tasks.register("writeKtlintCliClasspath") {
    description = "편집 직후 훅이 쓰는 ktlint CLI classpath 를 기록한다."
    val classpathFile = layout.buildDirectory.file("ktlint-cli-classpath.txt")
    val cliClasspath: FileCollection = ktlintCli
    outputs.file(classpathFile)
    doLast {
        classpathFile.get().asFile.writeText(cliClasspath.asPath)
    }
}

// CBA-7 통합 테스트 — @Tag("integration") 전용 태스크.
// Testcontainers MySQL + @SpringBootTest + @ActiveProfiles("docker") 로 seed 차단 검증.
// pg-service integrationTest 패턴 동일 구조.
val integrationTest by tasks.registering(Test::class) {
    val testSourceSet = sourceSets.test.get()
    testClassesDirs = testSourceSet.output.classesDirs
    classpath = testSourceSet.runtimeClasspath
    environment("DOCKER_API_VERSION", "1.44")

    useJUnitPlatform {
        includeTags("integration")
    }
    jvmArgs("-XX:+EnableDynamicAgentLoading", "-Xshare:off")
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    afterSuite(
        KotlinClosure2<TestDescriptor, TestResult, Unit>({ desc, result ->
            if (desc.parent == null) {
                println(
                    "\nIntegration Test Results: ${result.resultType} " +
                        "(${result.testCount} tests, " +
                        "${result.successfulTestCount} passed, " +
                        "${result.failedTestCount} failed, " +
                        "${result.skippedTestCount} skipped)",
                )
            }
        }),
    )

    // T2: D3 통합테스트 한정 자동 재시도 (단위 test 블록에는 적용 안 함)
    // Testcontainers cold-start 타이밍 flaky 흡수. 3개 이상 실패 시 재시도 중단해 진짜 결함을 가리지 않음.
    retry {
        maxRetries.set(2)
        maxFailures.set(3)
    }
}

tasks.check {
    dependsOn(integrationTest)
}
integrationTest {
    mustRunAfter(tasks.test)
}

tasks.named<com.github.spotbugs.snom.SpotBugsTask>("spotbugsMain") {
    excludeFilter.set(rootProject.file("config/spotbugs/spotbugs-exclude.xml"))
    reports.create("html") {
        required.set(true)
    }
    reports.create("xml") {
        required.set(true)
    }
}

tasks.named<com.github.spotbugs.snom.SpotBugsTask>("spotbugsTest") {
    excludeFilter.set(rootProject.file("config/spotbugs/spotbugs-exclude-test.xml"))
    reports.create("html") {
        required.set(true)
    }
    reports.create("xml") {
        required.set(true)
    }
}
