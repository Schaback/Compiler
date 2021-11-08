package compiler.ast;

import java.util.ArrayList;
import java.util.List;

import compiler.Token;
import compiler.utils.OptionalUtils;

import java.util.Objects;
import java.util.Optional;

public final class LocalVariableDeclarationStatement extends Statement implements VariableDefinition {

    private Type type;
    private String identifier;

    private Optional<Expression> initializer;

    public LocalVariableDeclarationStatement(Type type, Token identifier, Optional<Token> assign, Optional<Expression> initializer) {
        super();
        //noinspection ConstantConditions
        this.isError |= type == null || identifier == null || initializer.map(Objects::isNull).orElse(false);

        setSpan(type, identifier, new OptionalWrapper(assign), new OptionalWrapper(initializer));

        this.type = type;
        this.identifier = identifier != null ? identifier.getIdentContent() : null;
        this.initializer = initializer;
    }

    public Type getType() {
        return type;
    }

    public String getIdentifier() {
        return identifier;
    }

    public Optional<Expression> getInitializer() {
        return initializer;
    }

    @Override
    public boolean syntacticEq(AstNode otherAst) {
        if (!(otherAst instanceof LocalVariableDeclarationStatement other)) {
            return false;
        }
        return this.type.syntacticEq(other.type)
                && this.identifier.equals(other.identifier)
                && OptionalUtils.combine(this.initializer, other.initializer, AstNode::syntacticEq).orElse(true);
    }

    public Type getType() {
        return type;
    }

    public String getIdentifier() {
        return identifier;
    }

    public Optional<Expression> getInitializer() {
        return initializer;
    }

    @Override
    public List<AstNode> getChildren() {
        ArrayList<AstNode> temp = new ArrayList<>();
        temp.add(type);
        initializer.ifPresent(temp::add);
        return temp;
    }

    @Override
    public String getName() {
        return identifier;
    }
}
