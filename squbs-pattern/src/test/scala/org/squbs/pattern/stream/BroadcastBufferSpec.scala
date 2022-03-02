/*
 *  Copyright 2017 PayPal
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.squbs.pattern.stream

import akka.Done
import akka.actor.ActorSystem
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Keep, RunnableGraph, Sink, Source}
import akka.stream.{AbruptTerminationException, ClosedShape, Materializer, ThrottleMode}
import akka.util.ByteString
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.Eventually
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.squbs.testkit.Timeouts._

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.{Await, Future, Promise}
import scala.reflect._
import scala.util.control.NonFatal

abstract class BroadcastBufferSpec[T: ClassTag, Q <: QueueSerializer[T] : Manifest]
   (typeName: String) extends AnyFlatSpec with Matchers with BeforeAndAfterAll with Eventually {

  implicit val system = ActorSystem(s"Broadcast${typeName}BufferSpec", PersistentBufferSpec.testConfig)
  implicit val serializer = QueueSerializer[T]()
  import StreamSpecUtil._
  import system.dispatcher

  def createElement(n: Int): T

  def format(element: T): String

  val transform = Flow[Int] map createElement

  override def afterAll(): Unit = {
    Await.ready(system.terminate(), awaitMax)
  }

  it should s"buffer a stream of $elementCount elements using GraphDSL" in {
    val util = new StreamSpecUtil[T, T](2)
    import util._

    val buffer = new BroadcastBuffer[T](config)
    val streamGraph = RunnableGraph.fromGraph(GraphDSL.createGraph(flowCounter) { implicit builder =>
      sink =>
        import GraphDSL.Implicits._
        val bcBuffer = builder.add(buffer.async)
        val mr = builder.add(merge)
        in ~> transform ~> bcBuffer ~> mr ~> sink
                           bcBuffer ~> mr
        ClosedShape
    })
    val countFuture = streamGraph.run()
    val count = Await.result(countFuture, awaitMax)
    eventually { buffer.queue shouldBe 'closed }
    count shouldBe (elementCount * outputPorts)
    println(s"Total records processed $count")
    clean()
  }

  it should "buffer for a throttled stream" in {
    val util = new StreamSpecUtil[T, T](2)
    import util._
    val throttleShape = Flow[T].throttle(flowRate * 10, flowUnit, burstSize * 10, ThrottleMode.shaping)

    var t1, t2 = Long.MinValue
    val t0 = System.nanoTime

    def counter(recordFn: Long => Unit) = Flow[Any].map(_ => 1L).reduce(_ + _).map { s =>
      recordFn(System.nanoTime - t0)
      s
    }.toMat(Sink.head)(Keep.right)

    val buffer = new BroadcastBuffer[T](config)
    val streamGraph = RunnableGraph.fromGraph(GraphDSL.createGraph(counter(t1 = _)) { implicit builder =>
      sink =>
        import GraphDSL.Implicits._
        val bc = builder.add(Broadcast[T](2))
        val bcBuffer = builder.add(buffer.async)
        val mr = builder.add(merge)
        val throttle = builder.add(throttleShape)
        in ~> transform ~> bc ~> bcBuffer ~> mr ~> throttle ~> sink
                                 bcBuffer ~> mr
        bc ~> counter(t2 = _)
        ClosedShape
    })
    val countF = streamGraph.run()
    val count = Await.result(countF, awaitMax)
    eventually { buffer.queue shouldBe 'closed }
    println("Time difference (ms): " + (t1 - t2) / 1000000d)
    count shouldBe (elementCount * outputPorts)
    println(s"Total records processed $count")
    t1 should be > t2 // Give 6 seconds difference. In fact, it should be closer to 9 seconds.
    clean()
  }

  it should "recover from unexpected stream shutdown" in {
    implicit val util = new StreamSpecUtil[T, T](2)
    import util._

    val mat = Materializer(system)
    val finishedGenerating = Promise[Done]()
    val bBufferInCount = new AtomicInteger(0)
    val counter = new AtomicInteger(0)

    def fireFinished() = Flow[T].map { e =>
      if(counter.incrementAndGet() == failTestAt) finishedGenerating success Done
      e
    }.toMat(Sink.ignore)(Keep.right)

    val shutdownF = finishedGenerating.future map { d => mat.shutdown(); d }

    val graph = RunnableGraph.fromGraph(GraphDSL.createGraph(
      Sink.ignore, Sink.ignore, fireFinished())((_,_,_)) { implicit builder =>
      (sink1, sink2, sink3) =>
        import GraphDSL.Implicits._
        val buffer = new BroadcastBuffer[T](config)
          .withOnPushCallback(() => bBufferInCount.incrementAndGet())
          .withOnCommitCallback(i => commitCounter(i))
        val bcBuffer = builder.add(buffer.async)
        val bc = builder.add(Broadcast[T](2))

        in ~> transform ~> bc ~> bcBuffer ~> throttle ~> sink1
                                 bcBuffer ~> throttleMore ~> sink2
                           bc ~> sink3

        ClosedShape
    })
    val (sink1F, sink2F, _) = graph.run()(mat)

    Await.result(sink1F.failed, awaitMax) shouldBe an[AbruptTerminationException]
    Await.result(sink2F.failed, awaitMax) shouldBe an[AbruptTerminationException]

    var restartFrom = bBufferInCount.get
    println(s"Restart from count $restartFrom")

    var beforeShutDown = SinkCounts(atomicCounter(0).get, atomicCounter(1).get)

    Thread.sleep(30)

    // Ensure our counter readings are stable.
    // The `AbruptTerminationException` may not signal the stream is totally stopped.
    eventually {
      val prevRestartFrom = restartFrom
      val prevBeforeShutDown = beforeShutDown
      restartFrom = bBufferInCount.get
      beforeShutDown = SinkCounts(atomicCounter(0).get, atomicCounter(1).get)
      restartFrom shouldBe prevRestartFrom
      beforeShutDown shouldBe prevBeforeShutDown
    }

    resumeGraphAndDoAssertion(beforeShutDown, restartFrom)
    clean()
  }

  it should "recover from downstream failure" in {
    implicit val util = new StreamSpecUtil[T, T](2)
    import util._

    val injectCounter = new AtomicInteger(0)
    val inCounter = new AtomicInteger(0)

    val injectError = Flow[T].map { n =>
      val count = injectCounter.incrementAndGet()
      if (count == failTestAt) throw new NumberFormatException("This is a fake exception")
      else n
    }

    val graph = RunnableGraph.fromGraph(
      GraphDSL.createGraph(Sink.ignore, Sink.ignore)((_, _)) { implicit builder => (sink1, sink2) =>
          import GraphDSL.Implicits._
          val buffer = new BroadcastBuffer[T](config)
            .withOnPushCallback(() => inCounter.incrementAndGet())
            .withOnCommitCallback(i => commitCounter(i))
          val bcBuffer = builder.add(buffer.async)

          in ~> transform ~> bcBuffer ~> throttle ~> injectError ~> sink1
                             bcBuffer ~> throttle                ~> sink2

          ClosedShape
      })

    val (sink1F, sink2F) = graph.run()

    Await.result(sink1F.failed, awaitMax) shouldBe a[NumberFormatException]
    Await.ready(sink2F.recover { case _ => Done}, awaitMax) // Since Akka 2.6 if one branch fails the other fails, too.

    val beforeShutDown = SinkCounts(atomicCounter(0).get, atomicCounter(1).get)
    val restartFrom = inCounter.incrementAndGet()
    println(s"Restart from count $restartFrom")
    resumeGraphAndDoAssertion(beforeShutDown, restartFrom)
    clean()
  }

  it should "recover from upstream failure" in {
    implicit val util = new StreamSpecUtil[T, T](2)
    import util._

    val injectError = Flow[Int].map { n =>
      if (n == failTestAt) throw new NumberFormatException("This is a fake exception")
      else n
    }

    val buffer = new BroadcastBuffer[T](config).withOnCommitCallback(i => commitCounter(i))
    val graph1 = RunnableGraph.fromGraph(
      GraphDSL.createGraph(Sink.ignore, Sink.ignore)((_,_)) { implicit builder =>
        (sink1, sink2) =>
          import GraphDSL.Implicits._
          val bcBuffer = builder.add(buffer.async)

          in ~> injectError ~> transform ~> bcBuffer ~> throttle ~> sink1
                                            bcBuffer ~> throttle ~> sink2

          ClosedShape
      })
    val (sink1F, sink2F) = graph1.run()
    Await.result(for {a <- sink1F; b <- sink2F} yield (a, b), awaitMax)
    eventually { buffer.queue shouldBe 'closed }

    val beforeShutDown  = SinkCounts(atomicCounter(0).get, atomicCounter(1).get)
    resumeGraphAndDoAssertion(beforeShutDown, failTestAt)
    clean()
  }

  case class SinkCounts(sink1: Long, sink2: Long)

  private def resumeGraphAndDoAssertion(beforeShutDown: SinkCounts, restartFrom: Int)
                                       (implicit util: StreamSpecUtil[T, T]) = {
    import util._
    val buffer = new BroadcastBuffer[T](config)
    val graph = RunnableGraph.fromGraph(
      GraphDSL.createGraph(head, head,
        flowCounter, flowCounter)((_,_,_,_)) { implicit builder =>
        (first1, first2, last1, last2) =>
          import GraphDSL.Implicits._
          val bcBuffer = builder.add(buffer.async)
          val bc1 = builder.add(Broadcast[T](2))
          val bc2 = builder.add(Broadcast[T](2))
          Source(restartFrom to (elementCount + elementsAfterFail)) ~> transform ~>
            bcBuffer ~> bc1 ~> first1
                        bc1 ~> last1
            bcBuffer ~> bc2 ~> first2
                        bc2 ~> last2
          ClosedShape
      })
    val (head1F, head2F, last1F, last2F) = graph.run()
    val head1 = Await.result(head1F, awaitMax)
    val head2 = Await.result(head2F, awaitMax)
    println(s"First record processed after shutdown => ${(format(head1), format(head2))}")
    val last1 = Await.result(last1F, awaitMax)
    val last2 = Await.result(last2F, awaitMax)
    eventually { buffer.queue shouldBe 'closed }
    assertions(beforeShutDown, SinkCounts(last1, last2), SinkCounts(totalProcessed, totalProcessed))
  }

  private def assertions(beforeShutDown: SinkCounts, afterRecovery: SinkCounts, totalRecords: SinkCounts) = {
    println(s"Last record processed before shutdown => $beforeShutDown")
    println(s"Records processed after recovery => $afterRecovery")
    val processedRecords = (beforeShutDown.sink1 + afterRecovery.sink1, beforeShutDown.sink2 + afterRecovery.sink2)
    val lostRecords = (totalRecords.sink1 - processedRecords._1 , totalRecords.sink2 - processedRecords._2)
    println(s"Total records lost due to unexpected shutdown => $lostRecords")
    println(s"Total records processed => $processedRecords")

    processedRecords._1 should be >= totalRecords.sink1
    processedRecords._2 should be >= totalRecords.sink2
  }
}

class BroadcastByteStringBufferSpec extends BroadcastBufferSpec[ByteString, ByteStringSerializer]("ByteString") {

  def createElement(n: Int): ByteString = ByteString(s"Hello $n")

  def format(element: ByteString): String = element.utf8String
}

class BroadcastStringBufferSpec extends BroadcastBufferSpec[String, ObjectSerializer[String]]("Object") {

  def createElement(n: Int): String = s"Hello $n"

  def format(element: String): String = element
}

class BroadcastLongBufferSpec extends BroadcastBufferSpec[Long, LongSerializer]("Long") {

  def createElement(n: Int): Long = n

  def format(element: Long): String = element.toString
}

class BroadcastIntBufferSpec extends BroadcastBufferSpec[Int, IntSerializer]("Int") {

  def createElement(n: Int): Int = n

  def format(element: Int): String = element.toString
}

class BroadcastShortBufferSpec extends BroadcastBufferSpec[Short, ShortSerializer]("Short") {

  def createElement(n: Int): Short = n.toShort

  def format(element: Short): String = element.toString
}

class BroadcastByteBufferSpec extends BroadcastBufferSpec[Byte, ByteSerializer]("Byte") {

  def createElement(n: Int): Byte = n.toByte

  def format(element: Byte): String = element.toString
}

class BroadcastCharBufferSpec extends BroadcastBufferSpec[Char, CharSerializer]("Char") {

  def createElement(n: Int): Char = n.toChar

  def format(element: Char): String = element.toString
}

class BroadcastDoubleBufferSpec extends BroadcastBufferSpec[Double, DoubleSerializer]("Double") {

  def createElement(n: Int): Double = n.toDouble

  def format(element: Double): String = element.toString
}

class BroadcastFloatBufferSpec extends BroadcastBufferSpec[Float, FloatSerializer]("Float") {

  def createElement(n: Int): Float = n.toFloat

  def format(element: Float): String = element.toString
}

class BroadcastBooleanBufferSpec extends BroadcastBufferSpec[Boolean, BooleanSerializer]("Boolean") {

  def createElement(n: Int): Boolean = n % 2 == 0

  def format(element: Boolean): String = element.toString
}

class BroadcastPersonBufferSpec extends BroadcastBufferSpec[Person, PersonSerializer]("Person") {

  override implicit val serializer = new PersonSerializer()

  def createElement(n: Int): Person = Person(s"John Doe $n", 20)

  def format(element: Person): String = element.toString
}


