/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages.inout

import scala.annotation.tailrec
import swave.core.Stage
import swave.core.impl.stages.InOutStage
import swave.core.impl.util.RingBuffer
import swave.core.impl.{Inport, Outport}
import swave.core.macros._
import swave.core.util._

// format: OFF
@StageImplementation(interceptAllRequests = true)
private[core] final class BufferStage(size: Int, requestThreshold: Int) extends InOutStage {

  requireArg(size > 0, "`size` must be > 0")

  def kind = Stage.Kind.InOut.BufferWithBackpressure(size, requestThreshold)

  private[this] val buffer = new RingBuffer[AnyRef](roundUpToPowerOf2(size))

  connectInOutAndSealWith { (in, out) ⇒
    region.impl.registerForXStart(this)
    awaitingXStart(in, out)
  }

  /**
    * @param in  the active upstream
    * @param out the active downstream
    */
  def awaitingXStart(in: Inport, out: Outport) = state(
    xStart = () => {
      in.request(size.toLong)
      running(in, out, size.toLong, 0)
    })

  /**
    * Upstream and downstream active.
    *
    * @param in        the active upstream
    * @param out       the active downstream
    * @param pending   number of elements already requested from upstream but not yet received, >= 0
    * @param remaining number of elements already requested by downstream but not yet delivered, >= 0
    */
  def running(in: Inport, out: Outport, pending: Long, remaining: Long): State = {

    @tailrec def handleDemand(pend: Long, rem: Long): State =
      if (rem > 0 && buffer.nonEmpty) {
        out.onNext(buffer.unsafeRead())
        handleDemand(pend, rem - 1)
      } else {
        val alreadyRequested = pend ⊹ buffer.count
        val target = rem ⊹ size
        val delta = target - alreadyRequested
        val newPending =
          if (delta > requestThreshold) {
            in.request(delta)
            pend + delta
          } else pend
        running(in, out, newPending, rem)
      }

    state(
      request = (n, _) ⇒ handleDemand(pending, remaining ⊹ n),
      cancel = stopCancelF(in),

      onNext = (elem, _) ⇒ {
        requireState(buffer.canWrite)
        buffer.unsafeWrite(elem)
        handleDemand(pending - 1, remaining)
      },

      onComplete = _ ⇒ {
        if (remaining > 0) {
          requireState(buffer.isEmpty)
          stopComplete(out)
        } else {
          if (buffer.isEmpty) stopComplete(out) else draining(out)
        }
      },

      onError = stopErrorF(out))
  }

  /**
    * Upstream completed, downstream active and buffer non-empty.
    *
    * @param out the active downstream
    */
  def draining(out: Outport) = state(
    request = (n, _) ⇒ {
      @tailrec def rec(n: Int): State =
        if (buffer.nonEmpty) {
          if (n > 0) {
            out.onNext(buffer.unsafeRead())
            rec(n - 1)
          } else stay()
        } else stopComplete(out)
      rec(n)
    },

    cancel = stopF)
}
