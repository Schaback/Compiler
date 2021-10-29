package compiler.ast;

import java.util.ArrayList;
import compiler.utils.StreamUtils;

import java.util.List;
import java.util.Objects;

public class Method extends AstNode {

    private boolean isStatic;

    private String identifier;

    private Type returnType;

    private List<Parameter> parameters;

    private Block body;

    public Method(boolean isStatic, String identifier, Type returnType, List<Parameter> parameters, Block body) {
        this.isError |= identifier == null || returnType == null || parameters.stream().anyMatch(Objects::isNull) || body == null;

        this.isStatic = isStatic;
        this.identifier = identifier;
        this.returnType = returnType;
        this.parameters = parameters;
        this.body = body;
    }

    @Override
    public List<AstNode> getChildren() {
        ArrayList<AstNode> temp = new ArrayList<>();
        temp.add(returnType);
        temp.addAll(parameters);
        temp.add(body);
        return temp;
    }

    @Override
    public String getName() {
        return identifier;
    }

    @Override
    public boolean syntacticEq(AstNode otherAst) {
        if (!(otherAst instanceof Method other)) {
            return false;
        }
        return this.isStatic == other.isStatic
                && this.identifier.equals(other.identifier)
                && this.returnType.syntacticEq(other.returnType)
                && StreamUtils.zip(this.parameters.stream(), other.parameters.stream(), AstNode::syntacticEq).allMatch(x -> x)
                && this.body.syntacticEq(other.body);
    }
}
