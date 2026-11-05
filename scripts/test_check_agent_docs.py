"""check-agent-docs.py의 문서 식별자 대조(판정 3) 테스트.

임시 저장소에 Java·YAML·스크립트와 문서를 만들고, 문서의 백틱 식별자가 코드에 있는지 판정하는지 확인한다.
실행: python3 -m unittest scripts/test_check_agent_docs.py
"""
import importlib.util
import tempfile
import textwrap
import unittest
from pathlib import Path

SCRIPT = Path(__file__).resolve().parent / "check-agent-docs.py"
spec = importlib.util.spec_from_file_location("check_agent_docs", SCRIPT)
check_agent_docs = importlib.util.module_from_spec(spec)
spec.loader.exec_module(check_agent_docs)


def write(root, rel, content):
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(textwrap.dedent(content), encoding="utf-8")
    return path


class IdentifierCheckTest(unittest.TestCase):

    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.root = Path(self.tmp.name)
        write(self.root, "payment-service/src/main/java/a/PaymentEvent.java", """
            package a;
            import org.springframework.kafka.core.KafkaTemplate;
            public class PaymentEvent {
                private final KafkaTemplate<String, String> template;
                void fail() { throw new IllegalStateException("x"); }
            }
        """)
        write(self.root, "payment-service/src/main/resources/application.yml", """
            spring:
              kafka:
                consumer:
                  max-poll-records: 50
            payment:
              outbox:
                relay:
                  batch-size: 100
        """)
        write(self.root, "scripts/smoke/check.sh", """
            topic=payment.commands.confirm
        """)
        self.index = check_agent_docs.build_code_index(self.root)

    def tearDown(self):
        self.tmp.cleanup()

    def issues_for(self, body):
        doc = write(self.root, "docs/context/SAMPLE.md", body)
        _checked, issues = check_agent_docs.check_identifiers([doc], self.index, self.root)
        return issues

    def test_코드에_선언된_클래스는_통과한다(self):
        self.assertEqual([], self.issues_for("결제 이벤트는 `PaymentEvent`가 표현한다.\n"))

    def test_코드가_쓰는_라이브러리_클래스는_통과한다(self):
        self.assertEqual([], self.issues_for("`KafkaTemplate`과 `IllegalStateException`을 쓴다.\n"))

    def test_코드에_없는_클래스는_보고한다(self):
        issues = self.issues_for("삭제된 `RemovedOutboxWorker`가 처리한다.\n")
        self.assertEqual(1, len(issues))
        self.assertIn("RemovedOutboxWorker", issues[0])
        self.assertIn("docs/context/SAMPLE.md:1", issues[0])

    def test_주석이나_문자열에만_남은_이름은_코드에_있는_것으로_보지_않는다(self):
        write(self.root, "pg-service/src/test/java/b/LegacyNoteTest.java", """
            package b;
            // RemovedGatewayPort 는 예전 구조다. "http://example.com/GhostLiteralName"
            /* BlockCommentOnlyName 도 마찬가지 */
            class LegacyNoteTest { String s = "StringOnlyName"; }
        """)
        index = check_agent_docs.build_code_index(self.root)
        doc = write(self.root, "docs/context/LEGACY.md",
                    "`RemovedGatewayPort`, `BlockCommentOnlyName`, `StringOnlyName`, `LegacyNoteTest`\\n")
        issues = check_agent_docs.check_identifiers([doc], index, self.root)[1]
        self.assertEqual(3, len(issues))
        self.assertFalse(any("LegacyNoteTest" in i for i in issues))

    def test_port_out_아래_선언도_색인한다(self):
        write(self.root, "payment-service/src/main/java/a/application/port/out/OnlyHerePort.java", """
            package a.application.port.out;
            public interface OnlyHerePort { }
        """)
        index = check_agent_docs.build_code_index(self.root)
        doc = write(self.root, "docs/context/PORT.md", "`OnlyHerePort`\\n")
        self.assertEqual([], check_agent_docs.check_identifiers([doc], index, self.root)[1])

    def test_메서드가_붙은_클래스는_클래스_이름으로_대조한다(self):
        self.assertEqual([], self.issues_for("`PaymentEvent.fail()`이 예외를 던진다.\n"))
        self.assertEqual(1, len(self.issues_for("`GhostService.run()`이 돈다.\n")))

    def test_한_단어_대문자나_상태값은_대조하지_않는다(self):
        self.assertEqual([], self.issues_for("`Kafka`, `DONE`, `IN_FLIGHT`, `Redis` 상태\n"))

    def test_YAML_중첩_키를_펼친_설정_키는_통과한다(self):
        self.assertEqual([], self.issues_for("`spring.kafka.consumer.max-poll-records`를 50으로 둔다.\n"))

    def test_상위_키가_있으면_기본값으로_쓰는_하위_키도_통과한다(self):
        self.assertEqual([], self.issues_for("`payment.outbox.relay.poll-interval`은 기본값을 쓴다.\n"))

    def test_스크립트에_나오는_토픽_이름은_통과한다(self):
        self.assertEqual([], self.issues_for("토픽 `payment.commands.confirm`\n"))

    def test_어디에도_없는_설정_키는_보고한다(self):
        issues = self.issues_for("`payment.ghost.feature.enabled`를 켠다.\n")
        self.assertEqual(1, len(issues))
        self.assertIn("payment.ghost.feature.enabled", issues[0])

    def test_코드_펜스_안은_대조하지_않는다(self):
        self.assertEqual([], self.issues_for("```java\nRemovedOutboxWorker w;\n`GhostService`\n```\n"))

    def test_YAML에_정의된_이름도_코드에_있는_이름으로_본다(self):
        write(self.root, "observability/rules/alerts.yml", "groups:\n  - rules:\n      - alert: ServiceDown\n")
        index = check_agent_docs.build_code_index(self.root)
        doc = write(self.root, "docs/smoke/ALERT.md", "`ServiceDown` 알람\n")
        self.assertEqual([], check_agent_docs.check_identifiers([doc], index, self.root)[1])

    def test_하이픈으로_쓴_설정은_점으로_쓴_속성_이름과_같다고_본다(self):
        self.assertEqual([], self.issues_for("Kafka 속성 `max.poll.records`\n"))

    def test_외부_라이브러리_타입_목록에_있는_이름은_통과한다(self):
        self.assertIn("MissingMigrationException", check_agent_docs.EXTERNAL_TYPE_NAMES)
        self.assertEqual([], self.issues_for("Flyway가 `MissingMigrationException`을 던진다.\n"))

    def test_외부_설정_키_목록에_있는_기본값_속성은_통과한다(self):
        self.assertIn("max.poll.interval.ms", check_agent_docs.EXTERNAL_CONFIG_KEYS)
        self.assertEqual([], self.issues_for("Kafka consumer `max.poll.interval.ms` 기본값 5분\n"))

    def test_파일_이름처럼_보이는_점_표기는_설정_키로_보지_않는다(self):
        self.assertEqual([], self.issues_for("`docker-compose.infra.yml`과 `checkstyle-cli.properties`\n"))


if __name__ == "__main__":
    unittest.main()
