/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package swave.core

import scala.concurrent.Future
import scala.concurrent.duration._
import swave.testkit.TestError

class SpoutSpec extends SwaveSpec {

  implicit val env = StreamEnv()

  "Spouts should work as expected" - {

    "Spout.unfold" - {

      "EmitFinal" in {
        Spout.unfold(0 → 1) {
          case (a, b) if b > 20 ⇒ Spout.Unfolding.EmitFinal(a)
          case (a, b)           ⇒ Spout.Unfolding.Emit(a, b → (a + b))
        } should produce(0, 1, 1, 2, 3, 5, 8, 13)
      }

      "Complete" in {
        Spout.unfold(0 → 1) {
          case (a, b) if b > 20 ⇒ Spout.Unfolding.Complete
          case (a, b)           ⇒ Spout.Unfolding.Emit(a, b → (a + b))
        } should produce(0, 1, 1, 2, 3, 5, 8)
      }

      "Error" in {
        Spout.unfold(0 → 1) {
          case (a, b) if b > 20 ⇒ throw TestError
          case (a, b)           ⇒ Spout.Unfolding.Emit(a, b → (a + b))
        } should produceError(TestError)
      }
    }

    "Spout.unfoldAsync" - {
      implicit val timeout = Timeout(1.second)

      "EmitFinal" in {
        Spout.unfoldAsync(0 → 1) {
          case (a, b) if b > 20 ⇒ Future.successful(Spout.Unfolding.EmitFinal(a))
          case (a, b)           ⇒ Future.successful(Spout.Unfolding.Emit(a, b → (a + b)))
        } should produce(0, 1, 1, 2, 3, 5, 8, 13)
      }

      "Complete" in {
        Spout.unfoldAsync(0 → 1) {
          case (a, b) if b > 20 ⇒ Future.successful(Spout.Unfolding.Complete)
          case (a, b)           ⇒ Future.successful(Spout.Unfolding.Emit(a, b → (a + b)))
        } should produce(0, 1, 1, 2, 3, 5, 8)
      }

      "Error in fun" in {
        Spout.unfoldAsync(0 → 1) {
          case (a, b) if b > 20 ⇒ throw TestError
          case (a, b)           ⇒ Future.successful(Spout.Unfolding.Emit(a, b → (a + b)))
        } should produceError(TestError)
      }

      "Error in future" in {
        Spout.unfoldAsync(0 → 1) {
          case (a, b) if b > 20 ⇒ Future.failed(TestError)
          case (a, b)           ⇒ Future.successful(Spout.Unfolding.Emit(a, b → (a + b)))
        } should produceError(TestError)
      }
    }

    "Spout.tick" in {
      timed {
        implicit val timeout = Timeout(1.second)
        Spout.tick('a, elements = 4, per = 1.second).take(3) should produce('a, 'a, 'a)
      } should be > 500.millis
    }
  }
}
