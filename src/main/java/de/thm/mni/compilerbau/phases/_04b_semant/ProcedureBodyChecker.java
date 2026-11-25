package de.thm.mni.compilerbau.phases._04b_semant;

import de.thm.mni.compilerbau.CommandLineOptions;
import de.thm.mni.compilerbau.absyn.*;
import de.thm.mni.compilerbau.table.*;
import de.thm.mni.compilerbau.types.*;
import de.thm.mni.compilerbau.utils.SplError;

/**
 * This class is used to check if the currently compiled SPL program is semantically valid.
 * The body of each procedure has to be checked, consisting of {@link Statement}s, {@link Variable}s and {@link Expression}s.
 * Each node has to be checked for type issues or other semantic issues.
 * Calculated {@link Type}s can be stored in and read from the dataType field of the {@link Expression} and {@link Variable} classes.
 */
public class ProcedureBodyChecker {

    private final CommandLineOptions options;

    public ProcedureBodyChecker(CommandLineOptions options) {
        this.options = options;
    }

    public void checkProcedures(Program program, SymbolTable globalTable) {
        for (GlobalDefinition def : program.definitions) {
            if (def instanceof ProcedureDefinition) {
                checkProcedure((ProcedureDefinition) def, globalTable);
            }
        }
    }

    private void checkProcedure(ProcedureDefinition procDef, SymbolTable globalTable) {
        ProcedureEntry procEntry = (ProcedureEntry) globalTable.lookup(new Identifier(procDef.name.toString()));
        SymbolTable localTable = procEntry.localTable;

        for (Statement stmt : procDef.body) {
            checkStatement(stmt, localTable);
        }
    }

    private void checkStatement(Statement stmt, SymbolTable table) {
        switch (stmt) {
            case AssignStatement assignStmt -> checkAssignStatement(assignStmt, table);
            case IfStatement ifStmt -> checkIfStatement(ifStmt, table);
            case WhileStatement whileStmt -> checkWhileStatement(whileStmt, table);
            case CallStatement callStmt -> checkCallStatement(callStmt, table);
            case CompoundStatement compoundStatement -> checkCompoundStatement(compoundStatement,table);
            case EmptyStatement ignored -> checkEmptyStatement();
        }
    }

    private void checkAssignStatement(AssignStatement assignStmt, SymbolTable table) {
        Type targetType = switch (assignStmt.target) {
            case NamedVariable var -> checkNamedVariable(var, table);
            case ArrayAccess arrayAccess -> checkArrayAccess(arrayAccess, table);
        };

        Type valueType = checkExpression(assignStmt.value, table);

        if (!targetType.equals(valueType)) {
            throw SplError.IllegalAssignment(assignStmt.position, targetType, valueType);
        }
    }
        // looks fine
    private Type checkNamedVariable(NamedVariable var, SymbolTable table) {
        Entry entry = table.lookup(var.name);
        if (entry == null ) {
            throw SplError.UndefinedIdentifier(var.position, var.name);
        } else if (!(entry instanceof VariableEntry)) {
            throw SplError.NotAVariable(var.position, var.name);
        }
        return ((VariableEntry) entry).type;
    }

    // looks fine
    private Type checkArrayAccess(ArrayAccess arrayAccess, SymbolTable table) {
        Type arrayType = checkVariable(arrayAccess.array, table);
        if (!(arrayType instanceof ArrayType)) {
            throw SplError.IndexingNonArray(arrayAccess.position, arrayType);
        }
        Type indexType = checkExpression(arrayAccess.index, table);
        if (!indexType.equals(PrimitiveType.intType)) {
            throw SplError.IndexTypeMismatch(arrayAccess.index.position, indexType);
        }
        return ((ArrayType) arrayType).baseType;
    }

    private Type checkVariable(Variable var, SymbolTable table) {
        return switch (var) {
            case NamedVariable namedVar -> checkNamedVariable(namedVar, table);
            case ArrayAccess arrayAccess -> checkArrayAccess(arrayAccess, table);

        };
    }

    private Type checkExpression(Expression expr, SymbolTable table) {
        return switch (expr) {
            case IntLiteral ignored -> PrimitiveType.intType;
            case VariableExpression varExpr -> checkVariable(varExpr.variable, table);
            case BinaryExpression binExpr -> checkBinaryExpression(binExpr, table);
            case UnaryExpression unaryExpr -> checkUnaryExpression(unaryExpr, table);
        };
    }

    private Type checkBinaryExpression(BinaryExpression binExpr, SymbolTable table) {
        Type leftType = checkExpression(binExpr.leftOperand, table);
        Type rightType = checkExpression(binExpr.rightOperand, table);
        if (!leftType.equals(rightType) ) {
            throw SplError.OperandTypeMismatch(binExpr.position, binExpr.operator, leftType, rightType);
        }
        if (leftType instanceof ArrayType && rightType instanceof ArrayType) {
            throw SplError.OperandTypeMismatch(binExpr.position, binExpr.operator, leftType, rightType);
        }

        if (binExpr.operator.isArithmetic()) {

            return leftType;
        } else if (binExpr.operator.isComparison() || binExpr.operator.isEqualityOperator()) {

            return PrimitiveType.boolType;
        }
        return leftType;
    }

    private Type checkUnaryExpression(UnaryExpression unaryExpr, SymbolTable table) {
        Type operandType = checkExpression(unaryExpr.operand, table);

        if (unaryExpr.operator == UnaryExpression.Operator.MINUS) {
            if (!operandType.equals(PrimitiveType.intType)) {
                throw SplError.OperandTypeMismatch(unaryExpr.position, unaryExpr.operator, operandType);
            }
            return PrimitiveType.intType;
        }
        return operandType;
    }
    private void checkIfStatement(IfStatement ifStmt, SymbolTable table) {
        Type conditionType = checkExpression(ifStmt.condition, table);
        if (!conditionType.equals(PrimitiveType.boolType)) {
            throw SplError.IfConditionMustBeBoolean(ifStmt.condition.position, conditionType);
        }
        checkStatement(ifStmt.thenPart, table);
        if (ifStmt.elsePart != null) {
            checkStatement(ifStmt.elsePart, table);
        }
    }

    private void checkWhileStatement(WhileStatement whileStmt, SymbolTable table) {
        Type conditionType = checkExpression(whileStmt.condition, table);
        if (!conditionType.equals(PrimitiveType.boolType)) {
            throw SplError.WhileConditionMustBeBoolean(whileStmt.condition.position, conditionType);
        }
        checkStatement(whileStmt.body, table);
    }

    private void checkCallStatement(CallStatement callStmt, SymbolTable table) {
        Entry entry = table.lookup(callStmt.procedureName);
        if (!(entry instanceof ProcedureEntry procEntry)) {
            throw SplError.CallOfNonProcedure(callStmt.position, callStmt.procedureName);
        }
        if (procEntry.parameterTypes.size() != callStmt.arguments.size()) {
            throw SplError.ArgumentCountMismatch(callStmt.position, callStmt.procedureName, procEntry.parameterTypes.size(), callStmt.arguments.size());
        }
        for (int i = 0; i < callStmt.arguments.size(); i++) {
            Type expectedType = procEntry.parameterTypes.get(i).type;
            Type actualType = checkExpression(callStmt.arguments.get(i), table);
            if (!expectedType.equals(actualType)) {
                throw SplError.ArgumentTypeMismatch(callStmt.position, callStmt.procedureName, i + 1, expectedType, actualType);
            }
            if (procEntry.parameterTypes.get(i).isReference) {
                if (!(callStmt.arguments.get(i) instanceof VariableExpression)) {
                    throw SplError.ArgumentMustBeAVariable(callStmt.position, callStmt.procedureName, i + 1);
                }
            }
        }
    }
    private void checkCompoundStatement(CompoundStatement compStmt, SymbolTable table) {
        for (Statement stmt : compStmt.statements) {
            checkStatement(stmt, table);
        }
    }
    private void checkEmptyStatement() {
        // no checks
    }

}
