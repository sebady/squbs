/*
 * Copyright 2015 PayPal
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
import org.scalatest.OptionValues._
import org.scalatest.{AsyncFlatSpecLike, Matchers}

import scala.util.Failure

object JavaFailedFlowSvcSpec {

  val dummyJarsDir = getClass.getClassLoader.getResource("classpaths").getPath

  val classPath = dummyJarsDir + "/JavaFailedFlowSvc/META-INF/squbs-meta.conf"

  val config = ConfigFactory.parseString(
    s"""
       |squbs {
       |  actorsystem-name = JavaFailedFlowSvcSpec
       |  ${JMX.prefixConfig} = true
       |}
       |default-listener.bind-port = 0
       |akka.http.server.remote-address-header = on
    """.stripMargin
  )

  import Timeouts._

  val boot = UnicomplexBoot(config)
    .createUsing {(name, config) => ActorSystem(name, config)}
    .scanResources(withClassPath = false, classPath)
    .start(startupTimeout)
}


class JavaFailedFlowSvcSpec extends TestKit(JavaFailedFlowSvcSpec.boot.actorSystem) with AsyncFlatSpecLike with Matchers {

  "The JavaFailedFlowSvc" should "fail" in {
    import Timeouts._
    Unicomplex(system).uniActor ? SystemState map { state =>
      state shouldBe Failed
    }
  }

  "The JavaFailedFlowSvc" should "expose errors" in {
    import Timeouts._
    (Unicomplex(system).uniActor ? ReportStatus).mapTo[StatusReport] map { report =>
      report.state shouldBe Failed
      val initTry = report.cubes.values.head._2.value.reports.values.head.value
      initTry should matchPattern { case Failure(e: InstantiationException) => }
    }
  }
}
