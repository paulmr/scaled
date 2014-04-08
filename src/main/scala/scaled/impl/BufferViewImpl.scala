//
// Scaled - a scalable editor extensible via JVM languages
// http://github.com/samskivert/scaled/blob/master/LICENSE

package scaled.impl

import scala.collection.mutable.ArrayBuffer

import reactual.{Future, Value}

import scaled._

// TODO: should the point be automatically adjusted when text is inserted into the buffer before
// the point?

/** Implements [[BufferView]] and [[RBufferView]]. This class mainly defines the model, and
  * [[BufferArea]] etc. actually visualize the model and handle UX.
  */
class BufferViewImpl (editor :Editor, _buffer :BufferImpl, initWid :Int, initHei :Int)
    extends RBufferView(initWid, initHei) {

  private val _lines = ArrayBuffer[LineViewImpl]() ++ _buffer.lines.map(new LineViewImpl(_))

  def clearEphemeralPopup () {
    if (popup.isDefined && popup().isEphemeral) popup.clear()
  }

  // narrow the return types of these guys for our internal friends
  override def buffer :BufferImpl = _buffer
  override def lines :Seq[LineViewImpl] = _lines

  // respond to buffer changes by adding/removing line views
  _buffer.edited.onValue { change =>
    if (change.deleted > 0) {
      // _lines.slice(change.offset, change.offset+change.deleted) foreach onDeleted
      _lines.remove(change.offset, change.deleted)
    }
    if (change.added > 0) {
      val added = _buffer.lines.slice(change.offset, change.offset+change.added)
      val newlns = added map(new LineViewImpl(_))
      _lines.insert(change.offset, newlns :_*)
    }
  }
  // pass line edits onto the line views
  _buffer.lineEdited.onValue { change => _lines(change.loc.row).onEdit(change) }
  // pass style changes onto the line views
  _buffer.lineStyled.onValue { loc => _lines(loc.row).onStyle(loc) }
}
