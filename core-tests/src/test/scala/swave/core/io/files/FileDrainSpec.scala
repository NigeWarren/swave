/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package swave.core.io.files

import java.nio.file.{Files, Path}
import scala.concurrent.duration._
import scodec.bits.ByteVector
import swave.compat.scodec._
import swave.core.util._
import swave.core._

class FileDrainSpec extends SwaveSpec {
  import swave.core.io.files._

  implicit val env = StreamEnv()

  val TestLines = List[String](
    "a" * 1000 + "\n",
    "b" * 1000 + "\n",
    "c" * 1000 + "\n",
    "d" * 1000 + "\n",
    "e" * 1000 + "\n",
    "f" * 1000 + "\n")

  val TestBytes = TestLines.map(ByteVector.encodeAscii(_).right.get)

  "Drain.toPath must" - {

    "write lines to a short file" in withTempPath(create = true) { path ⇒
      val result = Spout.one(ByteVector("abc" getBytes UTF8)).drainTo(Drain.toPath(path, chunkSize = 512))
      result.await(5.seconds) shouldEqual 3
      verifyFileContents(path, "abc")
    }

    "write lines to a long file" in withTempPath(create = true) { path ⇒
      val result = Spout(TestBytes).drainTo(Drain.toPath(path, chunkSize = 512))
      result.await(5.seconds) shouldEqual 6006
      verifyFileContents(path, TestLines mkString "")
    }

    "create new file if required" in withTempPath(create = false) { path ⇒
      val result = Spout(TestBytes).drainTo(Drain.toPath(path, chunkSize = 512))
      result.await(5.seconds) shouldEqual 6006
      verifyFileContents(path, TestLines mkString "")
    }
  }

  private def withTempPath(create: Boolean)(block: Path ⇒ Unit): Unit = {
    val targetFile = Files.createTempFile("file-sink", ".tmp")
    if (!create) Files.delete(targetFile)
    try block(targetFile)
    finally Files.delete(targetFile)
  }

  private def verifyFileContents(path: Path, contents: String): Unit = {
    val out = Files.readAllBytes(path)
    new String(out) shouldEqual contents
  }
}
