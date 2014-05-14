/*
 * Copyright © 2011-2013 the spray project <http://spray.io>
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

package spray.site

import akka.event.Logging._
import shapeless._
import spray.routing.directives.{ DirectoryListing, LogEntry }
import spray.httpx.marshalling.Marshaller
import spray.httpx.TwirlSupport._
import spray.http._
import spray.routing._
import html._
import StatusCodes._

import geotrellis._
import geotrellis.process
import geotrellis.source.ValueSource
import geotrellis.services.ColorRampMap
import geotrellis.render.{ ColorRamp, ColorRamps }

class SiteServiceActor(settings: SiteSettings) extends HttpServiceActor {

  // format: OFF
  def receive = runRoute {
    dynamicIf(settings.devMode) {
      // for proper support of twirl + sbt-revolver during development
      get {
        path("favicon.ico") {
          getFromResource("theme/favicon.ico/")
        } ~
        path("favicon.png") {
          complete(NotFound) // fail early in order to prevent error response logging
        } ~
        pathPrefix("gt") {
          demoRoute
        } ~
        logRequestResponse(showErrorResponses _) {
          getFromResourceDirectory("theme") ~
            pathPrefix("_images") {
              getFromResourceDirectory("sphinx/json/_images")
            } ~
            logRequest(showRequest _) {
              pathSingleSlash {
                complete(page(home()))
              } ~
                pathPrefix("documentation" / Segment / "api") {
                  version =>
                    val dir = s"api/$version/"
                    pathEnd {
                      redirect(s"/documentation/$version/api/", MovedPermanently)
                    } ~
                      pathSingleSlash {
                        getFromResource(dir + "index.html")
                      } ~
                      getFromResourceDirectory(dir)
                } ~
                pathSuffixTest(Slash) {
                  path("home" /) {
                    redirect("/", MovedPermanently)
                  } ~
                    path("index" /) {
                      complete(page(index()))
                    } ~
                    pathPrefixTest("documentation" / !IntNumber ~ !PathEnd ~ Rest) {
                      subUri =>
                        redirect("/documentation/" + Main.settings.mainVersion + '/' + subUri, MovedPermanently)
                    } ~
                    requestUri {
                      uri =>
                        val path = uri.path.toString
                        "-RC[123]/".r.findFirstIn(path) match {
                          case Some(found) => redirect(uri.withPath(Uri.Path(path.replace(found, "-RC4/"))), MovedPermanently)
                          case None => reject
                        }
                    } ~
                    sphinxNode {
                      node =>
                        complete(page(document(node), node))
                    }
                } ~
                unmatchedPath {
                  ump =>
                    redirect(ump.toString + "/", MovedPermanently)
                }
            }
        }
      }
    }
  }

  val demoRoute = 
  pathPrefix("weighted-overlay"){
    path("wms") {
      parameters(
        'SERVICE, 'REQUEST, 'VERSION, 'FORMAT ? "",
        'BBOX, 'HEIGHT.as[Int], 'WIDTH.as[Int],
        'LAYERS, 'WEIGHTS,
        'PALETTE ? "ff0000,ffff00,00ff00,0000ff",
        'COLORS.as[Int] ? 4, 'BREAKS ? "",
        'COLORRAMP ? "colorRamp",
        'MASK ? "", 'SRS ? "", 'STYLES ? ""
      ) {
        (_, _, _, _, bbox, cols, rows, layersString, weightsString,
         palette, colors, breaksString, colorRamp, mask, srs, styles) => {
          val defaultRamp =
            ColorRamp.createWithRGBColors(0xBD4E2E, 0xC66E4B, 0xD08B6C, 0xDCAD92, 0xE9D3C1, 0xCCDBE0, 0xA8C5D8, 0x83B2D1, 0x5DA1CA, 0x2791C3)

          //println(s"TILE: $bbox, BREAKS: $breaksString")
          val re = RasterExtent(Extent.fromString(bbox), cols, rows)
          val layers = layersString.split(",")
          val weights = weightsString.split(",").map(_.toInt)

          val model = Model.weightedOverlay(layers, weights, Some(re))

          val breaks = breaksString.split(",").map(_.toInt)
          val ramp = {
            val cr = ColorRampMap.getOrElse(colorRamp, defaultRamp)
            if(cr.toArray.length < breaks.length) { cr.interpolate(breaks.length) }
            else { cr }
          }

          val png:ValueSource[Png] = model.renderPng(ramp, breaks)

          png.run match {
            case process.Complete(img, h) =>
              respondWithMediaType(MediaTypes.`image/png`) {
                complete(img)
              }
            case process.Error(message, trace) =>
              println(message)
              println(trace)
              println(re)

              failWith(new RuntimeException(message))
          }
        }
      }
    } ~
    path("breaks") {
      parameters('layers,
        'weights,
        'numBreaks.as[Int],
        'mask ? "") {
        (layersParam,weightsParam,numBreaks,mask) => {        
          val layers = layersParam.split(",")
          val weights = weightsParam.split(",").map(_.toInt)


          Model.weightedOverlay(layers,weights,None)
            .classBreaks(numBreaks)
            .run match {
            case process.Complete(breaks, _) =>
              val breaksArray = breaks.mkString("[", ",", "]")
              val json = s"""{ "classBreaks" : $breaksArray }"""
              complete(json)
            case process.Error(message,trace) =>
              failWith(new RuntimeException(message))
          }
        }
      }
    }
  } ~
  pathPrefix("hillshade"){
    path("wms"){
      parameters(
        'SERVICE, 'REQUEST, 'VERSION, 'FORMAT ? "",
        'BBOX, 'HEIGHT.as[Int], 'WIDTH.as[Int],
        'LAYERS,
        'PALETTE ? "ff0000,ffff00,00ff00,0000ff",
        'COLORS.as[Int] ? 4, 
        'BREAKS ? "0,10,20,30,40,50,60,70,80,90,100,110,120,127",
        'COLORRAMP ? "",
        'MASK ? "", 'SRS ? "", 'STYLES ? "",
        'AZIMUTH.as[Double], 'ALTITUDE.as[Double], 'ZFACTOR.as[Double]) {
        (_, _, _, _, bbox, cols, rows, layersString,
         palette, colors, breaksString, colorRamp, mask, srs, styles,
         azimuth , altitude, zFactor) => {
          println(s"HILL TILE: $bbox, BREAKS: $breaksString")
          
          var darkGreenToGreen = ColorRamp.createWithRGBColors(
            0x3A6D35, 0x1CA049, 0x4BAF48, 0x81C561, 0xA0CF88, 0xBEDBAD)

          val re = RasterExtent(Extent.fromString(bbox), cols, rows)
          val layers = layersString

          val model = Model.hillshade(layers, Some(re), azimuth, altitude, zFactor)
          val breaks = breaksString.split(",").map(_.toInt)
          val ramp = {
            val cr = ColorRampMap.getOrElse(colorRamp, darkGreenToGreen)
            if (cr.toArray.length < breaks.length) 
              cr.interpolate(breaks.length)
            else 
              cr
          }

          val png:ValueSource[Png] = model.renderPng(ramp, breaks)

          png.run match {
            case process.Complete(img, h) =>
              respondWithMediaType(MediaTypes.`image/png`) {
                complete(img)
              }
            case process.Error(message, trace) =>
              println(message)
              println(trace)
              println(re)

              failWith(new RuntimeException(message))
          }
        }
      }        
    }
  } ~
  pathPrefix("transit"){
    path("wms") {
      import Uri._
      val uri = Uri("http://transit.geotrellis.com/api/travelshed/wms")
      parameterSeq { params =>        
        redirect(
          uri.withQuery(params.map(t=> (t._1.toLowerCase, t._2)): _*), 
          StatusCodes.TemporaryRedirect
        )
      } 
    }
  } ~
  path("ping"){
    complete("PONG")
  } ~
  unmatchedPath { ump => //anything hitting the demo subroute will have standard 404
    complete(StatusCodes.NotFound)
  }
  // format: ON

  val sphinxNode = path(Rest).map(Main.root.find).flatMap[ContentNode :: HNil] {
    case None       ⇒ complete(NotFound, page(error404()))
    case Some(node) ⇒ provide(node)
  }

  def showRequest(request: HttpRequest) = LogEntry(request.uri, InfoLevel)

  def showErrorResponses(request: HttpRequest): Any ⇒ Option[LogEntry] = {
    case HttpResponse(OK, _, _, _)       ⇒ None
    case HttpResponse(NotFound, _, _, _) ⇒ Some(LogEntry("404: " + request.uri, WarningLevel))
    case r @ HttpResponse(Found | MovedPermanently, _, _, _) ⇒
      Some(LogEntry(s"${r.status.intValue}: ${request.uri} -> ${r.header[HttpHeaders.Location].map(_.uri.toString).getOrElse("")}", WarningLevel))
    case response ⇒ Some(
      LogEntry("Non-200 response for\n  Request : " + request + "\n  Response: " + response, WarningLevel))
  }

  def showRepoResponses(repo: String)(request: HttpRequest): HttpResponsePart ⇒ Option[LogEntry] = {
    case HttpResponse(OK, _, _, _) ⇒ Some(LogEntry(repo + " 200: " + request.uri, InfoLevel))
    case ChunkedResponseStart(HttpResponse(OK, _, _, _)) ⇒ Some(LogEntry(repo + " 200 (chunked): " + request.uri, InfoLevel))
    case HttpResponse(NotFound, _, _, _) ⇒ Some(LogEntry(repo + " 404: " + request.uri))
    case _ ⇒ None
  }

  implicit val ListingMarshaller: Marshaller[DirectoryListing] =
    Marshaller.delegate(MediaTypes.`text/html`) { (listing: DirectoryListing) ⇒
      listing.copy(
        files = listing.files.filterNot(file ⇒
          file.getName.startsWith(".") || file.getName.startsWith("archetype-catalog")))
    }(DirectoryListing.DefaultMarshaller)
}
