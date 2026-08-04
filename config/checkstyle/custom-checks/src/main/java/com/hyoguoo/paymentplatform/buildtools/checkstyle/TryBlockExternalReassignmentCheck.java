package com.hyoguoo.paymentplatform.buildtools.checkstyle;

import com.puppycrawl.tools.checkstyle.api.AbstractCheck;
import com.puppycrawl.tools.checkstyle.api.DetailAST;
import com.puppycrawl.tools.checkstyle.api.TokenTypes;
import java.util.ArrayList;
import java.util.List;

/**
 * try 블록 밖에서 선언한(초기화 여부 무관) 지역 변수를 try 블록 "안"에서
 * 재할당하는 패턴을 검출한다 (code-style.md "Try 블록 패턴"). catch·
 * finally 블록 안 재할당은 대상이 아니다 — 규칙 문구가 명시적으로
 * "try 블록 안"만 금지한다.
 *
 * <p>검사 범위는 try 문과 같은 블록에서 바로 앞서 선언된 지역 변수로
 * 한정한다. 중첩 블록(예: if 안의 try) 밖 바깥 스코프에서 선언된 변수
 * 까지는 추적하지 않는다 — 실제 위반 사례가 모두 같은 블록 레벨이라
 * 이 범위로 충분하고, 범위를 넓히면 섀도잉 오탐 위험이 커진다.
 */
public class TryBlockExternalReassignmentCheck extends AbstractCheck {

    public static final String MSG_KEY = "tryblock.external.reassignment";

    @Override
    public int[] getDefaultTokens() {
        return getAcceptableTokens();
    }

    @Override
    public int[] getAcceptableTokens() {
        return new int[] {TokenTypes.LITERAL_TRY};
    }

    @Override
    public int[] getRequiredTokens() {
        return getAcceptableTokens();
    }

    @Override
    public void visitToken(DetailAST tryAst) {
        DetailAST enclosingBlock = tryAst.getParent();
        if (enclosingBlock == null || enclosingBlock.getType() != TokenTypes.SLIST) {
            return;
        }

        List<String> externalVariables = collectVariablesDeclaredBefore(enclosingBlock, tryAst);
        if (externalVariables.isEmpty()) {
            return;
        }

        DetailAST tryBody = tryAst.findFirstToken(TokenTypes.SLIST);
        if (tryBody == null) {
            return;
        }

        for (String variableName : externalVariables) {
            DetailAST reassignment = findReassignment(tryBody, variableName);
            if (reassignment != null) {
                log(reassignment, MSG_KEY, variableName);
            }
        }
    }

    private List<String> collectVariablesDeclaredBefore(DetailAST enclosingBlock, DetailAST tryAst) {
        List<String> names = new ArrayList<>();
        for (DetailAST sibling = enclosingBlock.getFirstChild();
                sibling != null && sibling != tryAst;
                sibling = sibling.getNextSibling()) {
            if (sibling.getType() == TokenTypes.VARIABLE_DEF) {
                DetailAST identAst = sibling.findFirstToken(TokenTypes.IDENT);
                if (identAst != null) {
                    names.add(identAst.getText());
                }
            }
        }
        return names;
    }

    private DetailAST findReassignment(DetailAST subtree, String variableName) {
        for (DetailAST child = subtree.getFirstChild(); child != null; child = child.getNextSibling()) {
            // 람다·클래스·메서드는 별도 스코프라 내려가지 않는다(섀도잉 오탐 방지).
            if (isNewScope(child)) {
                continue;
            }
            if (isReassignmentOf(child, variableName)) {
                return child;
            }
            DetailAST found = findReassignment(child, variableName);
            if (found != null) {
                return found;
            }
        }
        return null;
    }

    private boolean isNewScope(DetailAST ast) {
        int type = ast.getType();
        return type == TokenTypes.LAMBDA
                || type == TokenTypes.CLASS_DEF
                || type == TokenTypes.ENUM_DEF
                || type == TokenTypes.INTERFACE_DEF
                || type == TokenTypes.METHOD_DEF;
    }

    private boolean isReassignmentOf(DetailAST ast, String variableName) {
        if (ast.getType() != TokenTypes.ASSIGN) {
            return false;
        }

        DetailAST parent = ast.getParent();
        if (parent == null || parent.getType() != TokenTypes.EXPR) {
            return false;
        }

        DetailAST lhs = ast.getFirstChild();
        return lhs != null && lhs.getType() == TokenTypes.IDENT && variableName.equals(lhs.getText());
    }
}
