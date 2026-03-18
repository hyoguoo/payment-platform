// @ts-check
const fs = require('fs');

/**
 * @param {string} file
 * @param {RegExp} pattern
 * @returns {number}
 */
function countMatches(file, pattern) {
    try {
        return (fs.readFileSync(file, 'utf8').match(pattern) || []).length;
    } catch {
        return 0;
    }
}

module.exports = async ({ github, context }) => {
    const csMain = countMatches('build/reports/checkstyle/main.xml', /<error /g);
    const csTest = countMatches('build/reports/checkstyle/test.xml', /<error /g);
    const sb = countMatches('build/reports/spotbugs/main.xml', /<BugInstance /g);
    const total = csMain + csTest + sb;

    const icon = total === 0 ? '✅' : '❌';
    const detail = total === 0
        ? '위반 사항 없음.'
        : `**${total}건 위반.** 인라인 리뷰 코멘트를 확인하세요.`;
    const body = [
        '<!-- lint-summary -->',
        `## ${icon} Lint (Checkstyle + SpotBugs)`,
        '| 도구 | 위반 수 |',
        '|------|--------|',
        `| Checkstyle (main) | ${csMain} |`,
        `| Checkstyle (test) | ${csTest} |`,
        `| SpotBugs | ${sb} |`,
        '',
        detail,
    ].join('\n');

    const { data: comments } = await github.rest.issues.listComments({
        owner: context.repo.owner,
        repo: context.repo.repo,
        issue_number: context.issue.number,
    });

    const existing = comments.find(c => c.body.includes('<!-- lint-summary -->'));
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
