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

package quasar.qscript

import slamdata.Predef._
import quasar._
import quasar.common.JoinType
import quasar.contrib.pathy.{AFile, ADir}
import quasar.ejson.EJson
import quasar.fp._
import quasar.fs._
import quasar.sql.CompilerHelpers
import quasar.qscript.MapFuncs._

import scala.Predef.implicitly

import matryoshka._
import matryoshka.data.Fix
import matryoshka.implicits._
import pathy.Path._
import scalaz._, Scalaz._

class QScriptRewriteSpec extends quasar.Qspec with CompilerHelpers with QScriptHelpers {
  val rewrite = new Rewrite[Fix]

  def normalizeFExpr(expr: Fix[QS]): Fix[QS] =
    expr.transCata[Fix[QS]](orOriginal(Normalizable[QS].normalizeF(_: QS[Fix[QS]])))

  def normalizeExpr(expr: Fix[QS]): Fix[QS] =
    expr.transCata[Fix[QS]](rewrite.normalize[QS])

  def simplifyJoinExpr(expr: Fix[QS]): Fix[QST] =
    expr.transCata[Fix[QST]](SimplifyJoin[Fix, QS, QST].simplifyJoin(idPrism.reverseGet))

  def includeToExcludeExpr(expr: Fix[QST]): Fix[QST] =
    expr.transCata[Fix[QST]](
      liftFG(repeatedly(quasar.qscript.Coalesce[Fix, QST, QST].coalesceSR[QST, ADir](idPrism))) >>>
      liftFG(repeatedly(quasar.qscript.Coalesce[Fix, QST, QST].coalesceSR[QST, AFile](idPrism))))

  type QSI[A] =
    (QScriptCore :\: ProjectBucket :\: ThetaJoin :/: Const[DeadEnd, ?])#M[A]

  val DEI = implicitly[Const[DeadEnd, ?] :<: QSI]
  val QCI = implicitly[QScriptCore :<: QSI]

  val UnreferencedI: QSI[Fix[QSI]] = QCI.inj(Unreferenced[Fix, Fix[QSI]]())

  implicit def qsiToQscriptTotal: Injectable.Aux[QSI, QST] =
    ::\::[QScriptCore](::\::[ProjectBucket](::/::[Fix, ThetaJoin, Const[DeadEnd, ?]]))

  // TODO instead of calling `.toOption` on the `\/`
  // write an `Equal[PlannerError]` and test for specific errors too
  "rewriter" should {
    "elide a no-op map in a constant boolean" in {
      val query = lpf.constant(Data.Bool(true))
      val run: QSI[Fix[QSI]] => QSI[Fix[QSI]] = {
        fa => QCI.prj(fa).fold(fa)(rewrite.elideNopQC(idPrism[QSI].reverseGet))
      }

      QueryFile.convertAndNormalize[Fix, QSI](query)(run).toOption must
        equal(chain(
          UnreferencedI,
          QCI.inj(Map((), BoolLit(true)))).some)
    }

    "expand a directory read" in {
      convert(lc.some, lpRead("/foo")).flatMap(
        _.transCataM(ExpandDirs[Fix, QS, QST].expandDirs(idPrism.reverseGet, lc)).toOption.run.copoint) must
      equal(chain(
        QCT.inj(Unreferenced[Fix, Fix[QST]]()),
        QCT.inj(Union((),
          Free.roll(QST[QS].inject(QC.inj(Map(Free.roll(RTF.inj(Const[Read[AFile], FreeQS](Read(rootDir </> dir("foo") </> file("bar"))))), Free.roll(MakeMap(StrLit("bar"), HoleF)))))),
          Free.roll(QST[QS].inject(QC.inj(Union(Free.roll(QST[QS].inject(QC.inj(Unreferenced[Fix, FreeQS]()))),
            Free.roll(QST[QS].inject(QC.inj(Map(Free.roll(RTF.inj(Const[Read[AFile], FreeQS](Read(rootDir </> dir("foo") </> file("car"))))), Free.roll(MakeMap(StrLit("car"), HoleF)))))),
            Free.roll(QST[QS].inject(QC.inj(Union(Free.roll(QST[QS].inject(QC.inj(Unreferenced[Fix, FreeQS]()))),
              Free.roll(QST[QS].inject(QC.inj(Map(Free.roll(RTF.inj(Const[Read[AFile], FreeQS](Read(rootDir </> dir("foo") </> file("city"))))), Free.roll(MakeMap(StrLit("city"), HoleF)))))),
              Free.roll(QST[QS].inject(QC.inj(Union(Free.roll(QST[QS].inject(QC.inj(Unreferenced[Fix, FreeQS]()))),
                Free.roll(QST[QS].inject(QC.inj(Map(Free.roll(RTF.inj(Const[Read[AFile], FreeQS](Read(rootDir </> dir("foo") </> file("person"))))), Free.roll(MakeMap(StrLit("person"), HoleF)))))),
                Free.roll(QST[QS].inject(QC.inj(Map(Free.roll(RTF.inj(Const[Read[AFile], FreeQS](Read(rootDir </> dir("foo") </> file("zips"))))), Free.roll(MakeMap(StrLit("zips"), HoleF)))))))))))))))))))),
        QCT.inj(LeftShift((), HoleF, ExcludeId, Free.point(RightSide))))(
        implicitly, Corecursive[Fix[QST], QST]).some)
    }

    "coalesce a Map into a subsequent LeftShift" in {
      val exp: QScriptCore[Fix[QScriptCore]] =
        LeftShift(
          Map(
            Unreferenced[Fix, Fix[QScriptCore]]().embed,
            BoolLit[Fix, Hole](true)).embed,
          HoleF,
          ExcludeId,
          Free.point[MapFunc, JoinSide](RightSide))

      Coalesce[Fix, QScriptCore, QScriptCore].coalesceQC(idPrism).apply(exp) must
      equal(
        LeftShift(
          Unreferenced[Fix, Fix[QScriptCore]]().embed,
          BoolLit[Fix, Hole](true),
          ExcludeId,
          Free.point[MapFunc, JoinSide](RightSide)).some)
    }

    "fold a constant array value" in {
      val value: Fix[EJson] =
        EJson.fromExt[Fix[EJson]].apply(ejson.Int[Fix[EJson]](7))

      val exp: QS[Fix[QS]] =
        QC.inj(Map(
          RootR.embed,
          Free.roll(MakeArray(Free.roll(Constant(value))))))

      val expected: QS[Fix[QS]] =
        QC.inj(Map(
          RootR.embed,
          Free.roll(Constant(ejson.CommonEJson.inj(ejson.Arr(List(value))).embed))))

      normalizeFExpr(exp.embed) must equal(expected.embed)
    }

    "elide a join with a constant on one side" in {
      val exp: QS[Fix[QS]] =
        TJ.inj(ThetaJoin(
          RootR.embed,
          Free.roll(QCT.inj(LeftShift(
            Free.roll(QCT.inj(Map(
              Free.roll(DET.inj(Const[DeadEnd, FreeQS](Root))),
              ProjectFieldR(HoleF, StrLit("city"))))),
            HoleF,
            IncludeId,
            Free.roll(ConcatArrays(
              Free.roll(MakeArray(Free.point(LeftSide))),
              Free.roll(MakeArray(Free.point(RightSide)))))))),
          Free.roll(QCT.inj(Map(
            Free.roll(QCT.inj(Unreferenced[Fix, FreeQS]())),
            StrLit("name")))),
          BoolLit[Fix, JoinSide](true),
          JoinType.Inner,
          ProjectFieldR(
            Free.roll(ProjectIndex(
              Free.roll(ProjectIndex(Free.point(LeftSide), IntLit(1))),
              IntLit(1))),
            Free.point(RightSide))))

      // TODO: only require a single pass
      normalizeExpr(normalizeExpr(exp.embed)) must equal(
        chain(
          RootR,
          QC.inj(LeftShift((),
            ProjectFieldR(HoleF, StrLit("city")),
            ExcludeId,
            ProjectFieldR(Free.point(RightSide), StrLit("name"))))))
    }

    "fold a constant doubly-nested array value" in {
      val value: Fix[EJson] =
        EJson.fromExt[Fix[EJson]].apply(ejson.Int[Fix[EJson]](7))

      val exp: QS[Fix[QS]] =
        QC.inj(Map(
          RootR.embed,
          Free.roll(MakeArray(Free.roll(MakeArray(Free.roll(Constant(value))))))))

      val expected: QS[Fix[QS]] =
        QC.inj(Map(
          RootR.embed,
          Free.roll(Constant(ejson.CommonEJson.inj(ejson.Arr(List(EJson.fromCommon[Fix[EJson]].apply(ejson.Arr(List(value)))))).embed))))

      normalizeFExpr(exp.embed) must equal(expected.embed)
    }

    "elide a join in the branch of a join" in {
      val exp: QS[Fix[QS]] =
        TJ.inj(ThetaJoin(
          RootR.embed,
          Free.roll(QCT.inj(Map(
            Free.roll(QCT.inj(Unreferenced())),
            StrLit("name")))),
          Free.roll(TJT.inj(ThetaJoin(
            Free.roll(DET.inj(Const(Root))),
            Free.roll(QCT.inj(LeftShift(
              Free.point(SrcHole),
              HoleF,
              IncludeId,
              Free.roll(ConcatArrays(
                Free.roll(MakeArray(Free.point(LeftSide))),
                Free.roll(MakeArray(Free.point(RightSide)))))))),
            Free.roll(QCT.inj(Map(
              Free.roll(QCT.inj(Unreferenced())),
              StrLit("name")))),
            BoolLit(true),
            JoinType.Inner,
            Free.roll(ConcatArrays(
              Free.roll(MakeArray(Free.point(LeftSide))),
              Free.roll(MakeArray(Free.point(RightSide)))))))),
          BoolLit(true),
          JoinType.Inner,
          Free.roll(ConcatArrays(
            Free.roll(MakeArray(Free.point(LeftSide))),
            Free.roll(MakeArray(Free.point(RightSide)))))))

      // TODO: only require a single pass
      normalizeExpr(normalizeExpr(exp.embed)) must
        equal(
          QC.inj(LeftShift(
            RootR.embed,
            HoleF,
            IncludeId,
            Free.roll(ConcatArrays(
              Free.roll(Constant(
                ejson.CommonEJson.inj(ejson.Arr(List(EJson.fromCommon[Fix[EJson]].apply(ejson.Str[Fix[ejson.EJson]]("name"))))).embed)),
              Free.roll(MakeArray(
                Free.roll(ConcatArrays(
                  Free.roll(MakeArray(
                    Free.roll(ConcatArrays(
                      Free.roll(MakeArray(Free.point(LeftSide))),
                      Free.roll(MakeArray(Free.point(RightSide))))))),
                  Free.roll(Constant(
                    ejson.CommonEJson.inj(ejson.Arr(List(EJson.fromCommon[Fix[EJson]].apply(ejson.Str[Fix[ejson.EJson]]("name"))))).embed)))))))))).embed)
    }

    "fold nested boolean values" in {
      val falseBool: Fix[EJson] =
        EJson.fromCommon[Fix[EJson]].apply(ejson.Bool[Fix[EJson]](false))

      val trueBool: Fix[EJson] =
        EJson.fromCommon[Fix[EJson]].apply(ejson.Bool[Fix[EJson]](true))

      val exp: QS[Fix[QS]] =
        QC.inj(Map(
          RootR.embed,
          Free.roll(MakeArray(
            // !false && (false || !true)
            Free.roll(And(
              Free.roll(Not(Free.roll(Constant(falseBool)))),
              Free.roll(Or(
                Free.roll(Constant(falseBool)),
                Free.roll(Not(Free.roll(Constant(trueBool))))))))))))

      val expected: QS[Fix[QS]] =
        QC.inj(Map(
          RootR.embed,
          Free.roll(Constant(
            ejson.CommonEJson.inj(ejson.Arr(List(EJson.fromCommon[Fix[EJson]].apply(ejson.Bool[Fix[ejson.EJson]](false))))).embed))))

      normalizeFExpr(exp.embed) must equal(expected.embed)
    }

    "simplify a ThetaJoin" in {
      val exp: QS[Fix[QS]] =
        TJ.inj(ThetaJoin(
          QC.inj(Unreferenced[Fix, Fix[QS]]()).embed,
          Free.roll(RTF.inj(Const(Read(rootDir </> file("foo"))))),
          Free.roll(RTF.inj(Const(Read(rootDir </> file("bar"))))),
          Free.roll(And(Free.roll(And(
            // reversed equality
            Free.roll(MapFuncs.Eq(
              Free.roll(ProjectField(Free.point(RightSide), StrLit("r_id"))),
              Free.roll(ProjectField(Free.point(LeftSide), StrLit("l_id"))))),
            // more complicated expression, duplicated refs
            Free.roll(MapFuncs.Eq(
              Free.roll(Add(
                Free.roll(ProjectField(Free.point(LeftSide), StrLit("l_min"))),
                Free.roll(ProjectField(Free.point(LeftSide), StrLit("l_max"))))),
              Free.roll(Subtract(
                Free.roll(ProjectField(Free.point(RightSide), StrLit("l_max"))),
                Free.roll(ProjectField(Free.point(RightSide), StrLit("l_min"))))))))),
            // inequality
            Free.roll(Lt(
              Free.roll(ProjectField(Free.point(LeftSide), StrLit("l_lat"))),
              Free.roll(ProjectField(Free.point(RightSide), StrLit("r_lat"))))))),
          JoinType.Inner,
          Free.roll(ConcatMaps(Free.point(LeftSide), Free.point(RightSide)))))

      simplifyJoinExpr(exp.embed) must equal(
        QCT.inj(Map(
          QCT.inj(Filter(
            EJT.inj(EquiJoin(
              QCT.inj(Unreferenced[Fix, Fix[QST]]()).embed,
              Free.roll(RTF.inj(Const(Read(rootDir </> file("foo"))))),
              Free.roll(RTF.inj(Const(Read(rootDir </> file("bar"))))),
              Free.roll(ConcatArrays(
                Free.roll(MakeArray(
                  Free.roll(ProjectField(Free.point(SrcHole), StrLit("l_id"))))),
                Free.roll(MakeArray(
                  Free.roll(Add(
                    Free.roll(ProjectField(Free.point(SrcHole), StrLit("l_min"))),
                    Free.roll(ProjectField(Free.point(SrcHole), StrLit("l_max"))))))))),
              Free.roll(ConcatArrays(
                Free.roll(MakeArray(
                  Free.roll(ProjectField(Free.point(SrcHole), StrLit("r_id"))))),
                Free.roll(MakeArray(
                  Free.roll(Subtract(
                    Free.roll(ProjectField(Free.point(SrcHole), StrLit("l_max"))),
                    Free.roll(ProjectField(Free.point(SrcHole), StrLit("l_min"))))))))),
              JoinType.Inner,
              Free.roll(ConcatArrays(
                Free.roll(MakeArray(Free.point(LeftSide))),
                Free.roll(MakeArray(Free.point(RightSide))))))).embed,
            Free.roll(Lt(
              Free.roll(ProjectField(
                Free.roll(ProjectIndex(Free.point(SrcHole), IntLit(0))),
                StrLit("l_lat"))),
              Free.roll(ProjectField(
                Free.roll(ProjectIndex(Free.point(SrcHole), IntLit(1))),
                StrLit("r_lat"))))))).embed,
          Free.roll(ConcatMaps(
            Free.roll(ProjectIndex(Free.point(SrcHole), IntLit(0))),
            Free.roll(ProjectIndex(Free.point(SrcHole), IntLit(1))))))).embed)
    }

    "transform a ShiftedRead with IncludeId to ExcludeId when possible" in {
      val sampleFile = rootDir </> file("bar")

      val originalQScript =
        QCT.inj(Map(
          SRTF.inj(Const[ShiftedRead[AFile], Fix[QST]](ShiftedRead(sampleFile, IncludeId))).embed,
          Free.roll(Add(
            Free.roll(ProjectIndex(HoleF, IntLit(1))),
            Free.roll(ProjectIndex(HoleF, IntLit(1))))))).embed

      val expectedQScript =
        QCT.inj(Map(
          SRTF.inj(Const[ShiftedRead[AFile], Fix[QST]](ShiftedRead(sampleFile, ExcludeId))).embed,
          Free.roll(Add(HoleF, HoleF)))).embed

      includeToExcludeExpr(originalQScript) must_= expectedQScript
    }
  }
}
