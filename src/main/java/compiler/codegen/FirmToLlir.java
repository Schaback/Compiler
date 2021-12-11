package compiler.codegen;

import compiler.codegen.llir.*;
import firm.BackEdges;
import firm.Graph;
import firm.Mode;
import firm.Program;
import firm.nodes.*;

import java.util.*;

public class FirmToLlir implements NodeVisitor {

    /**
     * Maps firm blocks to their corresponding BasicBlocks in the LlirGraph.
     */
    private final HashMap<Block, BasicBlock> blockMap;

    /**
     * Maps firm nodes to their corresponding LlirNodes.
     */
    private final HashMap<Node, LlirNode> nodeMap;

    /**
     * The current LlirGraph we are constructing.
     */
    private final LlirGraph llirGraph;

    /**
     * Remembers nodes that are to be marked as out
     */
    private final HashMap<Node, Register> markedOutNodes;

    private final HashMap<Node, List<MovRegisterInstruction>> unsourcedMoves;

    /**
     * Remebers if node have been visited already.
     */
    private final HashSet<Node> visited;

    private final Graph firmGraph;


    private FirmToLlir(Graph firmGraph) {
        this.blockMap = new HashMap<>();
        this.nodeMap = new HashMap<>();
        this.markedOutNodes = new HashMap<>();
        this.unsourcedMoves = new HashMap<>();
        this.visited = new HashSet<>();

        var gen = new VirtualRegister.Generator();

        this.llirGraph = new LlirGraph(gen);
        this.firmGraph = firmGraph;

        this.blockMap.put(firmGraph.getStartBlock(), llirGraph.getStartBlock());
    }

    private void lower() {
        // Generate all basic blocks
        this.firmGraph.walkBlocks(block -> {
            if (this.firmGraph.getEndBlock().equals(block) || this.firmGraph.getStartBlock().equals(block)) return;
            if (!this.blockMap.containsKey(block)) {
                this.blockMap.put(block, this.llirGraph.newBasicBlock());
            }
        });

        // Create method parameter llir nodes
        // TODO: for now they are just input nodes, we probably want to support some calling convention.
        BackEdges.enable(this.firmGraph);
        var startBlock = this.llirGraph.getStartBlock();

        var startNode = this.firmGraph.getStart();

        for (var proj : BackEdges.getOuts(startNode)) {
            if (proj.node.getMode().equals(Mode.getT())) {
                for (var arg : BackEdges.getOuts(proj.node)) {
                    var i = startBlock.newInput(this.llirGraph.getVirtualRegGenerator().nextRegister());
                    this.registerLlirNode(arg.node, i);
                }
            }
        }

        // Build llir
        this.visitNode(this.firmGraph.getEnd());

        assert this.unsourcedMoves.isEmpty();

        // Remember output nodes in their respective basic blocks.
        for (Node node : this.markedOutNodes.keySet()) {
            var llirNode = this.nodeMap.get(node);
            assert llirNode instanceof RegisterNode;
            var basicBlock = llirNode.getBasicBlock();

            basicBlock.addOutput((RegisterNode) llirNode);
        }
    }

    public static LlirGraph lowerFirm() {
        for (var graph : Program.getGraphs()) {
            var entity = graph.getEntity();
            if (entity.getName().equals("_Main_bar")) {
                var f = new FirmToLlir(graph);
                f.lower();
                return f.llirGraph;
            }
        }

        throw new UnsupportedOperationException("asdf");
    }

    private BasicBlock getBlock(Node n) {
        return this.blockMap.get((Block) n.getBlock());
    }

    private void registerLlirNode(Node firmNode, LlirNode llirNode) {
        this.nodeMap.put(firmNode, llirNode);

        if (this.unsourcedMoves.containsKey(firmNode)) {
            var regNode = (RegisterNode) llirNode;
            for (var mov : this.unsourcedMoves.get(firmNode)) {
                mov.setSource(regNode);
            }
            this.unsourcedMoves.remove(firmNode);
        }
    }

    /**
     * Finds input node of a basic block for a certain register.
     * If no such input node exists, it is added to the basic block.
     */
    private InputNode getInputNode(BasicBlock block, Register register) {
        var inputNode = block.getInputNodes().stream()
                .filter(i -> i.getTargetRegister().equals(register))
                .findAny();

        return inputNode.orElseGet(() -> block.addInput(register));
    }

    /**
     * Finds the correct llir predecessor node for a firm node (with its predecessor).
     * The returned node is in the same basic block as `node`, adding an input node
     * to the current basic block if the predecessor is in a different basic block.
     */
    private LlirNode getPredLlirNode(Node node, Node predNode) {
        var currentBlock = getBlock(node);

        if (predNode instanceof Const constant) {
            // First we check if the predecessor node is a constant.
            // Constants don't have an associated basic block and are
            // created on-the-fly when needed.

            var bb = getBasicBlock(node);
            return bb.newMovImmediate(constant.getTarval().asInt());

        } else if (this.nodeMap.containsKey(predNode)) {
            // Next we see if we already created a llir node for this firm node.
            // If the predecessor is outside the current basic block we possibly
            // add an input node.

            var predLlirNode = this.nodeMap.get(predNode);

            if (predLlirNode.getBasicBlock() == currentBlock) {
                return predLlirNode;
            } else if (predLlirNode instanceof RegisterNode predRegNode){
                var input = getInputNode(currentBlock, predRegNode.getTargetRegister());
                this.markedOutNodes.put(predNode, input.getTargetRegister());
                return input;
            } else {
                throw new AssertionError("This should probably not be reacheable.");
            }
        } else {
            // Within a basic block we traverse in topological order, meaning
            // predecessors of a node are visited before itself.
            // Therefore nodeMap would have to contain `predNode` if it were
            // in the current block.
            assert !predNode.getBlock().equals(node.getBlock());

            Register inputRegister;
            if (this.markedOutNodes.containsKey(predNode)) {
                inputRegister = this.markedOutNodes.get(predNode);
            } else {
                inputRegister = this.llirGraph.getVirtualRegGenerator().nextRegister();
                this.markedOutNodes.put(predNode, inputRegister);
            }

            return getInputNode(currentBlock, inputRegister);
        }
    }

    private BasicBlock getBasicBlock(Node n) {
        var firmBlock = n instanceof Block b ? b : (Block) n.getBlock();
        var block = this.blockMap.get(firmBlock);
        assert block != null;
        return block;
    }

    private void visitNode(Node n) {
        if (!this.visited.contains(n)) {
            this.visited.add(n);

            for (var pred : n.getPreds()) {
                // Ignore keep predecssors.
                if (pred instanceof Block) continue;

                this.visitNode(pred);
            }

            n.accept(this);
        }

        // Visit other blocks, if this is a control flow node. (Every block has at least one control flow node)
        if (n instanceof End || n instanceof Return || n instanceof Jmp || n instanceof Cond) {
            for (var pred : n.getBlock().getPreds()) {
                this.visitNode(pred);
            }
        }
    }

    public void visit(Proj proj) {
        var predNode = proj.getPred();

        if (proj.getMode().equals(Mode.getX())) {
        } else if (proj.getMode().equals(Mode.getM()) && predNode instanceof Start) {

            var llirBlock = this.blockMap.get((Block)proj.getBlock());
            this.registerLlirNode(proj, new MemoryInputNode(llirBlock));

        } else if (!this.nodeMap.containsKey(proj)) {

            if (this.nodeMap.containsKey(predNode)) {
                var llirPred = getPredLlirNode(proj, predNode);
                this.registerLlirNode(proj, llirPred);
            }

        }
    }

    public void visit(Return ret) {
        var bb = getBasicBlock(ret);

        Optional<RegisterNode> llirDataPred = Optional.empty();
        if (ret.getPredCount() > 1) {
            var dataPred = ret.getPred(1);

            llirDataPred = Optional.of((RegisterNode) getPredLlirNode(ret, dataPred));
        }

        var llirRet = bb.newReturn(llirDataPred);

        llirRet.getBasicBlock().finish(llirRet);
    }

    public void visit(Add add) {
        var bb = getBasicBlock(add);

        var lhs = (RegisterNode)getPredLlirNode(add, add.getLeft());
        var rhs = (RegisterNode)getPredLlirNode(add, add.getRight());

        var llirAdd = bb.newAdd(lhs, rhs);
        this.registerLlirNode(add, llirAdd);
    }

    public void visit(Jmp jump) {
        var bb = getBasicBlock(jump);

        var targetBlock = (Block)BackEdges.getOuts(jump).iterator().next().node;
        var targetBasicBlock = this.blockMap.get(targetBlock);

        var llirJump = bb.newJump(targetBasicBlock);
        bb.finish(llirJump);
    }

    public void visit(Cmp cmp) {
        var bb = getBasicBlock(cmp);

        var lhs = (RegisterNode)getPredLlirNode(cmp, cmp.getLeft());
        var rhs = (RegisterNode)getPredLlirNode(cmp, cmp.getRight());

        var llirCmp = bb.newCmp(lhs, rhs);
        this.registerLlirNode(cmp, llirCmp);
    }

    public void visit(Cond cond) {
        var bb = getBasicBlock(cond);

        var cmpPred = (Cmp)cond.getSelector();
        var llirCmp = (CmpInstruction) getPredLlirNode(cond, cmpPred);

        var predicate = switch(cmpPred.getRelation()) {
            case Equal -> BranchInstruction.Predicate.EQUAL;
            case Less -> BranchInstruction.Predicate.LESS_THAN;
            case LessEqual -> BranchInstruction.Predicate.LESS_EQUAL;
            case Greater -> BranchInstruction.Predicate.GREATER_THAN;
            case GreaterEqual -> BranchInstruction.Predicate.GREATER_EQUAL;
            default -> throw new UnsupportedOperationException("Unsupported branch predicate");
        };

        Proj trueProj = null;
        Proj falseProj = null;
        for (var pred : BackEdges.getOuts(cond)) {
            if (pred.node instanceof Proj proj) {
                assert proj.getMode().equals(Mode.getX());

                if (proj.getNum() == 0) {
                    falseProj = proj;
                } else if (proj.getNum() == 1) {
                    trueProj = proj;
                } else {
                    throw new IllegalArgumentException("Control flow projection found with num > 1");
                }
            }
        }
        assert trueProj != null && falseProj != null;

        Block trueTargetBlock = (Block)BackEdges.getOuts(trueProj).iterator().next().node;
        var trueTargetBasicBlock = getBasicBlock(trueTargetBlock);

        Block falseTargetBlock = (Block)BackEdges.getOuts(falseProj).iterator().next().node;
        var falseTargetBasicBlock = getBasicBlock(falseTargetBlock);

        var llirBranch = bb.newBranch(predicate, llirCmp, trueTargetBasicBlock, falseTargetBasicBlock);
        this.registerLlirNode(cond, llirBranch);

        bb.finish(llirBranch);
    }

    public void visit(Phi phi) {
        var bb = getBasicBlock(phi);

        if (phi.getMode().equals(Mode.getM())) {
            var memoryInput = new MemoryInputNode(bb);
            this.registerLlirNode(phi, memoryInput);
        } else {
            var register = this.llirGraph.getVirtualRegGenerator().nextRegister();

            for (int i = 0; i < phi.getPredCount(); i++) {
                var pred = phi.getPred(i);

                if (pred instanceof Const c) {
                    var predBb = getBasicBlock(phi.getBlock().getPred(i).getBlock());
                    var mov = predBb.newMovImmediateInto(c.getTarval().asInt(), register);
                    predBb.addOutput(mov);

                } else if (pred.getBlock() != phi.getBlock()) {
                    var predRegNode = (RegisterNode) this.nodeMap.get(pred);
                    var predBb = getBlock(pred);

                    var mov = predBb.newMovRegisterInto(register, predRegNode);
                    if (predRegNode == null) {
                        this.unsourcedMoves.putIfAbsent(pred, new ArrayList<>());
                        this.unsourcedMoves.get(pred).add(mov);
                    }

                    predBb.addOutput(mov);
                } else {
                    throw new UnsupportedOperationException("Ooops, this is a critical edge which we dont handle yet");
                }
            }

            this.registerLlirNode(phi, bb.newInput(register));
        }
    }

    // These nodes are explicitely ignored
    public void visit(Start node) {}
    public void visit(Const node) {}
    public void visit(End node) {}

    // These nodes are either not yet implemented or should never occur in the
    // firm graph during lowering to the backend.
    public void visit(Raise node) { throwUnsupportedNode(node); }
    public void visit(Sel node) { throwUnsupportedNode(node); }
    public void visit(Shl node) { throwUnsupportedNode(node); }
    public void visit(Shr node) { throwUnsupportedNode(node); }
    public void visit(Shrs node) { throwUnsupportedNode(node); }
    public void visit(Size node) { throwUnsupportedNode(node); }
    public void visit(Address node) { throwUnsupportedNode(node); }
    public void visit(Align node) { throwUnsupportedNode(node); }
    public void visit(Alloc node) { throwUnsupportedNode(node); }
    public void visit(Anchor node) { throwUnsupportedNode(node); }
    public void visit(And node) { throwUnsupportedNode(node); }
    public void visit(Bad node) { throwUnsupportedNode(node); }
    public void visit(Bitcast node) { throwUnsupportedNode(node); }
    public void visit(Block node) { throwUnsupportedNode(node); }
    public void visit(Builtin node) { throwUnsupportedNode(node); }
    public void visit(Call node) { throwUnsupportedNode(node); }
    public void visit(Confirm node) { throwUnsupportedNode(node); }
    public void visit(Store node) { throwUnsupportedNode(node); }
    public void visit(Sub node) { throwUnsupportedNode(node); }
    public void visit(Switch node) { throwUnsupportedNode(node); }
    public void visit(Sync node) { throwUnsupportedNode(node); }
    public void visit(Conv node) { throwUnsupportedNode(node); }
    public void visit(CopyB node) { throwUnsupportedNode(node); }
    public void visit(Deleted node) { throwUnsupportedNode(node); }
    public void visit(Div node) { throwUnsupportedNode(node); }
    public void visit(Dummy node) { throwUnsupportedNode(node); }
    public void visit(Tuple node) { throwUnsupportedNode(node); }
    public void visit(Unknown node) { throwUnsupportedNode(node); }
    public void visitUnknown(Node node) { throwUnsupportedNode(node); }
    public void visit(Eor node) { throwUnsupportedNode(node); }
    public void visit(Free node) { throwUnsupportedNode(node); }
    public void visit(IJmp node) { throwUnsupportedNode(node); }
    public void visit(Id node) { throwUnsupportedNode(node); }
    public void visit(Load node) { throwUnsupportedNode(node); }
    public void visit(Member node) { throwUnsupportedNode(node); }
    public void visit(Minus node) { throwUnsupportedNode(node); }
    public void visit(Mod node) { throwUnsupportedNode(node); }
    public void visit(Mul node) { throwUnsupportedNode(node); }
    public void visit(Mulh node) { throwUnsupportedNode(node); }
    public void visit(Mux node) { throwUnsupportedNode(node); }
    public void visit(NoMem node) { throwUnsupportedNode(node); }
    public void visit(Not node) { throwUnsupportedNode(node); }
    public void visit(Offset node) { throwUnsupportedNode(node); }
    public void visit(Or node) { throwUnsupportedNode(node); }
    public void visit(Pin node) { throwUnsupportedNode(node); }

    private void throwUnsupportedNode(Node n) {
        throw new UnsupportedOperationException(String.format("Instruction selection doesn't support for nodes of type '%s'.", n.getClass().getName()));
    }
}
