package de.thm.mni.compilerbau.phases._06_codegen;

import de.thm.mni.compilerbau.CommandLineOptions;
import de.thm.mni.compilerbau.absyn.*;
import de.thm.mni.compilerbau.table.ParameterType;
import de.thm.mni.compilerbau.table.ProcedureEntry;
import de.thm.mni.compilerbau.table.SymbolTable;
import de.thm.mni.compilerbau.table.VariableEntry;
import de.thm.mni.compilerbau.types.ArrayType;
import de.thm.mni.compilerbau.utils.NotImplemented;
import de.thm.mni.compilerbau.utils.SplError;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.LinkedList;
import java.util.Objects;
import java.util.Stack;

/**
 * This class is used to generate the assembly code for the compiled program.
 * This code is emitted via the {@link CodePrinter} in the output field of this class.
 */
public class CodeGenerator {
    final CommandLineOptions options;
    final CodePrinter output;
    private Register currentRegister;
    private int labelCounter;
    private final Stack<Register> registerStack;
    private String ComparisonLabel;
    private final LinkedList<String> labelStack;
    private boolean checkForReference = false;
    private boolean isCaller = true;
    private boolean isIndexChoice = false;



    /**
     * Initializes the code generator.
     *
     * @param options The command line options passed to the compiler
     * @param output  The PrintWriter to the output file.
     */
    public CodeGenerator(CommandLineOptions options, PrintWriter output) throws IOException {
        this.options = options;
        this.output = new CodePrinter(output);
        this.registerStack = new Stack<>();
        for (Register register = new Register(23); register.isFreeUse(); register = register.previous()) {
            registerStack.push(register);
        }
        this.currentRegister = Register.FIRST_FREE_USE;
        this.labelCounter = 0;
        this.labelStack = new LinkedList<>(); // Initialize the label stack
    }

    public void generateCode(Program program, SymbolTable table) {
        assemblerProlog();
        for (GlobalDefinition definition : program.definitions) {
            if (definition instanceof ProcedureDefinition procedure) {
                // for every procedure reset the register.
                generateCodeForProcedure(procedure, table);
            }
        }
    }

    /**
     * Emits needed import statements, to allow usage of the predefined functions and sets the correct settings
     * for the assembler.
     */
    private void assemblerProlog() {
        output.emitImport("printi");
        output.emitImport("printc");
        output.emitImport("readi");
        output.emitImport("readc");
        output.emitImport("exit");
        output.emitImport("time");
        output.emitImport("clearAll");
        output.emitImport("setPixel");
        output.emitImport("drawLine");
        output.emitImport("drawCircle");
        output.emitImport("_indexError");
        output.emit("");
        output.emit("\t.code");
        output.emit("\t.align\t4");
    }

    private void generateCodeForProcedure(ProcedureDefinition procedure, SymbolTable table) {
        output.emitExport(procedure.name.toString());
        output.emitLabel(procedure.name.toString());
        ProcedureEntry entry = (ProcedureEntry) table.lookup(procedure.name);
        int frameSize = entry.stackLayout.frameSize();
        int oldFPOffset = entry.stackLayout.oldFramePointerOffset();
        int oldRetAddrOffset = entry.stackLayout.oldReturnAddressOffset();
        // Procedure prolog
        output.emitInstruction("sub", Register.STACK_POINTER, Register.STACK_POINTER, frameSize, "allocate frame");
        output.emitInstruction("stw", Register.FRAME_POINTER, Register.STACK_POINTER, oldFPOffset, "save old frame pointer");
        output.emitInstruction("add", Register.FRAME_POINTER, Register.STACK_POINTER, frameSize, "setup new frame pointer");
        output.emitInstruction("stw", Register.RETURN_ADDRESS, Register.FRAME_POINTER, oldRetAddrOffset, "save return register");
        // Body

        for (Statement statement : procedure.body) {
            generateCodeForStatement(statement, entry);
        }

        // Procedure epilog
        output.emitInstruction("ldw", Register.RETURN_ADDRESS, Register.FRAME_POINTER, oldRetAddrOffset, "restore return register");
        output.emitInstruction("ldw", Register.FRAME_POINTER, Register.STACK_POINTER, oldFPOffset, "restore old frame pointer");
        output.emitInstruction("add", Register.STACK_POINTER, Register.STACK_POINTER, frameSize, "release frame");
        output.emitInstruction("jr", Register.RETURN_ADDRESS, "return");
    }

    private void generateCodeForStatement(Statement statement, ProcedureEntry entry) {
        switch (statement) {
            case AssignStatement assignStatement -> generateCodeForAssignStatement(assignStatement, entry);
            case IfStatement ifStatement -> generateCodeForIfStatement(ifStatement, entry);
            case WhileStatement whileStatement -> generateCodeForWhileStatement(whileStatement, entry);
            case CompoundStatement compoundStatement -> generateCodeForCompoundStatement(compoundStatement, entry);
            case EmptyStatement emptyStatement -> { }
            case CallStatement callStatement -> { generateCodeForCallStatement(callStatement, entry); }
        }
    }


    private void generateCodeForAssignStatement(AssignStatement assignStatement, ProcedureEntry entry) {
        Register addressRegister = allocateRegister();
        switch (assignStatement.target) {
            case NamedVariable namedVariable -> {
                VariableEntry varEntry = (VariableEntry) entry.localTable.lookup(namedVariable.name);
                int offset = varEntry.offset;
                currentRegister = addressRegister;
                output.emitInstruction("add", addressRegister, Register.FRAME_POINTER, offset);
                if (varEntry.isReference) {
                    output.emitInstruction("ldw", addressRegister, addressRegister, 0);
                }
                generateCodeForExpression(assignStatement.value, entry);
                output.emitInstruction("stw", currentRegister, addressRegister, 0);
                releaseRegister(currentRegister);
                releaseRegister(addressRegister);
            }
            case ArrayAccess arrayAccess -> {
                resolveArray(arrayAccess, entry, addressRegister);
                generateCodeForExpression(assignStatement.value, entry);
                output.emitInstruction("stw", currentRegister, addressRegister, 0);
                //output.emitInstruction("stw", addressRegister.next(), addressRegister, 0);
                releaseRegister(currentRegister);
                releaseRegister(addressRegister);
            }
        }
    }

    private void resolveArray(Variable variable, ProcedureEntry entry, Register addressRegister) {
        if (variable instanceof NamedVariable namedVariable) {
            VariableEntry varEntry = (VariableEntry) entry.localTable.lookup(namedVariable.name);
            int offset = varEntry.offset;
            output.emitInstruction("add", addressRegister, Register.FRAME_POINTER, offset);
            if (varEntry.isReference) {
                output.emitInstruction("ldw", addressRegister, addressRegister, 0);
            }
        } else if (variable instanceof ArrayAccess arrayAccess) {
            resolveArray(arrayAccess.array, entry, addressRegister); // Recursively resolve base address

            // Generate code for the index expression
            isIndexChoice = true;
            generateCodeForExpression(arrayAccess.index, entry);
            isIndexChoice = false;
            Register indexRegister = currentRegister;
            // issue here by arr

            ArrayType arrayType = resolveArrayType(arrayAccess.array, entry);
            int arraySize = arrayType.arraySize;

            // (i >= 0 and i < size)
            Register sizeRegister = allocateRegister();

            output.emitInstruction("add", sizeRegister, Register.NULL, arraySize);
            output.emitInstruction("bgeu", indexRegister, sizeRegister, "_indexError");

            //  address of A[i]
            int elementSize = arrayType.baseType.byteSize;
            output.emitInstruction("mul", indexRegister, indexRegister, elementSize);
            output.emitInstruction("add", addressRegister, addressRegister, indexRegister);

            releaseRegister(sizeRegister);
            //output.emitInstruction("indexRegister  to be pushed: ",sizeRegister);
            releaseRegister(indexRegister);
            //output.emitInstruction("sizeRegister  to be pushed: ",indexRegister);
        }
    }

    private ArrayType resolveArrayType(Variable array, ProcedureEntry entry) {
        if (array instanceof NamedVariable namedVariable) {
            VariableEntry varEntry = (VariableEntry) entry.localTable.lookup(namedVariable.name);
            return (ArrayType) varEntry.type;
        } else if (array instanceof ArrayAccess arrayAccess) {
            ArrayType outerArrayType = resolveArrayType(arrayAccess.array, entry);
            return (ArrayType) outerArrayType.baseType;
        } else {
            throw new IllegalStateException("Unexpected variable type: " + array.getClass());
        }
    }


    private void generateCodeForExpression(Expression expression, ProcedureEntry entry) {
        switch (expression) {
            case IntLiteral intLiteral -> {
                //Register tempRegister = allocateRegister();
                Register newRegister = allocateRegister();
                currentRegister = newRegister;
                output.emitInstruction("add", newRegister, Register.NULL, intLiteral.value);
            }
            case UnaryExpression unaryExpression -> {
                generateCodeForExpression(unaryExpression.operand, entry);
                String operation = "sub";
                Register subRegister = currentRegister;
                output.emitInstruction(operation, subRegister, Register.NULL,subRegister);
                //releaseRegister(subRegister);
                //currentRegister = currentRegister.previous();
            }
            case BinaryExpression binaryExpression -> {
                generateCodeForExpression(binaryExpression.leftOperand, entry);
                Register leftRegister = currentRegister;
                generateCodeForExpression(binaryExpression.rightOperand,entry);
                Register rightRegister = currentRegister;
                String operation;
                if(binaryExpression.operator.isArithmetic()) {
                switch (binaryExpression.operator) {
                    case ADD -> operation = "add";
                    case SUB -> operation = "sub";
                    case MUL -> operation = "mul";
                    case DIV -> operation = "div";
                    default -> throw new UnsupportedOperationException("Unsupported binary operator: " + binaryExpression.operator);
                }
                output.emitInstruction(operation, leftRegister, leftRegister, leftRegister.next());
                    releaseRegister(rightRegister);
                    currentRegister = leftRegister;
                } else if (binaryExpression.operator.isComparison()) {
                    ComparisonLabel = generateUniqueLabel();
                    //String endLabel = generateUniqueLabel();

                    switch (binaryExpression.operator) {
                        case EQU -> operation = "bne"; // always the opposite to consider
                        case NEQ -> operation = "beq"; // this should be then beq
                        case LST -> operation = "bge";
                        case LSE -> operation = "bgt";
                        case GRT -> operation = "ble";
                        case GRE -> operation = "blt";
                        default -> throw new UnsupportedOperationException("Unsupported comparison operator: " + binaryExpression.operator);
                    }
                    labelStack.add(ComparisonLabel);
                    output.emitInstruction(operation, leftRegister, rightRegister, ComparisonLabel);
                    releaseRegister(rightRegister);
                    releaseRegister(leftRegister);
                }

            }
            case VariableExpression variableExpression -> {
                switch (variableExpression.variable) {
                    case NamedVariable namedVariable -> {
                        VariableEntry varEntry = (VariableEntry) entry.localTable.lookup(namedVariable.name);
                        int offset = varEntry.offset;
                        Register addressRegister = allocateRegister();
                        currentRegister = addressRegister;
                        output.emitInstruction("add", addressRegister, Register.FRAME_POINTER, offset);
                        if (!checkForReference) { // caller
                            output.emitInstruction("ldw", addressRegister, addressRegister, 0);
                        }
                        if (varEntry.isReference) {
                            output.emitInstruction("ldw", addressRegister, addressRegister, 0);
                        }
                        if ( checkForReference && isIndexChoice && isCaller) {
                            output.emitInstruction("ldw", addressRegister, addressRegister, 0);
                        }
                    }
                    case ArrayAccess arrayAccess -> {
                        Register adressRegister = allocateRegister();
                        resolveArray(arrayAccess, entry, adressRegister);
                        if (!checkForReference) {
                            output.emitInstruction("ldw", adressRegister, adressRegister, 0);
                        }
                        currentRegister = adressRegister;
                    }
                }
            }
        }
    }
    private void generateCodeForIfStatement(IfStatement ifStatement, ProcedureEntry entry) {


        if (!(ifStatement.elsePart instanceof EmptyStatement)) {
            generateCodeForExpression(ifStatement.condition, entry); // from here comes the firstLabel
            generateCodeForStatement(ifStatement.thenPart,entry);
            String secondLabel = generateUniqueLabel();
            output.emitInstruction("j",secondLabel);
            output.emitLabel(labelStack.removeLast()); // this is the firstLabel
            generateCodeForStatement(ifStatement.elsePart,entry);
            output.emitLabel(secondLabel);
        }
        else {
            generateCodeForExpression(ifStatement.condition, entry); // from here comes the firstlabel
            generateCodeForStatement(ifStatement.thenPart,entry);
            output.emitLabel(labelStack.removeLast());
        }
    }

    private void generateCodeForWhileStatement(WhileStatement whileStatement, ProcedureEntry entry) {
        String label = generateUniqueLabel();
        output.emitLabel(label);
        generateCodeForExpression(whileStatement.condition,entry);
        generateCodeForStatement(whileStatement.body,entry);
        output.emitInstruction("j",label);
        output.emitLabel(labelStack.removeLast());

    }
    private void generateCodeForCompoundStatement(CompoundStatement compoundStatement, ProcedureEntry entry) {
        for (Statement statement : compoundStatement.statements) {
            //currentRegister = Register.FIRST_FREE_USE;
            generateCodeForStatement(statement, entry);
        }
    }
    private void generateCodeForCallStatement(CallStatement callStatement, ProcedureEntry entry) {
        isCaller = true;
        ProcedureEntry procedureEntry = (ProcedureEntry) entry.localTable.lookup(callStatement.procedureName);

        // Generate code for each argument
        for (int i = 0; i < callStatement.arguments.size(); i++) {
            Expression argument = callStatement.arguments.get(i);
            ParameterType parameterType = procedureEntry.parameterTypes.get(i);
            if ( parameterType.isReference) {
                checkForReference = true; // if the ref param is reference then no ldw
                generateCodeForExpression(argument, entry);
                checkForReference = false;
            }
            else {
                generateCodeForExpression(argument, entry);
            }
            if (parameterType.isReference) {
                output.emitInstruction("stw", currentRegister, Register.STACK_POINTER, parameterType.offset, "store argument #" + i);
            } else {
                output.emitInstruction("stw", currentRegister, Register.STACK_POINTER, parameterType.offset,"store argument #" + i);
            }
            releaseRegister(currentRegister);
        }

        // Generate the jump and link instruction
        output.emitInstruction("jal", callStatement.procedureName.toString());

        // Reset the current register
        currentRegister = Register.FIRST_FREE_USE;
        isCaller = false;
    }





    private Register allocateRegister() {
        if (registerStack.isEmpty()) {
            throw SplError.RegisterOverflow();
        }
        return registerStack.pop();
    }
    private void releaseRegister(Register register) {
        registerStack.push(register);
    }

    private String generateUniqueLabel() {
        return "L" + (labelCounter++);
    }
}
