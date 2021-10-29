package compiler.ast;

import compiler.utils.OptionalUtils;

import java.util.Objects;
import java.util.Optional;

public final class ReturnStatement extends Statement {
    private Optional<Expression> expression;

    @SuppressWarnings("ConstantConditions")
    public ReturnStatement(Optional<Expression> expression) {
        this.isError |= expression.map(Objects::isNull).orElse(false);

        this.expression = expression;
    }

    @Override
    public boolean syntacticEq(AstNode otherAst) {
        if (!(otherAst instanceof ReturnStatement other)) {
            return false;
        }
        return OptionalUtils.combine(this.expression, other.expression, AstNode::syntacticEq).orElse(true);
    }

}
