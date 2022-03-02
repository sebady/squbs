/*
 * Copyright 2017 PayPal
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.squbs.unicomplex

import akka.actor.ActorSystem
import akka.pattern._
import akka.testkit.TestKit
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.flatspec.AsyncFlatSpecLike
import org.scalatest.matchers.should.Matchers
import org.squbs.lifecycle.GracefulStop
import org.squbs.unicomplex.Timeouts._

object JavaRouteSvcSpec {

  val classPaths = Array(getClass.getClassLoader.getResource("classpaths/JavaRouteSvc").getPath)

  val config = ConfigFactory.parseString(
    s"""
       |default-listener.bind-port = 0
       |squbs {
       |  actorsystem-name = JavaRouteSvcSpec
       |  ${JMX.prefixConfig} = true
       |}
    """.stripMargin
  )

  val boot = UnicomplexBoot(config)
    .createUsing {(name, config) => ActorSystem(name, config)}
    .scanComponents(classPaths)
    .initExtensions.start()
}

class JavaRouteSvcSpec extends TestKit(
  JavaRouteSvcSpec.boot.actorSystem) with AsyncFlatSpecLike with BeforeAndAfterAll with Matchers {

  val portBindingsF = (Unicomplex(system).uniActor ? PortBindings).mapTo[Map[String, Int]]
  val portF = portBindingsF map { bindings => bindings("default-listener") }

  override def afterAll(): Unit = {
    Unicomplex(system).uniActor ! GracefulStop
  }

  it should "handle a normal request" in {
    for {
      port <- portF
      response <- entityAsString(s"http://127.0.0.1:$port/javaroutesvc/ping")
    } yield {
      response shouldBe "pong"
    }
  }

  it should "apply the rejection handler to the service" in {
    for {
      port <- portF
      response <- entityAsString(s"http://127.0.0.1:$port/javaroutesvc/reject")
    } yield {
      response shouldBe "rejected"
    }
  }

  it should "apply the exception handler to the service" in {
    for {
      port <- portF
      response <- entityAsString(s"http://127.0.0.1:$port/javaroutesvc/exception")
    } yield {
      response shouldBe "exception"
    }
  }
}
