package compiler.codegen;

import compiler.TranslationResult;
import compiler.codegen.llir.*;
import compiler.codegen.llir.nodes.*;
import compiler.codegen.llir.nodes.MemoryLocation;
import compiler.semantic.resolution.DefinedMethod;
import compiler.types.*;
import compiler.utils.FirmUtils;
import compiler.utils.GenericNodeWalker;
import firm.Util;
import firm.*;
import firm.nodes.*;

import java.util.*;
import java.util.stream.Stream;

public class FirmToLlir implements NodeVisitor {

    protected final TranslationResult translation;

    /**
     * Maps firm blocks to their corresponding BasicBlocks in the LlirGraph.
     */
    protected final HashMap<Block, BasicBlock> blockMap;

    /**
     * Stores the number of incoming and outgoing edges of a block.
     * We need this to determine whether an edge is critical.
     */
    protected static class BlockEdges{ int incoming; int outgoing; }
    protected final HashMap<Block, BlockEdges> blockEdges;

    /**
     * On critical edges we need to insert basic blocks to resolve phi nodes.
     * We store them in this hashmap where the key consists of the target block (which contains the phi)
     * and the edge index. (phiNode.getPred(idx))
     */
    protected record Edge(Block target, int idx) {}
    protected final HashMap<Edge, BasicBlock> insertedBlocks;

    /**
     * Maps firm nodes to their corresponding LlirNodes.
     *
     * A firm node might (in the future) have a different corresponding llir node for its value and its sideeffect.
     */
    protected final HashMap<Node, LlirNode> valueNodeMap;
    protected final HashMap<Node, SideEffect> sideEffectNodeMap;

    /**
     * The current LlirGraph we are constructing.
     */
    protected final LlirGraph llirGraph;

    /**
     * The virtual registers used as method parameters.
     */
    protected List<VirtualRegister> methodParameters;

    /**
     * Remembers nodes that are to be marked as output nodes.
     */
    protected final HashMap<Node, Register> markedOutNodes;

    /**
     * Every visited firm phi node (except memory phis), with its accumulating register.
     * (NOTE: this register might be different from the one in valueNodeMap, if this phi is in temporariedPhis)
     *
     * We use this map to resolve phis after the main lowering DFS through the firm graph has finished and all other nodes
     * are lowered.
     * If we try to resolve phis on-the-fly we predecesors might not exist yet as llir nodes which complicates this
     * problem.
     */
    private final HashMap<Phi, VirtualRegister> phis;

    /**
     * Due to the so called 'swap problem' phis that are used by other phis in the
     * same basic block need to use temporary values.
     *
     * e.g.
     * x1 = ϕ(x1, x2);
     * x2 = ϕ(x2, x1);
     *
     * The idea is, that for each such phi, we introduce a second virtual register which
     * copies the value of the phi and is used instead of the register in which we accumulate
     * the phis value.
     *
     * left predecessor bb:
     * x1 = y1
     * x2 = y2
     *
     * right predecessor bb:
     * x1 = y2
     * x2 = y1
     *
     * current bb:
     * y1 = x1
     * y2 = x2
     *
     * Any usage of the phi in llir will use the y{1,2} virtual registers.
     *
     * We collect such phis in a pass before lowering.
     */
    private final HashSet<Node> temporariedPhis;

    /**
     * When traversing the firm graph, we stop at phi nodes
     * and resume at the phi's predecessors once the previous
     * DFS has stopped.
     *
     * Otherwise we might enter the same basic block multiple times which can lead
     * to unexpected situations during construction.
     */
    private final ArrayDeque<Node> phiPredQueue;

    /**
     * When resolving phis, we add MovRegInstructions into previous basic blocks.
     * All these moves are stored in this list.
     * Since these moves overwrite a virtual register, we need to schedule these instructions
     * after any use of the original value (which is an input node).
     * This happens after the main construction.
     */
    private final List<RegisterNode> phiRegMoves;

    /**
     * Remembers if node have been visited already.
     */
    protected final HashSet<Node> visited;

    protected final Graph firmGraph;

    protected final DefinedMethod method;

    protected FirmToLlir(DefinedMethod method, Graph firmGraph, TranslationResult translation) {
        this.blockEdges = new HashMap<>();
        this.insertedBlocks = new HashMap<>();
        this.blockMap = new HashMap<>();
        this.valueNodeMap = new HashMap<>();
        this.sideEffectNodeMap = new HashMap<>();
        this.markedOutNodes = new HashMap<>();
        this.phis = new HashMap<>();
        this.temporariedPhis = new HashSet<>();
        this.phiPredQueue= new ArrayDeque<>();
        this.visited = new HashSet<>();
        this.phiRegMoves = new ArrayList<>();

        this.translation = translation;

        var gen = new VirtualRegister.Generator();

        this.llirGraph = new LlirGraph(gen);
        this.methodParameters = new ArrayList<>();
        this.method = method;
        this.firmGraph = firmGraph;

        this.blockMap.put(firmGraph.getStartBlock(), llirGraph.getStartBlock());
    }

    protected void lower() {

        // Generate all basic blocks
        this.firmGraph.walkBlocksPostorder(block -> {
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
            if (node instanceof Phi && !node.getMode().equals(Mode.getM())) {
                for (var pred : node.getPreds()) {
                    if (pred instanceof Phi && pred.getBlock().equals(node.getBlock())) {
                        this.temporariedPhis.add(pred);
                    }
                }
            }
        });

        // Create method parameter llir nodes
        var startBlock = this.llirGraph.getStartBlock();

        var startNode = this.firmGraph.getStart();

        // Method parameters are (at first) just virtual registers.
        var methodParamTys = Stream.concat(this.method.getContainingClass().stream(), this.method.getParameterTy().stream());
        this.methodParameters = methodParamTys.map(arg ->
            this.llirGraph.getVirtualRegGenerator().nextRegister(tyToRegisterWidth((Ty)arg))
        ).toList();

        // Find the firm (proj) nodes which represent the method parameters in the firm graph and associate them with the corresponding
        // input nodes of the start basic block.
        for (var proj : BackEdges.getOuts(startNode)) {
            if (proj.node.getMode().equals(Mode.getT())) {
                for (var arg : BackEdges.getOuts(proj.node)) {
                    if (arg.node instanceof Anchor) continue;

                    var argProj = (Proj) arg.node;
                    var width = modeToRegisterWidth(argProj.getMode());
                    var argVirtReg = this.methodParameters.get(argProj.getNum());
                    assert argVirtReg.getWidth() == width;

                    var i = startBlock.newInput(argVirtReg);

                    this.registerLlirNode(arg.node, i);
                }
            } else if (proj.node.getMode().equals(Mode.getM())) {
                this.registerLlirNode(proj.node, startBlock.getMemoryInput());
            }
        }

        // Build llir
        this.visitNode(this.firmGraph.getEnd());

        while (!this.phiPredQueue.isEmpty()) {
            var node = this.phiPredQueue.pop();
            this.visitNode(node);
        }

        // resolve phis
        // We sort the list to maintain determinism
        var phiKeyList = new ArrayList<>(this.phis.keySet());
        phiKeyList.sort(Comparator.comparingInt(Node::getNr));
        for (var phi : phiKeyList) {
            this.resolvePhi(phi);
        }

        // Mark all nodes with side effect, that are not used outside their bb as output nodes.
        GenericNodeWalker.walkNodes(this.firmGraph, node -> {
            for (var pred : node.getPreds()) {
                if (pred.getMode().equals(Mode.getM()) && !pred.getBlock().equals(node.getBlock())) {
                    var predBB = getBasicBlock(pred);

                    if (valueNodeMap.containsKey(pred)) {
                        predBB.addOutput(valueNodeMap.get(pred));
                    }
                    if (sideEffectNodeMap.containsKey(pred)) {
                        predBB.addOutput(sideEffectNodeMap.get(pred).asLlirNode());
                    }

                }
            }
        });

        // Remember output nodes in their respective basic blocks.
        // We sort the list to maintain determinism
        var markedOutNodesList = new ArrayList<>(this.markedOutNodes.keySet());
        markedOutNodesList.sort(Comparator.comparingInt(Node::getNr));
        for (Node node : markedOutNodesList) {
            var llirNode = this.valueNodeMap.get(node);
            assert llirNode instanceof RegisterNode;
            var basicBlock = llirNode.getBasicBlock();

            basicBlock.addOutput(llirNode);
        }

        // Finally add schedule dependencies where necessary.
        this.phiRegMoves.forEach(phiRegMov -> {
            var bb = phiRegMov.getBasicBlock();
            var overwritesInputNodeRegister = bb.getInputNodes().stream().filter(inputNode -> inputNode.getTargetRegister().equals(phiRegMov.getTargetRegister())).toList();
            assert overwritesInputNodeRegister.size() <= 1;
            if (overwritesInputNodeRegister.size() == 1) {
                // We need to schedule phiRegMov after any use of the input node.
                var inputNode = overwritesInputNodeRegister.get(0);

                for (var node : bb.getAllNodes()) {
                    if (node == phiRegMov) continue;
                    if (node.getPreds().anyMatch(inputNode::equals)) {
                        phiRegMov.addScheduleDependency(node);
                    }
                }
            }
        });
    }

    protected static Register.Width modeToRegisterWidth(Mode m) {
        if (m.equals(Mode.getP()) || m.equals(Mode.getLs()) || m.equals(Mode.getLu())) {
            return Register.Width.BIT64;
        } else if (m.equals(Mode.getIu()) || m.equals(Mode.getIs())) {
            return Register.Width.BIT32;
        } else if (m.equals(Mode.getBu())) {
            return Register.Width.BIT8;
        } else {
            throw new IllegalArgumentException("No applicable mov width for mode.");
        }
    }

    public static Register.Width tyToRegisterWidth(Ty ty) {
        if (ty instanceof ClassTy || ty instanceof ArrayTy) {
            return Register.Width.BIT64;
        } else if (ty instanceof BoolTy) {
            return Register.Width.BIT8;
        } else if (ty instanceof IntTy) {
            return Register.Width.BIT32;
        }else {
            throw new IllegalArgumentException();
        }
    }

    public record LoweringResult(
            Map<DefinedMethod, LlirGraph> methodLlirGraphs,
            Map<DefinedMethod, List<VirtualRegister>> methodParameters
    ){}

    public static LoweringResult lowerFirm(TranslationResult translationResult, boolean dump, boolean optimize) {
        // TODO: replace with own lowering
        Util.lowerSels();

        HashMap<DefinedMethod, LlirGraph> methodLlirGraphs = new HashMap<>();
        HashMap<DefinedMethod, List<VirtualRegister>> methodParameters = new HashMap<>();

        for (var method : translationResult.methodGraphs().keySet()) {
                var graph = translationResult.methodGraphs().get(method);

                if (dump) {
                    Dump.dumpGraph(graph, "before-lowering-to-llir");
                }

                BackEdges.enable(graph);
                FirmToLlir f = optimize ? new InstructionSelection(method, graph, translationResult) : new FirmToLlir(method, graph, translationResult);
                f.lower();

                methodLlirGraphs.put(method, f.llirGraph);
                methodParameters.put(method, f.methodParameters);
        }

        return new LoweringResult(methodLlirGraphs, methodParameters);
    }

    protected void registerLlirNode(Node firmNode, LlirNode llirNode, SideEffect sideEffect) {
        this.valueNodeMap.put(firmNode, llirNode);

        if (sideEffect != null) {
            this.sideEffectNodeMap.put(firmNode, sideEffect);
        }
    }

    /**
     * Associates a firm node with a llir node.
     */
    protected void registerLlirNode(Node firmNode, LlirNode llirNode) {
        if (llirNode instanceof SideEffect sideEffect) {
            this.registerLlirNode(firmNode, llirNode, sideEffect);
        } else {
            this.registerLlirNode(firmNode, llirNode, null);
        }
    }

    protected void registerSideEffect(Node sideEffect, SideEffect llirNode) {
        assert sideEffect.getMode().equals(Mode.getM());

        this.sideEffectNodeMap.put(sideEffect, llirNode);
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
    protected LlirNode getPredLlirNode(Node node, Node predNode) {
        var currentBlock = getBasicBlock(node);

        if (predNode instanceof Const constant) {
            // First we check if the predecessor node is a constant.
            // Constants don't have an associated basic block and are
            // created on-the-fly when needed.

            var bb = getBasicBlock(node);
            return bb.newMovImmediate(constant.getTarval().asLong(), modeToRegisterWidth(predNode.getMode()));

        } else if (this.valueNodeMap.containsKey(predNode)) {
            // Next we see if we already created a llir node for this firm node.
            // If the predecessor is outside the current basic block we possibly
            // add an input node.

            var predLlirNode = this.valueNodeMap.get(predNode);

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
                inputRegister = this.llirGraph.getVirtualRegGenerator().nextRegister(modeToRegisterWidth(predNode.getMode()));
                this.markedOutNodes.put(predNode, inputRegister);
            }

            return getInputNode(currentBlock, inputRegister);
        }
    }

    protected SideEffect getPredSideEffectNode(Node node, Node predNode) {
        var currentBlock = getBasicBlock(node);
        var predBlock = getBasicBlock(predNode);
        var predLlirNode = this.sideEffectNodeMap.get(predNode);

        if (currentBlock.equals(predBlock)) {
            return predLlirNode;
        } else {
            return currentBlock.getMemoryInput();
        }
    }

    protected BasicBlock getBasicBlock(Node n) {
        var firmBlock = n instanceof Block b ? b : (Block) n.getBlock();
        var block = this.blockMap.get(firmBlock);
        assert block != null;
        return block;
    }

    private void resolvePhi(Phi phi) {
        var register = this.phis.get(phi);

        for (int i = 0; i < phi.getPredCount(); i++) {
            var pred = phi.getPred(i);

            BasicBlock predBb;
            // What is the correct basic block to place mov for the phi.
            if (isCriticalEdge(phi, phi.getBlock().getPred(i))) {
                predBb = getInsertedBlockOnCriticalEdge((Block) phi.getBlock(), i);

            } else {
                assert pred.getBlock() != phi.getBlock();
                predBb = getBasicBlock(phi.getBlock().getPred(i));
            }

            // If the predecessor is constant, simply rematerialize the value.
            if (pred instanceof Const c) {
                var mov = predBb.newMovImmediateInto(c.getTarval().asLong(), register, modeToRegisterWidth(c.getMode()));
                predBb.addOutput(mov);
                this.phiRegMoves.add(mov);
            } else {
                // Get the potential predecessor llir node
                var predRegNode = (RegisterNode) this.valueNodeMap.get(pred);
                assert predRegNode != null;

                // The llir predecessor node exists, but in a different block where we need to place the mov.
                if (predRegNode.getBasicBlock() != predBb) {
                    predRegNode.getBasicBlock().addOutput(predRegNode);
                    predRegNode = predBb.addInput(predRegNode.getTargetRegister());
                }

                var mov = predBb.newMovRegisterInto(register, predRegNode);
                this.phiRegMoves.add(mov);

                predBb.addOutput(mov);
            }
        }
    }

    protected void visitNode(Node n) {
        if (!this.visited.contains(n)) {
            this.visited.add(n);

            n.accept(this);
        }

        // Visit other blocks, if this is a control flow node. (Every block has at least one control flow node)
        if (n instanceof End || n instanceof Return || n instanceof Jmp || n instanceof Cond) {
            for (var pred : n.getBlock().getPreds()) {
                // Otherwise we can get trapped in an infinite loop cycle Jmp A -> Jmp B -> Jmp A -> ..., etc.
                if (!this.visited.contains(pred)) {
                    this.visitNode(pred);
                }
            }

            // In the case of infinite loops the only way to leave the end block is using the keep alive edges on
            // the end node.
            // These however can and will point to Block which can't use with visitNode, so I've created this...
            if (n instanceof End end) {
                FirmUtils.preds(end).stream()
                        .flatMap(pred -> {
                            if (pred instanceof Block block) {
                                return FirmUtils.preds(block).stream();
                            } else {
                                return Stream.of(pred);
                            }
                        })
                        .filter(pred -> !this.visited.contains(pred))
                        .forEach(this::visitNode);
            }

            if (n instanceof Return ret)  {
                var memNode = this.sideEffectNodeMap.get(ret.getMem());
                var bb = getBasicBlock(ret.getMem());
                bb.addOutput(memNode.asLlirNode());
            }
        }
    }

    public void visit(Proj proj) {
        this.visitNode(proj.getPred());

        var predNode = proj.getPred();

        if (proj.getMode().equals(Mode.getX())) {
            // These nodes are handled by visit(Cond).
            assert true;
        } else if (proj.getPred().equals(firmGraph.getStart())) {
            // These projection nodes represent method parameters and are handled at the beginning of
            // the lowering process.
            assert true;
        } else if (proj.getMode().equals(Mode.getM())) {
            LlirNode llirNode;
            if (predNode instanceof Start) {
                var llirBlock = this.blockMap.get((Block) proj.getBlock());
                llirNode = new MemoryInputNode(llirBlock);
            } else {
                var llirPred = this.sideEffectNodeMap.get(predNode);
                llirNode = llirPred.asLlirNode();
            }
            this.registerLlirNode(proj, llirNode);

            if (FirmUtils.preds(this.firmGraph.getEnd()).contains(proj.getBlock())) {
                var llirBlock = getBasicBlock(proj);
                llirBlock.addOutput(llirNode);
            }

        } else if (!this.valueNodeMap.containsKey(proj)) {

            if (this.valueNodeMap.containsKey(predNode)) {
                var llirPred = getPredLlirNode(proj, predNode);
                this.registerLlirNode(proj, llirPred);
            } else {
                throw new AssertionError("Pred of proj node should have been visited before");
            }
        }
    }

    public void visit(Return ret) {
        this.visitNode(ret.getMem());
        if (ret.getPredCount() > 1) {
            this.visitNode(ret.getPred(1));
        }

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
        this.visitNode(add.getLeft());
        this.visitNode(add.getRight());

        var bb = getBasicBlock(add);

        var lhs = (RegisterNode)getPredLlirNode(add, add.getLeft());
        var rhs = (RegisterNode)getPredLlirNode(add, add.getRight());

        var llirAdd = bb.newAdd(lhs, rhs);
        this.registerLlirNode(add, llirAdd);
    }

    public void visit(Minus minus) {
        this.visitNode(minus.getOp());

        var bb = getBasicBlock(minus);

        var llirPred = (RegisterNode) getPredLlirNode(minus, minus.getOp());

        var zero = bb.newMovImmediate(0, modeToRegisterWidth(minus.getMode()));
        var llirMinus = bb.newSub(zero, llirPred);

        this.registerLlirNode(minus, llirMinus);
    }

    public void visit(Sub sub) {
        this.visitNode(sub.getLeft());
        this.visitNode(sub.getRight());

        var bb = getBasicBlock(sub);

        var lhs = (RegisterNode)getPredLlirNode(sub, sub.getLeft());
        var rhs = (RegisterNode)getPredLlirNode(sub, sub.getRight());

        var llirAdd = bb.newSub(lhs, rhs);
        this.registerLlirNode(sub, llirAdd);
    }

    public void visit(Eor xor) {
        this.visitNode(xor.getLeft());
        this.visitNode(xor.getRight());

        var bb = getBasicBlock(xor);

        var lhs = (RegisterNode)getPredLlirNode(xor, xor.getLeft());
        var rhs = (RegisterNode)getPredLlirNode(xor, xor.getRight());

        var llirXor = bb.newXor(lhs, rhs);
        this.registerLlirNode(xor, llirXor);
    }

    public void visit(And and) {
        this.visitNode(and.getLeft());
        this.visitNode(and.getRight());

        var bb = getBasicBlock(and);

        var lhs = (RegisterNode)getPredLlirNode(and, and.getLeft());
        var rhs = (RegisterNode)getPredLlirNode(and, and.getRight());

        var llirAnd = bb.newAnd(lhs, rhs);
        this.registerLlirNode(and, llirAnd);
    }

    public void visit(Mul mul) {
        this.visitNode(mul.getLeft());
        this.visitNode(mul.getRight());

        var bb = getBasicBlock(mul);

        var lhs = (RegisterNode)getPredLlirNode(mul, mul.getLeft());
        var rhs = (RegisterNode)getPredLlirNode(mul, mul.getRight());

        var llirMul = bb.newMul(lhs, rhs);
        this.registerLlirNode(mul, llirMul);
    }

    public void visit(Div div) {
        this.visitNode(div.getLeft());
        this.visitNode(div.getRight());
        this.visitNode(div.getMem());

        var bb = getBasicBlock(div);

        var mem = getPredSideEffectNode(div, div.getMem());
        var dividend = (RegisterNode)getPredLlirNode(div, div.getLeft());
        var divisor = (RegisterNode)getPredLlirNode(div, div.getRight());

        var llirDiv = bb.newDiv(dividend, divisor, mem);

        this.registerLlirNode(div, llirDiv);
    }

    public void visit(Mod mod) {
        this.visitNode(mod.getLeft());
        this.visitNode(mod.getRight());
        this.visitNode(mod.getMem());

        var bb = getBasicBlock(mod);

        var mem = getPredSideEffectNode(mod, mod.getMem());
        var dividend = (RegisterNode)getPredLlirNode(mod, mod.getLeft());
        var divisor = (RegisterNode)getPredLlirNode(mod, mod.getRight());

        var llirMod = bb.newMod(dividend, divisor, mem);

        this.registerLlirNode(mod, llirMod);
    }

    public void visit(Shl shl) {
        this.visitNode(shl.getLeft());
        this.visitNode(shl.getRight());

        var bb = getBasicBlock(shl);

        var lhs = (RegisterNode) getPredLlirNode(shl, shl.getLeft());
        var rhs = (RegisterNode) getPredLlirNode(shl, shl.getRight());

        var llirShl = bb.newShiftLeft(lhs, rhs);

        this.registerLlirNode(shl, llirShl);
    }

    public void visit(Shr shr) {
        this.visitNode(shr.getLeft());
        this.visitNode(shr.getRight());

        var bb = getBasicBlock(shr);

        var lhs = (RegisterNode) getPredLlirNode(shr, shr.getLeft());
        var rhs = (RegisterNode) getPredLlirNode(shr, shr.getRight());

        var llirShr = bb.newShiftRight(lhs, rhs);

        this.registerLlirNode(shr, llirShr);
    }

    public void visit(Shrs shrs) {
        this.visitNode(shrs.getLeft());
        this.visitNode(shrs.getRight());

        var bb = getBasicBlock(shrs);

        var lhs = (RegisterNode) getPredLlirNode(shrs, shrs.getLeft());
        var rhs = (RegisterNode) getPredLlirNode(shrs, shrs.getRight());

        var llirShrs = bb.newArithmeticShiftRight(lhs, rhs);

        this.registerLlirNode(shrs, llirShrs);
    }

    public void visit(Jmp jump) {
        var bb = getBasicBlock(jump);

        var targetBlock = (Block)BackEdges.getOuts(jump).iterator().next().node;

        var llirTargetBlock = getBasicBlock(targetBlock);
        bb.finish(bb.newJump(llirTargetBlock));
    }

    public void visit(Not not) {
        this.visitNode(not.getOp());

        var llirNode = getPredLlirNode(not, not.getOp());
        registerLlirNode(not, llirNode);
    }

    protected Predicate getCmpPredicate(Node node) {
        if (node instanceof Not not) {
            var pred = getCmpPredicate(not.getOp());
            return pred.invert();
        } else if (node instanceof Cmp cmp) {
            return switch(cmp.getRelation()) {
                case Equal -> Predicate.EQUAL;
                case Less -> Predicate.LESS_THAN;
                case LessEqual -> Predicate.LESS_EQUAL;
                case Greater -> Predicate.GREATER_THAN;
                case GreaterEqual -> Predicate.GREATER_EQUAL;
                default -> throw new UnsupportedOperationException("Unsupported branch predicate");
            };
        } else {
            throw new AssertionError("Unreacheable, method should only be called with a cmp node.");
        }
    }


    protected record CmpLowerResult(CmpLikeInstruction cmp, Predicate predicate){}

    // cmp might not be the direct predecessor of cond. (There might be a Not node inbetween)
    protected CmpLowerResult lowerCmpSelector(Cond cond, Cmp cmp) {
        this.visitNode(cmp.getLeft());
        this.visitNode(cmp.getRight());

        // The firm Cmp node might be not in the same basic block as the Cond (due to CSE)
        // but we want to place it right before the conditional jump.
        var bb = getBasicBlock(cond);

        var lhs = (RegisterNode) getPredLlirNode(cmp, cmp.getLeft());
        var rhs = (RegisterNode) getPredLlirNode(cmp, cmp.getRight());

        return new CmpLowerResult(bb.newCmp(lhs, rhs), getCmpPredicate(cmp));
    }

    protected CmpLowerResult lowerCondSelector(Cond cond, Node pred)  {
        if (pred instanceof Not not) {
            var result = this.lowerCondSelector(cond, not.getOp());
            return new CmpLowerResult(result.cmp, result.predicate.invert());
        } else {
            return this.lowerCmpSelector(cond, (Cmp) pred);
        }
    }

    public void visit(Cond cond) {
        var bb = getBasicBlock(cond);

        var cmpResult = this.lowerCondSelector(cond, cond.getSelector());
        var llirCmp = cmpResult.cmp();
        var predicate = cmpResult.predicate();

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

    public void visit(Conv node) {
        this.visitNode(node.getOp());

        var bb = getBasicBlock(node);

        var pred = (RegisterNode)getPredLlirNode(node, node.getOp());

        assert node.getOp().getMode().equals(Mode.getIs());
        assert node.getMode().equals(Mode.getLs());

        var llirNode = bb.newMovSignExtend(pred);

        registerLlirNode(node, llirNode);
    }

    public void visit(Store store) {
        this.visitNode(store.getPtr());
        this.visitNode(store.getValue());
        this.visitNode(store.getMem());

        var bb = getBasicBlock(store);

        var memNode = getPredSideEffectNode(store, store.getMem());
        var addrNode = (RegisterNode)getPredLlirNode(store, store.getPtr());
        var valueNode = (RegisterNode)getPredLlirNode(store, store.getValue());

        var llirStore = bb.newMovStore(MemoryLocation.base(addrNode), valueNode, memNode, modeToRegisterWidth(store.getValue().getMode()));
        registerLlirNode(store, llirStore);
    }

    public void visit(Load load) {
        this.visitNode(load.getPtr());
        this.visitNode(load.getMem());

        var bb = getBasicBlock(load);
        var memNode = getPredSideEffectNode(load, load.getMem());
        var addrNode = (RegisterNode)getPredLlirNode(load, load.getPtr());

        Mode outputMode = load.getLoadMode();

        var llirLoad = bb.newMovLoad(MemoryLocation.base(addrNode), memNode, modeToRegisterWidth(outputMode));
        registerLlirNode(load, llirLoad);
    }

    public void visit(Call call) {
        for (var pred : call.getPreds()) {
            if (pred.equals(call.getMem())) continue;
            if (pred.equals(call.getPtr())) continue;

            this.visitNode(pred);
        }

        this.visitNode(call.getMem());

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

        CallInstruction llirCall;
        // If no corresponding method definition was found, it has to be an allocation invocation.
        if (calledMethod == null) {
            assert args.size() == 2;
            llirCall = bb.newAllocCall(memNode, args.get(0), args.get(1));
        } else {
            llirCall = bb.newMethodCall(calledMethod, memNode, args);
        }

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
            var preds = new ArrayList<Node>();
            block.getPreds().iterator().forEachRemaining(preds::add);
            var pred = preds.get(predIdx);

            var insertedBlock = llirGraph.newBasicBlock();
            var targetBlock = getBasicBlock(block);
            insertedBlock.finish(insertedBlock.newJump(targetBlock));

            if (pred instanceof Jmp jmp) {
                var llirPred = (JumpInstruction) this.valueNodeMap.get(jmp);
                llirPred.setTarget(insertedBlock);

            } else if (pred instanceof Proj proj) {
                assert proj.getMode().equals(Mode.getX());

                var isFalse = proj.getNum() == 0;
                var llirPred = (BranchInstruction) this.valueNodeMap.get(proj.getPred());

                if (isFalse) {
                    llirPred.setFalseBlock(insertedBlock);
                } else {
                    llirPred.setTrueBlock(insertedBlock);
                }
            }

            this.insertedBlocks.put(edge, insertedBlock);
        }

        return this.insertedBlocks.get(edge);
    }

    public void visit(Phi phi) {
        for (var pred : phi.getPreds()) {
            this.phiPredQueue.push(pred);
        }

        var bb = getBasicBlock(phi);

        if (phi.getMode().equals(Mode.getM())) {
            var memoryInput = bb.getMemoryInput();
            this.registerLlirNode(phi, memoryInput);
        } else {
            var register = this.llirGraph.getVirtualRegGenerator().nextRegister(modeToRegisterWidth(phi.getMode()));
            this.phis.put(phi, register);

            // If this phi is in temporariedPhis, this means the swap problem might occur here.
            // We need to store the value in a different register, than where the phi is accumulated
            // and any use of this phi uses this new register.
            var input = bb.newInput(register);
            if (this.temporariedPhis.contains(phi)) {
                var tmpRegister = this.llirGraph.getVirtualRegGenerator().nextRegister(input.getTargetRegister().getWidth());
                var mov = bb.newMovRegisterInto(tmpRegister, input);
                this.registerLlirNode(phi, mov);
            } else {
                this.registerLlirNode(phi, input);
            }
        }
    }

    public void visit(Unknown node) {
        var bb = getBasicBlock(node);
        this.registerLlirNode(node, bb.newMovImmediate(0, modeToRegisterWidth(node.getMode())));
    }

    // These nodes are explicitely ignored
    public void visit(Start node) {}
    public void visit(Const node) {}
    public void visit(End node) {}
    public void visit(Address node) {}
    public void visit(Cmp cmp) {}

    // These nodes are either not yet implemented or should never occur in the
    // firm graph during lowering to the backend.
    public void visit(Raise node) { throwUnsupportedNode(node); }
    public void visit(Sel node) { throwUnsupportedNode(node); }
    public void visit(Size node) { throwUnsupportedNode(node); }
    public void visit(Align node) { throwUnsupportedNode(node); }
    public void visit(Alloc node) { throwUnsupportedNode(node); }
    public void visit(Anchor node) { throwUnsupportedNode(node); }
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
