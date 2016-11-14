/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.macros

private[macros] trait ConnectInOutAndSealWith { this: Util =>
  val c: scala.reflect.macros.whitebox.Context
  import c.universe._

  def connectInOutAndSealWith(f: Tree): List[Tree] = unblock {
    val q"($ctx0: $_, $in0: $_, $out0: $_) => $block0" = f
    val ctx                                            = freshName("ctx")
    val in                                             = freshName("in")
    val out                                            = freshName("out")
    val block                                          = replaceIdents(block0, ctx0 -> ctx, in0 -> in, out0 -> out)

    q"""
      initialState(awaitingSubscribeOrOnSubscribe())

      def awaitingSubscribeOrOnSubscribe() = state(
        intercept = false,

        onSubscribe = from ⇒ {
          _inputStages = from.stage :: Nil
          awaitingSubscribe(from)
        },

        subscribe = from ⇒ {
          _outputStages = from.stage :: Nil
          from.onSubscribe()
          awaitingOnSubscribe(from)
        })

      def awaitingSubscribe(in: Inport) = state(
        intercept = false,

        subscribe = from ⇒ {
          _outputStages = from.stage :: Nil
          from.onSubscribe()
          ready(in, from)
        })

      def awaitingOnSubscribe(out: Outport) = state(
        intercept = false,

        onSubscribe = from ⇒ {
          _inputStages = from.stage :: Nil
          ready(from, out)
        })

      def ready(in: Inport, out: Outport) = state(
        intercept = false,

        xSeal = c ⇒ {
          configureFrom(c)
          in.xSeal(c)
          out.xSeal(c)
          val $ctx = c
          val $in = in
          val $out = out
          $block
        })
     """
  }
}
