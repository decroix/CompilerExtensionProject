package amyc
package analyzer

import utils._
import ast.{Identifier, NominalTreeModule => N, SymbolicTreeModule => S}

// Name analyzer for Amy
// Takes a nominal program (names are plain strings, qualified names are string pairs)
// and returns a symbolic program, where all names have been resolved to unique Identifiers.
// Rejects programs that violate the Amy naming rules.
// Also populates and returns the symbol table.
object NameAnalyzer extends Pipeline[N.Program, (S.Program, SymbolTable)] {
  def run(ctx: Context)(p: N.Program): (S.Program, SymbolTable) = {
    import ctx.reporter._

    // Step 0: Initialize symbol table
    val table = new SymbolTable

    // Step 1: Add modules to table 
    val modNames = p.modules.groupBy(_.name)
    modNames.foreach { case (name, modules) =>
      if (modules.size > 1) {
        fatal(s"Two modules named $name in program", modules.head.position)
      }
    }

    modNames.keys.toList foreach table.addModule


    // Helper method: will transform a nominal type 'tt' to a symbolic type,
    // given that we are within module 'inModule'.
    def transformType(tt: N.TypeTree, inModule: String): S.Type = {
      tt.tpe match {
        case N.IntType => S.IntType
        case N.BooleanType => S.BooleanType
        case N.StringType => S.StringType
        case N.UnitType => S.UnitType
        case N.ClassType(qn@N.QualifiedName(module, name)) =>
          table.getType(module getOrElse inModule, name) match {
            case Some(symbol) =>
              S.ClassType(symbol)
            case None =>
              fatal(s"Could not find type $qn", tt)
          }
      }
    }

    // Step 2: Check name uniqueness of definitions in each module
    p.modules.foreach{
      mod =>
        mod.defs.groupBy(_.name).foreach{
          case (name, defs) =>
            if(defs.size > 1) fatal(s"Two definitions named $name in program", defs.head.position)
        }
    }

    // Step 3: Discover types and add them to symbol table
    p.modules.foreach{
      mod =>
        mod.defs.foreach{
          case N.AbstractClassDef(name) => table.addType(mod.name, name)
          case _ => Nil
        }
    }

    // Step 4: Discover type constructors, add them to table
    p.modules.foreach{
      mod =>
        mod.defs.foreach{
          case position@N.CaseClassDef(name, fields, parent) =>
            val modName = mod.name
            val argType = fields.map(f => transformType(f, modName))
            val par =  table.getType(mod.name, parent) match {
              case Some(identifier) => identifier
              case None => fatal(s"Parent of class $name not found", position)
            }
            table.addConstructor(modName, name, argType, par)
          case _ => Nil
        }
    }

    // Step 5: Discover functions signatures, add them to table
    p.modules.foreach{
      mod =>
        mod.defs.foreach{
          case N.FunDef(name, params, retType, expr) =>
            val modName = mod.name
            val argTypes = params.map(p => transformType(p.tt, modName))
            val retT = transformType(retType, modName)
            table.addFunction(modName, name, argTypes, retT)
          case _ => Nil
        }
    }

    // Step 6: We now know all definitions in the program.
    //         Reconstruct modules and analyse function bodies/ expressions
    
    // This part is split into three transfrom functions,
    // for definitions, FunDefs, and expressions.
    // Keep in mind that we transform constructs of the NominalTreeModule 'N' to respective constructs of the SymbolicTreeModule 'S'.
    // transformFunDef is given as an example, as well as some code for the other ones

    def transformDef(df: N.ClassOrFunDef, module: String): S.ClassOrFunDef = { df match {
      case acd : N.AbstractClassDef =>
        val N.AbstractClassDef(name) = acd
        table.getType(module, name) match {
          case Some(identifier) => S.AbstractClassDef(identifier)
          case None => fatal(s"Type of $name not found", acd)
        }

      case ccd : N.CaseClassDef =>
        val N.CaseClassDef(name, fields, parent) = ccd
        table.getConstructor(module, name) match {
          case Some((identifier, constr)) =>
            val pid = table.getType(module, parent) match {
              case Some(id) => id
              case None => fatal(s"Type of $parent not found", ccd)
            }
            val f = fields.map(f => S.TypeTree(transformType(f, module)).setPos(f))
            S.CaseClassDef(identifier, f, pid)
          case None =>
            fatal(s"Type of $parent not found", ccd)
        }

      case fd: N.FunDef =>
        transformFunDef(fd, module)
    }}.setPos(df)

    def transformFunDef(fd: N.FunDef, module: String): S.FunDef = {
      val N.FunDef(name, params, retType, body) = fd
      val Some((sym, sig)) = table.getFunction(module, name)

      params.groupBy(_.name).foreach { case (name, ps) =>
        if (ps.size > 1) {
          fatal(s"Two parameters named $name in function ${fd.name}", fd)
        }
      }

      val paramNames = params.map(_.name)

      val newParams = params zip sig.argTypes map { case (pd@N.ParamDef(name, tt), tpe) =>
        val s = Identifier.fresh(name)
        S.ParamDef(s, S.TypeTree(tpe).setPos(tt)).setPos(pd)
      }

      val paramsMap = paramNames.zip(newParams.map(_.name)).toMap

      S.FunDef(
        sym,
        newParams,
        S.TypeTree(sig.retType).setPos(retType),
        transformExpr(body)(module, (paramsMap, Map()))
      ).setPos(fd)
    }


    // This function takes as implicit a pair of two maps:
    // The first is a map from names of parameters to their unique identifiers,
    // the second is similar for local variables.
    // Make sure to update them correctly if needed given the scoping rules of Amy
    def transformExpr(expr: N.Expr)
                     (implicit module: String, names: (Map[String, Identifier], Map[String, Identifier])): S.Expr = {
      val (params, locals) = names
      val res = expr match {
        //CAse Pattern mathing
        case N.Match(scrut, cases) =>
          // Returns a transformed pattern along with all bindings
          // from strings to unique identifiers for names bound in the pattern.
          // Also, calls 'fatal' if a new name violates the Amy naming rules.
          def transformPattern(pat: N.Pattern): (S.Pattern, List[(String, Identifier)]) = {
            pat match {
              //Case class pattern matching
              case position@N.CaseClassPattern(constr, args) =>
                table.getConstructor(constr.module.getOrElse(module), constr.name) match {
                  case Some((identifier, cSig)) =>
                    if(args.size != cSig.argTypes.size) fatal(s"Wrong number of arguments in class ${constr.name}", position)
                    val args2 = args.map(a => transformPattern(a))
                    val identifiers = args2.flatMap(_._2)
                    identifiers.groupBy(_._1).foreach{
                      case (n, idents) => if(idents.size > 1) fatal(s"Some pattern have the same name", position)
                    }
                    val finalArgs = args2.map(_._1)
                    (S.CaseClassPattern(identifier, finalArgs).setPos(position), identifiers)
                  case None =>
                    fatal(s"Class ${constr.name} does not exist")
                }

              //Case Litteral pattern matching
              case position@N.LiteralPattern(lit) =>
                val transformedlit = lit match {
                  case N.IntLiteral(i) => S.IntLiteral(i)
                  case N.StringLiteral(s) => S.StringLiteral(s)
                  case N.BooleanLiteral(b) => S.BooleanLiteral(b)
                  case N.UnitLiteral() => S.UnitLiteral()
                }
                (S.LiteralPattern(transformedlit).setPos(position), Nil)

              //Case Id pattern matching
              case position@N.IdPattern(name) =>
                if(locals.get(name).isDefined) fatal(s"Case name identifier $name is already used")
                val id = Identifier.fresh(name)
                (S.IdPattern(id).setPos(position), List((name, id)))

              //Case wildcard pattern matching
              case position@N.WildcardPattern() =>
                (S.WildcardPattern().setPos(position), Nil)
            }

          }

          def transformCase(cse: N.MatchCase) = {
            val N.MatchCase(pat, rhs) = cse
            val (newPat, moreLocals) = transformPattern(pat)
            //Recursively run transformExpr with new found locals
            val nextRhs = transformExpr(rhs)(module, (params, locals ++ moreLocals))
            S.MatchCase(newPat, nextRhs).setPos(cse)
          }

          S.Match(transformExpr(scrut), cases.map(transformCase))

        //Case Sequence
        case position@N.Sequence(e1, e2) =>
          S.Sequence(transformExpr(e1), transformExpr(e2))

        //Case call
        case position@N.Call(qname, args) =>
          table.getFunction(qname.module.getOrElse(module), qname.name) match {
            //Function Call
            case Some((identifier, sig)) =>
              if(sig.argTypes.size != args.size) fatal(s"Number of arguments not matching", expr)
              S.Call(identifier, args.map(a => transformExpr(a)))

            //Type Call
            case None =>
              table.getConstructor(qname.module.getOrElse(module), qname.name) match {
                case Some((identifier, sig)) =>
                  if(sig.argTypes.size != args.size) fatal(s"Number of arguments not matching", expr)
                  S.Call(identifier, args.map(a => transformExpr(a)))

                //When function and type not found
                case None => fatal(s"Can't find this Type or Function :${qname.name}", position)
              }
          }

        //Case Variable
        case position@N.Variable(name) =>
          locals.get(name) match {
            case Some(identifier) => S.Variable(identifier)
            case None => fatal(s"Variable $name does not exist", position)
          }

        //Case local variable definition
        case position@N.Let(df, value, body) =>
          val name = df.name
          if(locals.contains(name)) fatal(s"This name is alredy defined $name")
          val identifier = Identifier.fresh(name)
          val finalDf = S.ParamDef(identifier, S.TypeTree(transformType(df.tt, module)).setPos(df.tt))
          val nextlocals = (params, locals + (name -> identifier))

          S.Let(finalDf, transformExpr(value), transformExpr(body)(module, nextlocals))


        // Binary operators
        case N.Plus(lhs, rhs) =>
          S.Plus(transformExpr(lhs), transformExpr(rhs))
        case N.Minus(lhs, rhs) =>
          S.Minus(transformExpr(lhs), transformExpr(rhs))
        case N.Times(lhs, rhs) =>
          S.Times(transformExpr(lhs), transformExpr(rhs))
        case N.Div(lhs, rhs) =>
          S.Div(transformExpr(lhs), transformExpr(rhs))
        case N.Mod(lhs, rhs) =>
          S.Mod(transformExpr(lhs), transformExpr(rhs))
        case N.LessThan(lhs, rhs) =>
          S.LessThan(transformExpr(lhs), transformExpr(rhs))
        case N.LessEquals(lhs, rhs) =>
          S.LessEquals(transformExpr(lhs), transformExpr(rhs))
        case N.And(lhs, rhs) =>
          S.And(transformExpr(lhs), transformExpr(rhs))
        case N.Or(lhs, rhs) =>
          S.Or(transformExpr(lhs), transformExpr(rhs))
        case N.Equals(lhs, rhs) =>
          S.Equals(transformExpr(lhs), transformExpr(rhs))
        case N.Concat(lhs, rhs) =>
          S.Concat(transformExpr(lhs), transformExpr(rhs))

        // Unary operators
        case N.Not(e) =>
          S.Not(transformExpr(e))
        case N.Neg(e) =>
          S.Neg(transformExpr(e))

        // Literals
        case N.IntLiteral(value) =>
          S.IntLiteral(value)
        case N.BooleanLiteral(value) =>
          S.BooleanLiteral(value)
        case N.StringLiteral(value) =>
          S.StringLiteral(value)
        case N.UnitLiteral() =>
          S.UnitLiteral()


      }
      res.setPos(expr)
    }

    // Putting it all together to construct the final program for step 6.
    val newProgram = S.Program(
      p.modules map { case mod@N.ModuleDef(name, defs, optExpr) =>
        S.ModuleDef(
          table.getModule(name).get,
          defs map (transformDef(_, name)),
          optExpr map (transformExpr(_)(name, (Map(), Map())))
        ).setPos(mod)
      }
    ).setPos(p)

    (newProgram, table)

  }
}
