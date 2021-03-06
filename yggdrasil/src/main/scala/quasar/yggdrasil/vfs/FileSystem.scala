/*
 * Copyright 2014–2017 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.yggdrasil.vfs

import quasar.blueeyes._
import quasar.blueeyes.json._
import quasar.blueeyes.json.serialization._
import DefaultSerialization._
import quasar.niflheim._

import quasar.precog.common.Path
import quasar.precog.common.ingest.FileContent

import scalaz.Validation._
import scalaz.syntax.std.option._
import scalaz.syntax.apply._

sealed class PathData(val typeName: PathData.DataType)
object PathData {
  sealed abstract class DataType(val name: String) {
    def contentType: MimeType
  }

  object DataType {
    implicit val decomposer: Decomposer[DataType] with Extractor[DataType] = new Decomposer[DataType] with Extractor[DataType] {
      def decompose(t: DataType) = t match {
        case BLOB(contentType) => JObject("type" -> JString("blob"), "mimeType" -> JString(contentType.value))
        case NIHDB => JObject("type" -> JString("nihdb"), "mimeType" -> JString(FileContent.XQuirrelData.value))
      }

      def validated(v: JValue) = {
        val mimeTypeV = v.validated[String]("mimeType").flatMap { mimeString =>
          MimeTypes.parseMimeTypes(mimeString).headOption.toSuccess(Extractor.Error.invalid("No recognized mimeType values foundin %s".format(v.renderCompact)))
        }

        (v.validated[String]("type") tuple mimeTypeV) flatMap {
          case ("blob", mimeType) => success(BLOB(mimeType))
          case ("nihdb", FileContent.XQuirrelData) => success(NIHDB)
          case (unknownType, mimeType) => failure(Extractor.Error.invalid("Data type %s (mimetype %s) is not a recognized PathData datatype".format(unknownType, mimeType.toString)))
        }
      }
    }
  }

  case class BLOB(contentType: MimeType) extends DataType("blob")
  case object NIHDB extends DataType("nihdb") { val contentType = FileContent.XQuirrelData }
}

case class BlobData(data: Array[Byte], mimeType: MimeType) extends PathData(PathData.BLOB(mimeType))
case class NIHDBData(data: Seq[NIHDB.Batch]) extends PathData(PathData.NIHDB)

object NIHDBData {
  val Empty = NIHDBData(Seq.empty)
}

sealed trait PathOp {
  def path: Path
}

case class Read(path: Path, version: Version) extends PathOp
case class FindChildren(path: Path) extends PathOp
case class FindPathMetadata(path: Path) extends PathOp
case class CurrentVersion(path: Path) extends PathOp
