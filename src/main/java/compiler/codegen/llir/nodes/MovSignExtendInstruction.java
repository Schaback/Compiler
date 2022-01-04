package compiler.codegen.llir.nodes;

import compiler.codegen.llir.BasicBlock;
import compiler.codegen.llir.Register;

import java.util.stream.Stream;

public final class MovSignExtendInstruction extends RegisterNode {

    private RegisterNode input;

    public MovSignExtendInstruction(BasicBlock bb, RegisterNode input) {
        super(bb);
        assert input.getTargetRegister().getWidth() == Register.Width.BIT32;

        this.input = input;

        initTargetRegister(Register.Width.BIT64);
    }

    public RegisterNode getInput() {
        return input;
    }

    @Override
    public Stream<LlirNode> getPreds() {
        return Stream.of(this.input);
    }

    @Override
    public int getPredSize() {
        return 1;
    }

    @Override
    public String getMnemonic() {
        return "movsx";
    }
}
