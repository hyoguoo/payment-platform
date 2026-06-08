// @ts-check
'use strict';

/**
 * 취합 job에서 6서비스 커버리지 XML + lint 요약 아티팩트를 읽어
 * PR 단일 코멘트로 조립한다.
 *
 * 호출 방식 (ci.yml report job — actions/github-script@v9):
 *   const script = require('./.github/scripts/report-comment.js');
 *   await script({ github, context, artifactsDir });
 *
 * @param {{ github: object, context: object, artifactsDir: string }} params
 *   artifactsDir: 다운로드된 아티팩트 루트 디렉토리 경로
 */
module.exports = async ({ github, context, artifactsDir }) => {
    const fs = require('fs');
    const path = require('path');

    const SERVICES = [
        'payment-service',
        'pg-service',
        'product-service',
        'user-service',
        'gateway',
        'eureka-server',
    ];

    // ── lint 요약 수집 ───────────────────────────────────────────────────────
    /**
     * @type {{ service: string, checkstyle_main: number, checkstyle_test: number, spotbugs: number }[]}
     */
    const lintResults = [];
    for (const svc of SERVICES) {
        const lintFile = path.join(artifactsDir, `lint-${svc}`, `lint-${svc}.json`);
        try {
            const raw = fs.readFileSync(lintFile, 'utf8');
            lintResults.push(JSON.parse(raw));
        } catch {
            // 아티팩트 없음 (서비스 job 실패 등) — 0 으로 채움
            lintResults.push({ service: svc, checkstyle_main: 0, checkstyle_test: 0, spotbugs: 0 });
        }
    }

    // ── 커버리지 XML 파싱 (report-level 총계 LINE 카운터) ───────────────────
    /**
     * JaCoCo XML 에서 report-level 총계 LINE 카운터를 파싱한다.
     * JaCoCo XML 은 class/package/sourcefile 별 카운터가 앞에 오고
     * <report> 직속 총계 카운터가 문서 끝에 위치하므로, 모든 type="LINE" 매치 중
     * 마지막 매치가 report-level 총계다.
     *
     * @param {string} xmlPath
     * @returns {{ covered: number, missed: number } | null}
     */
    function parseJacocoCoverage(xmlPath) {
        try {
            const xml = fs.readFileSync(xmlPath, 'utf8');
            // global 플래그로 모든 LINE 카운터를 수집한 뒤 마지막(report-level 총계)을 사용.
            const matches = [...xml.matchAll(/type="LINE"\s+missed="(\d+)"\s+covered="(\d+)"/g)];
            if (matches.length === 0) return null;
            const last = matches[matches.length - 1];
            return { missed: parseInt(last[1], 10), covered: parseInt(last[2], 10) };
        } catch {
            return null;
        }
    }

    /**
     * @type {{ service: string, covered: number, missed: number, pct: string }[]}
     */
    const coverageResults = [];
    for (const svc of SERVICES) {
        // upload-artifact v4+ 는 단일 파일 업로드 시 LCA(파일 부모)를 루트로 평탄화한다.
        // download 후 실제 경로: artifacts/coverage-<svc>/jacocoTestReport.xml (평탄 경로).
        // lint 아티팩트와 동일 규약으로 통일.
        const xmlPath = path.join(
            artifactsDir,
            `coverage-${svc}`,
            'jacocoTestReport.xml',
        );
        const cov = parseJacocoCoverage(xmlPath);
        if (cov) {
            const total = cov.covered + cov.missed;
            const pct = total > 0
                ? ((cov.covered / total) * 100).toFixed(2) + '%'
                : 'N/A';
            coverageResults.push({ service: svc, covered: cov.covered, missed: cov.missed, pct });
        } else {
            coverageResults.push({ service: svc, covered: 0, missed: 0, pct: 'N/A' });
        }
    }

    // ── 코멘트 본문 조립 ──────────────────────────────────────────────────────
    const lintTotalViolations = lintResults.reduce(
        (sum, r) => sum + r.checkstyle_main + r.checkstyle_test + r.spotbugs,
        0,
    );
    const lintIcon = lintTotalViolations === 0 ? '✅' : '❌';

    const coverageRows = coverageResults
        .map(r => `| ${r.service} | ${r.pct} | ${r.covered} | ${r.missed} |`)
        .join('\n');

    const lintRows = lintResults
        .map(r => {
            const total = r.checkstyle_main + r.checkstyle_test + r.spotbugs;
            const icon = total === 0 ? '✅' : '❌';
            return `| ${r.service} | ${r.checkstyle_main} | ${r.checkstyle_test} | ${r.spotbugs} | ${icon} |`;
        })
        .join('\n');

    const body = [
        '<!-- report-comment -->',
        '## CI 결과 요약',
        '',
        '### 커버리지 (라인)',
        '| 서비스 | 커버리지 | covered | missed |',
        '|--------|---------|---------|--------|',
        coverageRows,
        '',
        `### ${lintIcon} Lint (Checkstyle + SpotBugs)`,
        '| 서비스 | Checkstyle(main) | Checkstyle(test) | SpotBugs | 결과 |',
        '|--------|-----------------|-----------------|---------|------|',
        lintRows,
        '',
        lintTotalViolations === 0
            ? '위반 사항 없음.'
            : `**전체 ${lintTotalViolations}건 위반.** 인라인 리뷰 코멘트를 확인하세요.`,
    ].join('\n');

    // ── update-or-create 패턴 (코멘트 난립 방지, O4) ─────────────────────────
    const { data: comments } = await github.rest.issues.listComments({
        owner: context.repo.owner,
        repo: context.repo.repo,
        issue_number: context.issue.number,
    });

    const existing = comments.find(c => c.body.includes('<!-- report-comment -->'));
    if (existing) {
        await github.rest.issues.updateComment({
            owner: context.repo.owner,
            repo: context.repo.repo,
            comment_id: existing.id,
            body,
        });
    } else {
        await github.rest.issues.createComment({
            owner: context.repo.owner,
            repo: context.repo.repo,
            issue_number: context.issue.number,
            body,
        });
    }
};
