package de.thm.mni.compilerbau.phases._01_scanner;

import de.thm.mni.compilerbau.utils.SplError;
import de.thm.mni.compilerbau.phases._02_03_parser.Sym;
import de.thm.mni.compilerbau.absyn.Position;
import de.thm.mni.compilerbau.table.Identifier;
import de.thm.mni.compilerbau.CommandLineOptions;
import java_cup.runtime.*;

%%


%class Scanner
%public
%line
%column
%cup
%eofval{
    return new java_cup.runtime.Symbol(Sym.EOF, yyline + 1, yycolumn + 1);   //This needs to be specified when using a custom sym class name
%eofval}

%{
    public CommandLineOptions options = null;

    private Symbol symbol(int type) {
      return new Symbol(type, yyline + 1, yycolumn + 1);
    }

    private Symbol symbol(int type, Object value) {
      return new Symbol(type, yyline + 1, yycolumn + 1, value);
    }
%}
// Macro definitions for character classes
L = [A-Za-z_]
D = [0-9]
H = [0-9A-Fa-f]
ID = {L}({L}|{D})*   // [A-Za-z_][A-Za-z0-9_]*
DECNUM = {D}+
HEXNUM = "0x"{H}+
%%
// Token definitions

// Keywords
"array"      { return symbol(Sym.ARRAY); }
"else"       { return symbol(Sym.ELSE); }
"if"         { return symbol(Sym.IF); }
"of"         { return symbol(Sym.OF); }
"proc"       { return symbol(Sym.PROC); }
"ref"        { return symbol(Sym.REF); }
"type"       { return symbol(Sym.TYPE); }
"var"        { return symbol(Sym.VAR); }
"while"      { return symbol(Sym.WHILE); }
[ \n\t\r] {   }
// Operators
"+"                        { return symbol(Sym.PLUS); }
"-"                          { return symbol(Sym.MINUS); }
"*"                        { return symbol(Sym.STAR); }
"/"                          { return symbol(Sym.SLASH); }
":="                         { return symbol(Sym.ASGN); }
"#"                         { return symbol(Sym.NE); }
"<"                          { return symbol(Sym.LT); }
"<="                         { return symbol(Sym.LE); }
">"                          { return symbol(Sym.GT); }
">="                         { return symbol(Sym.GE); }
"="                         { return symbol(Sym.EQ); }
// Delimiters
","                          { return symbol(Sym.COMMA); }
";"                          { return symbol(Sym.SEMIC); }
":"                          { return symbol(Sym.COLON); }
"("                          { return symbol(Sym.LPAREN); }
")"                          { return symbol(Sym.RPAREN); }
"{"                          { return symbol(Sym.LCURL); }
"}"                          { return symbol(Sym.RCURL); }
"["                          { return symbol(Sym.LBRACK); }
"]"                          { return symbol(Sym.RBRACK); }
\/\/[^\n]* { }
// Literals and Identifiers

    {ID}  {return  symbol(Sym.IDENT,new  Identifier(yytext()));}
    '\\n' { return symbol(Sym.INTLIT,10);}
     '.' {  return  symbol(Sym.INTLIT,(int)yytext().charAt(1)); }
    {DECNUM}  {  return symbol(Sym.INTLIT, Integer.parseInt(yytext()));}
      {HEXNUM}  {
                    String hexStr = yytext().substring(2); // Remove '0x' prefix
                    int intValue = Integer.parseInt(hexStr, 16); // Convert from hexadecimal to integer
                    return symbol(Sym.INTLIT, intValue);
                }
[^]		{throw SplError.LexicalError(new Position(yyline + 1, yycolumn + 1), yytext().charAt(0));}
