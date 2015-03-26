package com.cloudera.hue.livy.server.sessions

import java.lang.ProcessBuilder.Redirect
import java.net.URL

import com.cloudera.hue.livy.{LivyConf, Logging, Utils}

import scala.annotation.tailrec
import scala.collection.JavaConversions._
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Future
import scala.io.Source
import scala.util.control.Breaks._

object ProcessSession extends Logging {

  val CONF_LIVY_JAR = "livy.assembly.jar"

  def create(conf: LivyConf, id: String, kind: Session.Kind): Session = {
    val process = startProcess(conf, id, kind)
    new ProcessSession(id, kind, process)
  }

  // Loop until we've started a process with a valid port.
  private def startProcess(conf: LivyConf, id: String, kind: Session.Kind): Process = {
    val args = ArrayBuffer(
      "spark-submit",
      "--class", "com.cloudera.hue.livy.repl.Main"
    )

    sys.env.get("LIVY_REPL_JAVA_OPTS").foreach { case javaOpts =>
      args += "--driver-java-options"
      args += javaOpts
    }

    conf.getOption("livy.repl.driverClassPath").foreach { case extraClassPath =>
      args += "--driver-class-path"
      args += extraClassPath
    }

    args += livyJar(conf)
    args += kind.toString

    val pb = new ProcessBuilder(args)

    val callbackUrl = System.getProperty("livy.server.callback-url")
    pb.environment().put("LIVY_CALLBACK_URL", f"$callbackUrl/sessions/$id/callback")
    pb.environment().put("LIVY_PORT", "0")

    pb.redirectOutput(Redirect.PIPE)
    pb.redirectError(Redirect.INHERIT)

    pb.start()
  }

  private def livyJar(conf: LivyConf): String = {
    conf.getOption(CONF_LIVY_JAR).getOrElse {
      Utils.jarOfClass(getClass).head
    }
  }
}

private class ProcessSession(id: String, kind: Session.Kind, process: Process) extends WebSession(id, kind) {

  val stdoutThread = new Thread {
    override def run() = {
      val regex = """Starting livy-repl on (https?://.*)""".r

      val lines = Source.fromInputStream(process.getInputStream).getLines()

      // Loop until we find the ip address to talk to livy-repl.
      @tailrec
      def readUntilURL(): Boolean = {
        if (lines.hasNext) {
          val line = lines.next()
          println(line)

          line match {
            case regex(url_) =>
              url = new URL(url_)
              true
            case _ => readUntilURL()
          }
        } else {
          false
        }
      }

      if (readUntilURL()) {
        for (line <- lines) {
          println(line)
        }
      }
    }
  }

  stdoutThread.setName("process session stdout reader")
  stdoutThread.setDaemon(true)
  stdoutThread.start()

  override def stop(): Future[Unit] = {
    super.stop() andThen { case r =>
      // Make sure the process is reaped.
      process.waitFor()
      stdoutThread.join()

      r
    }
  }
}
