/*
 * Copyright 2014 - 2015 SlamData Inc.
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

package quasar.config

import com.mongodb.ConnectionString
import quasar.Predef._
import quasar.config.FsPath.NonexistentFileError
import quasar.fp._
import quasar._, Evaluator._, Errors._
import quasar.Evaluator.EnvironmentError.EnvFsPathError
import quasar.fs.{Path => EnginePath}

import java.io.{File => JFile}
import scala.util.Properties._
import argonaut._, Argonaut._
import monocle._
import scalaz.{Lens => _, _}, Scalaz._
import scalaz.concurrent.Task
import simulacrum.typeclass
import pathy._, Path._

sealed trait MountConfig {
  def validate(path: EnginePath): EnvironmentError \/ Unit
}
final case class MongoDbConfig(uri: ConnectionString) extends MountConfig {
  def validate(path: EnginePath) =
    if (path.relative) -\/(InvalidConfig("Not an absolute path: " + path))
    else if (!path.pureDir) -\/(InvalidConfig("Not a directory path: " + path))
    else \/-(())
}
object MongoConnectionString {
  def parse(uri: String): String \/ ConnectionString =
    \/.fromTryCatchNonFatal(new ConnectionString(uri)).leftMap(_.toString)

  def decode(uri: String): DecodeResult[ConnectionString] = {
    DecodeResult(parse(uri).leftMap(κ((s"invalid connection URI: $uri", CursorHistory(Nil)))))
  }
  implicit val codec: CodecJson[ConnectionString] =
    CodecJson[ConnectionString](
      c => jString(c.getURI),
      _.as[String].flatMap(decode))
}
object MongoDbConfig {
  import MongoConnectionString.codec
  implicit def Codec: CodecJson[MongoDbConfig] =
    casecodec1(MongoDbConfig.apply, MongoDbConfig.unapply)("connectionUri")
}

final case class ViewConfig(query: sql.Expr) extends MountConfig {
  def validate(path: EnginePath) =
    if (path.relative) -\/(InvalidConfig("Not an absolute path: " + path))
    else if (path.pureDir) -\/(InvalidConfig("Not a file path: " + path))
    else \/-(())
}
object ViewConfig {
  private val UriPattern = "([a-z][a-z0-9+-.]*):///\\?q=(.+)".r

  private def fromUri(uri: String): String \/ ViewConfig = for {
    gs       <- UriPattern.unapplySeq(uri) \/> ("could not parse URI: " + uri)
    scheme   <- nonEmpty(gs(0))            \/> ("missing URI scheme: " + uri)
    _        <- if (scheme == "sql2") \/-(()) else -\/("unrecognized scheme: " + scheme)
    queryEnc <- nonEmpty(gs(1))            \/> ("missing query: " + uri)
    queryStr <- \/.fromTryCatchNonFatal(java.net.URLDecoder.decode(queryEnc, "UTF-8")).leftMap(_.getMessage + "; " + queryEnc)
    query <- new sql.SQLParser().parse(sql.Query(queryStr)).leftMap(_.message)
  } yield ViewConfig(query)

  private def toUri(cfg: ViewConfig) =
    "sql2:///?q=" + java.net.URLEncoder.encode(sql.pprint(cfg.query), "UTF-8")

  implicit def Codec = CodecJson[ViewConfig](
    cfg => Json("connectionUri" := toUri(cfg)),
    c => {
      val uriC = (c --\ "connectionUri")
      for {
        uri <- uriC.as[String]
        cfg <- DecodeResult(fromUri(uri).leftMap(e => (e.toString, uriC.history)))
      } yield cfg
    })

  private def nonEmpty(strOrNull: String) =
    if (strOrNull == "") None else Option(strOrNull)
}

object MountConfig {
  implicit val Codec = CodecJson[MountConfig](
    encoder = _ match {
      case x @ MongoDbConfig(_) => ("mongodb", MongoDbConfig.Codec.encode(x)) ->: jEmptyObject
      case x @ ViewConfig(_)    => ("view", ViewConfig.Codec.encode(x)) ->: jEmptyObject
    },
    decoder = c => (c.fields match {
      case Some("mongodb" :: Nil) => c.get[MongoDbConfig]("mongodb")
      case Some("view" :: Nil)    => c.get[ViewConfig]("view")
      case Some(t :: Nil)         => DecodeResult.fail("unrecognized mount type: " + t, c.history)
      case _                      => DecodeResult.fail("invalid mount: " + c.focus, c.history)
    }).map(v => v: MountConfig))
}

trait ConfigOps[C] {
  import FsPath._

  def mountingsLens: Lens[C, MountingsConfig]

  def defaultPathForOS(file: RelFile[Sandboxed])(os: OS): Task[FsPath[File, Sandboxed]] = {
    def localAppData: OptionT[Task, FsPath.Aux[Abs, Dir, Sandboxed]] =
      OptionT(Task.delay(envOrNone("LOCALAPPDATA")))
        .flatMap(s => OptionT(parseWinAbsAsDir(s).point[Task]))

    def homeDir: OptionT[Task, FsPath.Aux[Abs, Dir, Sandboxed]] =
      OptionT(Task.delay(propOrNone("user.home")))
        .flatMap(s => OptionT(parseAbsAsDir(os, s).point[Task]))

    val dirPath: RelDir[Sandboxed] = os.fold(
      currentDir,
      dir("Library") </> dir("Application Support"),
      dir(".config"))

    val baseDir = OptionT.some[Task, Boolean](os.isWin)
      .ifM(localAppData, OptionT.none)
      .orElse(homeDir)
      .map(_.forgetBase)
      .getOrElse(Uniform(currentDir))

    baseDir map (_ </> dirPath </> file)
  }

  /**
   * The default path to the configuration file for the current operating system.
   *
   * NB: Paths read from environment/props are assumed to be absolute.
   */
  private def defaultPath: Task[FsPath[File, Sandboxed]] =
    OS.currentOS >>= defaultPathForOS(dir("quasar") </> file("quasar-config.json"))

  private def alternatePath: Task[FsPath[File, Sandboxed]] =
    OS.currentOS >>= defaultPathForOS(dir("SlamData") </> file("slamengine-config.json"))

  def fromFile(path: FsPath[File, Sandboxed])(implicit D: DecodeJson[C]): EnvTask[C] = {
    import java.nio.file._
    import java.nio.charset._

    for {
      codec  <- liftE[EnvironmentError](systemCodec)
      strPath = printFsPath(codec, path)
      text   <- liftE[EnvironmentError](Task.delay(
                  new String(Files.readAllBytes(Paths.get(strPath)), StandardCharsets.UTF_8)))
      config <- EitherT(Task.now(fromString(text).leftMap {
                  case InvalidConfig(message) => InvalidConfig("Failed to parse " + path + ": " + message)
                  case e => e
                }))
      _      <- liftE[EnvironmentError](Task.delay { println("Read config from path: " + strPath) })
    } yield config

  }

  def fromFileOrDefaultPaths(path: Option[FsPath[File, Sandboxed]])(implicit D: DecodeJson[C]): EnvTask[C] = {
    def load(path: Task[FsPath[File, Sandboxed]]): EnvTask[C] =
      EitherT.right(path).flatMap { p =>
        handleWith(fromFile(p)) {
          case ex: java.nio.file.NoSuchFileException =>
            EitherT.left(Task.now(EnvFsPathError(NonexistentFileError(p))))
        }
      }

    path.cata(p => load(Task.now(p)), load(defaultPath).orElse(load(alternatePath)))
  }

  def loadAndTest(path: FsPath[File, Sandboxed])(implicit D: DecodeJson[C]): EnvTask[C] =
    for {
      config <- fromFile(path)
      _      <- mountingsLens.get(config).values.toList.map(Backend.test).sequenceU
    } yield config


  def toFile(config: C, path: Option[FsPath[File, Sandboxed]])(implicit E: EncodeJson[C]): Task[Unit] = {
    import java.nio.file._
    import java.nio.charset._

    for {
      codec <- systemCodec
      p1    <- path.fold(defaultPath)(Task.now)
      cfg   <- Task.delay {
        val text = config.shows
        val p = Paths.get(printFsPath(codec, p1))
        ignore(Option(p.getParent).map(Files.createDirectories(_)))
        ignore(Files.write(p, text.getBytes(StandardCharsets.UTF_8)))
        ()
      }
    } yield cfg
  }

  def fromString(value: String)(implicit D: DecodeJson[C]): EnvironmentError \/ C =
    Parse.decodeEither[C](value).leftMap(InvalidConfig(_))

  implicit def showInstance(implicit E: EncodeJson[C]): Show[C] = new Show[C] {
    override def shows(f: C): String = EncodeJson.of[C].encode(f).pretty(quasar.fp.multiline)
  }

}