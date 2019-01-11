package amyc
package analyzer

import utils._
import ast.SymbolicTreeModule._
import ast.Identifier

// The type checker for Amy
// Takes a symbolic program and rejects it if it does not follow the Amy typing rules.
object TypeChecker extends Pipeline[(Program, SymbolTable), (Program, SymbolTable)] {

  def run(ctx: Context)(v: (Program, SymbolTable)): (Program, SymbolTable) = {
    import ctx.reporter._

    val (program, table) = v

    case class Constraint(found: Type, expected: Type, pos: Position)

    // Represents a type variable.
    // It extends Type, but it is meant only for internal type checker use,
    //  since no Amy value can have such type.
    case class TypeVariable private (id: Int) extends Type
    object TypeVariable {
      private val c = new UniqueCounter[Unit]
      def fresh(): TypeVariable = TypeVariable(c.next(()))
    }

    // Generates typing constraints for an expression `e` with a given expected type.
    // The environment `env` contains all currently available bindings (you will have to
    //  extend these, e.g., to account for local variables).
    // Returns a list of constraints among types. These will later be solved via unification.
    def genConstraints(e: Expr, expected: Type)(implicit env: Map[Identifier, Type]): List[Constraint] = {
      
      // This helper returns a list of a single constraint recording the type
      //  that we found (or generated) for the current expression `e`
      def topLevelConstraint(found: Type): List[Constraint] =
        List(Constraint(found, expected, e.position))
      
      e match {

        case Match(scrut, cases) =>
          // Returns additional constraints from within the pattern with all bindings
          // from identifiers to types for names bound in the pattern.
          // (This is analogous to `transformPattern` in NameAnalyzer.)
          def handlePattern(pat: Pattern, scrutExpected: Type):
            (List[Constraint], Map[Identifier, Type]) =
          {
            pat match {
              case LiteralPattern(lit) =>
                (genConstraints(lit, scrutExpected), Map.empty)
              case IdPattern(name) =>
                (Nil, Map(name -> scrutExpected))
              case WildcardPattern() =>
                (Nil, Map.empty)
              case CaseClassPattern(constr, args) =>
                table.getConstructor(constr) match {
                  case None => fatal("typeError : Class constructor not found for this pattern", pat)
                  case Some(t) =>
                    if(t.argTypes.size != args.size) fatal("typeError : Arguments does not match for this class", pat)
                    t.argTypes.zip(args).foldLeft((genConstraints(Variable(constr), scrutExpected)(env + (constr -> t.retType)), Map.empty[Identifier, Type]))((prev, curr) =>{
                      val hpat = handlePattern(curr._2, curr._1)
                      (prev._1 ++ hpat._1, prev._2 ++ hpat._2)
                    })
                }
            }
          }

          def handleCase(cse: MatchCase, scrutExpected: Type): List[Constraint] = {
            val (patConstraints, moreEnv) = handlePattern(cse.pat, scrutExpected)
            patConstraints ++ genConstraints(cse.expr, expected)(env ++ moreEnv)
          }

          val st = TypeVariable.fresh()
          genConstraints(scrut, st) ++ cases.flatMap(cse => handleCase(cse, st))

        // Variables
        case Variable(name) =>
            topLevelConstraint(env(name))

        // Literals
        case IntLiteral(_) =>
          topLevelConstraint(IntType)
        case BooleanLiteral(_) =>
          topLevelConstraint(BooleanType)
        case StringLiteral(_) =>
          topLevelConstraint(StringType)
        case UnitLiteral() =>
          topLevelConstraint(UnitType)

        // Binary operators
        case Plus(lhs, rhs) =>
          genConstraints(lhs, IntType) ++ genConstraints(rhs, IntType) ++ topLevelConstraint(IntType)
        case Minus(lhs, rhs) =>
          genConstraints(lhs, IntType) ++ genConstraints(rhs, IntType) ++ topLevelConstraint(IntType)
        case Times(lhs, rhs) =>
          genConstraints(lhs, IntType) ++ genConstraints(rhs, IntType) ++ topLevelConstraint(IntType)
        case Div(lhs, rhs) =>
          genConstraints(lhs, IntType) ++ genConstraints(rhs, IntType) ++ topLevelConstraint(IntType)
        case Mod(lhs, rhs) =>
          genConstraints(lhs, IntType) ++ genConstraints(rhs, IntType) ++ topLevelConstraint(IntType)
        case LessThan(lhs, rhs) =>
          genConstraints(lhs, IntType) ++ genConstraints(rhs, IntType) ++ topLevelConstraint(BooleanType)
        case LessEquals(lhs, rhs) =>
          genConstraints(lhs, IntType) ++ genConstraints(rhs, IntType) ++ topLevelConstraint(BooleanType)
        case And(lhs, rhs) =>
          genConstraints(lhs, BooleanType) ++ genConstraints(rhs, BooleanType) ++ topLevelConstraint(BooleanType)
        case Or(lhs, rhs) =>
          genConstraints(lhs, BooleanType) ++ genConstraints(rhs, BooleanType) ++ topLevelConstraint(BooleanType)
        case Equals(lhs, rhs) =>
          val sameType = TypeVariable.fresh()
          genConstraints(lhs, sameType) ++ genConstraints(rhs, sameType) ++ topLevelConstraint(BooleanType)
        case Concat(lhs, rhs) =>
          genConstraints(lhs, StringType) ++ genConstraints(rhs, StringType) ++ topLevelConstraint(StringType)

        // Unary operators
        case Not(e) =>
          genConstraints(e, BooleanType) ++ topLevelConstraint(BooleanType)
        case Neg(e) =>
          genConstraints(e, IntType) ++ topLevelConstraint(IntType)

        // Function/ type constructor call
        case Call(qname, args) =>
          table.getFunction(qname) match {
            case Some(f) =>
              if(f.argTypes.size != args.size) fatal(s"typeError : Type function arguments size does not match", e)
              f.argTypes.zip(args).foldLeft(List.empty[Constraint])((constrains, a) => constrains ++ genConstraints(a._2, a._1))++ topLevelConstraint(f.retType)

            case None =>
              table.getConstructor(qname) match {
                case Some(t) =>
                  if(t.argTypes.size != args.size) fatal(s"typeError : Type constructor arguments size does not match", e)
                  t.argTypes.zip(args).foldLeft(List.empty[Constraint])((constrains, a) => constrains ++ genConstraints(a._2, a._1))++ topLevelConstraint(t.retType)
                case None =>
                  fatal(s"typeError : Type or function constructor ${qname.name} not found", e)
              }
          }

        // The ; operator
        case Sequence(e1, e2) =>
          genConstraints(e1, TypeVariable.fresh()) ++ genConstraints(e2, expected)

        // Local variable definition
        case Let(df, value, body) =>
          genConstraints(value, df.tt.tpe) ++ genConstraints(body, expected)(env + (df.name -> df.tt.tpe))

        // If-then-else
        case Ite(cond, thenn, elze) =>
          genConstraints(cond, BooleanType) ++ genConstraints(thenn, expected) ++ genConstraints(elze, expected)

        // Represents a computational error; prints its message, then exits
        case Error(msg) =>
          genConstraints(msg, StringType)
      }
    }


    // Given a list of constraints `constraints`, replace every occurence of type variable
    //  with id `from` by type `to`.
    def subst_*(constraints: List[Constraint], from: Int, to: Type): List[Constraint] = {
      // Do a single substitution.
      def subst(tpe: Type, from: Int, to: Type): Type = {
        tpe match {
          case TypeVariable(`from`) => to
          case other => other
        }
      }

      constraints map { case Constraint(found, expected, pos) =>
        Constraint(subst(found, from, to), subst(expected, from, to), pos)
      }
    }

    // Solve the given set of typing constraints and
    //  call `typeError` if they are not satisfiable.
    // We consider a set of constraints to be satisfiable exactly if they unify.
    def solveConstraints(constraints: List[Constraint]): Unit = {
      constraints match {
        case Nil => ()
        case Constraint(found, expected, pos) :: more =>
          // HINT: You can use the `subst_*` helper above to replace a type variable
          //       by another type in your current set of constraints.
          expected match {
            //Replace every TypeVariable type with the real found type and solve the rest
            case TypeVariable(id) =>
              solveConstraints(subst_*(more, id, found))//TODO on more or constraints ?
            case _ =>
              //Check if type found math with expected one
              if(found != expected) fatal(s"typeError : Expected type ${expected.toString} but found ${found.toString}" , pos)
              //No error solve the rest
              solveConstraints(more)
          }
      }
    }

    // Putting it all together to type-check each module's functions and main expression.
    program.modules.foreach { mod =>
      // Put function parameters to the symbol table, then typecheck them against the return type
      mod.defs.collect { case FunDef(_, params, retType, body) =>
        val env = params.map{ case ParamDef(name, tt) => name -> tt.tpe }.toMap
        solveConstraints(genConstraints(body, retType.tpe)(env))
      }

      // Type-check expression if present. We allow the result to be of an arbitrary type by
      // passing a fresh (and therefore unconstrained) type variable as the expected type.
      val tv = TypeVariable.fresh()
      mod.optExpr.foreach(e => solveConstraints(genConstraints(e, tv)(Map())))
    }

    v

  }
}
