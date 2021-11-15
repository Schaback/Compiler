package compiler.ast;

import compiler.Token;

import java.util.List;

public final class IntLiteral extends Expression {
    private final String value;

    public IntLiteral(Token value) {
        super();
        this.isError |= value == null;
        setSpan(value);

        this.value = value != null ? value.getIntLiteralContent() : null;
    }

    @Override
    public boolean syntacticEq(AstNode otherAst) {
        if (!(otherAst instanceof IntLiteral other)) {
            return false;
        }
        return this.value.equals(other.value);
    }

    public String getValue() {
        return value;
    }
}
