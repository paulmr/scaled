//
// Scaled - a scalable editor extensible via JVM languages
// http://github.com/samskivert/scaled/blob/master/LICENSE

package scaled

import scala.annotation.tailrec

import reactual.SignalV

/** Models a single line of text, which may or may not be part of a buffer.
  *
  * Lines may be created externally from character or string data or they may be obtained as part
  * of a buffer. In the former case they are immutable and will have type [[Line]], in the latter
  * case they are a view on mutable data and have type [[LineV]]. Do not retain a reference to a
  * [[LineV]] as it may change after you relinquish control of the editor thread.
  */
abstract class LineV {

  /** The length (in characters) of this line. */
  def length :Int

  /** Returns the character at `pos`. If `pos` is >= [[length]] line 0 is returned. */
  def charAt (pos :Int) :Char

  /** Returns the CSS style class applied to the character at `pos`. If no custom style has been
    * applied, or `pos` is >= [[length]], [[Line.defaultStyle]] is returned. */
  def styleAt (pos :Int) :String

  /** Bounds the supplied column into this line. This adjusts it to be in [0, [[length]]] (inclusive
    * of the length because the point can be after the last char on this line). */
  def bound (col :Int) :Int = math.max(0, math.min(length, col))

  /** Bounds the supplied loc into this line by bounding its column via [[bound(Int)]]. */
  def bound (loc :Loc) :Loc = loc.atCol(bound(loc.col))

  /** Returns a view of the specified slice of this line. */
  def view (start :Int, until :Int = length) :LineV

  /** Extracts the specified region of this line into a new line.
    * @param start the index of the first character to include in the slice.
    * @param until one past the index of the last character to include in the slice. */
  def slice (start :Int, until :Int = length) :Line

  /** Copies `[start, until)` from this line into `cs`/`ss` at `offset`. */
  def sliceInto (start :Int, until :Int, cs :Array[Char], ss :Array[String], offset :Int) :Unit

  /** Returns the characters in `[start, until)` as a string. */
  def sliceString (start :Int, until :Int) :String

  /** Returns the contents of this line as a string. */
  def asString :String

  override def equals (other :Any) = other match {
    case ol :LineV =>
      @tailrec def loop (ii :Int) :Boolean = (ii < 0) || (charAt(ii) == ol.charAt(ii) &&
        styleAt(ii) == ol.styleAt(ii) && loop(ii-1))
      length == ol.length && loop(length-1)
    case _ => false
  }

  override def hashCode = {
    @tailrec def loop (code :Int, ii :Int) :Int =
      if (ii < 0) code else loop(31*code + charAt(ii), ii-1)
    loop(1, length-1)
  }
}

/** Models a single immutable line of text that is not associated with a buffer.
  *
  * The constructor takes ownership of `cs`. Do not mutate it after using it to create a `Line`.
  * Pass `cs.clone` if the caller needs to retain the ability to mutate the array.
  */
class Line (_cs :Array[Char], _ss :Array[String], _offset :Int, val length :Int) extends LineV {
  def this (cs :Array[Char], ss :Array[String]) = this(cs, ss, 0, cs.length)
  def this (cs :Array[Char], style :String) = this(cs, Array.fill(cs.length)(style))
  def this (s :String, style :String) = this(s.toCharArray, style)
  def this (s :String) = this(s, Line.defaultStyle)

  /** Returns a new line which contains `other` appended to `this`. */
  def merge (other :Line) :Line = {
    val cs = Array.ofDim[Char](length + other.length)
    val ss = Array.ofDim[String](cs.length)
    sliceInto(0, length, cs, ss, 0)
    other.sliceInto(0, other.length, cs, ss, length)
    new Line(cs, ss)
  }

  override def charAt (pos :Int) = if (pos < length) _cs(pos+_offset) else 0
  override def styleAt (pos :Int) = if (pos < length) _ss(pos+_offset) else Line.defaultStyle
  override def view (start :Int, until :Int) =
    if (start == 0 && until == length) this else slice(start, until)
  override def slice (start :Int, until :Int) = new Line(_cs, _ss, _offset+start, until-start)
  override def sliceInto (start :Int, until :Int, cs :Array[Char], ss :Array[String], offset :Int) {
    System.arraycopy(_cs, _offset+start, cs, offset, until-start)
    System.arraycopy(_ss, _offset+start, ss, offset, until-start)
  }
  override def sliceString (start :Int, until :Int) = new String(_cs, _offset+start, until-start)
  override def asString :String = new String(_cs, _offset, length)
  override def toString () = s"$asString [${_offset}:$length/${_cs.length}]"
}

/** `Line` related types and utilities. */
object Line {

  final val defaultStyle = "textFace"

  /** An event emitted when one or more characters are deleted from a line and replaced by one or
    * more characters. The change will already have been applied when this event is dispatched. */
  case class Edit (
    /** The location in the buffer at which the edited line resides (`row`) and the offset into that
      * line at which characters were replaced (`col`). */
    loc :Loc,
    /** The characters that were deleted, as a line. */
    deletedLine :Line,
    /** The number of characters that were added. */
    added :Int,
    /** The buffer that contains the line that was edited. */
    buffer :Buffer) extends Undoable {

    /** Returns the `idx`th added character. */
    def addedChar (idx :Int) = buffer.line(loc).charAt(loc.col+idx)
    /** The characters that were added as a line view. */
    def addedLine :LineV = buffer.line(loc).view(loc.col, added)
    /** The number of characters that were deleted. */
    def deleted = deletedLine.length

    // remove the added characters and add the removed characters
    override def undo () = buffer.replace(loc, added, deletedLine)

    override def toString = s"LEdit[$loc -'$deletedLine +'$addedLine]"
  }

  /** An empty line. */
  final val Empty = new Line(Array[Char](), Array[String]())
}
