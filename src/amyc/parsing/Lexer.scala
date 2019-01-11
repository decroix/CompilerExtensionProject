package amyc
package parsing

import utils._
import scala.io.Source
import java.io.File

// The lexer for Amy.
// Transforms an iterator coming from scala.io.Source to a stream of (Char, Position),
// then uses a functional approach to consume the stream.
object Lexer extends Pipeline[List[File], Stream[Token]] {
  import Tokens._

  /** Maps a string s to the corresponding keyword,
    * or None if it corresponds to no keyword
    */
  private def keywords(s: String): Option[Token] = s match {
    case "abstract" => Some(ABSTRACT())
    case "Boolean"  => Some(BOOLEAN())
    case "case"     => Some(CASE())
    case "class"    => Some(CLASS())
    case "def"      => Some(DEF())
    case "else"     => Some(ELSE())
    case "error"    => Some(ERROR())
    case "extends"  => Some(EXTENDS())
    case "false"    => Some(FALSE())
    case "if"       => Some(IF())
    case "Int"      => Some(INT())
    case "match"    => Some(MATCH())
    case "object"   => Some(OBJECT())
    case "String"   => Some(STRING())
    case "true"     => Some(TRUE())
    case "Unit"     => Some(UNIT())
    case "val"      => Some(VAL())
    case _          => None
  }

  private def lexFile(ctx: Context)(f: File): Stream[Token] = {
    import ctx.reporter._

    // Special character which represents the end of an input file
    val EndOfFile: Char = scala.Char.MaxValue

    val source = Source.fromFile(f)

    // Useful type alias:
    // The input to the lexer will be a stream of characters,
    // along with their positions in the files
    type Input = (Char, Position)

    def mkPos(i: Int) = Position.fromFile(f, i)

    // The input to the lexer
    val inputStream: Stream[Input] =
      source.toStream.map(c => (c, mkPos(source.pos))) #::: Stream((EndOfFile, mkPos(source.pos)))

    /** Gets rid of whitespaces and comments and calls readToken to get the next token.
      * Returns the first token and the remaining input that did not get consumed
      */
    @scala.annotation.tailrec
    def nextToken(stream: Stream[Input]): (Token, Stream[Input]) = {
      require(stream.nonEmpty)

      val (currentChar, currentPos) #:: rest = stream

      // Use with care!
      def nextChar = rest.head._1


      //Made by me
      def nextStream(tStream: Stream[Input], prevChar: Char) : Stream[Input] = {
        val (currChar, _) #:: trest = tStream
        if((prevChar == '*' && currChar == '/') || currChar == EndOfFile){
          if(currChar == EndOfFile){
            ctx.reporter.fatal("Unclosed comment", currentPos)
          }
          else{
            trest
          }
        }
        else{
          nextStream(trest, currChar)
        }
      }

      if (Character.isWhitespace(currentChar)) {
        nextToken(stream.dropWhile{ case (c, _) => Character.isWhitespace(c) } )
      } else if (currentChar == '/' && nextChar == '/') {
        // Single-line comment
        nextToken(stream.dropWhile{ case (c, _) => c != '\n' && c != '\r' && c != EndOfFile})
      } else if (currentChar == '/' && nextChar == '*') {
        // Multi-line comment
        nextToken(nextStream(rest.tail, rest.tail.head._1))
      } else {
        readToken(stream)
      }
    }

    /** Reads the next token from the stream. Assumes no whitespace or comments at the beginning.
      * Returns the first token and the remaining input that did not get consumed.
      */
    def readToken(stream: Stream[Input]): (Token, Stream[Input]) = {
      require(stream.nonEmpty)

      val (currentChar, currentPos) #:: rest = stream

      // Use with care!
      def nextChar = rest.head._1

      // Returns input token with correct position and uses up one character of the stream
      def useOne(t: Token) = (t.setPos(currentPos), rest)
      // Returns input token with correct position and uses up two characters of the stream
      def useTwo(t: Token) = (t.setPos(currentPos), rest.tail)

      currentChar match {
        case `EndOfFile` => useOne(EOF())

        // Reserved word or Identifier
        case _ if Character.isLetter(currentChar) =>
          val (wordLetters, afterWord) = stream.span { case (ch, _) =>
            Character.isLetterOrDigit(ch) || ch == '_'
          }
          val word = wordLetters.map(_._1).mkString
          // Hint: Decide if it's a letter or reserved word (use our infrastructure!),
          // and return the correct token, along with the remaining input stream.
          // Make sure you set the correct position for the token.
          if(keywords(word).isDefined){
            (keywords(word).get.setPos(currentPos), afterWord)
          }
          else {
            (ID(word).setPos(currentPos), afterWord)
          }

        // Int literal
        case _ if Character.isDigit(currentChar) =>
          // Hint: Use a strategy similar to the previous example.
          // Make sure you fail for integers that do not fit 32 bits.
          val (digitChars, afterDigit) = stream.span{ case (ch, _) =>
            Character.isDigit(ch)
          }
          val digit = digitChars.map(a => a._1).mkString
          val bigIntDigit = BigInt(digit)
          if(bigIntDigit.isValidInt){
            (INTLIT(bigIntDigit.toInt).setPos(currentPos), afterDigit)
          }
          else{
            ctx.reporter.error("Integer does not fit 32 bits", currentPos)
            (Tokens.BAD().setPos(currentPos), afterDigit)
          }
        // String literal
        case '"' =>
          val (stringChars, afterString) = stream.tail.span{case (ch, _) =>
            ch != '"' && ch != '\n' && ch != '\r' && ch != EndOfFile
          }
          if(afterString.head._1 != '"'){
            ctx.reporter.fatal("Unclosed string", currentPos)
          }
          else{
            val string = stringChars.map(c => c._1).mkString
            (STRINGLIT(string).setPos(currentPos), afterString.tail)
          }


        //semicolon
        case ';' =>
          useOne(SEMICOLON())

        //Plus and Concat
        case '+' =>
          if(nextChar == '+'){
            useTwo(CONCAT())
          }
          else{
            useOne(PLUS())
          }

        //Minus
        case '-' =>
          useOne(MINUS())

        //Times
        case '*' =>
          useOne(TIMES())

        //Division
        case '/' =>
          useOne(DIV())

        //Modulo
        case '%' =>
          useOne(MOD())

        //Less than and less than or equals
        case '<' =>
          if(nextChar == '='){
            useTwo(LESSEQUALS())
          }
          else{
            useOne(LESSTHAN())
          }

        //And
        case '&' =>
          if (nextChar == '&'){
            useTwo(AND())
          }
          else{
            ctx.reporter.error("unknown operator", currentPos)
            useOne(BAD())
          }


        //Or
        case '|' =>
          if (nextChar == '|'){
            useTwo(OR())
          }
          else{
            ctx.reporter.error("unknown operator", currentPos)
            useOne(BAD())
          }

        //Right arrow, Equals, equals sign
        case '=' =>
          nextChar match {
            case '>' => useTwo(RARROW())
            case '=' => useTwo(EQUALS())
            case _ => useOne(EQSIGN())
          }

        // Bang
        case '!' =>
          useOne(BANG())

        //Left brace
        case '{' =>
          useOne(LBRACE())

        //Right brace
        case '}' =>
          useOne(RBRACE())

        //Left parenthesis
        case '(' =>
          useOne(LPAREN())

        //Right parenthesis
        case ')' =>
          useOne(RPAREN())

        //Comma
        case ',' =>
          useOne(COMMA())

        //Colon
        case ':' =>
          useOne(COLON())

        //Dot
        case '.' =>
          useOne(DOT())

        //Underscore
        case '_' =>
          useOne(UNDERSCORE())

        case _ =>
          ctx.reporter.error("Unknown character", currentPos)
          useOne(BAD())
               // (You can look at Tokens.scala for an exhaustive list of tokens)
               // There should also be a case for all remaining (invalid) characters in the end
      }
    }

    // To lex a file, call nextToken() until it returns the empty Stream as "rest"
    def tokenStream(s: Stream[Input]): Stream[Token] = {
      if (s.isEmpty) Stream()
      else {
        val (token, rest) = nextToken(s)
        token #:: tokenStream(rest)
      }
    }

    tokenStream(inputStream)
  }

  // Lexing all input files means putting the tokens from each file one after the other
  def run(ctx: Context)(files: List[File]): Stream[Token] = {
    files.toStream flatMap lexFile(ctx)
  }
}

/** Extracts all tokens from input and displays them */
object DisplayTokens extends Pipeline[Stream[Token], Unit] {
  def run(ctx: Context)(tokens: Stream[Token]): Unit = {
    tokens.toList foreach { t => println(s"$t(${t.position.withoutFile})") }
  }
}
