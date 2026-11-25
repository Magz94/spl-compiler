package de.thm.mni.compilerbau.phases._04a_tablebuild;

import de.thm.mni.compilerbau.CommandLineOptions;
import de.thm.mni.compilerbau.absyn.*;
import de.thm.mni.compilerbau.table.*;
import de.thm.mni.compilerbau.types.*;
import de.thm.mni.compilerbau.utils.SplError;

import java.util.stream.Collectors;
/**
 * This class is used to create and populate a {@link SymbolTable} containing entries for every symbol in the currently
 * compiled SPL program.
 * Every declaration of the SPL program needs its corresponding entry in the {@link SymbolTable}.
 * <p>
 * Calculated {@link Type}s can be stored in and read from the dataType field of the {@link Expression},
 * {@link TypeExpression} or {@link Variable} classes.
 */
public class TableBuilder {
    private final CommandLineOptions options;
    private final SymbolTable globalTable;
    private boolean mainIsFound;
    private boolean mainNotAsProcedureIsFound;


    public TableBuilder(CommandLineOptions options) {
        this.options = options;
        this.globalTable = TableInitializer.initializeGlobalTable();
        this.mainIsFound=false;
         this.mainNotAsProcedureIsFound = false;
    }
    public SymbolTable buildSymbolTable(Program program) {
        for (GlobalDefinition def : program.definitions) {
            processDefinition(def, globalTable);
        }
            if (mainNotAsProcedureIsFound) {
                throw SplError.MainIsNotAProcedure();
            }
            if (!mainIsFound) {
                throw SplError.MainIsMissing();
            }


        return globalTable;
    }



    private void processDefinition(GlobalDefinition def, SymbolTable currentTable) {
        switch (def) {
            case TypeDefinition typeDef -> {
                Identifier typeName = new Identifier(typeDef.name.toString());
                if (currentTable.lookup(typeName) != null) { // is there a same name of this procedure ?
                    throw SplError.RedefinitionOfIdentifier(typeDef.position, typeDef.name);
                }
                if ( typeName.toString().equals("main")) {
                    mainNotAsProcedureIsFound = true;
                }
                Type type = resolveTypeExpression(typeDef.typeExpression);
                currentTable.enter(typeName, new TypeEntry(type));
            }
            case ProcedureDefinition procDef -> {
                Identifier procName = new Identifier(procDef.name.toString());
                if (currentTable.lookup(procName) != null) {
                    throw SplError.RedefinitionOfIdentifier(procDef.position, procDef.name);
                }
                SymbolTable localTable = new SymbolTable(currentTable);
                ProcedureEntry procEntry = new ProcedureEntry(localTable, procDef.parameters.stream()
                        .map(param -> new ParameterType(resolveTypeExpression(param.typeExpression), param.isReference))
                        .collect(Collectors.toList()));
                currentTable.enter(procName, procEntry);
                processProcedure(procDef, localTable);
                if (procName.toString().equals("main")) {
                    mainIsFound = true;
                }

                if (procName.toString().equals("main") && !procDef.parameters.isEmpty()) {
                        throw SplError.MainMustNotHaveParameters();
                    }

                if (options.phaseOption == CommandLineOptions.PhaseOption.TABLES) {
                    printSymbolTableAtEndOfProcedure(new Identifier(procDef.name.toString()), procEntry);
                }
            }
            default -> throw new IllegalStateException("Unexpected value: " + def);
        }

    }

    private void processProcedure(ProcedureDefinition procDef, SymbolTable localTable) {
        for (ParameterDefinition param : procDef.parameters) {
            Identifier paramName = new Identifier(param.name.toString());
            Type paramType = resolveTypeExpression(param.typeExpression);
            if (paramType instanceof ArrayType && !param.isReference) {
                throw SplError.ParameterMustBeReference(param.position, param.name, paramType);
            }
            localTable.enter(paramName, new VariableEntry(paramType, param.isReference), SplError.RedefinitionOfIdentifier(param.position, param.name));
        }
        for (VariableDefinition varDef : procDef.variables) {
            Identifier varName = new Identifier(varDef.name.toString());

            if (varDef.typeExpression instanceof NamedTypeExpression) {
                Identifier typeName = new Identifier(((NamedTypeExpression) varDef.typeExpression).name.toString());
                Entry entry = localTable.lookup(typeName);
                if ( entry instanceof VariableEntry ) {
                    throw SplError.NotAType(varDef.position, typeName);
                }
            }
            Type varType = resolveTypeExpression(varDef.typeExpression);
            localTable.enter(varName, new VariableEntry(varType, false),SplError.RedefinitionOfIdentifier(varDef.position, varDef.name)); // only insert a unique variable inside its scope,
        }

    }
    private Type resolveTypeExpression(TypeExpression expr) {
        return switch (expr) {
            case NamedTypeExpression namedTypeExpr -> {
                Identifier typeName = new Identifier(namedTypeExpr.name.toString());
                Entry entry = globalTable.lookup(typeName);
                if ( (entry instanceof ProcedureEntry) ) {
                    throw SplError.NotAType(namedTypeExpr.position,namedTypeExpr.name);
                }
                else if (entry == null ) {
                    throw SplError.UndefinedIdentifier( namedTypeExpr.position,namedTypeExpr.name);
                }

                yield ((TypeEntry) entry).type;
            }
            case ArrayTypeExpression arrayTypeExpr -> {
                Type baseType = resolveTypeExpression(arrayTypeExpr.baseType);
                yield new ArrayType(baseType, arrayTypeExpr.arraySize);
            }
        };
    }


    public static void printSymbolTableAtEndOfProcedure(Identifier name, ProcedureEntry entry) {
        System.out.format("Symbol table at end of procedure '%s':\n", name);
        System.out.println(entry.localTable.toString());
    }
}
