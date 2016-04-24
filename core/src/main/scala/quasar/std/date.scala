/*
 * Copyright 2014–2016 SlamData Inc.
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

package quasar.std

import quasar.Predef._
import quasar.{Data, Func, Mapping, Type, SemanticError}, SemanticError._
import quasar.fp._

import org.threeten.bp.{Duration, Instant, LocalDate, LocalTime, Period, ZoneOffset}
import scalaz._, Validation.success

trait DateLib extends Library {
  def parseTimestamp(str: String): SemanticError \/ Data.Timestamp =
    \/.fromTryCatchNonFatal(Instant.parse(str)).bimap(
      κ(DateFormatError(Timestamp, str, None)),
      Data.Timestamp.apply)

  def parseDate(str: String): SemanticError \/ Data.Date =
    \/.fromTryCatchNonFatal(LocalDate.parse(str)).bimap(
      κ(DateFormatError(Date, str, None)),
      Data.Date.apply)

  def parseTime(str: String): SemanticError \/ Data.Time =
    \/.fromTryCatchNonFatal(LocalTime.parse(str)).bimap(
      κ(DateFormatError(Time, str, None)),
      Data.Time.apply)

  def parseInterval(str: String): SemanticError \/ Data.Interval = {
    \/.fromTryCatchNonFatal(Duration.parse(str)).bimap(
      κ(DateFormatError(Interval, str, Some("expected, e.g. P3DT12H30M15.0S; note: year/month not currently supported"))),
      Data.Interval.apply)
  }

  private def startOfDayInstant(date: LocalDate): Instant =
    date.atStartOfDay.atZone(ZoneOffset.UTC).toInstant

  def startOfDay(date: Data.Date): Data.Timestamp =
    Data.Timestamp(startOfDayInstant(date.value))

  def startOfNextDay(date: Data.Date): Data.Timestamp =
    Data.Timestamp(startOfDayInstant(date.value.plus(Period.ofDays(1))))

  // NB: SQL specifies a function called `extract`, but that doesn't have comma-
  //     separated arguments. `date_part` is Postgres’ name for the same thing
  //     with commas.
  val Extract = Func(Mapping, 
    "date_part",
    "Pulls out a part of the date. The first argument is one of the strings defined for Postgres’ `date_type function. This is a partial function – using an unsupported string has undefined results.",
    Type.Numeric, Type.Str :: Type.Temporal :: Nil,
    noSimplification,
    constTyper(Type.Numeric),
    basicUntyper)

  val Date = Func(Mapping, 
    "date",
    "Converts a string in the format (YYYY-MM-DD) to a date value. This is a partial function – arguments that don’t satisify the constraint have undefined results.",
    Type.Date, Type.Str :: Nil,
    noSimplification,
    partialTyperV {
      case Type.Const(Data.Str(str)) :: Nil => parseDate(str).map(Type.Const(_)).validation.toValidationNel
      case Type.Str                  :: Nil => success(Type.Date)
    },
    basicUntyper)

  val Time = Func(Mapping, 
    "time",
    "Converts a string in the format (HH:MM:SS[.SSS]) to a time value. This is a partial function – arguments that don’t satisify the constraint have undefined results.",
    Type.Time, Type.Str :: Nil,
    noSimplification,
    partialTyperV {
      case Type.Const(Data.Str(str)) :: Nil => parseTime(str).map(Type.Const(_)).validation.toValidationNel
      case Type.Str                  :: Nil => success(Type.Time)
    },
    basicUntyper)

  val Timestamp = Func(Mapping, 
    "timestamp",
    "Converts a string in the format (ISO 8601, UTC, e.g. 2015-05-12T12:22:00Z) to a timestamp value. This is a partial function – arguments that don’t satisify the constraint have undefined results.",
    Type.Timestamp, Type.Str :: Nil,
    noSimplification,
    partialTyperV {
      case Type.Const(Data.Str(str)) :: Nil => parseTimestamp(str).map(Type.Const(_)).validation.toValidationNel
      case Type.Str                  :: Nil => success(Type.Timestamp)
    },
    basicUntyper)

  val Interval = Func(Mapping, 
    "interval",
    "Converts a string in the format (ISO 8601, e.g. P3DT12H30M15.0S) to an interval value. Note: year/month not currently supported. This is a partial function – arguments that don’t satisify the constraint have undefined results.",
    Type.Interval, Type.Str :: Nil,
    noSimplification,
    partialTyperV {
      case Type.Const(Data.Str(str)) :: Nil => parseInterval(str).map(Type.Const(_)).validation.toValidationNel
      case Type.Str                  :: Nil => success(Type.Interval)
    },
    basicUntyper)

  val TimeOfDay = Func(Mapping, 
    "time_of_day",
    "Extracts the time of day from a (UTC) timestamp value.",
    Type.Time, Type.Timestamp :: Nil,
    noSimplification,
    partialTyper {
      case Type.Const(Data.Timestamp(value)) :: Nil => Type.Const(Data.Time(value.atZone(ZoneOffset.UTC).toLocalTime))
      case Type.Timestamp :: Nil => Type.Time
    },
    basicUntyper)

  val ToTimestamp = Func(Mapping, 
    "to_timestamp",
    "Converts an integer epoch time value (i.e. milliseconds since 1 Jan. 1970, UTC) to a timestamp constant.",
    Type.Timestamp, Type.Int :: Nil,
    noSimplification,
    partialTyper {
      case Type.Const(Data.Int(millis)) :: Nil => Type.Const(Data.Timestamp(Instant.ofEpochMilli(millis.toLong)))
      case Type.Int :: Nil => Type.Timestamp
    },
    basicUntyper)

  def functions = Extract :: Date :: Time :: Timestamp :: Interval :: TimeOfDay :: ToTimestamp :: Nil
}
object DateLib extends DateLib
