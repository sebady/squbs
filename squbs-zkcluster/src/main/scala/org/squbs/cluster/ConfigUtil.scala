package org.squbs.cluster

import java.net.NetworkInterface

import com.typesafe.config.{Config, ConfigException}

import scala.collection.JavaConversions._
import scala.collection.mutable
import scala.concurrent.duration.FiniteDuration
import scala.util.Try

/**
 * Created by zhuwang on 1/28/15.
 */
object ConfigUtil {

  implicit class RichConfig(val underlying: Config) extends AnyVal {

    def getOptionalString(path: String): Option[String] = {
      try {
        Option(underlying.getString(path))
      } catch {
        case e: ConfigException.Missing => None
      }
    }

    def getOptionalStringList(path: String): Option[Seq[String]] = {
      val list =
        try {
          Some(underlying.getStringList(path))
        } catch {
          case e: ConfigException.Missing => None
        }
      list map (_.toSeq)
    }


    def getOptionalInt(path: String): Option[Int] = {
      try {
        Option(underlying.getInt(path))
      } catch {
        case e: ConfigException.Missing => None
      }
    }

    def getOptionalBoolean(path: String): Option[Boolean] = {
      try {
        Option(underlying.getBoolean(path))
      } catch {
        case e: ConfigException.Missing => None
      }
    }

    def getOptionalConfig(path: String): Option[Config] = {
      try {
        Some(underlying.getConfig(path))
      } catch {
        case e: ConfigException.Missing => None
      }
    }


    def getOptionalConfigList(path: String): Option[Seq[Config]] = {
      val list =
        try {
          Some(underlying.getConfigList(path))
        } catch {
          case e: ConfigException.Missing => None
        }
      list map (_.toSeq)
    }

    def getOptionalDuration(path: String): Option[FiniteDuration] = {
      import scala.concurrent.duration._
      Try(Duration.create(underlying.getDuration(path, MILLISECONDS), MILLISECONDS)).toOption
    }
  }

  def ipv4 = {
    val addresses = mutable.Set.empty[String]
    val enum = NetworkInterface.getNetworkInterfaces
    while (enum.hasMoreElements) {
      val addrs = enum.nextElement.getInetAddresses
      while (addrs.hasMoreElements) {
        addresses += addrs.nextElement.getHostAddress
      }
    }

    val pattern = "\\d+\\.\\d+\\.\\d+\\.\\d+".r
    val matched = addresses.filter({
      case pattern() => true
      case _ => false
    })
      .filter(_ != "127.0.0.1")

    matched.head
  }

}
