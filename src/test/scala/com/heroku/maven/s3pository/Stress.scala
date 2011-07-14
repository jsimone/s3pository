package com.heroku.maven.s3pository

import com.heroku.maven.s3pository.S3rver._

import java.util.concurrent.atomic.AtomicInteger
import com.twitter.finagle.stats.SummarizingStatsReceiver
import com.twitter.finagle.Service
import com.twitter.finagle.builder.ClientBuilder
import com.twitter.finagle.http.Http
import java.net.{InetSocketAddress, URI}
import com.twitter.util.{Time, Future, MapMaker}
import org.jboss.netty.handler.codec.http._
import org.jboss.netty.handler.codec.http.HttpHeaders.Names._
import scala.xml._
import util.{Random}
import java.io.{FileOutputStream, File}

/**
 * Set S3_KEY and S3_SECRET as env vars
 * args are
 * group or proxy to hit (http://somehost:80/somepath/ ) needs a trailing slash please
 * concurrency
 * totalRequests to make
 * S3 bucket to get a list of keys to concatenate to /somepath to get a good path to hit
 */
object Stress {

  def main(args: Array[String]) {
    val uri = new URI(args(0))
    val concurrency = args(1).toInt
    val totalRequests = args(2).toInt
    val bucket = args(3)

    val errors = new AtomicInteger(0)
    val responses = MapMaker[HttpResponseStatus, AtomicInteger] {
      config =>
        config.compute {
          k =>
            new AtomicInteger(0)
        }
    }

    val listClient: Service[HttpRequest, HttpResponse] = ClientBuilder()
      .codec(Http())
      .hosts(new InetSocketAddress("s3.amazonaws.com", 80))
      .hostConnectionCoresize(concurrency)
      .retries(3)
      .hostConnectionLimit(concurrency)
      .build()


    val listRequest = get("/").s3headers(bucket)

    //get the keys in the bucket
    val xResp = XML.loadString(listClient(listRequest).get().getContent.toString("UTF-8"))
    //to a list
    val keys = (xResp \\ "Contents" \\ "Key") map (_.text)
    // add some 404s
    val badKeys = (1 to (keys.size / 4)).toList map ("some/bad/random/artifact" + _.toString)
    // get enough keys for the run
    var keyList = Stream.continually(Random.shuffle(keys ++ badKeys).toStream).flatten.take(totalRequests).toList


    listClient.release()


    val statsReceiver = new SummarizingStatsReceiver

    val client: Service[HttpRequest, HttpResponse] = ClientBuilder()
      .codec(Http())
      .hosts(new InetSocketAddress(uri.getHost, uri.getPort))
      .hostConnectionCoresize(concurrency)
      .reportTo(statsReceiver)
      .retries(3)
      .hostConnectionLimit(concurrency)
      .build()

    val completedRequests = new AtomicInteger(0)

    val requests = Future.parallel(concurrency) {
      Future.times(totalRequests / concurrency) {
        val request = get(uri.getPath + keyList.head).headers(Map(HOST -> uri.getHost))
        //dont particularly care about thread safety
        keyList = keyList.tail
        println("making request")
        client(request
        ) onSuccess {
          response =>
            println("onSuccess")
            responses(response.getStatus).incrementAndGet()
            if (response.getStatus.getCode == 200) {
              val devnull = new File("/dev/null")
              val stream = new FileOutputStream(devnull)
              response.getContent.readBytes(stream, response.getHeader(CONTENT_LENGTH).toInt)
              stream.close()
              println("read")
            }
        } handle {
          case e =>
            errors.incrementAndGet()
        } ensure {
          completedRequests.incrementAndGet()
        }
      }
    }

    val start = Time.now

    Future.join(requests) ensure {
      client.release()

      val duration = start.untilNow
      println("%20s\t%s".format("Status", "Count"))
      for ((status, stat) <- responses)
        println("%20s\t%d".format(status, stat.get))
      println("================")
      println("%d requests completed in %dms (%f requests per second)".format(
        completedRequests.get, duration.inMilliseconds,
        totalRequests.toFloat / duration.inMillis.toFloat * 1000))
      println("%d errors".format(errors.get))

      println("stats")
      println("=====")

      statsReceiver.print()
    }
    println("Done Main")
  }

}