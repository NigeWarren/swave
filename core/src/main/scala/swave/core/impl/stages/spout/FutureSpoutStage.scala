/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.impl.stages.spout

import scala.concurrent.Future
import scala.util.{Failure, Success}
import swave.core.Stage
import swave.core.impl.stages.SpoutStage
import swave.core.impl.{CallingThreadExecutionContext, Outport}
import swave.core.macros.StageImplementation

// format: OFF
@StageImplementation
private[core] final class FutureSpoutStage(future: Future[AnyRef]) extends SpoutStage {

  def kind = Stage.Kind.Spout.FromFuture(future)

  connectOutAndSealWith { out ⇒
    region.impl.requestDispatcherAssignment()
    region.impl.registerForXStart(this)
    running(out)
  }

  def running(out: Outport): State = {

    def awaitingXStart() = state(
      xStart = () => {
        future.onComplete(region.enqueueXEvent(this, _))(CallingThreadExecutionContext)
        awaitingValueOrRequest()
      })

    def awaitingValueOrRequest() = state(
      request = (_, _) ⇒ awaitingValue(),
      cancel = stopF,

      xEvent = {
        case Success(x :AnyRef) => awaitingRequest(x)
        case Failure(e) => stopError(e, out)
      })

    def awaitingValue() = state(
      request = (_, _) ⇒ stay(),
      cancel = stopF,

      xEvent = {
        case Success(x :AnyRef) =>
          out.onNext(x)
          stopComplete(out)
        case Failure(e) => stopError(e, out)
      })

    def awaitingRequest(value: AnyRef) = state(
      request = (_, _) ⇒ {
        out.onNext(value)
        stopComplete(out)
      },

      cancel = stopF)

    awaitingXStart()
  }
}
