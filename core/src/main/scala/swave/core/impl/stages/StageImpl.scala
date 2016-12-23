/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages

import scala.annotation.{compileTimeOnly, tailrec}
import swave.core.impl.util.{AbstractInportList, ResizableRingBuffer}
import swave.core.util._
import swave.core.impl._
import swave.core._

/**
  * Signals:
  * - subscribe
  * - request
  * - cancel
  * - onSubscribe
  * - onNext
  * - onComplete
  * - onError
  * and
  * - xSeal
  * - xStart
  * - xEvent
  *
  * Phases (disjunct sets of states):
  *
  * CONNECTING (potentially several states)
  * - must throw exception upon reception of signal other than `subscribe` and `onSubscribe`
  * - upon signal reception:
  *   - must not allocate resources which require explicit releasing
  *   - must transition to AWAITING-XSEAL when all expected peers are wired
  *
  * AWAITING-XSEAL (single state)
  * - must throw exception upon reception of signal other than `xSeal`
  * - upon reception of `xSeal`:
  *   - must propagate `xSeal` signal to all connected peer stages
  *   - must not send non-`xSeal` signals
  *   - may register for reception of `xStart`, `xRun` and/or `xCleanUp`
  *   - when registered for reception of `xStart`:
  *     - must queue (intercept) all other signals until reception of `xStart`
  *     - must transition to AWAITING-XSTART
  *   - otherwise: must transition to RUNNING
  *
  * AWAITING-XSTART (single state)
  * - only for stages which have previously registered interest in `xStart`
  * - must throw exception upon reception of any non `x...` signal
  * - must ignore `xSeal`
  * - upon reception of `xStart`:
  *   - may send one or more non-x signals
  *   - must transition to RUNNING
  *   - must dequeue all intercepted signals
  *
  * RUNNING (potentially several states)
  * - must throw exception upon reception of `xStart`
  * - must ignore `xSeal`
  * - upon signal reception:
  *   - may send one or more signals
  *   - must queue (intercept) all signals while handling one signal instance
  *   - must dequeue all intercepted signals after having finished handling one signal instance
  *   - may transition to STOPPED
  *
  * STOPPED (single state)
  * - ignore all signals
  */
private[swave] abstract class StageImpl extends PortImpl {
  import StageImpl.RequestBacklogList

  type State = Int // semantic alias

  // TODO: evaluate moving the boolean into the (upper) _state integer bits
  private[this] var _intercepting                        = false
  private[this] var _state: State                        = _ // current state; the STOPPED state is always encoded as zero
  private[this] var _requestBacklog: RequestBacklogList  = _
  private[this] var _buffer: ResizableRingBuffer[AnyRef] = _
  private[this] var _region: Region                      = _
  protected final var interceptingStates: Int            = _ // bit mask holding a 1 bit for every state which requires interception support

  protected final implicit def self: this.type             = this
  protected final def initialState(s: State): Unit         = _state = s
  protected final def stay(): State                        = _state
  protected final def illegalState(msg: String)            = new IllegalStateException(msg + " in " + this)
  protected final def setIntercepting(flag: Boolean): Unit = _intercepting = flag

  final def region: Region       = _region
  final def assignTempRegion(r: Region): Unit  = _region = r
  final def resetRegion(): Unit  = _region = null
  final def stageImpl: StageImpl = this
  final def isSealed: Boolean    = region ne null
  final def isStopped: Boolean   = _state == 0

  def stateName: String = s"<unknown id ${_state}>"
  override def toString = s"${getClass.getSimpleName}@${identityHash(this)}/$stateName"

  /////////////////////////////////////// SUBSCRIBE ///////////////////////////////////////

  final def subscribe()(implicit from: Outport): Unit =
    if (!_intercepting) {
      _intercepting = interceptionNeeded
      _subscribe(from)
      handleInterceptions()
    } else storeInterception(Statics._0L, from)

  private def _subscribe(from: Outport): Unit = _state = _subscribe0(from)

  protected def _subscribe0(from: Outport): State =
    _state match {
      case 0 ⇒ stay()
      case _ ⇒
        throw new IllegalReuseException(
          s"Port already connected in $this. Are you trying to reuse a stage instance?",
          illegalState(s"Unexpected subscribe() from out '$from'"))
    }

  /////////////////////////////////////// REQUEST ///////////////////////////////////////

  final def request(n: Long)(implicit from: Outport): Unit = {
    val mbs = region.mbs
    val count =
      if (n > mbs) {
        from.stageImpl.updateRequestBacklog(this, n)
        mbs
      } else n.toInt

    if (!_intercepting) {
      _intercepting = interceptionNeeded
      _request(count, from)
      if (_intercepting) handleInterceptions()
    } else interceptRequest(count, from)
  }

  private def _request(n: Int, from: Outport): Unit = _state = _request0(n, from)

  protected def _request0(n: Int, from: Outport): State = stay()

  protected final def requestF(in: Inport)(n: Int, from: Outport): State = {
    in.request(n.toLong)
    stay()
  }

  protected final def interceptRequest(n: Int, from: Outport): Unit =
    storeInterception(Statics._1L, if (n <= 16) Statics.INTS(n - 1) else new java.lang.Integer(n), from)

  /////////////////////////////////////// CANCEL ///////////////////////////////////////

  final def cancel()(implicit from: Outport): Unit = {
    from.stageImpl.clearRequestBacklog(this)
    if (!_intercepting) {
      _intercepting = interceptionNeeded
      _cancel(from)
      if (_intercepting) handleInterceptions()
    } else interceptCancel(from)
  }

  private def _cancel(from: Outport): Unit = _state = _cancel0(from)

  protected def _cancel0(from: Outport): State =
    _state match {
      case 0 ⇒ stay()
      case _ ⇒ throw illegalState(s"Unexpected cancel() from out '$from'")
    }

  protected final def interceptCancel(from: Outport): Unit =
    storeInterception(Statics._2L, from)

  /////////////////////////////////////// ONSUBSCRIBE ///////////////////////////////////////

  final def onSubscribe()(implicit from: Inport): Unit =
    if (!_intercepting) {
      _intercepting = interceptionNeeded
      _onSubscribe(from)
      if (_intercepting) handleInterceptions()
    } else storeInterception(Statics._3L, from)

  private def _onSubscribe(from: Inport): Unit = _state = _onSubscribe0(from)

  protected def _onSubscribe0(from: Inport): State =
    _state match {
      case 0 ⇒ stay()
      case _ ⇒
        throw new IllegalReuseException(
          s"Port already connected in $this. Are you trying to reuse a stage instance?",
          illegalState(s"Unexpected onSubscribe() from out '$from'"))
    }

  /////////////////////////////////////// ONNEXT ///////////////////////////////////////

  final def onNext(elem: AnyRef)(implicit from: Inport): Unit =
    if (!_intercepting) {
      _intercepting = interceptionNeeded
      _onNext(elem, from)
      if (_intercepting) handleInterceptions()
    } else interceptOnNext(elem, from)

  @tailrec
  private def _onNext(elem: AnyRef,
                      from: Inport,
                      last: RequestBacklogList = null,
                      current: RequestBacklogList = _requestBacklog): Unit =
    if (current.nonEmpty) {
      val currentIn = current.in
      if ((from eq null) || (currentIn eq from)) {
        _state = _onNext0(elem, from)
        if (current.tail ne current) { // is `current` still valid, i.e. not cancelled? (tail eq self is special condition)
          val newPending = current.pending - 1
          if (newPending == 0) {
            val mbs = region.mbs
            val newRemaining = current.remaining - mbs
            val n =
              if (newRemaining > 0) {
                current.pending = mbs
                current.remaining = newRemaining
                mbs.toLong
              } else {
                if (last ne null) last.tail = current.tail else _requestBacklog = current.tail
                current.remaining
              }
            region.impl.enqueueRequest(currentIn.stageImpl, n)
          } else current.pending = newPending
        }
      } else _onNext(elem, from, current, current.tail)
    } else _state = _onNext0(elem, from)

  protected def _onNext0(elem: AnyRef, from: Inport): State =
    _state match {
      case 0 ⇒ stay()
      case _ ⇒ throw illegalState(s"Unexpected onNext($elem) from out '$from'")
    }

  protected final def onNextF(out: Outport)(elem: AnyRef, from: Inport): State = {
    out.onNext(elem)
    stay()
  }

  protected final def interceptOnNext(elem: AnyRef, from: Inport): Unit =
    storeInterception(Statics._4L, elem, from)

  /////////////////////////////////////// ONCOMPLETE ///////////////////////////////////////

  final def onComplete()(implicit from: Inport): Unit =
    if (!_intercepting) {
      _intercepting = interceptionNeeded
      _onComplete(from)
      if (_intercepting) handleInterceptions()
    } else interceptOnComplete(from)

  private def _onComplete(from: Inport): Unit = {
    clearRequestBacklog(from)
    _state = _onComplete0(from)
  }

  protected def _onComplete0(from: Inport): State =
    _state match {
      case 0 ⇒ stay()
      case _ ⇒ throw illegalState(s"Unexpected onComplete() from out '$from'")
    }

  protected final def interceptOnComplete(from: Inport): Unit =
    storeInterception(Statics._5L, from)

  /////////////////////////////////////// ONERROR ///////////////////////////////////////

  final def onError(error: Throwable)(implicit from: Inport): Unit =
    if (!_intercepting) {
      _intercepting = interceptionNeeded
      _onError(error, from)
      if (_intercepting) handleInterceptions()
    } else interceptOnError(error, from)

  private def _onError(error: Throwable, from: Inport): Unit = {
    clearRequestBacklog(from)
    _state = _onError0(error, from)
  }

  protected def _onError0(error: Throwable, from: Inport): State =
    _state match {
      case 0 ⇒ stay()
      case _ ⇒ throw illegalState(s"Unexpected onError($error) from out '$from'")
    }

  protected final def interceptOnError(error: Throwable, from: Inport): Unit =
    storeInterception(Statics._6L, error, from)

  /////////////////////////////////////// XSEAL ///////////////////////////////////////

  final def xSeal(region: Region): Unit =
    if (!isSealed) {
      _region = region
      _state = _xSeal()
      if (_region ne null)
        _region.impl.registerStageSealed() // don't call `region.impl...` here as `_region` might now be different!

      @tailrec def sealBoundaries(remaining: List[Module.Boundary]): Unit =
        if (remaining.nonEmpty) {
          val s = remaining.head.stage.stageImpl
          s.xSeal(region)
          sealBoundaries(remaining.tail)
        }
      @tailrec def sealModules(remaining: List[Module.ID]): Unit =
        if (remaining.nonEmpty) {
          if (remaining.head.markSealed()) sealBoundaries(remaining.head.boundaries)
          sealModules(remaining.tail)
        }
      sealModules(boundaryOf)
    }

  protected def _xSeal(): State =
    _state match {
      case 0 ⇒ stay()
      case _ ⇒
        throw new UnclosedStreamGraphException(s"Unconnected Port in $this", illegalState("Unexpected xSeal()"))
    }

  /////////////////////////////////////// XSTART ///////////////////////////////////////

  final def xStart(): Unit =
    if (!isStopped) {
      _state = _xStart()
      handleInterceptions()
    }

  protected def _xStart(): State = throw illegalState(s"Unexpected xStart()")

  /////////////////////////////////////// XEVENT ///////////////////////////////////////

  final def xEvent(ev: AnyRef): Unit =
    if (!_intercepting) {
      _intercepting = interceptionNeeded
      _xEvent(ev)
      if (_intercepting) handleInterceptions()
    } else storeInterception(Statics._7L, ev)

  private def _xEvent(ev: AnyRef): Unit = _state = _xEvent0(ev)

  protected def _xEvent0(ev: AnyRef): State =
    if (isStopped) stay() else throw illegalState(s"Unexpected xEvent($ev)")

  final def enqueueXEvent(ev: AnyRef): Unit =
    if (isSealed) region.impl.enqueueXEvent(this, ev)
    else xEvent(ev)

  /////////////////////////////////////// STATE DESIGNATOR ///////////////////////////////////////

  @compileTimeOnly("`state(...) can only be used as the single implementation expression of a 'State Def'!")
  protected final def state(intercept: Boolean = true,
                            subscribe: Outport ⇒ State = null,
                            request: (Int, Outport) ⇒ State = null,
                            cancel: Outport ⇒ State = null,
                            onSubscribe: Inport ⇒ State = null,
                            onNext: (AnyRef, Inport) ⇒ State = null,
                            onComplete: Inport ⇒ State = null,
                            onError: (Throwable, Inport) ⇒ State = null,
                            xSeal: () ⇒ State = null,
                            xStart: () ⇒ State = null,
                            xEvent: AnyRef ⇒ State = null): State = 0

  /////////////////////////////////////// STOPPERS ///////////////////////////////////////

  protected final def stop(e: Throwable = null): State = {
    if (isSealed) region.impl.registerStageStopped()
    _buffer = null // don't hang on to elements
    0 // STOPPED state encoding
  }

  protected final def stopF(out: Outport): State = stop()

  protected final def stopCancel(in: Inport): State = {
    in.cancel()
    stop()
  }

  protected final def stopCancelF(in: Inport)(out: Outport): State = stopCancel(in)

  protected final def stopCancel[L >: Null <: AbstractInportList[L]](ins: L, except: Inport = null): State = {
    cancelAll(ins, except)
    stop()
  }

  protected final def stopCancelF[L >: Null <: AbstractInportList[L]](ins: L)(out: Outport): State = stopCancel(ins)

  protected final def stopComplete(out: Outport): State = {
    out.onComplete()
    stop()
  }

  protected final def stopCompleteF(out: Outport)(in: Inport): State = stopComplete(out)

  protected final def stopError(e: Throwable, out: Outport): State = {
    out.onError(e)
    stop(e)
  }

  protected final def stopErrorF(out: Outport)(e: Throwable, in: Inport): State =
    stopError(e, out)

  /////////////////////////////////////// CANCEL HELPERS ///////////////////////////////////////

  @tailrec protected final def cancelAll[L >: Null <: AbstractInportList[L]](ins: L, except: Inport = null): Unit =
    if (ins ne null) {
      if (ins.in ne except) ins.in.cancel()
      cancelAll(ins.tail)
    }

  protected final def cancelAllAndStopComplete[L >: Null <: AbstractInportList[L]](ins: L,
                                                                                   except: Inport,
                                                                                   out: Outport): State = {
    cancelAll(ins, except)
    stopComplete(out)
  }

  protected final def cancelAllAndStopCompleteF[L >: Null <: AbstractInportList[L]](ins: L, out: Outport)(
      in: Inport): State = {
    cancelAll(ins, except = in)
    stopComplete(out)
  }

  protected final def cancelAllAndStopErrorF[L >: Null <: AbstractInportList[L]](ins: L, out: Outport)(
      e: Throwable,
      in: Inport): State = {
    cancelAll(ins, except = in)
    stopError(e, out)
  }

  /////////////////////////////////////// PRIVATE ///////////////////////////////////////

  private def interceptionNeeded: Boolean = ((interceptingStates >> _state) & 1) != 0
  private def fullInterceptions: Boolean  = interceptingStates < 0

  private def storeInterception(signal: java.lang.Integer, from: Port): Unit =
    if (fullInterceptions) {
      if (!buffer.write(signal, from))
        throw illegalState(s"Interception buffer overflow on signal $signal($from)'")
    } else {
      if (!buffer.write(signal))
        throw illegalState(s"Interception buffer overflow on signal $signal()'")
    }

  private def storeInterception(signal: java.lang.Integer, arg: AnyRef): Unit =
    if (!buffer.write(signal, arg))
      throw illegalState(s"Interception buffer overflow on signal $signal($arg)'")

  private def storeInterception(signal: java.lang.Integer, arg0: AnyRef, from: Port): Unit =
    if (fullInterceptions) {
      if (!buffer.write(signal, arg0, from))
        throw illegalState(s"Interception buffer overflow on signal $signal($arg0, $from)")
    } else {
      if (!buffer.write(signal, arg0))
        throw illegalState(s"Interception buffer overflow on signal $signal($arg0)")
    }

  private def buffer = {
    if (_buffer eq null) {
      val initialSize = roundUpToPowerOf2(region.mbs)
      _buffer = new ResizableRingBuffer(initialSize, initialSize << 4)
    }
    _buffer
  }

  @tailrec private def handleInterceptions(): Unit =
    if ((_buffer ne null) && _buffer.nonEmpty) {
      def read() = _buffer.unsafeRead()
      val signal = read().asInstanceOf[java.lang.Integer].intValue()
      def from() = if (fullInterceptions) read().asInstanceOf[StageImpl] else null
      signal match {
        case 0 ⇒ _subscribe(from())
        case 1 ⇒ _request(read().asInstanceOf[java.lang.Integer].intValue(), from())
        case 2 ⇒ _cancel(from())
        case 3 ⇒ _onSubscribe(from())
        case 4 ⇒ _onNext(read(), from())
        case 5 ⇒ _onComplete(from())
        case 6 ⇒ _onError(read().asInstanceOf[Throwable], from())
        case 7 ⇒ _xEvent(read())
      }
      handleInterceptions()
    } else _intercepting = false

  private def updateRequestBacklog(in: Inport, requested: Long): Unit = {
    @tailrec def rec(current: RequestBacklogList): Unit =
      if (current ne null) {
        if (current.in eq in) current.remaining = current.remaining ⊹ requested
        else rec(current.tail)
      } else {
        val mbs = region.mbs
        _requestBacklog = new RequestBacklogList(in, _requestBacklog, mbs, requested - mbs)
      }
    rec(_requestBacklog)
  }

  private def clearRequestBacklog(in: Inport): Unit =
    _requestBacklog = _requestBacklog.remove(in)
}

private[swave] object StageImpl {

  private final class RequestBacklogList(in: Inport,
                                         tail: RequestBacklogList,
                                         var pending: Int, // already requested from `in` but not yet received
                                         var remaining: Long) // already requested by us but not yet forwarded to `in`
      extends AbstractInportList[RequestBacklogList](in, tail)
}
