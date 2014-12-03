//
// Scaled - a scalable editor extensible via JVM languages
// http://github.com/scaled/scaled/blob/master/LICENSE

package scaled.impl

import java.nio.file.Path
import javafx.application.{Application, Platform}
import javafx.beans.binding.Bindings
import javafx.geometry.{HPos, VPos}
import javafx.scene.control.Label
import javafx.scene.layout.{BorderPane, Region, VBox}
import javafx.stage.Stage
import scala.collection.mutable.{Map => MMap}
import scaled._
import scaled.major.TextMode
import scaled.util.Errors

/** The editor pane groups together the various UI components that are needed to edit a single
  * buffer. This includes the code area, status line and minibuffer area. It also manages the
  * [[BufferView]] state and wires everything together.
  *
  * Multiple instances of an editor pane may be instantiated at the same time (and placed into tabs
  * or simply shown one at a time, depending on the user's configuration), but each editor pane is
  * largely an island unto itself.
  */
class WindowImpl (val stage :Stage, ws :WorkspaceImpl, size :(Int, Int))
    extends Region with Window {

  class FrameImpl extends BorderPane with Frame {
    var onKill :Connection = _
    var disp :DispatcherImpl = _

    def focus () {
      val buf = view.buffer
      stage.setTitle(s"Scaled - ${ws.name} - ${buf.name}")
      disp.area.requestFocus()
      ws.focusedBuffer(buf) // move the focused buffer to the head of the buffers
    }

    def setBuffer (buf :BufferImpl) :BufferViewImpl =
      // if we're already displaying this buffer, just keep it
      if (disp != null && buf == view.buffer) view else {
        val Geometry(width, height, _, _) = geometry

        // create the modeline and add some default data before anyone else sneaks in
        val mline = new ModeLineImpl()
        mline.addDatum(buf.dirtyV map(if (_) " *" else " -"), "* indicates unsaved changes")
        mline.addDatum(buf.nameV, "Name of the current buffer")

        val view = new BufferViewImpl(buf, width, height)
        // TODO: move this to LineNumberMode? (and enable col number therein)
        mline.addDatum(view.point map(p => s" L${p.row+1} C${p.col} "), "Current line number")
        // add "*" to our list of tags as this is a "real" buffer; we want global minor modes, but we
        // don't want non-real buffers (like the minimode buffer) to have global minor modes
        val tags = "*" :: Mode.tagsHint(buf.state)

        // listen for buffer death and repopulate this frame as needed
        if (onKill != null) onKill.close()
        onKill = buf.killed.onEmit { setBuffer(ws.buffers.headOption || ws.getScratch()) }

        if (disp != null) disp.dispose()
        disp = new DispatcherImpl(
          WindowImpl.this, resolver(this, buf), view, mline,
          // if no mode was specified, have the package manager infer one
          Mode.nameHint(buf.state, ws.app.pkgMgr.detectMode(buf.name, buf.lines(0).asString)),
          Mode.argsHint(buf.state), tags)

        // TODO: rename this buffer to name<2> (etc.) if its name conflicts with an existing buffer;
        // also set up a listener on it such that if it is written to a new file and that new file
        // has a name that conflicts with an existing buffer, we name<2> it then as well

        setCenter(disp.area)
        setBottom(mline)
        focus()

        // if this buffer is for a non-existent file and is empty, report that we're looking at a
        // "new file" to avoid confusion if the user expected to open a real file but typoed; it's
        // what emacs does and who are we to question five decades of hard won experience
        if (buf.store.file.isDefined && !buf.store.exists) emitStatus("(New file)")

        view
      }

    override def view = disp.area.bview
    override def geometry = WindowImpl.this.geometry // TODO
    override def visit (buffer :Buffer) = setBuffer(buffer.asInstanceOf[BufferImpl])
  }

  /** Used to resolve modes in this window/frame. */
  def resolver (frame :FrameImpl, buf :BufferImpl) = new ModeResolver(ws.app.svcMgr, this, frame) {
    override def modes (major :Boolean) = Set() ++ ws.app.pkgMgr.modes(major)
    override def minorModes (tags :Seq[String]) = ws.app.pkgMgr.minorModes(tags)

    override protected def locate (major :Boolean, mode :String) =
      ws.app.pkgMgr.mode(major, mode) match {
        case Some(mode) => mode
        case None       => throw Errors.feedback(s"Unknown mode: $mode")
      }
    override protected def configScope =
      Config.Scope(buf.state, state, ws.state, ws.app.state)
    override protected def injectInstance[T] (clazz :Class[T], args :List[Any]) =
      ws.app.svcMgr.injectInstance(clazz, args)
  }

  /** Called when this window is going away. Cleans up. */
  def dispose () {
    _msgConn.close()
    _frames foreach { _.disp.dispose() }
    _frames.clear()
  }

  private val _frames = SeqBuffer[FrameImpl]()
  private val _frame = new FrameImpl() // TEMP: for now we have only one frame
  _frames += _frame
  getChildren().add(_frame)

  // pass workspace status messages along via our status bar
  private val _msgConn = ws.statusMsg.onValue(emitStatus(_, true))

  //
  // Window interface methods

  override def geometry = Geometry(size._1, size._2, 0, 0) // TODO!
  override def frames = _frames
  override def focus = _focus.get
  override def workspace = ws
  override def mini = _mini
  override def statusMini = _statusMini

  override val onClose = Signal[Window]()
  override def close () = ws.close(this)

  override def popStatus (msg :String, subtext :String) {
    _statusPopup.showStatus(msg, subtext)
    ws.recordMessage(msg)
    if (subtext.length > 0) ws.recordMessage(subtext)
  }
  override def emitStatus (msg :String, ephemeral :Boolean) {
    _statusLine.setText(msg)
    if (!ephemeral) ws.recordMessage(msg)
  }
  override def emitError (err :Throwable) :Unit = err match {
    // if this is a wrapped reaction exception, unwrap
    case re :ReactionException => re.getSuppressed foreach(emitError)
    case _ =>
      // TODO: color the status label red or something
      popStatus(err.getMessage match {
        case null => err.toString
        case msg  => msg
      })
      if (!Errors.isFeedback(err)) {
        val trace = Errors.stackTraceToString(err)
        ws.app.debugLog(trace)
        ws.recordMessage(trace)
      }
    }

  override def clearStatus () = {
    _statusPopup.clear()
    _statusLine.setText("")
    _focus.get.view.clearEphemeralPopup()
  }

  // used internally to open files passed on the command line or via remote cmd
  def visitPath (path :Path) {
    _frame.visitFile(Store(path))
    stageToFront()
  }
  def visitScratchIfEmpty () {
    if (_frame.disp == null) _frame.setBuffer(ws.getScratch())
    stageToFront()
  }
  def stageToFront () {
    stage.show()
    stage.toFront() // move our window to front if it's not there already
    stage.requestFocus() // and request window manager focus
  }

  //
  // implementation details

  getStyleClass.add("editor")

  private val _statusPopup = new StatusPopup()
  _statusPopup.maxWidthProperty.bind(Bindings.subtract(widthProperty, 20))
  getChildren.add(_statusPopup)

  private val _statusLine = new Label(" ")
  _statusLine.setWrapText(true)
  _statusLine.getStyleClass.add("status")
  getChildren.add(_statusLine)

  private val _miniActive = Value(false)
  private def checkMiniShow () = if (_miniActive()) throw Errors.feedback(
    "Command attempted to use minibuffer while in minibuffer")

  private val _mini = new MiniOverlay(this) {
    override def willShow () = checkMiniShow()
    override def onShow () {
      _miniActive() = true
    }
    override def onClear () = {
      _miniActive() = false
      _focus.get.focus() // restore buffer focus on clear
    }
  }
  _mini.maxWidthProperty.bind(Bindings.subtract(widthProperty, 20))
  getChildren.add(_mini)

  private val _statusMini = new MiniStatus(this) {
    override def willShow () = checkMiniShow()
    override def onShow () {
      _miniActive() = true
      _statusLine.setVisible(false)
    }
    override def onClear () {
      _statusLine.setVisible(true)
      _miniActive() = false
      _focus.get.focus() // restore buffer focus on clear
    }
  }
  getChildren.add(_statusMini)

  // we manage focus specially, via this reactive value
  private val _focus = Value[FrameImpl](_frame)
  _focus onValue onFocusChange

  private def onFocusChange (frame :FrameImpl) {
    frame.focus()
  }

  // we manage layout manually for a variety of nefarious reasons
  override protected def computeMinWidth (height :Double) = _focus.get.minWidth(height)
  override protected def computeMinHeight (width :Double) = _focus.get.minHeight(width)
  override protected def computePrefWidth (height :Double) = _focus.get.prefWidth(height)
  override protected def computePrefHeight (width :Double) = _focus.get.prefHeight(width)
  override protected def computeMaxWidth (height :Double) = Double.MaxValue
  override protected def computeMaxHeight (width :Double) = Double.MaxValue

  override def layoutChildren () {
    val bounds = getLayoutBounds
    val vw = bounds.getWidth ; val vh = bounds.getHeight
    val statusHeight = _statusLine.prefHeight(vw) ; val contentHeight = vh-statusHeight
    _focus.get.resize(vw, contentHeight)
    // the status line and status minibuffer occupy the same space; only one is visible at a time
    _statusLine.resizeRelocate(0, contentHeight, vw, statusHeight)
    _statusMini.resizeRelocate(0, contentHeight, vw, statusHeight)

    // the status overlay is centered in the top 1/4th of the screen
    if (_statusPopup.isVisible) layoutInArea(
      _statusPopup, 0, 0, vw, vh/4, 0, null, false, false, HPos.CENTER, VPos.CENTER)
    // the minibuffer overlay is top-aligned at height/4 and extends downward
    if (_mini.isVisible) layoutInArea(
      _mini, 0, vh/4, vw, 3*vh/4, 0, null, false, false, HPos.CENTER, VPos.TOP)
  }
}
