#!/usr/bin/env python3
"""Generate a self-contained, evidence-linked local quality report.

Usage: python3 scripts/quality/report.py --root live-drill/quality-baseline
Only local files are read; report.html is the only output. Missing runs stay untested.
"""

import argparse
import html
import json
import math
import shlex
from collections import defaultdict
from datetime import datetime, timedelta, timezone
from pathlib import Path
from urllib.parse import quote


KST = timezone(timedelta(hours=9))
RUNS = (("preparation-01", "계측 준비", "preparation"),
        ("normal-01", "정상 부하 1회", "normal"),
        ("normal-02", "정상 부하 2회", "normal"),
        ("fault-01", "payment 중단·복구", "fault"),
        ("fault-02", "payment 중단·복구 2회", "fault"))
EVIDENCE_NAMES = ("run.json", "command.json", "environment.json", "before.json", "after.json",
                  "attempts.jsonl", "summary.json", "result.json", "settlement.json",
                  "timeline.jsonl", "resources.jsonl", "fault.json", "after-kill.json",
                  "at-health.json", "recovery-observation.json", "alert-observations.jsonl",
                  "source-hashes.json", "source.patch", "SHA256SUMS.json", "k6.log")
PALETTE = ("#0e7490", "#b45309", "#7c3aed", "#be123c")


def esc(value):
    return html.escape(str(value), quote=True)


def link(path, label):
    return f'<a href="{esc(quote(str(path), safe="/.-_"))}">{esc(label)}</a>'


def read_json(path):
    if not path.is_file():
        return None
    try:
        return json.loads(path.read_text(encoding="utf-8"))
    except (OSError, ValueError):
        return None


def read_jsonl(path):
    if not path.is_file():
        return []
    records = []
    try:
        for line in path.read_text(encoding="utf-8").splitlines():
            if line.strip():
                records.append(json.loads(line))
    except (OSError, ValueError):
        return []
    return records


def stamp(value):
    if not isinstance(value, (int, float)) or not math.isfinite(value):
        return "—"
    return datetime.fromtimestamp(value / 1000, KST).strftime("%Y-%m-%d %H:%M:%S.%f")[:-3] + " KST"


def number(value):
    return f"{value:,}" if isinstance(value, int) else "—"


def percent(value):
    return f"{value * 100:.2f}%" if isinstance(value, (int, float)) else "—"


def chip(value):
    text = str(value) if value is not None else "근거 없음"
    cls = {"PASS": "pass", "FAIL": "fail", "INCONCLUSIVE": "unknown",
           "EXCLUDED": "excluded"}.get(text, "muted")
    return f'<span class="chip {cls}">{esc(text)}</span>'


def verdict_meaning(value):
    return {"PASS": "정해 둔 기준을 충족했다.",
            "FAIL": "정해 둔 기준에 미치지 못했다.",
            "INCONCLUSIVE": "자료가 부족해 통과·실패를 확정하지 못했다.",
            "EXCLUDED": "이번 결론의 근거로 사용하지 않는다."}.get(value, "판정 기록이 없다.")


def overview_section(root, excluded):
    """Put the reader-facing conclusion before the audit trail; use saved results only."""
    normal_cards = []
    normal_measured = 0
    for run_id, title in (("normal-01", "정상 부하 1회"), ("normal-02", "정상 부하 2회")):
        directory = root / run_id
        result = read_json(directory / "result.json")
        run = read_json(directory / "run.json")
        settlement = read_json(directory / "settlement.json")
        status = (official_verdict(run, "normal", None, excluded, result, settlement, run_id)
                  if result else "미실행")
        if not result:
            body = '<p class="quiet">아직 측정하지 않았다.</p>'
        else:
            normal_measured += 1
            counts = result.get("counts") or {}
            body = (f'<p><strong>{number(counts.get("started"))}건</strong>을 실제 시작했다. '
                    f'confirm 접수 응답 p95는 <strong>{number(result.get("confirmP95Ms"))}ms</strong>, '
                    f'5초 내 완료 관측은 <strong>{number(counts.get("fastDone"))}건</strong>이다.</p>'
                    f'<p class="micro">60초 내 완료 {number(counts.get("eventualDone"))}건 · '
                    f'주문·재고 대조 {esc((settlement or {}).get("verdict", "근거 없음"))}</p>')
        normal_cards.append(f'<article class="story-card"><h3><a href="#{esc(run_id)}">{esc(title)}</a> {chip(status)}</h3>{body}</article>')

    fault_id = next((run_id for run_id in ("fault-02", "fault-01")
                     if (root / run_id / "result.json").is_file()), None)
    if fault_id:
        directory = root / fault_id
        result = read_json(directory / "result.json") or {}
        settlement = read_json(directory / "settlement.json") or {}
        fault = read_json(directory / "fault.json") or {}
        run = read_json(directory / "run.json")
        status = official_verdict(run, "fault", fault, excluded, result, settlement, fault_id)
        counts = result.get("counts") or {}
        unknown = (settlement.get("counts") or {}).get("unknownAcceptance")
        recovered = len(fault.get("recoveredOrders") or {})
        targets = len(fault.get("targetOrderIds") or [])
        observation = (f'중단 알람을 확인했고, 복구 대상으로 정한 주문 {number(targets)}건 중 '
                       f'{number(recovered)}건의 완료·재고 반영을 관측했다. '
                       if fault.get("detectionPass") is True and fault.get("recoveryPass") is True
                       else '중단 알람과 주문 회복은 아래 실행 기록에서 각각 확인해야 한다. ')
        limitation = (f'결제 시작 단계의 오류로 주문 ID를 받지 못한 시도 {number(unknown)}건은 '
                      '서버 접수 여부를 확정하지 못했다. '
                      if isinstance(unknown, int) and unknown else '')
        fault_body = (f'<p>{observation}{limitation}따라서 이 장애 시험의 결론은 '
                      f'<strong>{esc(status)}</strong>이다. {esc(verdict_meaning(status))}</p>'
                      f'<p class="micro">실제 시작 {number(counts.get("started"))}건 · '
                      f'5초 내 완료 관측 {number(counts.get("fastDone"))}건 · '
                      f'접수 여부 확인 불가 {number(unknown)}건</p>'
                      f'<p><a href="#{esc(fault_id)}">장애 실행의 시간순 기록 보기</a></p>')
    else:
        status = "미실행"
        fault_body = '<p class="quiet">프로세스 중단·회복 시험을 아직 실행하지 않았다.</p>'
    normal_intro = (f'정상 상태의 두 실행 중 {normal_measured}회를 측정했다. '
                    '장애 시험은 서버를 중단했을 때 사용자가 본 결과와 재기동 뒤 처리된 주문을 나누어 읽는다.')
    readiness = ('<p class="note">측정 도구 준비, 새 공식 실측 미실행.</p>'
                 if normal_measured == 0 and fault_id is None else '')
    return ('<section class="run overview"><div class="eyebrow">먼저 읽을 결과</div>'
            '<h2>정상 부하와 장애에서 확인한 범위</h2>'
            + readiness
            + f'<p>{esc(normal_intro)}</p>'
            '<div class="story-grid">' + ''.join(normal_cards) + '</div>'
            f'<article class="story-fault"><h3>장애 시험 {chip(status)}</h3>{fault_body}</article>'
            '<p class="micro">PASS는 기준 충족, FAIL은 기준 미달, INCONCLUSIVE는 근거 부족으로 판정 보류, '
            'EXCLUDED는 공식 근거 제외를 뜻한다.</p></section>')


def table(headers, rows):
    head = "".join(f"<th>{esc(item)}</th>" for item in headers)
    body = "".join("<tr>" + "".join(f"<td>{item}</td>" for item in row) + "</tr>"
                   for row in rows)
    return f'<div class="table-wrap"><table><thead><tr>{head}</tr></thead><tbody>{body}</tbody></table></div>'


def raw_links(directory):
    names = [name for name in EVIDENCE_NAMES if (directory / name).is_file()]
    names += sorted(p.name for p in directory.glob("prometheus-*.json") if p.is_file())
    return " ".join(link(f"{directory.name}/{name}", name) for name in names)


def safe_command(command, run_id):
    if not isinstance(command, dict):
        return "실행 명령 기록 없음"
    rate, duration = command.get("rate"), command.get("durationSeconds")
    if not isinstance(rate, int) or not isinstance(duration, int):
        return "실행 명령 기록 부족"
    suffix = " --fault" if command.get("fault") is True else ""
    if isinstance(command.get('preallocatedVUs'), int):
        suffix += f" --preallocated-vus {command['preallocatedVUs']}"
    project = command.get('composeProject')
    output = command.get('outputRoot')
    if not isinstance(project, str) or not project.strip() or not isinstance(output, str) or not output.strip():
        return "실행 명령 기록 부족: 프로젝트 또는 출력 경로가 없다. 운영 기준의 재현 절차를 확인한다."
    return (f"COMPOSE_PROJECT_NAME={shlex.quote(str(project))} python3 scripts/quality/run.py "
            f"--run-id {shlex.quote(run_id)} --rate {rate} --duration-seconds {duration}{suffix} "
            f"--output-root {shlex.quote(str(output))}")


def complete_pass_evidence(run, result, settlement, expected_run_id=None):
    """Check the saved summaries' identity and internal counts before promoting PASS."""
    run_id = run.get("runId")
    if (not isinstance(run_id, str) or not run_id.strip()
            or (expected_run_id is not None and run_id != expected_run_id)
            or result.get("runId") != run_id or settlement.get("runId") != run_id
            or type(result.get("schemaVersion")) is not int or result["schemaVersion"] != 1
            or type(settlement.get("schemaVersion")) is not int or settlement["schemaVersion"] != 1
            or run.get("sourceChanged") is not False or type(run.get("exitCode")) is not int
            or run["exitCode"] != 0 or result.get("validityIssues") != []):
        return False
    counts = result.get("counts")
    settled = settlement.get("counts")
    if not isinstance(counts, dict) or not isinstance(settled, dict):
        return False
    result_fields = ("expected", "started", "finished", "missingFinish", "iterations",
                     "dropped", "fastDone", "eventualDone", "acceptanceUnknown")
    settled_fields = ("attempts", "knownOrders", "unknownAcceptance",
                      "unattributedNewOrders", "pending", "failures")
    if (any(type(counts.get(key)) is not int or counts[key] < 0 for key in result_fields)
            or any(type(settled.get(key)) is not int or settled[key] < 0 for key in settled_fields)
            or counts["expected"] == 0 or counts["started"] == 0
            or counts["finished"] + counts["missingFinish"] != counts["started"]
            or counts["iterations"] < counts["started"]
            or not 0 <= counts["fastDone"] <= counts["eventualDone"] <= counts["started"]
            or counts["acceptanceUnknown"] != 0
            or settled["attempts"] != counts["started"]
            or settled["knownOrders"] > settled["attempts"]):
        return False
    for key in ("failures", "pending", "unknownAcceptance", "unattributedNewOrders"):
        entries = settlement.get(key)
        if not isinstance(entries, list) or len(entries) != settled[key] or entries:
            return False
    goals = result.get("goals")
    required = ("arrival", "confirmP95", "doneWithin5s", "doneWithin60s")
    if (not isinstance(goals, dict)
            or any(goals.get(key) not in ("PASS", "FAIL") for key in required)):
        return False
    return result.get("verdict") == ("PASS" if all(goals[key] == "PASS" for key in required) else "FAIL")


def official_verdict(run, kind, fault, excluded=False, result=None, settlement=None,
                     expected_run_id=None):
    """Only promote a stored PASS when the evidence prerequisites also hold."""
    if excluded:
        return "EXCLUDED"
    if not isinstance(run, dict):
        return "INCONCLUSIVE"
    recorded = run.get("verdict", "INCONCLUSIVE")
    if recorded == 'FAIL':
        return recorded
    if kind == "fault":
        if not isinstance(fault, dict):
            return "INCONCLUSIVE"
        if fault.get("healthFailed") is True or (fault.get("detectionEvaluated") is True
                                                and fault.get("detectionPass") is False):
            return "FAIL"
        if (not fault.get("initialOrderIds") or fault.get("observationsComplete") is not True
                or fault.get("loadMaintainedThroughRecovery") is not True
                or fault.get("detectionEvaluated") is not True):
            return "INCONCLUSIVE"
        if fault.get("detectionPass") is not True or fault.get("recoveryPass") is not True:
            return "FAIL"
    if recorded == "PASS":
        if not isinstance(result, dict) or not isinstance(settlement, dict):
            return "INCONCLUSIVE"
        if not complete_pass_evidence(run, result, settlement, expected_run_id):
            return "INCONCLUSIVE"
        quality = result.get("verdict")
        stock = settlement.get("verdict")
        if run.get("quality") != quality or run.get("settlement") != stock:
            return "INCONCLUSIVE"
        if stock == "FAIL":
            return "FAIL"
        if stock != "PASS":
            return "INCONCLUSIVE"
        if kind != "fault":
            if quality == "FAIL":
                return "FAIL"
            if quality != "PASS":
                return "INCONCLUSIVE"
        elif quality not in ("PASS", "FAIL", "INCONCLUSIVE"):
            return "INCONCLUSIVE"
    if run.get("sourceChanged") is True and recorded == "PASS":
        return "INCONCLUSIVE"
    return recorded


def clean_environment_section(root):
    path = root / "clean-environment.json"
    data = read_json(path)
    if not isinstance(data, dict):
        return ""
    volumes = data.get("volumes")
    names = ([str(row.get("name")) for row in volumes
              if isinstance(row, dict) and row.get("name")]
             if isinstance(volumes, list) else [])
    volume_rows = ([(esc(row.get("name", "—")), esc(row.get("createdAt", "생성 시각 없음")))
                    for row in volumes if isinstance(row, dict)]
                   if isinstance(volumes, list) else [])
    checks = (("새 네트워크", "freshNetwork"), ("새 볼륨", "freshVolumes"),
              ("결제 상태 비어 있음", "emptyPaymentState"), ("Kafka 비어 있음", "emptyKafka"),
              ("Redis 비어 있음", "emptyRedis"), ("이전 볼륨 보존", "previousVolumesPreserved"))
    rows = [(label, "수집 기록상 확인" if data.get(field) is True else "확인 근거 없음")
            for label, field in checks]
    baseline = data.get("baseline")
    baseline_link = (link(baseline, baseline) if isinstance(baseline, str)
                     and Path(baseline).name == baseline and (root / baseline).is_file()
                     else "기준 전 스냅샷 링크 없음")
    return ('<details class="run audit"><summary>새 실행 환경의 출발 상태와 볼륨 기록</summary>'
            '<p class="note">아래는 수집기가 기록한 clean 시작 조건이다. 판정 원본은 '
            + link("clean-environment.json", "clean-environment.json") + '에서 확인한다.</p>'
            + f'<p>프로젝트 <strong>{esc(data.get("project", "—"))}</strong> · '
              f'네트워크 <strong>{esc(data.get("network", "—"))}</strong> · '
              f'생성 기록 {esc(stamp(data.get("createdAtMs")))}</p>'
            + table(("출발 조건", "수집 기록"), [(esc(a), esc(b)) for a, b in rows])
            + f'<p class="micro">볼륨 {number(len(names))}개: {esc(", ".join(names) if names else "목록 없음")} · '
              f'시드 전 기준: {baseline_link}</p>'
            + (table(("전용 볼륨", "생성 시각 기록"), volume_rows) if volume_rows else "")
            + '</details>')


def exclusion_section(root):
    own = read_json(root / "evidence-status.json")
    if isinstance(own, dict) and own.get("status") == "EXCLUDED":
        reason = own.get("reason", "근거 제외 사유 기록 없음")
        return ('<section class="run exclusion"><h2>이전 측정: 확정 근거에서 제외</h2>'
                f'<p>{esc(reason)}</p><p>{link("evidence-status.json", "제외 판정 원자료")} · '
                f'대체 기록 {esc(own.get("supersededBy", "—"))}</p></section>', True, None)
    previous_root = own.get("previousRoot") if isinstance(own, dict) else None
    if (not isinstance(previous_root, str) or previous_root in ("", ".", "..")
            or Path(previous_root).name != previous_root or previous_root == root.name):
        return "", False, None
    previous = root.parent / previous_root / "evidence-status.json"
    prior = read_json(previous)
    if isinstance(prior, dict) and prior.get("status") == "EXCLUDED":
        prior_link = f"../{previous_root}/evidence-status.json"
        return ('<section class="run exclusion"><h2>이전 측정: 확정 근거에서 제외</h2>'
                f'<p>{esc(prior.get("reason", "제외 사유 기록 없음"))}</p>'
                f'<p>{link(prior_link, "이전 측정의 제외 판정 원자료")}</p>'
                '</section>', False, prior_link)
    return "", False, None


def quality_table(result, settlement, kind):
    if not result:
        return '<p class="quiet">실측 결과 없음. 이 실행의 목표 달성 여부는 미판정입니다.</p>'
    counts = result.get("counts") or {}
    goals = result.get("goals") or {}
    if kind == "fault":
        heading = ('<p class="note">중단 중 사용자가 경험한 접수 오류와 완료 지연을 보여준다. '
                   '탐지·회복 근거까지 포함한 결론은 위의 시험 판정을 따른다. '
                   '정상 부하 SLO 달성률에 합산하지 않는다.</p>')
    elif kind == "preparation":
        heading = '<p class="note">1시도/초 계측 준비 결과다. 10시도/초 정상 목표의 달성 근거가 아니다.</p>'
    else:
        heading = ""
    rows = [
        ("실제 시작 / 계획", f'{number(counts.get("started"))} / {number(counts.get("expected"))}',
         chip(goals.get("arrival"))),
        ("confirm 접수 응답 p95", f'{number(result.get("confirmP95Ms"))} ms',
         chip(goals.get("confirmP95"))),
        ("confirm 시작 후 5초 내 완료 관측", f'{number(counts.get("fastDone"))} / {number(counts.get("started"))} · {percent(result.get("fastDoneRate"))}',
         chip(goals.get("doneWithin5s"))),
        ("구매 시작 뒤 60초 내 완료 관측", f'{number(counts.get("eventualDone"))} / {number(counts.get("started"))} · {percent(result.get("eventualDoneRate"))}',
         chip(goals.get("doneWithin60s"))),
        ("주문·재고 대조", f'{number((settlement or {}).get("counts", {}).get("knownOrders"))}개 주문 확인',
         chip((settlement or {}).get("verdict"))),
    ]
    detail = (f'k6 발사 누락 {number(counts.get("dropped"))}건 · 완료 로그 누락 '
              f'{number(counts.get("missingFinish"))}건 · API 오류 시도 '
              f'{number(counts.get("apiErrorAttempts"))}건 · '
              f'계획 경계의 추가 시작 {number(counts.get("extraStarts"))}건')
    verdict_heading = "정상 목표와 비교" if kind == "fault" else "판정"
    return heading + table(("관측", "실측", verdict_heading), rows) + f'<p class="micro">{esc(detail)}</p>'


def resource_chart(samples):
    points = defaultdict(list)
    for sample in samples:
        at = sample.get("atMs")
        if not isinstance(at, (int, float)):
            continue
        for container in sample.get("containers", []):
            name = container.get("Name") or container.get("name")
            raw = container.get("CPUPerc")
            if not name or not isinstance(raw, str):
                continue
            try:
                cpu = float(raw.rstrip("%"))
            except ValueError:
                continue
            if math.isfinite(cpu):
                points[str(name)].append((at, cpu))
    available = sorted(((max(y for _, y in series), name, series)
                        for name, series in points.items() if len(series) >= 2), reverse=True)[:4]
    if not available:
        return '<p class="quiet">자원 시계열은 아직 수집되지 않았습니다.</p>'
    xmin = min(x for _, _, series in available for x, _ in series)
    xmax = max(x for _, _, series in available for x, _ in series)
    ymax = max(y for value, _, _ in available for y in (value,))
    xmax = max(xmax, xmin + 1)
    ymax = max(ymax, 1)
    paths = []
    legend = []
    for index, (peak, name, series) in enumerate(available):
        color = PALETTE[index]
        xy = " ".join(f'{40 + (x - xmin) / (xmax - xmin) * 830:.1f},'
                      f'{170 - y / ymax * 140:.1f}' for x, y in series)
        paths.append(f'<polyline points="{xy}" fill="none" stroke="{color}" '
                     'stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/>')
        legend.append(f'<span><i style="background:{color}"></i>{esc(name)} · 최고 {peak:.1f}%</span>')
    svg = ('<svg viewBox="0 0 900 200" role="img" '
           'aria-label="실측 Docker CPU 사용률 시계열">'
           '<line x1="40" y1="170" x2="870" y2="170" stroke="#9ca3af"/>'
           '<line x1="40" y1="30" x2="870" y2="30" stroke="#e5e7eb"/>'
           f'<text x="5" y="35" font-size="12" fill="#6b7280">{ymax:.0f}%</text>'
           '<text x="18" y="175" font-size="12" fill="#6b7280">0</text>'
           + "".join(paths) + '</svg>')
    return (f'<figure class="chart">{svg}<figcaption>Docker CPU 사용률 원표본 '
            f'{number(len(samples))}개 중 수치가 있는 상위 4개 컨테이너. 표본 사이를 보간하지 않는다.</figcaption>'
            f'<div class="legend">{"".join(legend)}</div></figure>')


def timeline_table(events):
    if not events:
        return '<p class="quiet">타임라인 기록 없음</p>'
    selected = [e for e in events if e.get("kind") not in ("recovery_progress", "settlement_pending")]
    rows = []
    labels = {"load_start": "부하 시작", "kill_requested": "서버 강제 종료 요청",
              "payment_stopped": "서버 중단", "recovery_cohort_initial": "중단 당시 처리 중인 주문 고정",
              "service_down_firing": "중단 알람 발생", "restart_requested": "재기동 요청",
              "payment_health_up": "서버 정상 응답", "recovery_cohort_fixed": "추적할 주문 목록 확정",
              "recovery_complete": "대상 주문 회복 관측", "recovery_deadline_exceeded": "회복 기한 초과",
              "load_finished": "부하 종료", "run_finished": "시험 종료"}
    for event in selected[:35]:
        details = ", ".join(f"{key}={value}" for key, value in event.items()
                            if key not in ("kind", "atMs", "orderIds", "container") and
                            not isinstance(value, (dict, list)))
        if isinstance(event.get("orderIds"), list):
            details += f' · 주문 {len(event["orderIds"])}건'
        kind = event.get("kind", "—")
        rows.append((esc(stamp(event.get("atMs"))), esc(labels.get(kind, kind)), esc(details or "—")))
    if len(selected) > 35:
        rows.append(("…", "…", esc(f"추가 {len(selected)-35}개는 원자료에서 확인")))
    return table(("시각", "사건", "근거·수량"), rows)


def fault_section(fault, events):
    if not fault:
        return '<p class="quiet">장애 시험 원자료 없음. 탐지·회복은 미검증입니다.</p>'
    killed = fault.get("killedAtMs")
    firing = fault.get("firstFiringMs")
    health = fault.get("healthUpMs")
    load_maintained = fault.get("loadMaintainedThroughRecovery") is True
    eligible = (bool(fault.get("initialOrderIds")) and
                fault.get("observationsComplete") is True and load_maintained)
    detection_verdict = (("PASS" if fault.get("detectionPass") else "FAIL")
                         if fault.get("detectionEvaluated") is True else "INCONCLUSIVE")
    recovery_verdict = ("PASS" if fault.get("recoveryPass") else "FAIL") if eligible else "INCONCLUSIVE"
    health_verdict = ("FAIL" if fault.get("healthFailed") is True else
                      "PASS" if isinstance(health, int) else "INCONCLUSIVE")
    rows = [
        ("서버 중단 알람 발생", f'{(firing-killed)/1000:.1f}초' if isinstance(firing, int) and isinstance(killed, int) else "근거 없음",
         chip(detection_verdict)),
        ("재기동 후 서버 정상 응답", esc(stamp(health)), chip(health_verdict)),
        ("주문 회복까지 신규 요청 유지", "확인" if load_maintained else "확인되지 않음",
         chip("PASS" if load_maintained else "INCONCLUSIVE")),
        ("서버 정상화 후 5분 내 추적 주문 완료", f'{number(len(fault.get("recoveredOrders") or {}))} / {number(len(fault.get("targetOrderIds") or []))}건',
         chip(recovery_verdict)),
    ]
    initial = fault.get("initialOrderIds") or []
    recovered = fault.get("recoveredOrders") or {}
    representative = next((order for order in initial if order in recovered), None)
    order_html = '<p class="quiet">재기동 이후 DONE·재고 반영을 확인할 대표 대상 주문이 없습니다.</p>'
    if representative:
        observed = recovered[representative]
        order_html = (f'<p><strong>대표 주문 {esc(representative)}</strong></p>' +
                      table(("단계", "시각"), [
                          ("payment 종료", esc(stamp(killed))),
                          ("재기동 요청", esc(stamp(fault.get("restartRequestedMs")))),
                          ("서버 정상 응답", esc(stamp(health))),
                          ("DONE 전이", esc(stamp(observed.get("doneAtMs")))),
                          ("재고 적용", esc(stamp(observed.get("stockAppliedAtMs")))),
                      ]))
    qualifier = '' if eligible else '<p class="note">부하 유지·관측 완료·중단 당시 미종결 접수 주문의 근거가 모두 필요하다. 현재 회복 판정은 근거 부족입니다.</p>'
    return ('<p class="micro">중단 당시 처리 중이던 주문과 서버가 다시 정상화될 때까지 접수된 주문을 '
            '추적 목록으로 고정했다. 이 목록의 완료와 재고 반영을 확인한다.</p>'
            + qualifier + table(("운영 기준", "실측", "판정"), rows) +
            f'<p class="micro">서버 중단: {esc(stamp(killed))} · 서버 정상 응답: {esc(stamp(health))} · '
            f'회복 기한: {esc(stamp(fault.get("recoveryDeadlineMs")))}</p>' +
            order_html + '<h4>장애 진행 기록</h4>' + timeline_table(events))


def findings(result, settlement, environment, resources, run, kind):
    messages = []
    if not run:
        messages.append("실행 전체 판정(run.json) 없음")
    elif run.get("executionError"):
        messages.append(f"실행 오류: {run['executionError']}")
    if run and run.get("sourceChanged") is True:
        messages.append("측정 중 소스 변경: 기록된 PASS를 통과 근거로 사용하지 않음")
    if result:
        for issue in result.get("validityIssues") or []:
            messages.append(f"계측 제한: {issue}")
        for goal, verdict in (result.get("goals") or {}).items():
            if verdict == "FAIL":
                label = "장애 중 사용자 영향" if kind == "fault" else "품질 목표 미달"
                messages.append(f"{label}: {goal}")
        counts = result.get("counts") or {}
        for key, label in (("missingFinish", "종료 기록 누락"),
                           ("dropped", "부하 발생기가 보내지 못한 시도"),
                           ("acceptanceUnknown", "서버 접수 여부 확인 불가")):
            if counts.get(key):
                messages.append(f"{label}: {counts[key]}건")
    if settlement:
        for key, label in (("failures", "주문·재고 불일치"),
                           ("pending", "대조를 완료하지 못한 항목"),
                           ("unknownAcceptance", "영속 접수 여부 확인 불가"),
                           ("unattributedNewOrders", "시도 기록에 없는 신규 주문")):
            entries = settlement.get(key) or []
            if entries:
                messages.append(f"{label}: {len(entries)}건 — 원자료 확인 필요")
    if not environment:
        messages.append("실행 환경 캡처 없음")
    if any("captureError" in sample for sample in resources):
        messages.append("자원 표본 일부 수집 실패")
    if not messages:
        messages.append("현재 원자료의 판정·대조에서 보고된 문제 없음. 다른 실패 경로는 이 실행만으로 검증되지 않음.")
    return '<ul>' + ''.join(f'<li>{esc(item)}</li>' for item in messages) + '</ul>'


def originals(directory):
    images = [p for p in (directory / "originals").rglob("*")
              if p.is_file() and p.suffix.lower() in (".png", ".jpg", ".jpeg", ".webp")]
    if not images:
        return '<p class="quiet">원본 화면 캡처 없음</p>'
    cards = []
    captions = read_json(directory / 'originals' / 'captions.json') or {}
    for path in sorted(images):
        rel = path.relative_to(directory.parent)
        href = esc(quote(str(rel), safe="/.-_"))
        caption = captions.get(path.name, path.name)
        cards.append(f'<figure class="shot"><a href="{href}"><img src="{href}" loading="lazy" '
                     f'alt="{esc(caption)}"></a><figcaption>{esc(caption)} · 원본 열기</figcaption></figure>')
    return '<div class="shots">' + ''.join(cards) + '</div>'


def run_section(root, run_id, title, kind, excluded=False):
    directory = root / run_id
    run = read_json(directory / "run.json")
    result = read_json(directory / "result.json")
    settlement = read_json(directory / "settlement.json")
    command = read_json(directory / "command.json")
    environment = read_json(directory / "environment.json")
    fault = read_json(directory / "fault.json")
    events = read_jsonl(directory / "timeline.jsonl")
    resources = read_jsonl(directory / "resources.jsonl")
    if not directory.is_dir() or not result:
        if not directory.is_dir():
            label = "미실행"
        elif excluded:
            label = "EXCLUDED"
        elif run:
            label = official_verdict(run, kind, fault, result=result, settlement=settlement,
                                     expected_run_id=run_id)
        else:
            label = "수집 중 / 결과 미완성"
        evidence = f'<p class="micro">{raw_links(directory)}</p>' if directory.is_dir() else ""
        note = (run or {}).get("executionError") or (run or {}).get("note") or "수치는 채우지 않았습니다."
        return f'<section id="{esc(run_id)}" class="run"><div class="run-head"><h2>{esc(title)}</h2>{chip(label)}</div><p class="quiet">{esc(note)}</p>{evidence}</section>'
    status = official_verdict(run, kind, fault, excluded, result, settlement, run_id)
    command_text = safe_command(command, run_id)
    environment_summary = ""
    if environment:
        environment_summary = (f'<p class="micro">호스트: {esc(environment.get("host", "—"))} · '
                               f'Docker: {esc(environment.get("dockerVersion", "—"))} · '
                               f'k6: {esc(environment.get("k6Version", "—"))} · '
                               f'기준 Git HEAD: {esc(environment.get("gitCommit", "—"))} · '
                               '미커밋 측정 도구는 실행별 소스 사본·해시를 참조한다.</p>')
    fault_html = ('<h3>탐지와 회복</h3>' + fault_section(fault, events)) if kind == "fault" else ""
    recorded = ((f'<p class="micro">run.json 기록 판정 {chip(run.get("verdict"))}; '
                 '확정 근거 적격성을 반영한 실행 전체 판정은 위에 표시했다.</p>')
                if run and run.get("verdict") != status else "")
    return (f'<section id="{esc(run_id)}" class="run"><div class="run-head"><h2>{esc(title)}</h2>'
            f'<span>이 시험의 결론 {chip(status)}</span> '
            f'<span>{"중단 중 사용자 결과" if kind == "fault" else "사용자 결과"} {chip(result.get("verdict"))}</span> '
            f'<span>주문·재고 대조 {chip((settlement or {}).get("verdict"))}</span></div>'
            f'<p class="micro">실행 ID {esc(run_id)} · 계획 도착률 {number(result.get("rate"))}시도/초 · '
            f'유입 시간 {number(result.get("durationSeconds"))}초</p>'
            + recorded
            + quality_table(result, settlement, kind)
            + fault_html + '<h3>중간 과정과 확인할 문제</h3>'
            + findings(result, settlement, environment, resources, run, kind)
            + '<details class="audit"><summary>측정 조건·자원 사용·명령·원자료 펼치기</summary>'
            + f'<p class="micro">측정 구간 {esc(stamp(result.get("measurementStartMs")))} ~ '
              f'{esc(stamp(result.get("measurementEndMs")))} · 종료 코드 {number((run or {}).get("exitCode"))}</p>'
            + '<h3>실행 환경과 자원</h3>' + environment_summary + resource_chart(resources)
            + '<h3>재현 명령</h3><pre><code>' + esc(command_text) + '</code></pre>'
            + '<h3>원자료</h3><p class="links">' + raw_links(directory) + '</p>'
            + '<h3>원본 화면</h3>' + originals(directory) + '</details></section>')


def render(root):
    root = Path(root)
    root.mkdir(parents=True, exist_ok=True)
    exclusion_html, excluded, prior_exclusion = exclusion_section(root)
    clean_html = clean_environment_section(root)
    overview_html = overview_section(root, excluded)
    figures_html = ""
    for name, caption in (('normal-latency.png', '정상 두 회의 접수·완료 관측 p95. 완료 관측에는 500ms 폴링 간격이 포함된다.'),
                          ('fault-load-comparison.png', '장애 두 회의 실제 유입과 사용자 기한 내 완료율. 음영은 프로세스 중단 구간이다.')):
        if (root / 'figures' / name).is_file():
            figures_html += (f'<figure class="shot"><a href="figures/{name}">'
                             f'<img src="figures/{name}" alt="{esc(caption)}"></a>'
                             f'<figcaption>{esc(caption)}</figcaption></figure>')
    if figures_html:
        figures_html = ('<section class="run"><h2>실측으로 그린 흐름</h2>'
                        '<div class="shots">' + figures_html + '</div></section>')
    cards = []
    summary_rows = []
    for run_id, title, kind in RUNS:
        result = read_json(root / run_id / "result.json")
        settle = read_json(root / run_id / "settlement.json")
        run = read_json(root / run_id / "run.json")
        fault = read_json(root / run_id / "fault.json") if kind == "fault" else None
        state = ("미실행" if not (root / run_id).is_dir() else
                 official_verdict(run, kind, fault, excluded, result, settle, run_id))
        if (root / run_id).is_dir() and not run:
            state = "EXCLUDED" if excluded else "수집 중 / 판정 미완성"
        cards.append(f'<a class="nav-card" href="#{esc(run_id)}"><span>{esc(title)}</span>'
                     f'{chip(state)}<small>사용자 품질 {esc((result or {}).get("verdict", "—"))} · '
                     f'재고 대조 {esc((settle or {}).get("verdict", "—"))}</small></a>')
        note = ("측정 중 소스 변경" if (run or {}).get("sourceChanged") is True else
                "장애 중 사용자 영향 별도" if kind == "fault" and result else
                "원자료 없음" if not result else "실행별 원자료 확인")
        summary_rows.append((f'<a href="#{esc(run_id)}">{esc(title)}</a>',
                             chip((run or {}).get("verdict")), chip(state), esc(note)))
    if prior_exclusion:
        summary_rows.insert(0, (link(prior_exclusion, "이전 측정"), "—", chip("EXCLUDED"),
                                "이전 측정의 제외 사유는 원자료 참조"))
    journal_path = root / "JOURNAL.md"
    journal = link("JOURNAL.md", "실행 중 결정·발견 기록") if journal_path.is_file() else "기록 없음"
    probe_note = (f'<p>{link("measurement-probes/README.md", "상태 응답 검증 프로브 기록")} '
                  '— 해당 임시 시험의 원자료는 보존되지 않아 독립 재계산이 불가능하다.</p>') if (root / "measurement-probes/README.md").is_file() else ""
    journal_items = []
    if journal_path.is_file():
        journal_items = [line.strip()[2:].strip() for line in journal_path.read_text(encoding="utf-8").splitlines()
                         if line.lstrip().startswith("- ")]
    journal_preview = ('<ul>' + ''.join(f'<li>{esc(item)}</li>' for item in journal_items[-12:]) + '</ul>')
    if not journal_items:
        journal_preview = '<p class="quiet">작업 기록 항목 없음</p>'
    run_html = "".join(run_section(root, *run, excluded=excluded) for run in RUNS)
    summary_html = ('<details class="run audit"><summary>실행별 종합 판정과 근거 적격성</summary>'
                    '<p class="micro">기록 판정과 확정 근거 적격성을 나란히 표시한다. '
                    '이전 측정과 실행 중 소스가 바뀐 기록은 PASS 주장으로 사용하지 않는다.</p>'
                    + table(("실행", "run.json 기록", "확정 근거 판정", "읽을 때 참고"), summary_rows)
                    + '</details>')
    return f'''<!doctype html>
<html lang="ko"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Payment Platform | 실행·품질·운영 검증</title><style>
:root{{--ink:#142633;--muted:#506575;--paper:#f6f8fa;--card:#fff;--line:#dce4e9;--accent:#075985}}
*{{box-sizing:border-box}}body{{margin:0;background:var(--paper);color:var(--ink);font:15px/1.65 system-ui,-apple-system,"Apple SD Gothic Neo",sans-serif}}
main{{max-width:1120px;margin:auto;padding:40px 24px 90px}}a{{color:var(--accent);text-decoration:underline;text-underline-offset:3px}}
header{{padding:42px;background:#142633;color:white;border-radius:20px}}header p{{max-width:760px;color:#d9e8ee}}
h1{{font-size:clamp(28px,4vw,43px);line-height:1.2;margin:8px 0 15px}}h2{{font-size:24px;margin:0}}h3{{font-size:18px;margin:32px 0 10px}}h4{{margin:24px 0 8px}}
.eyebrow{{letter-spacing:.14em;text-transform:uppercase;font-size:12px;font-weight:800;color:#8fd3e5}}
.lede{{font-size:17px}}.nav{{display:grid;grid-template-columns:repeat(4,1fr);gap:12px;margin:26px 0}}
.nav-card{{background:white;border:1px solid var(--line);padding:17px;border-radius:13px;text-decoration:none;color:var(--ink);display:grid;gap:8px}}
.nav-card span:first-child{{font-weight:750}}.nav-card small{{color:var(--muted)}}.run{{background:white;border:1px solid var(--line);border-radius:18px;padding:30px;margin:25px 0;box-shadow:0 8px 30px #11223308}}
.run-head{{display:flex;align-items:center;gap:10px;flex-wrap:wrap}}.chip{{display:inline-block;padding:3px 10px;border-radius:999px;font-size:12px;font-weight:750;background:#e9eff2;color:#475569}}
.chip.pass{{background:#daf3e5;color:#116037}}.chip.fail{{background:#fde6e2;color:#9a271c}}.chip.unknown{{background:#fff0ca;color:#835500}}
.micro,.quiet{{font-size:13px;color:var(--muted)}}.quiet{{padding:10px 0}}.note{{background:#eef5fa;border-left:3px solid var(--accent);padding:10px 13px;border-radius:0 8px 8px 0}}
.table-wrap{{overflow-x:auto}}table{{width:100%;border-collapse:collapse;margin:12px 0}}th,td{{text-align:left;vertical-align:top;border-bottom:1px solid var(--line);padding:11px 10px}}th{{background:#f0f5f7;font-size:13px}}td:first-child{{font-weight:600}}
pre{{padding:15px 18px;background:#102433;color:#edf7fa;border-radius:10px;overflow:auto}}.links{{display:flex;flex-wrap:wrap;gap:8px}}.links a{{background:#eef4f7;border-radius:6px;padding:2px 7px;font-size:12px}}
.chart{{margin:16px 0}}svg{{display:block;width:100%;max-height:300px;background:#fcfdfe;border:1px solid var(--line);border-radius:8px}}
figcaption{{font-size:12px;color:var(--muted)}}.legend{{display:flex;flex-wrap:wrap;gap:12px;margin-top:8px;font-size:12px}}.legend i{{display:inline-block;width:10px;height:10px;margin-right:5px;border-radius:50%}}
.shots{{display:grid;grid-template-columns:repeat(2,1fr);gap:15px}}.shot{{margin:0}}.shot img{{max-width:100%;border:1px solid var(--line);border-radius:7px}}
.overview{{border-color:#b9d8e5;background:linear-gradient(145deg,#fff,#f4fbfe)}}.overview h2{{margin:4px 0 10px}}
.story-grid{{display:grid;grid-template-columns:repeat(2,1fr);gap:13px;margin:20px 0}}.story-card{{padding:18px;border:1px solid var(--line);border-radius:12px;background:white}}
.story-card h3,.story-fault h3{{margin:0 0 9px}}.story-card p,.story-fault p{{margin:8px 0}}.story-fault{{border-left:4px solid #b45309;background:#fffaf0;padding:15px 19px;border-radius:0 10px 10px 0}}
details.audit>summary{{cursor:pointer;font-size:16px;font-weight:750;color:var(--accent);list-style-position:outside}}details.audit>summary:hover{{text-decoration:underline}}
details.audit[open]>summary{{margin-bottom:20px}}.run>details.audit{{border-top:1px solid var(--line);margin-top:25px;padding-top:17px}}
@media(max-width:780px){{.nav{{grid-template-columns:repeat(2,1fr)}}.shots,.story-grid{{grid-template-columns:1fr}}main{{padding:15px 13px 50px}}header{{padding:28px}}.run{{padding:18px}}}}
</style></head><body><main><header><div class="eyebrow">Payment Platform · Local Evidence</div>
<h1>실행 조건에서 결제는 어디까지 완료되는가</h1>
<p class="lede">로컬 단일 호스트의 서비스별 컨테이너 구성에서 정상 부하와 payment 중단·회복을 검증한다. 각 결과는 원자료에 연결되며 실행하지 않은 조건은 비워 둔다.</p></header>
{exclusion_html}{overview_html}
<nav class="nav" aria-label="실행별 이동">{''.join(cards)}</nav>
<section class="run"><h2>가정과 목표</h2><p>일반 온라인 상점의 가상 하루 결제 시도 30,000건 중 20%가 프로모션 10분에 집중한다고 가정했다. 계산상 정상 설계 유입은 초당 10시도다. 이는 실사용량이나 실제 완료 TPS가 아니다.</p>
{table(("목표", "기준", "해석"), [
    ("confirm 접수 응답", "p95 500ms 이하", "호출 전체 시간, 정상 실행별 판정"),
    ("빠른 완료", "전체 시도의 ≥95%가 5초 내 DONE", "confirm 호출 시작부터 관측"),
    ("정상 완료", "전체 시도의 ≥99.9%가 60초 내 DONE", "checkout 시작부터 관측"),
    ("내부 정합성", "설명하지 못한 주문·재고 불일치 0건", "시작 재고와 고유 DONE 주문으로 대조"),
    ("탐지·회복", "중단 알람 120초 이내 · 서버 정상화 후 주문 회복 5분 이내", "대표 프로세스 장애 한정"),
])}
<p class="micro">목표는 사전 설정한 제품·운영 가정이다. 정상 실행 둘의 p95나 성공률을 평균 내지 않는다. 장애 실행의 사용자 영향은 별도 표로 읽는다.</p></section>
{figures_html}{run_html}{summary_html}{clean_html}
<section class="run"><h2>근거와 해석 범위</h2>
<p>이 보고서의 수치는 각 실행의 result.json과 settlement.json에서 읽었다. 재현 명령은 command.json에 기록된 부하 조건·실행 ID·프로젝트·출력 경로로 표시하며, 필요한 기록이 없으면 명령을 추정하지 않는다. 상세 환경, 시작·종료 스냅샷, 원본 로그와 시계열은 각 실행의 원자료 링크에서 볼 수 있다.</p>
<p>로컬 Fake PG 정상 승인 조건의 플랫폼 주문·재고와 선택한 payment 프로세스 장애를 검증한다. 외부 상시 배포, 호스트 장애, 실제 PG 과금, 월간 SLO는 이 자료로 입증하지 않는다.</p>
<details class="audit"><summary>실행 중 발견과 계측 점검 기록</summary><p>{journal}</p>{journal_preview}{probe_note}</details></section>
<footer class="micro">Generated from local evidence. Empty stages remain untested.</footer>
</main></body></html>'''


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path("live-drill/quality-baseline"))
    args = parser.parse_args()
    output = args.root / "report.html"
    output.write_text(render(args.root), encoding="utf-8")
    print(output)


if __name__ == "__main__":
    main()
