import zio.test._
import zio.Console
import zio.ZIO
import zio.test.TestAspect._
import zio.test.Assertion._

object HelloWorldSpec extends ZIOSpecDefault {
  def spec =
    suite("ZIO-QUARTZ-H2 tests")(
      test("Say hello test") {
        for {
          _ <- Console.printLine("Hello ZIO Test")
        } yield (assertTrue(true))
      }
    )
}
