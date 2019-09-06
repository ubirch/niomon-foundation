package com.ubirch.niomon.base

import com.typesafe.scalalogging.StrictLogging
import com.ubirch.niomon.healthcheck.{Checks, HealthCheckServer}
import io.prometheus.client.CollectorRegistry
import net.manub.embeddedkafka.EmbeddedKafka
import org.apache.kafka.common.serialization.StringDeserializer
import org.scalatest.{BeforeAndAfterAll, BeforeAndAfterEach, FlatSpec, Matchers}

import scala.concurrent.duration._
import scala.concurrent.{Await, Awaitable}

class NioMicroserviceTest extends FlatSpec with Matchers with EmbeddedKafka with StrictLogging with BeforeAndAfterAll with BeforeAndAfterEach {
  var healthCheckServer = new HealthCheckServer(Map(), Map(), "localhost")
  healthCheckServer.setReadinessCheck(Checks.system())

  "NioMicroservice" should "work" in {
    withRunningKafka {
      var n = 0
      val microservice = NioMicroserviceLive[String, String]("test", new NioMicroserviceLogic.Simple(_) {
        override def process(input: String): (String, String) = {
          n += 1
          s"foobar$n" -> "default"
        }
      }, healthCheckServer)

      val control = microservice.run

      publishStringMessageToKafka("foo", "eins")
      publishStringMessageToKafka("foo", "zwei")
      publishStringMessageToKafka("foo", "drei")

      val records = consumeNumberStringMessagesFrom("bar", 3)

      records.size should equal(3)
      records should contain allOf("foobar1", "foobar2", "foobar3")

      val readyStatus = await(healthCheckServer.ready())

      println(readyStatus)

      await(control.drainAndShutdown()(microservice.system.dispatcher))
    }
  }

  it should "send error to error topic and continue to work if error topic configured" in {
    withRunningKafka {
      // NOTE: look at application.conf in test resources for relevant config
      val microservice = NioMicroserviceLive[String, String]("test-with-error", new NioMicroserviceLogic.Simple(_) {
        var first = true

        override def process(input: String): (String, String) = {
          if (first) {
            first = false
            throw new RuntimeException("foobar")
          } else {
            "barbaz" -> "default"
          }
        }
      }, healthCheckServer)

      val control = microservice.run

      publishStringMessageToKafka("foo", "quux")
      publishStringMessageToKafka("foo", "kex")

      implicit val d: StringDeserializer = new StringDeserializer
      val records = consumeNumberMessagesFromTopics[String](Set("bar", "error"), 2)

      records.size should equal(2)
      records.keys should contain only ("bar", "error")
      records("bar") should contain only "barbaz"
      records("error") should contain only """{"error":"RuntimeException: foobar","causes":[],"microservice":"test-with-error","requestId":null}"""

      await(control.drainAndShutdown()(microservice.system.dispatcher))
    }
  }

  it should "shutdown on error with no error topic configured" in {
    withRunningKafka {
      val microservice = NioMicroserviceLive[String, String]("test1", new NioMicroserviceLogic.Simple(_) {
        override def process(input: String): (String, String) = {
          throw new RuntimeException("foobar")
        }
      }, healthCheckServer)
      val after = microservice.runUntilDone

      publishStringMessageToKafka("foo", "quux")

      val res = await(after.failed)

      res shouldBe a[RuntimeException]
      res.getMessage should equal("foobar")
    }
  }

  def await[T](x: Awaitable[T]): T = Await.result(x, Duration.Inf)

  override def beforeEach(): Unit = {
    CollectorRegistry.defaultRegistry.clear()
  }

  // comment this region out to get kafka per test
  // region OneKafka
//  override def beforeAll(): Unit = {
//    EmbeddedKafka.start()
//  }
//
//  override def afterAll(): Unit = {
//    EmbeddedKafka.stop()
//  }
//
//  def withRunningKafka(body: => Any): Any = body
  // endregion
}
