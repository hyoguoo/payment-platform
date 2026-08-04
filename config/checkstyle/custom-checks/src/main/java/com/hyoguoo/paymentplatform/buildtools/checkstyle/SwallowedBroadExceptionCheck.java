package com.hyoguoo.paymentplatform.buildtools.checkstyle;

import com.puppycrawl.tools.checkstyle.api.AbstractCheck;
import com.puppycrawl.tools.checkstyle.api.DetailAST;
import com.puppycrawl.tools.checkstyle.api.TokenTypes;
import java.util.Set;

/**
 * 광범위 예외(Exception / RuntimeException / Throwable / Error)를 잡고
 * 아무 동작도 하지 않는 catch 블록을 검출한다
 * (error-logging.md "catch (Exception e) swallow 금지").
 *
 * <p>재throw·명시적 fallback 여부는 catch 블록 안에 문장이 하나라도
 * 있으면 이미 만족된다고 본다. 로그만 남기고 흐름을 그대로 이어가는
 * 더 약한 형태의 삼킴은 재throw/return 여부까지 구분해야 해 이 검사
 * 범위 밖이다 — 가장 명백한 형태인 "완전히 빈 catch 블록"만 잡는다.
 */
public class SwallowedBroadExceptionCheck extends AbstractCheck {

    public static final String MSG_KEY = "swallowed.broad.exception";

    private static final Set<String> BROAD_EXCEPTION_TYPES =
            Set.of("Exception", "RuntimeException", "Throwable", "Error");

    @Override
    public int[] getDefaultTokens() {
        return getAcceptableTokens();
    }

    @Override
    public int[] getAcceptableTokens() {
        return new int[] {TokenTypes.LITERAL_CATCH};
    }

    @Override
    public int[] getRequiredTokens() {
        return getAcceptableTokens();
    }

    @Override
    public void visitToken(DetailAST catchAst) {
        if (!catchesBroadException(catchAst)) {
            return;
        }

        DetailAST catchBody = catchAst.findFirstToken(TokenTypes.SLIST);
        if (catchBody != null && isEmptyBlock(catchBody)) {
            log(catchAst, MSG_KEY);
        }
    }

    private boolean catchesBroadException(DetailAST catchAst) {
        DetailAST parameterDef = catchAst.findFirstToken(TokenTypes.PARAMETER_DEF);
        if (parameterDef == null) {
            return false;
        }

        DetailAST typeAst = parameterDef.findFirstToken(TokenTypes.TYPE);
        return typeAst != null && containsBroadExceptionType(typeAst);
    }

    private boolean containsBroadExceptionType(DetailAST typeAst) {
        for (DetailAST child = typeAst.getFirstChild(); child != null; child = child.getNextSibling()) {
            if (child.getType() == TokenTypes.IDENT && BROAD_EXCEPTION_TYPES.contains(child.getText())) {
                return true;
            }
            if (containsBroadExceptionType(child)) {
                return true;
            }
        }
        return false;
    }

    private boolean isEmptyBlock(DetailAST slist) {
        // SLIST 의 첫 자식이 RCURLY 라는 것은 그 사이에 statement 가 없다는 뜻이다.
        // 주석은 AST 노드가 아니므로 "주석만 있는 catch" 도 빈 블록으로 잡힌다 —
        // 의도적으로 무시하는 케이스는 억제 목록에 사유와 함께 등재해 드러낸다.
        DetailAST firstChild = slist.getFirstChild();

        return firstChild != null && firstChild.getType() == TokenTypes.RCURLY;
    }
}
