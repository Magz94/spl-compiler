package de.thm.mni.compilerbau.phases._05_varalloc;

import de.thm.mni.compilerbau.CommandLineOptions;
import de.thm.mni.compilerbau.absyn.*;
import de.thm.mni.compilerbau.table.ParameterType;
import de.thm.mni.compilerbau.table.ProcedureEntry;
import de.thm.mni.compilerbau.table.SymbolTable;
import de.thm.mni.compilerbau.table.VariableEntry;
import de.thm.mni.compilerbau.utils.*;

import java.util.*;
import java.util.stream.IntStream;

/**
 * This class is used to calculate the memory needed for variables and stack frames of the currently compiled SPL program.
 * Those value have to be stored in their corresponding fields in the {@link ProcedureEntry}, {@link VariableEntry} and
 * {@link ParameterType} classes.
 */
public class VarAllocator {
    public static final int REFERENCE_BYTESIZE = 4;

    private final CommandLineOptions options;

    /**
     * @param options The options passed to the compiler
     */
    public VarAllocator(CommandLineOptions options) {
        this.options = options;
    }

    public void allocVars(Program program, SymbolTable table) {
        // First pass: Allocate offsets for parameters and local variables, calculate sizes
        for (GlobalDefinition globalDef : program.definitions) {
            if (globalDef instanceof ProcedureDefinition procDef) {
                ProcedureEntry procEntry = (ProcedureEntry) table.lookup(procDef.name);
                int argumentAreaSize = allocateParameterOffsets(procDef, procEntry);
                int localVarAreaSize = allocateLocalVariableOffsets(procDef, procEntry);
                procEntry.stackLayout.argumentAreaSize = argumentAreaSize;
                procEntry.stackLayout.localVarAreaSize = localVarAreaSize;
                procEntry.stackLayout.outgoingAreaSize = 0;
            }
        }
        // Second pass: Calculate the outgoing argument area size
        for (GlobalDefinition globalDef : program.definitions) {
            if (globalDef instanceof ProcedureDefinition procDef) {
                ProcedureEntry procEntry = (ProcedureEntry) table.lookup(procDef.name);

                // area sie calculation
                procEntry.stackLayout.outgoingAreaSize = calculateOutgoingAreaSize(procDef, table);
            }
        }
        if (options.phaseOption == CommandLineOptions.PhaseOption.VARS) {
            formatVars(program, table);
        }
    }

    private int allocateParameterOffsets(ProcedureDefinition procDef, ProcedureEntry procEntry) {
        int actualOffset = 0;
        for (int i = 0; i < procDef.parameters.size(); i++) {
            ParameterDefinition paramDef = procDef.parameters.get(i);
            ParameterType paramType = procEntry.parameterTypes.get(i);
            paramType.offset = actualOffset;
            VariableEntry varEntry = (VariableEntry) procEntry.localTable.lookup(paramDef.name);
            if (varEntry != null) {
                varEntry.offset = actualOffset;
            }
            actualOffset += paramType.isReference ? REFERENCE_BYTESIZE : paramType.type.byteSize;
        }
        return actualOffset;
    }

    private int allocateLocalVariableOffsets(ProcedureDefinition procDef, ProcedureEntry procEntry) {
        int actualOffset = 0;
        for (VariableDefinition variable : procDef.variables) {
            VariableEntry varEntry = (VariableEntry) procEntry.localTable.lookup(variable.name);
            actualOffset -= varEntry.isReference ? REFERENCE_BYTESIZE : varEntry.type.byteSize;
            varEntry.offset = actualOffset;
        }
        return Math.abs(actualOffset); // Return the positive size
    }

    private int calculateOutgoingAreaSize(ProcedureDefinition procDef, SymbolTable table) {
        return calculateStatementOutgoingAreaSize(procDef.body, table);
    }
    private int calculateStatementOutgoingAreaSize(List<Statement> statements, SymbolTable table) {
        int maxArgumentSize = 0;

        for (Statement stmt : statements) {
            maxArgumentSize = Math.max(maxArgumentSize, calculateSingleStatementOutgoingAreaSize(stmt, table));
        }

        return maxArgumentSize;
    }
    private int calculateSingleStatementOutgoingAreaSize(Statement stmt, SymbolTable table) {
        return switch (stmt) {
            case CallStatement callStmt -> {
                ProcedureEntry calledProcEntry = (ProcedureEntry) table.lookup(callStmt.procedureName);
                yield calledProcEntry.stackLayout.argumentAreaSize;
            }
            case IfStatement ifStmt -> Math.max(
                    calculateSingleStatementOutgoingAreaSize(ifStmt.thenPart, table),
                    !(ifStmt.elsePart instanceof EmptyStatement) ? calculateSingleStatementOutgoingAreaSize(ifStmt.elsePart, table) : 0 // changed ifStmt.elsePart != null
            );
            case WhileStatement whileStmt -> Math.max(
                    0,
                    calculateSingleStatementOutgoingAreaSize(whileStmt.body, table)
            );
            case CompoundStatement compoundStmt -> {
                int maxArgumentSize = 0;
                for (Statement innerStmt : compoundStmt.statements) {
                    maxArgumentSize = Math.max(maxArgumentSize, calculateSingleStatementOutgoingAreaSize(innerStmt, table));
                }
                yield maxArgumentSize;
            }
            default -> 0;
        };
    }
    /**
     * Formats and prints the variable allocation to a human-readable format
     * The stack layout
     *
     * @param program The abstract syntax tree of the program
     * @param table   The symbol table containing all symbols of the spl program
     */
private static void formatVars(Program program, SymbolTable table) {
    program.definitions.stream().filter(dec -> dec instanceof ProcedureDefinition).map(dec -> (ProcedureDefinition) dec).forEach(procDec -> {
        ProcedureEntry entry = (ProcedureEntry) table.lookup(procDec.name);

        var isLeafOptimized = false; // This is a remainder from a bonus assignment, but I refuse to adjust this entire mess of a method
        var varparBasis = (isLeafOptimized ? "SP" : "FP");

        AsciiGraphicalTableBuilder ascii = new AsciiGraphicalTableBuilder();
        ascii.line("...", AsciiGraphicalTableBuilder.Alignment.CENTER);

        {
            final var zipped = IntStream.range(0, procDec.parameters.size()).boxed()
                    .map(i -> new Pair<>(procDec.parameters.get(i), new Pair<>(((VariableEntry) entry.localTable.lookup(procDec.parameters.get(i).name)), entry.parameterTypes.get(i))))
                    .sorted(Comparator.comparing(p -> Optional.ofNullable(p.second.first.offset).map(o -> -o).orElse(Integer.MIN_VALUE)));

            zipped.forEach(v -> {
                boolean consistent = Objects.equals(v.second.first.offset, v.second.second.offset);

                ascii.line("par " + v.first.name.toString(), "<- " + varparBasis + " + " +
                                (consistent
                                        ? Objects.toString(v.second.first.offset, "NULL")
                                        : String.format("INCONSISTENT(%s/%s)", Objects.toString(v.second.first.offset, "NULL"), Objects.toString(v.second.second.offset, "NULL"))),
                        AsciiGraphicalTableBuilder.Alignment.LEFT);
            });
        }

        ascii.sep("BEGIN", "<- " + varparBasis);
        if (!procDec.variables.isEmpty()) {
            procDec.variables.stream()
                    .map(v -> new AbstractMap.SimpleImmutableEntry<>(v, ((VariableEntry) entry.localTable.lookup(v.name))))
                    .sorted(Comparator.comparing(e -> Try.execute(() -> -e.getValue().offset).getOrElse(0)))
                    .forEach(v -> ascii.line("var " + v.getKey().name.toString(),
                            "<- " + varparBasis + " - " + Optional.ofNullable(v.getValue().offset).map(o -> -o).map(Objects::toString).orElse("NULL"),
                            AsciiGraphicalTableBuilder.Alignment.LEFT));

            if (!isLeafOptimized) ascii.sep("");
        }

        if (isLeafOptimized) ascii.close("END");
        else {
            ascii.line("Old FP",
                    "<- SP + " + Try.execute(entry.stackLayout::oldFramePointerOffset).map(Objects::toString).getOrElse("UNKNOWN"),
                    AsciiGraphicalTableBuilder.Alignment.LEFT);

            ascii.line("Old Return",
                    "<- FP - " + Try.execute(() -> -entry.stackLayout.oldReturnAddressOffset()).map(Objects::toString).getOrElse("UNKNOWN"),
                    AsciiGraphicalTableBuilder.Alignment.LEFT);

            if (entry.stackLayout.outgoingAreaSize == null || entry.stackLayout.outgoingAreaSize > 0) {

                ascii.sep("outgoing area");

                if (entry.stackLayout.outgoingAreaSize != null) {
                    var max_args = entry.stackLayout.outgoingAreaSize / 4;

                    for (int i = 0; i < max_args; ++i) {
                        ascii.line(String.format("arg %d", max_args - i),
                                String.format("<- SP + %d", (max_args - i - 1) * 4),
                                AsciiGraphicalTableBuilder.Alignment.LEFT);
                    }
                } else {
                    ascii.line("UNKNOWN SIZE", AsciiGraphicalTableBuilder.Alignment.LEFT);
                }
            }

            ascii.sep("END", "<- SP");
            ascii.line("...", AsciiGraphicalTableBuilder.Alignment.CENTER);
        }

        System.out.printf("Variable allocation for procedure '%s':\n", procDec.name);
        System.out.printf("  - size of argument area = %s\n", Objects.toString(entry.stackLayout.argumentAreaSize, "NULL"));
        System.out.printf("  - size of localvar area = %s\n", Objects.toString(entry.stackLayout.localVarAreaSize, "NULL"));
        System.out.printf("  - size of outgoing area = %s\n", Objects.toString(entry.stackLayout.outgoingAreaSize, "NULL"));
        System.out.printf("  - frame size = %s\n", Try.execute(entry.stackLayout::frameSize).map(Objects::toString).getOrElse("UNKNOWN"));
        System.out.println();
        if (isLeafOptimized) System.out.println("  Stack layout (leaf optimized):");
        else System.out.println("  Stack layout:");
        System.out.println(ascii.toString().indent(4));
        System.out.println();
    });
}
}
