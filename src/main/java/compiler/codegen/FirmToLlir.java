package compiler.codegen;

import compiler.FrontendResult;
import compiler.TranslationResult;
import compiler.codegen.llir.*;
import compiler.utils.GenericNodeWalker;
import firm.*;
import firm.nodes.*;

import java.util.*;
import java.util.function.Function;

public class FirmToLlir implements NodeVisitor {

    private final TranslationResult translation;

    /**
     * Maps firm blocks to their corresponding BasicBlocks in the LlirGraph.
     */
    private final HashMap<Block, BasicBlock> blockMap;

    /**
     * Stores the number of incoming and outgoing edges of a block.
     * We need this to determine whether an edge is critical.
     */
    private static class BlockEdges{ int incoming; int outgoing; }
    private final HashMap<Block, BlockEdges> blockEdges;

    /**
     * On critical edges we need to insert basic blocks to resolve phi nodes.
     * We store them in this hashmap where the key consists of the target block (which contains the phi)
     * and the edge index. (phiNode.getPred(idx))
     */
    private record Edge(Block target, int idx) {}
    private final HashMap<Edge, BasicBlock> insertedBlocks;

    /**
     * Maps firm nodes to their corresponding LlirNodes.
     */
    private final HashMap<Node, LlirNode> nodeMap;

    /**
     * The current LlirGraph we are constructing.
     */
    private final LlirGraph llirGraph;

    /**
     * Remembers nodes that are to be marked as output nodes.
     */
    private final HashMap<Node, Register> markedOutNodes;

    /**
     * Register moves that require a source.
     * These are generated by phi nodes where a predecessor hasn't been
     * generated yet.
     *
     * Maps the node whose instruction is missing to the move which needs
     * the register of said instruction.
     */
    private final HashMap<Node, List<MovRegisterInstruction>> unsourcedMoves;

    /**
     * Due to the so called 'swap problem' phis that are used by other phis in the
     * same basic block need to use temporary values.
     * We collect such phis in a pass before lowering.
     */
    private final HashSet<Node> temporariedPhis;

    /**
     * Remebers if node have been visited already.
     */
    private final HashSet<Node> visited;

    private final Graph firmGraph;


    private FirmToLlir(Graph firmGraph, TranslationResult translation) {
        this.blockEdges = new HashMap<>();
        this.insertedBlocks = new HashMap<>();
        this.blockMap = new HashMap<>();
        this.nodeMap = new HashMap<>();
        this.markedOutNodes = new HashMap<>();
        this.unsourcedMoves = new HashMap<>();
        this.temporariedPhis = new HashSet<>();
        this.visited = new HashSet<>();

        this.translation = translation;

        var gen = new VirtualRegister.Generator();

        this.llirGraph = new LlirGraph(gen);
        this.firmGraph = firmGraph;

        this.blockMap.put(firmGraph.getStartBlock(), llirGraph.getStartBlock());
    }

    private void lower() {
        BackEdges.enable(this.firmGraph);

        // Generate all basic blocks
        this.firmGraph.walkBlocks(block -> {
            var blockEdge = new BlockEdges();
            blockEdge.incoming = block.getPredCount();
            blockEdge.outgoing = 0;
            this.blockEdges.put(block, blockEdge);

            if (this.firmGraph.getEndBlock().equals(block) || this.firmGraph.getStartBlock().equals(block)) return;
            if (!this.blockMap.containsKey(block)) {
                this.blockMap.put(block, this.llirGraph.newBasicBlock());
            }
        });

        GenericNodeWalker.walkNodes(this.firmGraph, node -> {
            if (node.getMode().equals(Mode.getX())) {
                for (var succBlock : BackEdges.getOuts(node)) {
                    if (succBlock.node instanceof Block) {
                        this.blockEdges.get((Block)node.getBlock()).outgoing += 1;
                    }
                }
            }
            if (node instanceof Phi) {
                for (var pred : node.getPreds()) {
                    if (pred instanceof Phi) {
                        this.temporariedPhis.add(pred);
                    }
                }
            }
        });

        // Create method parameter llir nodes
        // TODO: for now they are just input nodes, we probably want to support some calling convention.
        BackEdges.disable(this.firmGraph);
        BackEdges.enable(this.firmGraph);
        var startBlock = this.llirGraph.getStartBlock();

        var startNode = this.firmGraph.getStart();

        for (var proj : BackEdges.getOuts(startNode)) {
            if (proj.node.getMode().equals(Mode.getT())) {
                for (var arg : BackEdges.getOuts(proj.node)) {
                    if (arg.node instanceof Anchor) continue;
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

    public static LlirGraph lowerFirm(TranslationResult translationResult) {
        // TODO: replace with own lowering
        Util.lowerSels();

        for (var method : translationResult.methodGraphs().keySet()) {
            if (method.getName().equals("bar")) {
                var graph = translationResult.methodGraphs().get(method);
                var f = new FirmToLlir(graph, translationResult);
                Dump.dumpGraph(graph, "before-lowering-to-llir");
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

    private SideEffect getPredSideEffectNode(Node node, Node predNode) {
        var currentBlock = getBlock(node);
        var predBlock = getBlock(predNode);
        var predLlirNode = (SideEffect) this.nodeMap.get(predNode);

        if (currentBlock.equals(predBlock)) {
            return predLlirNode;
        } else {

            if (!predBlock.getOutputNodes().contains(predLlirNode)) {
                predBlock.addOutput(predLlirNode.asLlirNode());
            }

            return currentBlock.getMemoryInput();
        }
    }

    private BasicBlock getBasicBlock(Node n) {
        var firmBlock = n instanceof Block b ? b : (Block) n.getBlock();
        var block = this.blockMap.get(firmBlock);
        assert block != null;
        return block;
    }

    /**
     * Utility method to insert control flow edges into llir graph.
     * If the control flow edge is critical, this method will insert
     * the second jump from the inserted basic block to the original target block
     * and return the inserted basic block.
     * Otherwise it will return the provided targetBlock.
     */
    private BasicBlock insertControlFlowEdge(Node start, Block targetBlock) {
        var startBlock = (Block)start.getBlock();

        var targetBasicBlock = getBasicBlock(targetBlock);

        var predIdx = -1;
        for (int i = 0; i < targetBlock.getPredCount(); i++) {
            if (targetBlock.getPred(i).equals(start)) {
                predIdx = i;
                break;
            }
        }
        assert predIdx >= 0;

        if (isCriticalEdge(targetBlock, start)) {
            var insertedBb = getInsertedBlockOnCriticalEdge(targetBlock, predIdx);
            insertedBb.finish(insertedBb.newJump(targetBasicBlock));
            return insertedBb;
        } else {
            return targetBasicBlock;
        }
    }

    private void visitNode(Node n) {
        if (!this.visited.contains(n)) {
            this.visited.add(n);

            for (var pred : n.getPreds()) {
                // Ignore keep predecssors.
                if (pred instanceof Block) continue;

                this.visitNode(pred);

                if (pred.getMode().equals(Mode.getM()) && !pred.getBlock().equals(n.getBlock())) {
                    var predBB = getBasicBlock(pred);
                    predBB.addOutput(nodeMap.get(pred));
                }
            }

            n.accept(this);
        }

        // Visit other blocks, if this is a control flow node. (Every block has at least one control flow node)
        if (n instanceof End || n instanceof Return || n instanceof Jmp || n instanceof Cond) {
            for (var pred : n.getBlock().getPreds()) {
                this.visitNode(pred);
            }

            if (n instanceof Return ret)  {
                var memNode = this.nodeMap.get(ret.getMem());
                var bb = getBasicBlock(ret.getMem());
                bb.addOutput(memNode);
            }
        }
    }

    public void visit(Proj proj) {
        var predNode = proj.getPred();

        if (proj.getMode().equals(Mode.getX())) {
            // These nodes are handled by visit(Cond).
        } else if (proj.getMode().equals(Mode.getM())) {
            if (predNode instanceof Start) {
                var llirBlock = this.blockMap.get((Block) proj.getBlock());
                this.registerLlirNode(proj, new MemoryInputNode(llirBlock));
            } else {
                var llirPred = this.nodeMap.get(predNode);
                assert llirPred instanceof SideEffect;

                registerLlirNode(proj, llirPred);
            }

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

    public void visit(Minus minus) {
        var bb = getBasicBlock(minus);

        var llirPred = (RegisterNode) getPredLlirNode(minus, minus.getOp());

        var zero = bb.newMovImmediate(0);
        var llirMinus = bb.newSub(zero, llirPred);

        this.registerLlirNode(minus, llirMinus);
    }

    public void visit(Sub sub) {
        var bb = getBasicBlock(sub);

        var lhs = (RegisterNode)getPredLlirNode(sub, sub.getLeft());
        var rhs = (RegisterNode)getPredLlirNode(sub, sub.getRight());

        var llirAdd = bb.newSub(lhs, rhs);
        this.registerLlirNode(sub, llirAdd);
    }

    public void visit(Eor xor) {
        var bb = getBasicBlock(xor);

        var lhs = (RegisterNode)getPredLlirNode(xor, xor.getLeft());
        var rhs = (RegisterNode)getPredLlirNode(xor, xor.getRight());

        var llirAdd = bb.newXor(lhs, rhs);
        this.registerLlirNode(xor, llirAdd);
    }

    public void visit(Mul mul) {
        var bb = getBasicBlock(mul);

        var lhs = (RegisterNode)getPredLlirNode(mul, mul.getLeft());
        var rhs = (RegisterNode)getPredLlirNode(mul, mul.getRight());

        var llirMul = bb.newMul(lhs, rhs);
        this.registerLlirNode(mul, llirMul);
    }

    public void visit(Div div) {
        var bb = getBasicBlock(div);

        var mem = getPredSideEffectNode(div, div.getMem());
        var dividend = (RegisterNode)getPredLlirNode(div, div.getLeft());
        var divisor = (RegisterNode)getPredLlirNode(div, div.getRight());

        var llirDiv = bb.newDiv(dividend, divisor, mem);
        this.registerLlirNode(div, llirDiv);
    }

    public void visit(Mod mod) {
        var bb = getBasicBlock(mod);

        var mem = getPredSideEffectNode(mod, mod.getMem());
        var modidend = (RegisterNode)getPredLlirNode(mod, mod.getLeft());
        var modisor = (RegisterNode)getPredLlirNode(mod, mod.getRight());

        var llirMod = bb.newMod(modidend, modisor, mem);
        this.registerLlirNode(mod, llirMod);
    }

    public void visit(Jmp jump) {
        var bb = getBasicBlock(jump);

        var targetBlock = (Block)BackEdges.getOuts(jump).iterator().next().node;

        var actualTargetBlock = this.insertControlFlowEdge(jump, targetBlock);
        bb.finish(bb.newJump(actualTargetBlock));
    }

     public void visit(Cmp cmp) {
        var bb = getBasicBlock(cmp);

        var lhs = (RegisterNode) getPredLlirNode(cmp, cmp.getLeft());
        var rhs = (RegisterNode) getPredLlirNode(cmp, cmp.getRight());

        var llirCmp = bb.newCmp(lhs, rhs);
        this.registerLlirNode(cmp, llirCmp);
    }

    public void visit(Not not) {
        var llirNode = getPredLlirNode(not, not.getOp());
        registerLlirNode(not, llirNode);
    }

    private BranchInstruction.Predicate getCmpPredicate(Node node) {
        if (node instanceof Not not) {
            var pred = getCmpPredicate(not.getOp());
            return pred.invert();
        } else if (node instanceof Cmp cmp) {
            return switch(cmp.getRelation()) {
                case Equal -> BranchInstruction.Predicate.EQUAL;
                case Less -> BranchInstruction.Predicate.LESS_THAN;
                case LessEqual -> BranchInstruction.Predicate.LESS_EQUAL;
                case Greater -> BranchInstruction.Predicate.GREATER_THAN;
                case GreaterEqual -> BranchInstruction.Predicate.GREATER_EQUAL;
                default -> throw new UnsupportedOperationException("Unsupported branch predicate");
            };
        } else {
            throw new AssertionError("Unreacheable, method should only be called with a cmp node.");
        }
    }

    public void visit(Cond cond) {
        var bb = getBasicBlock(cond);

        var cmpPred = cond.getSelector();
        var llirCmp = (CmpInstruction) getPredLlirNode(cond, cmpPred);

        var predicate = getCmpPredicate(cmpPred);

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
        var trueTargetBasicBlock = this.insertControlFlowEdge(trueProj, trueTargetBlock);

        Block falseTargetBlock = (Block)BackEdges.getOuts(falseProj).iterator().next().node;
        var falseTargetBasicBlock = this.insertControlFlowEdge(falseProj, falseTargetBlock);

        var llirBranch = bb.newBranch(predicate, llirCmp, trueTargetBasicBlock, falseTargetBasicBlock);
        this.registerLlirNode(cond, llirBranch);

        bb.finish(llirBranch);
    }

    public void visit(Conv node) {
        var pred = getPredLlirNode(node, node.getOp());
        registerLlirNode(node, pred);
    }

    public void visit(Store store) {
        var bb = getBasicBlock(store);

        var memNode = getPredSideEffectNode(store, store.getMem());
        var addrNode = (RegisterNode)this.nodeMap.get(store.getPtr());
        var valueNode = (RegisterNode)this.nodeMap.get(store.getValue());

        var llirStore = bb.newMovStore(addrNode, valueNode, memNode);
        registerLlirNode(store, llirStore);
    }

    public void visit(Load load) {
        var bb = getBasicBlock(load);
        var memNode = getPredSideEffectNode(load, load.getMem());
        var addrNode = (RegisterNode)this.nodeMap.get(load.getPtr());

        var llirLoad = bb.newMovLoad(addrNode, memNode);
        registerLlirNode(load, llirLoad);
    }

    public void visit(Call call) {
        var bb = getBasicBlock(call);
        var memNode = getPredSideEffectNode(call, call.getMem());

        List<RegisterNode> args = new ArrayList<>();

        for (var pred : call.getPreds()) {
            if (pred.equals(call.getMem())) continue;
            if (pred.equals(call.getPtr())) continue;

            var llirPred = (RegisterNode)getPredLlirNode(call, pred);
            args.add(llirPred);
        }

        var calledMethod = this.translation.methodReferences().get(call);

        var llirCall = bb.newCall(calledMethod, memNode, args);
        registerLlirNode(call, llirCall);
    }

    /**
     * Determines if an edge is critical.
     * The method uses the dependency direction of the firmgraph,
     * the actual control flow is reversed, going from end to start.
     */
    private boolean isCriticalEdge(Node start, Node end) {
        var startBlock = start instanceof Block ? (Block) start : (Block)start.getBlock();
        var endBlock = end instanceof Block ? (Block) end: (Block)end.getBlock();

        return this.blockEdges.get(startBlock).incoming > 1 && this.blockEdges.get(endBlock).outgoing > 1;
    }

    /**
     * Retrieves (and creates lazily) the basic block that is inserted on a critical edge in order to lower phis correctly.
     */
    private BasicBlock getInsertedBlockOnCriticalEdge(Block block, int predIdx) {
        var edge = new Edge(block, predIdx);

        if (!this.insertedBlocks.containsKey(edge)) {
            this.insertedBlocks.put(edge, llirGraph.newBasicBlock());
        }

        return this.insertedBlocks.get(edge);
    }

    public void visit(Phi phi) {
        var bb = getBasicBlock(phi);

        if (phi.getMode().equals(Mode.getM())) {
            var memoryInput = bb.getMemoryInput();
            this.registerLlirNode(phi, memoryInput);
        } else {
            var register = this.llirGraph.getVirtualRegGenerator().nextRegister();

            for (int i = 0; i < phi.getPredCount(); i++) {
                var pred = phi.getPred(i);

                BasicBlock predBb;
                if (isCriticalEdge(phi, pred)) {
                    predBb = getInsertedBlockOnCriticalEdge((Block) phi.getBlock(), i);

                } else {
                    assert pred.getBlock() != phi.getBlock();
                    predBb = getBlock(pred);
                }

                if (pred instanceof Const c) {
                    var mov = predBb.newMovImmediateInto(c.getTarval().asInt(), register);
                    predBb.addOutput(mov);

                } else {

                    var predRegNode = (RegisterNode) this.nodeMap.get(pred);

                    var mov = predBb.newMovRegisterInto(register, predRegNode);
                    if (predRegNode == null) {
                        this.unsourcedMoves.putIfAbsent(pred, new ArrayList<>());
                        this.unsourcedMoves.get(pred).add(mov);
                    }

                    predBb.addOutput(mov);
                }
            }

            var input = bb.newInput(register);
            if (this.temporariedPhis.contains(phi)) {
                var tmpRegister = this.llirGraph.getVirtualRegGenerator().nextRegister();
                var mov = bb.newMovRegisterInto(tmpRegister, input);
                this.registerLlirNode(phi, mov);
            } else {
                this.registerLlirNode(phi, input);
            }
        }
    }

    // These nodes are explicitely ignored
    public void visit(Start node) {}
    public void visit(Const node) {}
    public void visit(End node) {}
    public void visit(Address node) {}

    // These nodes are either not yet implemented or should never occur in the
    // firm graph during lowering to the backend.
    public void visit(Raise node) { throwUnsupportedNode(node); }
    public void visit(Sel node) { throwUnsupportedNode(node); }
    public void visit(Shl node) { throwUnsupportedNode(node); }
    public void visit(Shr node) { throwUnsupportedNode(node); }
    public void visit(Shrs node) { throwUnsupportedNode(node); }
    public void visit(Size node) { throwUnsupportedNode(node); }
    public void visit(Align node) { throwUnsupportedNode(node); }
    public void visit(Alloc node) { throwUnsupportedNode(node); }
    public void visit(Anchor node) { throwUnsupportedNode(node); }
    public void visit(And node) { throwUnsupportedNode(node); }
    public void visit(Bad node) { throwUnsupportedNode(node); }
    public void visit(Bitcast node) { throwUnsupportedNode(node); }
    public void visit(Block node) { throwUnsupportedNode(node); }
    public void visit(Builtin node) { throwUnsupportedNode(node); }
    public void visit(Confirm node) { throwUnsupportedNode(node); }
    public void visit(Switch node) { throwUnsupportedNode(node); }
    public void visit(Sync node) { throwUnsupportedNode(node); }
    public void visit(CopyB node) { throwUnsupportedNode(node); }
    public void visit(Deleted node) { throwUnsupportedNode(node); }
    public void visit(Dummy node) { throwUnsupportedNode(node); }
    public void visit(Tuple node) { throwUnsupportedNode(node); }
    public void visit(Unknown node) { throwUnsupportedNode(node); }
    public void visitUnknown(Node node) { throwUnsupportedNode(node); }
    public void visit(Free node) { throwUnsupportedNode(node); }
    public void visit(IJmp node) { throwUnsupportedNode(node); }
    public void visit(Id node) { throwUnsupportedNode(node); }
    public void visit(Member node) { throwUnsupportedNode(node); }
    public void visit(Mulh node) { throwUnsupportedNode(node); }
    public void visit(Mux node) { throwUnsupportedNode(node); }
    public void visit(NoMem node) { throwUnsupportedNode(node); }
    public void visit(Offset node) { throwUnsupportedNode(node); }
    public void visit(Or node) { throwUnsupportedNode(node); }
    public void visit(Pin node) { throwUnsupportedNode(node); }

    private void throwUnsupportedNode(Node n) {
        throw new UnsupportedOperationException(String.format("Instruction selection doesn't support for nodes of type '%s'.", n.getClass().getName()));
    }
}
