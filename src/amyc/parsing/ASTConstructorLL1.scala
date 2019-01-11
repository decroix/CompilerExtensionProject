package amyc
package parsing

import amyc.ast.NominalTreeModule._
import amyc.parsing.Tokens._
import amyc.utils.Positioned
import grammarcomp.parsing._

// Implements the translation from parse trees to ASTs for the LL1 grammar.
// Corresponds to Parser.msGrammarLL1
// This extends the plain ASTConstructor as some things will be the same.
// You should override whatever has changed.
// Make sure to use ASTConstructor as an example
class ASTConstructorLL1 extends ASTConstructor {

  override def constructQname(pTree: NodeOrLeaf[Token]): (QualifiedName, Positioned) = {
    pTree match {
      case Node('QName ::= _, List(id, otpQname)) =>
        otpQname match {
          case Node('OptQName ::= _, List(_, id2)) =>
            val (module, pos) = constructName(id)
            val (name, _) = constructName(id2)
            (QualifiedName(Some(module), name), pos)
          case Node('OptQName ::= _, Nil) =>
            val (name, pos) = constructName(id)
            (QualifiedName(None, name), pos)

        }
    }
  }

  override def constructExpr(ptree: NodeOrLeaf[Token]): Expr = {
    ptree match {
      case Node('Expr ::= List(VAL(), _), List(Leaf(vt), param, _, value, _, body)) =>
        Let(constructParam(param), constructExprMatch(value), constructExpr(body)).setPos(vt)
      case Node('Expr ::= List('ExprMatch, _), List(exprOr, semi)) =>
        semi match {
          case Node('Semi ::= List(SEMICOLON(), _), List(_, expr)) =>
            Sequence(constructExprMatch(exprOr), constructExpr(expr))
          case Node(_, Nil) =>
            constructExprMatch(exprOr)
        }
      case _ => constructExprMatch(ptree)
    }

  }

  def constructExprMatch(ptree: NodeOrLeaf[Token]): Expr = {
    ptree match {
      case Node('ExprMatch ::= List('ExprOr, _), List(exprOr, optMatch)) =>
        optMatch match {
          case Node('OptMatch ::= (MATCH() :: _), List(Leaf(pos), _, cases, _)) =>
            val scrut = constructExprOr(exprOr)
            Match(scrut, constructCases(cases, List())).setPos(pos)
          case Node(_, Nil) =>
            constructExprOr(exprOr)
        }
      case _ => constructExprOr(ptree)
    }
  }

  def constructCases(cases: NodeOrLeaf[Token], l: List[MatchCase]): List[MatchCase] = {
    cases match {
      case Node('CasesOpt ::= List('Cases), List(cs)) => constructCases(cs, l)
      case Node('CasesOpt ::= _, _) => l
      case Node('Cases ::= _, List(cs, next)) => constructCases(next, l :+ constructCase(cs))
    }
  }

  def constructExprOr(ptree: NodeOrLeaf[Token]): Expr = {
    ptree match {
      case Node('ExprOr ::= _, List(exprAnd, optOr)) =>
        optOr match {
          case Node('OptOr ::= List(OR(), _), List(_,_)) =>
            constructOpExpr(constructExprAnd(exprAnd), optOr)
          case Node(_, Nil) =>
            constructExprAnd(exprAnd)
        }
      case _ => constructExprAnd(ptree)
    }
  }

  def constructExprAnd(ptree: NodeOrLeaf[Token]): Expr = {
    ptree match {
      case Node('ExprAnd ::= _, List(exprEq, optAnd)) =>
        optAnd match {
          case Node('OptAnd ::= List(AND(), _), List(_,_)) =>
            constructOpExpr(constructExprEq(exprEq), optAnd)
          case Node(_, Nil) =>
            constructExprEq(exprEq)
        }
      case _ => constructExprEq(ptree)
    }
  }

  def constructExprEq(ptree: NodeOrLeaf[Token]): Expr = {
    ptree match {
      case Node('ExprEq ::= _, List(exprLess, optEq)) =>
        optEq match {
          case Node('OptEq ::= List(EQUALS(), _), List(_,_)) =>
            constructOpExpr(constructExprLess(exprLess), optEq)
          case Node(_, Nil) =>
            constructExprLess(exprLess)
        }
      case _ => constructExprLess(ptree)
    }
  }

  def constructExprLess(ptree: NodeOrLeaf[Token]): Expr = {
    ptree match {
      case Node('ExprLess ::= _, List(exprPlus, optLess)) =>
        optLess match {
          case Node('OptLess ::= List(LESSTHAN(), _), List(_,_)) =>
            constructOpExpr(constructExprPlus(exprPlus), optLess)
          case Node('OptLess ::= List(LESSEQUALS(), _), List(_,_)) =>
            constructOpExpr(constructExprPlus(exprPlus), optLess)
          case Node(_,Nil) =>
            constructExprPlus(exprPlus)
        }
      case _ => constructExprPlus(ptree)
    }
  }

  def constructExprPlus(ptree: NodeOrLeaf[Token]): Expr = {
    ptree match {
      case Node('ExprPlus ::= _, List(exprMult, optPlus)) =>
        optPlus match {
          case Node('OptPlus ::= List(PLUS(), _), List(_,_)) =>
            constructOpExpr(constructExprMult(exprMult), optPlus)
          case Node('OptPlus ::= List(MINUS(), _), List(_,_)) =>
            constructOpExpr(constructExprMult(exprMult), optPlus)
          case Node('OptPlus ::= List(CONCAT(), _), List(_,_)) =>
            constructOpExpr(constructExprMult(exprMult), optPlus)
          case Node(_, Nil) =>
            constructExprMult(exprMult)
        }
      case _ => constructExprMult(ptree)
    }
  }

  def constructExprMult(ptree: NodeOrLeaf[Token]): Expr = {
    ptree match {
      case Node('ExprMult ::= _, List(exprUnary, optMult)) =>
        optMult match {
          case Node('OptMult ::= List(TIMES(), _), List(_,_)) =>
            constructOpExpr(constructExprUnary(exprUnary), optMult)
          case Node('OptMult ::= List(DIV(), _), List(_,_)) =>
            constructOpExpr(constructExprUnary(exprUnary), optMult)
          case Node('OptMult ::= List(MOD(), _), List(_,_)) =>
            constructOpExpr(constructExprUnary(exprUnary), optMult)
          case Node(_, Nil) =>
            constructExprUnary(exprUnary)
        }
      case _ => constructExprUnary(ptree)
    }
  }

  def constructExprUnary(ptree: NodeOrLeaf[Token]): Expr = {
    ptree match {
      case Node('ExprUnary ::= List(MINUS(), _), List(Leaf(m), exprRest)) =>
        val expr = constructExprRest(exprRest)
        Neg(expr).setPos(m)
      case Node('ExprUnary ::= List(BANG(), _), List(Leaf(b), exprRest)) =>
        val expr = constructExprRest(exprRest)
        Not(expr).setPos(b)
      case Node('ExprUnary ::= List('ExprRest), List(exprRest)) =>
        constructExprRest(exprRest)
      case _ => constructExprRest(ptree)
    }
  }

  def constructExprRest(ptree: NodeOrLeaf[Token]): Expr = {
    ptree match {
      case Node('ExprRest ::= List(LPAREN(), _), List(Leaf(lp), optPar)) =>
        optPar match {
          case Node('OptPar ::= _, List(_)) => UnitLiteral().setPos(lp)
          case Node('OptPar ::= _, List(e, _)) => constructExpr(e).setPos(lp)
        }
      case Node('ExprRest ::= List('ExprId), List(exprId)) =>
        exprId match {
          case Node('ExprId ::= _, List(id, optId)) =>
            optId match {
              case Node('OptId ::= (DOT() :: _), List(_, secId, _, args, _)) =>
                val (module, pos) = constructName(id)
                val (name, _) = constructName(secId)
                val argss = constructList(args, constructExpr, hasComma = true)
                val qn = QualifiedName(Some(module), name)
                Call(qn, argss).setPos(pos)
              case Node('OptId ::= (LPAREN() :: _), List(_, args, _)) =>
                val (name, pos) = constructName(id)
                val qn = QualifiedName(None, name)
                val argss = constructList(args, constructExpr, hasComma = true)
                Call(qn, argss).setPos(pos)
              case Node('OptId ::= _, _) =>
                val (name, pos) = constructName(id)
                Variable(name).setPos(pos)
            }
        }
      case Node('ExprRest ::= List('Literal), List(l)) =>
        constructLiteral(l)
      case Node('ExprRest ::= _, List(ifErr)) => {
        ifErr match {
          case Node('If ::= _, List(Leaf(it), _, cond, _, _, thenn, _, _, _, elze, _)) =>
            Ite(
              constructExpr(cond),
              constructExpr(thenn),
              constructExpr(elze)
            ).setPos(it)
          case Node('Error ::= _, List(Leaf(ert), _, msg, _)) =>
            Error(constructExpr(msg)).setPos(ert)
        }
      }
    }
  }

  override def constructPattern(pTree: NodeOrLeaf[Token]): Pattern = {
    pTree match {
      case Node('Pattern ::= List(UNDERSCORE()), List(Leaf(ut))) =>
        WildcardPattern().setPos(ut)
      case Node('Pattern ::= List('Literal), List(lit)) =>
        val literal = constructLiteral(lit)
        LiteralPattern(literal).setPos(literal)
      case Node('Pattern ::= List(LPAREN(), _), (Leaf(lpar) :: _)) =>
        LiteralPattern(UnitLiteral()).setPos(lpar)
      case Node('Pattern ::= List('PatId), List(idPat)) =>
        idPat match {
          case Node('PatId ::= _, List(id, optPatId)) =>
            optPatId match {
              case Node('OptPatId ::= List('OptQName, _), List(optQName, _, patterns, _)) =>
                optQName match {
                  case Node('OptQName ::= List(DOT(), _), List(_, secId)) =>
                    val (module, pos) = constructName(id)
                    val (name, _) = constructName(secId)
                    val qn = QualifiedName(Some(module), name)
                    val patts = constructList(patterns, constructPattern, hasComma = true)
                    CaseClassPattern(qn, patts).setPos(pos)
                  case Node('OptQName ::= _, _) =>
                    val (name, pos) = constructName(id)
                    val qn = QualifiedName(None, name)
                    val patts = constructList(patterns, constructPattern, hasComma = true)
                    CaseClassPattern(qn, patts).setPos(pos)
                }
              case Node('OptPatId ::= _, _) =>
                val (name, pos) = constructName(id)
                IdPattern(name).setPos(pos)
            }
        }

    }
  }

  override def constructOp(ptree: NodeOrLeaf[Token]) = {
    ptree match {
      case Node(_, List(Leaf(t))) =>
        tokenToExpr(t)
      case Leaf(t) =>
        tokenToExpr(t)
    }
  }

  // Important helper method:
  // Because LL1 grammar is not helpful in implementing left associativity,
  // we give you this method to reconstruct it.
  // This method takes the left operand of an operator (leftopd)
  // as well as the tree that corresponds to the operator plus the right operand (ptree)
  // It parses the right hand side and then reconstruct the operator expression
  // with correct associativity.
  // If ptree is empty, it means we have no more operators and the leftopd is returned.
  // Note: You may have to override constructOp also, depending on your implementation
  def constructOpExpr(leftopd: Expr, ptree: NodeOrLeaf[Token]): Expr = {
    ptree match {
      case Node(_, List()) => //epsilon rule of the nonterminals
        leftopd
      case Node(sym ::= _, List(op, rightNode))
        if Set('OptOr, 'OptAnd, 'OptEq, 'OptLess, 'OptPlus, 'OptMult) contains sym =>
        rightNode match {
          case Node(_, List(nextOpd, suf)) => // 'Expr? ::= Expr? ~ 'OpExpr,
            val nextAtom = constructExpr(nextOpd)
            constructOpExpr(constructOp(op)(leftopd, nextAtom).setPos(leftopd), suf) // captures left associativity
        }
    }
  }

}

