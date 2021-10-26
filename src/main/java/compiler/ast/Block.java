package compiler.ast;

import compiler.utils.StreamUtils;

import java.util.List;

public final class Block extends Statement {
    private List<Statement> statements;

    public Block(List<Statement> statements) {
        this.statements = statements;
    }

    @Override
    public boolean syntacticEq(AstNode otherAst) {
        if (!(otherAst instanceof Block other)) {
            return false;
        }
        return StreamUtils.zip(this.statements.stream(), other.statements.stream(), AstNode::syntacticEq).allMatch(x -> x);
    }
}
