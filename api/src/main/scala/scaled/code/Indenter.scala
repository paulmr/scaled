//
// Scaled - a scalable editor extensible via JVM languages
// http://github.com/samskivert/scaled/blob/master/LICENSE

package scaled.code

import reactual.ValueV
import scala.annotation.tailrec
import scaled._
import scaled.util.Chars

/** Encapsulates a strategy for indenting a line of code. */
abstract class Indenter (ctx :Indenter.Context) {

  /** Requests that the indentation for `line` be computed.
    *
    * @param block the nearest enclosing block that encloses line.
    * @param line the line to be indented.
    * @param pos position of the first non-whitespace character on `line`.
    *
    * @return `Some(col)` indicating the column to which `line` should be indented, or `None` if
    * this strategy is not appropriate for `line`.
    */
  def apply (block :Block, line :LineV, pos :Loc) :Option[Int]

  /** Returns an indentation `steps` steps inset from `anchor`.
    * @param anchor the indentation of the anchor line (in characters, not steps).
    */
  protected def indentFrom (anchor :Int, steps :Int) :Int =
    anchor + steps * ctx.indentWidth

  protected val buffer = ctx.buffer

  /** Issues an indentation debugging message. */
  protected def debug (msg :String) :Unit = if (ctx.debug) println(msg)

  protected def findCodeForward (m :Matcher, start :Loc, stop :Loc = buffer.end) :Loc =
    Indenter.findCodeForward(buffer, m, start, stop)

  protected def findCodeBackward (m :Matcher, start :Loc, stop :Loc = buffer.start) :Loc =
    Indenter.findCodeBackward(buffer, m, start, stop)
}

object Indenter {
  import Chars._

  /** Provides context to Indenters. */
  abstract class Context {
    def buffer :BufferV
    def debug :Boolean
    def indentWidth :Int
  }

  /** Returns the number of whitespace chars at the start of `line`. */
  def readIndent (line :LineV) :Int =
    // TODO: this will eventually have to handle tabs; we'll probably just scan the line oursevles
    // ticking up our counter 1 for each space and tab-width for each tab
    line.firstNonWS

  /** Returns the number of whitespace chars at the start of the line at `pos`. */
  def readIndent (buffer :BufferV, pos :Loc) :Int = readIndent(buffer.line(pos))

  /** Reads the indentation of the line at `pos`, with the caveat that if the start of that line
    * appears to be the continuation of an arglist, we scan back to the line that starts the
    * arglist and return the indentation of that line instead.
    */
  def readIndentSkipArglist (buffer :BufferV, pos :Loc) :Int = {
    // scans forward to look for ( or ); if we see a ) first, then we're in an arglist
    def inArgList (start :Loc, end :Loc) =
      buffer.charAt(buffer.scanForward(isOpenOrCloseParen, start, end)) == ')'
    // scans backward to find the ( which opens our arglist; doesn't handle nesting or scala style
    // multiple arglists...
    def findArgListStart (from :Loc) = buffer.scanBackward(isOpenParen, from)
    val firstNonWS = pos.atCol(buffer.line(pos).firstNonWS)
    // TODO: this doesn't handle `if (foo() && \n bar()) {`
    // it scans forward from bar and sees an open paren and bails
    val start = if (inArgList(firstNonWS, pos)) findArgListStart(firstNonWS) else firstNonWS
    readIndent(buffer, start)
  }

  val isOpenOrCloseParen = (c :Char, s :Syntax) => (c == '(' || c == ')') && s.isCode
  val isOpenParen        = (c :Char, s :Syntax) => (c == '('            ) && s.isCode

  /** Returns true if `m` matches the first non-whitespace characters of `line`. */
  def startsWith (line :LineV, m :Matcher) :Boolean = line.matches(m, line.firstNonWS)

  /** Returns true if `m` matches the last non-whitespace characters of `line`. */
  def endsWith (line :LineV, m :Matcher) :Boolean = line.lastIndexOf(m) match {
    case -1 => false
    case ii => line.indexOf(isNotWhitespace, ii+m.matchLength) == -1
  }

  /** Performs [[Buffer.findForward]] but skips over non-code matches. */
  def findCodeForward (buffer :BufferV, m :Matcher, start :Loc, stop :Loc) :Loc =
    buffer.findForward(m, start, stop) match {
      case Loc.None => Loc.None
      // findForward starts looking at `loc` so we have to bump forward a character before making
      // our recursive call
      case loc => if (buffer.syntaxAt(loc).isCode) loc
                  else findCodeForward(buffer, m, buffer.forward(loc, 1), stop)
    }

  /** Performs [[Buffer.findBackward]] but skips over non-code matches. */
  def findCodeBackward (buffer :BufferV, m :Matcher, start :Loc, stop :Loc) :Loc =
    buffer.findBackward(m, start, stop) match {
      case Loc.None => Loc.None
      // findBackward starts looking prior to `loc` not at `loc` so we don't need to adjust `loc`
      // before making our recursive call
      case loc => if (buffer.syntaxAt(loc).isCode) loc else findCodeBackward(buffer, m, loc, stop)
    }

  /** Returns the token (word) immediately preceding `pos` in `line`. If non-whitespace, non-word
    * characters precede `pos` or no word characters precede `pos`, `None` is returned.
    *
    * If `pos` itself and the character immediately preceding `pos` are both word characters, this
    * function will "work" in that the token up to but not including `pos` will be returned, but
    * that's a strange thing to ask for, so beware.
    */
  def prevToken (line :LineV, pos :Int) :Option[String] = line.lastIndexOf(isWord, pos-1) match {
    case -1     => None
    case endIdx =>
      // make sure only whitespace characters intervene between endIdx and pos
      val nonWhite = line.indexOf(isNotWhitespace, endIdx+1)
      if (nonWhite != -1 && nonWhite < pos) None
      else Some(line.sliceString(line.lastIndexOf(isNotWord, endIdx)+1, endIdx+1))
  }

  /** Uses a heuristic to attempt to detect the number of spaces per indent in `buffer`. This
    * generates a histogram of indent sizes for the first 64 lines, then checks whether they're all
    * multiples of 8, 7, 6, 5, 4, 3, or 2. 1 will never be auto-detected.
    * @return the detected indent, or 0 if we were unable to confidently identify an indent.
    */
  def detectIndent (buffer :BufferV) :Int = {
    val counts = new Array[Int](25) // count all indents up to and including 24
    val maxRow = math.min(buffer.lines.size, 64) // scan at most the first 64 lines
    // TODO: what about files with giant copyright blurbs at the top?...
    var row = 0 ; while (row < maxRow) {
      val indent = readIndent(buffer.line(row))
      if (indent < counts.length) counts(indent) += 1
      row += 1
    }
    // counts up the number of indents matching the candidate size
    // if any non-matching indents are found, zero is returned
    def matches (size :Int) :Int = {
      // skip indent=0 when counting matching indents
      var ii = 1 ; var total = 0 ; while (ii < counts.length) {
        val count = counts(ii)
        if (count > 0 && ii % size != 0) return 0
        total += count
        ii += 1
      }
      total
    }
    val MinMatches = 4
    var size = 8 ; while (size > 1) {
      if (matches(size) > MinMatches) return size
      size -= 1
    }
    0 // alas, we can't tell
  }

  /** Indents based on the innermost block that contains pos.
    *
    *  - Lines following a brace are indented one step from the line that contains the brace.
    *    The closing brace is indented to match the line that contains the opening brace.
    *
    *    void foo () {
    *      nextLine();
    *    }
    *
    *  - A line following a dangling open paren is indented one step from the line with the open
    *    paren:
    *
    *    void someFn (
    *      a :Int, b :String, c :Double)
    *
    *  - A line following an open paren which is not the last thing on the line is a continued
    *    argument list and is indented to the same column as the open paren:
    *
    *    void someFn (a :Int, b :String, c :Double,
    *                 d :Triple, e :Quadruple)
    *
    *  - A line following a non-dangling square bracket is indented to the first non-whitespace
    *    character following the bracket:
    *
    *    val foo = [ "one",
    *                "two",
    *                "three" ]
    *    val bar = ["one",
    *               "two",
    *               "three"]
    *
    *  - A line following a dangling square bracket is indented as normal block:
    *
    *    val foo = [
    *      "bar",
    *      "baz"
    *    ]
    */
  class ByBlock (ctx :Context) extends Indenter(ctx) {

    /** Reads the indentation of the block starting at `pos`. The default implementation uses
      * [[readIndentSkipArglist]] to handle blocks that start on arglist continuation lines. Modes
      * with other special needs may wish to customize this further.
      */
    protected def readBlockIndent (pos :Loc) = readIndentSkipArglist(buffer, pos)

    def apply (block :Block, line :LineV, pos :Loc) :Option[Int] = {
      val bstart = block.start
      val openNonWS = buffer.line(bstart).indexOf(isNotWhitespace, bstart.col+1)
      val openIsEOL = openNonWS == -1
      val indent = buffer.charAt(bstart) match {
        // align to the first non-whitespace character following the bracket (for non-danglers)
        case '[' if (!openIsEOL) =>
          debug(s"Aligning to firstNonWs after square bracket ($block $openNonWS)")
          openNonWS
        // align to the open paren (for non-danglers)
        case '(' if (!openIsEOL) =>
          debug(s"Aligning to (non-dangling) open paren ($block)")
          bstart.col + 1
        // use block indentation for everything else (including {)
        case _ =>
          val blockIndent = readBlockIndent(bstart)
          // if the first non-whitespace character is our close brace, use the same indent
          // as the line with the open brace
          if (block.isValid && block.end == pos) {
            debug(s"Aligning close brace with open ($block)")
            blockIndent
          }
          // if the block start is the start of the buffer, don't indent
          else if (bstart == buffer.start) {
            debug(s"Block start is buffer start, no indent. ($block)")
            0
          }
          // otherwise indent one from there; TODO: otherwise use previous line's indent?
          else {
            debug(s"Identing one step from block ($block @ $blockIndent)")
            indentFrom(blockIndent, 1)
          }
      }
      Some(indent)
    }
  }

  /** Indents the line following one-liner conditionals like `if` and `while`. The conditionals must
    * have an arg list, i.e. this rule checks that the previous line ends with `)`, then matches
    * the token preceding the open `(` against `tokens` to check for applicability. Examples:
    *
    * ```
    * if (foo)
    *   bar()
    * while (foo)
    *   bar()
    * // etc.
    * ```
    *
    * Use `OneLinerNoArgs` for non-conditional one liners, like `else`, `do`, etc.
    */
  class OneLinerWithArgs (ctx :Context, blocker :Blocker, tokens :Set[String])
      extends Indenter(ctx) {
    def apply (block :Block, line :LineV, pos :Loc) :Option[Int] = {
      // seek backward to the first non-whitespace character
      val pc = buffer.scanBackward(isNotWhitespace, pos, block.start)
      // if it's not on the preceding line, or it's not a ')', we're inapplicable
      if (pc.row != pos.row-1 || buffer.charAt(pc) != ')') None
      else {
        // find the open paren, and check that the token preceding it is in `tokens`
        blocker.apply(pc.nextC, Syntax.Default) flatMap { b =>
          prevToken(buffer.line(b.start), b.start.col) flatMap { token =>
            if (!tokens(token)) None
            else {
              debug(s"Indenting one liner + args '$token' @ $b")
              Some(indentFrom(readIndent(buffer, b.start), 1))
            }
          }
        }
      }
    }
  }

  /** Indents the line following one-liner non-conditionals like `else` and `do`. The token on the
    * line preceding must exactly match one of our candidate tokens. Use `OneLinerWithArgs` for
    * conditional one liners, like `if`, `while`, etc.
    */
  class OneLinerNoArgs (ctx :Context, tokens :Set[String]) extends Indenter(ctx) {
    def apply (block :Block, line :LineV, pos :Loc) :Option[Int] = {
      // check whether the line immediately preceding this one ends with one of our tokens
      if (pos.row == 0) None
      else {
        val pline = buffer.line(pos.row-1)
        prevToken(pline, pline.length) flatMap { token =>
          if (!tokens(token)) None
          else {
            debug(s"Indenting one liner (no args) '$token' @ $pos")
            Some(indentFrom(readIndent(pline), 1))
          }
        }
      }
    }
  }

  /** Aligns `catch` statements with their preceding `try`. */
  class TryCatchAlign (ctx :Context) extends PairAnchorAlign(ctx) {
    protected val anchorM = Matcher.regexp("\\btry\\b")
    protected val secondM = Matcher.regexp("catch\\b")
  }

  /** Aligns `finally` statements with their preceding `try`. */
  class TryFinallyAlign (ctx :Context) extends PairAnchorAlign(ctx) {
    protected val anchorM = Matcher.regexp("\\btry\\b")
    protected val secondM = Matcher.regexp("finally\\b")
  }

  /** Aligns `else` and `else if` statements with their preceding `if`. */
  class IfElseIfElseAlign (ctx :Context) extends TripleAnchorAlign(ctx) {
    protected val firstMiddleM = Matcher.regexp("\\b(if|else\\s+if)\\b")
    protected val middleM = Matcher.regexp("else\\s+if\\b")
    protected val lastM = Matcher.regexp("else\\b")
  }

  /** Aligns `else` and `elif` statements with their preceding `if`. */
  class IfElifElseAlign (ctx :Context) extends TripleAnchorAlign(ctx) {
    protected val firstMiddleM = Matcher.regexp("\\b(if|elif)\\b")
    protected val middleM = Matcher.regexp("elif\\b")
    protected val lastM = Matcher.regexp("else\\b")
  }

  /** Aligns `else` statements with their preceding `if`. NOTE: this does not handle `else if`.
    * If your language uses those, use `IfElseIfElse` instead. */
  class IfElseAlign (ctx :Context) extends PairAnchorAlign(ctx) {
    protected val anchorM = Matcher.regexp("\\bif\\b")
    protected val secondM = Matcher.regexp("else\\b")
  }

  /** Indents `bar` and `baz` keywords to match the `foo` keyword for `foo / bar* / baz?` constructs.
    * This is generally only needed for `if / else if / else` because the `else if` can repeat.
    *
    * For `foo / bar? / baz?` constructs (like `try/catch/finally`) just use a pair of
    * `PairAnchorAlign` rules `(foo, bar)` and `(foo, baz)`.
    */
  abstract class TripleAnchorAlign (ctx :Context) extends AnchorAlign(ctx) {

    /** The matcher that matches the first or middle keyword (must be regexp).
      * This will be used to match the keywords anywhere in a line. */
    protected val firstMiddleM :Matcher

    /** The matcher that matches the middle keyword.
      * This will only be used to match the keyword at the start of a line. */
    protected val middleM :Matcher

    /** The matcher that matches the last keyword.
      * This will only be used to match the keyword at the start of a line. */
    protected val lastM :Matcher

    def apply (block :Block, line :LineV, pos :Loc) :Option[Int] = {
      if (!line.matches(lastM, pos.col) && !line.matches(middleM, pos.col)) None
      else indentToAnchor(block, pos, firstMiddleM)
    }
  }

  /** Indents `bar` keywords to match their `foo` counterpart. Examples of `foo/bar` may include
    * `if/else`, `try/catch`, `try/finally`, `for/yield`. NOTE: this should not be used for
    * `if/else if/else` because the `else if` can repeat, which this won't handle. Use
    * `TripleAnchorAlign` for that case.
    */
  abstract class PairAnchorAlign (ctx :Context) extends AnchorAlign(ctx) {

    /** The matcher that matches the anchor.
      * This will be used to match the anchor anywhere on a line. */
    protected val anchorM :Matcher

    /** The matcher that matches the second keyword.
      * This will only be used to match the keyword at the start of a line. */
    protected val secondM :Matcher

    def apply (block :Block, line :LineV, pos :Loc) :Option[Int] = {
      if (!line.matches(secondM, pos.col)) None
      else indentToAnchor(block, pos, anchorM)
    }
  }

  /** Indents statements relative to an anchor keyword. This is chiefly useful for things like
    * aligning an `else` with the `if` that preceded it, etc.
    */
  abstract class AnchorAlign (ctx :Context) extends Indenter(ctx) {

    protected def indentToAnchor (block :Block, pos :Loc, anchorM :Matcher) :Option[Int] =
      findCodeBackward(anchorM, pos, block.start) match {
        case Loc.None => None
        case      loc => debug(s"Aligning to '$anchorM' @ $loc") ; Some(loc.col)
      }
  }
}
