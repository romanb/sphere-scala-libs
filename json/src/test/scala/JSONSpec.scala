package io.sphere.json

import scalaz._
import Scalaz._

import io.sphere.json.generic._
import io.sphere.util.Money

import java.util.{ Locale, Currency }

import net.liftweb.json.JsonAST._
import net.liftweb.json.JsonParser
import net.liftweb.json.JsonParser.ParseException
import org.joda.time._
import org.joda.time.format.ISODateTimeFormat
import org.scalatest._
import org.scalatest.matchers.MustMatchers

object JSONSpec {
  case class Project(nr: Int, name: String, version: Int = 1, milestones: List[Milestone] = Nil)
  case class Milestone(name: String, date: Option[DateTime] = None)

  sealed abstract class Animal
  case class Dog(name: String) extends Animal
  case class Cat(name: String) extends Animal
  case class Bird(name: String) extends Animal

  sealed trait GenericBase[A]
  case class GenericA[A](a: A) extends GenericBase[A]
  case class GenericB[A](a: A) extends GenericBase[A]

  object Singleton

  sealed abstract class SingletonEnum
  case object SingletonA extends SingletonEnum
  case object SingletonB extends SingletonEnum
  case object SingletonC extends SingletonEnum

  sealed trait Mixed
  case object SingletonMixed extends Mixed
  case class RecordMixed(i: Int) extends Mixed

  object ScalaEnum extends Enumeration {
    val One, Two, Three = Value
  }

  // case class Node(value: Option[List[Node]]) // JSON instances for recursive data types cannot be derived
}

class JSONSpec extends FunSpec with MustMatchers {
  import JSONSpec._

  describe("JSON") {
    it("must read/write a custom class using custom typeclass instances") {
      import JSONSpec.{ Project, Milestone }

      implicit object MilestoneJSON extends JSON[Milestone] {
        def write(m: Milestone): JValue = JObject(
          JField("name", JString(m.name)) ::
          JField("date", toJValue(m.date)) :: Nil
        )
        def read(j: JValue): ValidationNel[JSONError, Milestone] = j match {
          case o: JObject =>
            (field[String]("name")(o) |@|
             field[Option[DateTime]]("date")(o)) { Milestone }
          case _ => fail("JSON object expected.")
        }
      }
      implicit object ProjectJSON extends JSON[Project] {
        def write(p: Project): JValue = JObject(
          JField("nr", JInt(p.nr)) ::
          JField("name", JString(p.name)) ::
          JField("version", JInt(p.version)) ::
          JField("milestones", toJValue(p.milestones)) :: Nil
        )
        def read(jval: JValue): ValidationNel[JSONError, Project] = jval match {
          case o: JObject =>
            (field[Int]("nr")(o) |@|
            field[String]("name")(o) |@|
            field[Int]("version", Some(1))(o) |@|
            field[List[Milestone]]("milestones", Some(Nil))(o)) { Project }
          case _ => fail("JSON object expected.")
        }
      }

      val proj = Project(42, "Linux")
      fromJSON[Project](toJSON(proj)) must equal (Success(proj))

      // Now some invalid JSON to test the error accumulation
      val wrongTypeJSON = """
      {
        "nr":"1",
        "name":23,
        "version":1,
        "milestones":[{"name":"Bravo", "date": "xxx"}]
      }
      """
      val Failure(errs) = fromJSON[Project](wrongTypeJSON)
      errs.list must equal (List(
        JSONFieldError(List("nr"), "JSON Number in the range of an Int expected."),
        JSONFieldError(List("name"), "JSON String expected."),
        JSONFieldError(List("milestones", "date"), "Failed to parse date/time: xxx")
      ))

      // Now without a version value and without a milestones list. Defaults should apply.
      val noVersionJSON = """{"nr":1,"name":"Linux"}"""
      fromJSON[Project](noVersionJSON) must equal (Success(Project(1, "Linux")))
    }

    it ("must fail reading wrong currency code.") {
      val wrongMoney = """{"currencyCode":"WRONG","centAmount":1000}"""
      fromJSON[Money](wrongMoney).isFailure must be (true)
    }

    it("must provide derived JSON instances for product types (case classes)") {
      import JSONSpec.{ Project, Milestone }
      implicit val milestoneJSON = deriveJSON[Milestone]
      implicit val projectJSON = deriveJSON[Project]
      val proj = Project(42, "Linux", 7, Milestone("1.0") :: Milestone("2.0") :: Milestone("3.0") :: Nil)
      fromJSON[Project](toJSON(proj)) must equal (Success(proj))
    }

    it("must provide derived JSON instances for sum types") {
      implicit val animalJSON = deriveJSON[Animal]
      List(Bird("Peewee"), Dog("Hasso"), Cat("Felidae")) foreach { a: Animal =>
        fromJSON[Animal](toJSON(a)) must equal (Success(a))
      }
    }

    it("must provide derived instances for product types with concrete type parameters") {
      implicit val aJSON = deriveJSON[GenericA[String]]
      val a = GenericA("hello")
      fromJSON[GenericA[String]](toJSON(a)) must equal (Success(a))
    }

    it("must provide derived instances for product types with generic type parameters") {
      implicit def aJSON[A: FromJSON: ToJSON] = deriveJSON[GenericA[A]]
      val a = GenericA("hello")
      fromJSON[GenericA[String]](toJSON(a)) must equal (Success(a))
    }

    it("must provide derived instances for singleton objects") {
      implicit val singletonJSON = deriveJSON[Singleton.type]
      fromJSON[Singleton.type](toJSON(Singleton)) must equal (Success(Singleton))

      implicit val singleEnumJSON = deriveJSON[SingletonEnum]
      List(SingletonA, SingletonB, SingletonC) foreach { s: SingletonEnum =>
        fromJSON[SingletonEnum](toJSON(s)) must equal (Success(s))
      }
    }

    it("must provide derived instances for sum types with a mix of case class / object") {
      implicit val mixedJSON = deriveJSON[Mixed]
      List(SingletonMixed, RecordMixed(1)) foreach { m: Mixed =>
        fromJSON[Mixed](toJSON(m)) must equal (Success(m))
      }
    }

    it("must provide derived instances for scala.Enumeration") {
      implicit val scalaEnumJSON = deriveJSON[ScalaEnum.Value]
      ScalaEnum.values.foreach { v =>
        fromJSON[ScalaEnum.Value](toJSON(v)) must equal (Success(v))
      }
    }

    // TODO
    // it("must provide derived instances for sum types with type parameters") {
    //   implicit def aJSON[A: FromJSON: ToJSON]: JSON[GenericA[A]] = deriveJSON[GenericA[A]]
    //   implicit def bJSON[B: FromJSON: ToJSON]: JSON[GenericB[B]] = deriveJSON[GenericB[B]]
    //   // implicit def genJSON[A: FromJSON: ToJSON]: JSON[GenericBase[A]] = {
    //   //   implicit def json0[A >: Nothing <: Any](implicit jr0: FromJSON[A], jw0: ToJSON[A]): JSON[GenericA[A]] = io.sphere.json.generic.`package`.jsonProduct({
    //   //     ((x0: A) => GenericA.apply(x0))
    //   //   });
    //   //   implicit def json1[A >: Nothing <: Any](implicit jr0: FromJSON[A], jw0: ToJSON[A]): JSON[GenericB[A]] = io.sphere.json.generic.`package`.jsonProduct({
    //   //     ((x0: A) => GenericB.apply(x0))
    //   //   });
    //   //   io.sphere.json.generic.`package`.jsonTypeSwitch[GenericBase[A], GenericA[A], GenericB[A]](Nil)
    //   // }
    //   implicit def genJSON[A: FromJSON: ToJSON]: JSON[GenericBase[A]] = // deriveJSON[GenericBase[A]]
    //     jsonTypeSwitch[GenericBase[A], GenericA[A], GenericB[A]](Nil)

    //   List(GenericA("hello"), GenericB("olleh")) foreach { g: GenericBase[String] =>
    //     fromJSON[GenericBase[String]](toJSON(g)) must equal (Success(g))
    //   }
    // }
  }
}
