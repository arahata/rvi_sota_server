/**
 * Copyright: Copyright (C) 2015, Jaguar Land Rover
 * License: MPL-2.0
 */
package org.genivi.sota.core

import java.io.File
import java.security.MessageDigest

import akka.actor.ActorSystem
import akka.event.Logging
import akka.http.scaladsl.common.StrictForm
import akka.http.scaladsl.model.StatusCodes._
import akka.http.scaladsl.model.{StatusCodes, Uri, HttpResponse}
import akka.http.scaladsl.server.{PathMatchers, ExceptionHandler, Directives}
import akka.http.scaladsl.server.PathMatchers.Slash
import Directives._
import akka.parboiled2.util.Base64
import akka.stream.ActorMaterializer
import akka.stream.io.SynchronousFileSink
import akka.util.ByteString
import cats.data.Xor
import eu.timepit.refined._
import eu.timepit.refined.string._
import io.circe.generic.auto._
import org.genivi.sota.core.data._
import org.genivi.sota.core.db.{Packages, Vehicles, InstallRequests}
import org.genivi.sota.rest.Validation._
import org.genivi.sota.rest.{ErrorCode, ErrorRepresentation}
import scala.concurrent.Future
import slick.driver.MySQLDriver.api.Database


object ErrorCodes {
  val ExternalResolverError = ErrorCode( "external_resolver_error" )
}

class VehiclesResource(db: Database)
                      (implicit system: ActorSystem, mat: ActorMaterializer) {

  import system.dispatcher
  import org.genivi.sota.CirceSupport._

  val route = pathPrefix("vehicles") {
    (put & refined[Vehicle.Vin](Slash ~ Segment ~ PathEnd)) { vin =>
          complete(db.run( Vehicles.create(Vehicle(vin)) ).map(_ => NoContent))
        }
    } ~
    path("vehicles") {
      get {
        parameters('regex.?) { (regex) =>
          val query = regex match {
            case Some(r) => Vehicles.searchByRegex(r)
            case _ => Vehicles.list()
          }
          complete{ db.run(query) }
        }
      }
    }
}

class UpdateRequestsResource(db: Database, resolver: ExternalResolverClient, updateService: UpdateService)
                            (implicit system: ActorSystem, mat: ActorMaterializer) {
  import system.dispatcher
  import eu.timepit.refined.string.uuidPredicate
  import org.genivi.sota.core.db.UpdateSpecs
  import UpdateSpec._
  import org.genivi.sota.CirceSupport._

  implicit val _db = db
  val route = pathPrefix("updates") {
    (get & refined[Uuid](Slash ~ Segment ~ PathEnd)) { uuid =>
      complete(db.run(UpdateSpecs.listUpdatesById(uuid)))
    }
  } ~
  path("updates") {
    get {
      complete(updateService.all(db, system.dispatcher))
    } ~
    post {
      entity(as[UpdateRequest]) { req =>
        complete(
          updateService.queueUpdate(
            req,
            pkg => resolver.resolve(pkg.id).map {
              m => m.map { case (v, p) => (v.vin, p) }
            }
          )
        )
      }
    }
  }
}

class PackagesResource(resolver: ExternalResolverClient, db : Database)
                      (implicit system: ActorSystem, mat: ActorMaterializer) {

  import akka.stream.stage._
  import system.dispatcher

  private[this] val log = Logging.getLogger( system, "packagesResource" )

  def digestCalculator(algorithm: String) : PushPullStage[ByteString, String] = new PushPullStage[ByteString, String] {
    val digest = MessageDigest.getInstance(algorithm)

    override def onPush(chunk: ByteString, ctx: Context[String]): SyncDirective = {
      digest.update(chunk.toArray)
      ctx.pull()
    }

    override def onPull(ctx: Context[String]): SyncDirective = {
      if (ctx.isFinishing) ctx.pushAndFinish(Base64.rfc2045().encodeToString(digest.digest(), false))
      else ctx.pull()
    }

    override def onUpstreamFinish(ctx: Context[String]): TerminationDirective = {
      // If the stream is finished, we need to emit the last element in the onPull block.
      // It is not allowed to directly emit elements from a termination block
      // (onUpstreamFinish or onUpstreamFailure)
      ctx.absorbTermination()
    }
  }

  def savePackage( packageId: Package.Id, fileData: StrictForm.FileData )(implicit system: ActorSystem, mat: ActorMaterializer) : Future[(Uri, Long, String)] = {
    val fileName = fileData.filename.getOrElse(s"${packageId.name.get}-${packageId.version.get}")
    val file = new File(fileName)
    val data = fileData.entity.dataBytes
    for {
      size <- data.runWith( SynchronousFileSink( file ) )
      digest <- data.transform(() => digestCalculator("SHA-1")).runFold("")( (acc, data) => acc ++ data)
    } yield (file.toURI().toString(), size, digest)
  }

  val route = pathPrefix("packages") {
    get {
      import org.genivi.sota.CirceSupport._
      parameters('regex.as[String Refined Regex].?) { (regex: Option[String Refined Regex]) =>
        import org.genivi.sota.CirceSupport._
        val query = (regex) match {
          case Some(r) => Packages.searchByRegex(r.get)
          case None => Packages.list
        }
        complete(db.run(query))
      }
    } ~
    (put & refined[Package.ValidName]( Slash ~ Segment) & refined[Package.ValidVersion](Slash ~ Segment ~ PathEnd)).as(Package.Id.apply _) { packageId =>
      formFields('description.?, 'vendor.?, 'file.as[StrictForm.FileData]) { (description, vendor, fileData) =>
        completeOrRecoverWith(
          for {
            _                   <- resolver.putPackage(packageId, description, vendor)
            (uri, size, digest) <- savePackage(packageId, fileData)
            _                   <- db.run(Packages.create( Package(packageId, uri, size, digest, description, vendor) ))
          } yield NoContent
        ) {
          case ExternalResolverRequestFailed(msg, cause) => {
            import org.genivi.sota.CirceSupport._
            log.error( cause, s"Unable to create/update package: $msg" )
            complete( StatusCodes.ServiceUnavailable -> ErrorRepresentation( ErrorCodes.ExternalResolverError, msg ) )
          }
          case e => failWith(e)
        }
      }
    }
  }

}
import org.genivi.sota.core.rvi.{ServerServices, RviClient}
class WebService(registeredServices: ServerServices, resolver: ExternalResolverClient, db : Database)
                (implicit system: ActorSystem, mat: ActorMaterializer, rviClient: RviClient) extends Directives {
  implicit val log = Logging(system, "webservice")

  import io.circe.Json
  import Json.{obj, string}

  val exceptionHandler = ExceptionHandler {
    case e: Throwable =>
      extractUri { uri =>
        log.error(s"Request to $uri errored: $e")
        val entity = obj("error" -> string(e.getMessage()))
        complete(HttpResponse(InternalServerError, entity = entity.toString()))
      }
  }
  val vehicles = new VehiclesResource( db )
  val packages = new PackagesResource(resolver, db)
  val updateRequests = new UpdateRequestsResource(db, resolver, new UpdateService(registeredServices))

  val route = pathPrefix("api" / "v1") {
    handleExceptions(exceptionHandler) {
       vehicles.route ~ packages.route ~ updateRequests.route
    }
  }

}