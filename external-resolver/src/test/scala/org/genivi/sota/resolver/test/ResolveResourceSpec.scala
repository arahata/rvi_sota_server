package org.genivi.sota.resolver.test

import akka.http.scaladsl.model.StatusCodes
import eu.timepit.refined.Refined
import io.circe.generic.auto._
import org.genivi.sota.marshalling.CirceMarshallingSupport._
import org.genivi.sota.resolver.common.Errors.Codes
import org.genivi.sota.resolver.components.Component
import org.genivi.sota.resolver.packages.{Package, PackageFilter}
import org.genivi.sota.resolver.vehicles.Vehicle
import org.genivi.sota.rest.{ErrorRepresentation, ErrorCodes}


class ResolveResourceWordSpec extends ResourceWordSpec {

  val pkgName = "resolvePkg"

  "Resolve resource" should {

    "return all VINs if the filter is trivially true" in {

      // Add some vehicles.
      val vins = List(
        "00RESOLVEVIN12345",
        "01RESOLVEVIN12345",
        "10RESOLVEVIN12345",
        "11RESOLVEVIN12345")

      vins map addVehicleOK

      // Add a package.
      addPackageOK("resolvePkg", "0.0.1", None, None)

      // Add a trival filter that lets all vins through.
      addFilterOK("truefilter", "TRUE")
      addPackageFilterOK(pkgName, "0.0.1", "truefilter")

      resolveOK(pkgName, "0.0.1", vins)
    }

    "support filtering by VIN" in {

      // Add another filter.
      addFilterOK("0xfilter", s"""vin_matches "^00.*" OR vin_matches "^01.*"""")
      addPackageFilterOK(pkgName, "0.0.1", "0xfilter")

      resolveOK(pkgName, "0.0.1",
        List("00RESOLVEVIN12345", "01RESOLVEVIN12345"))
    }

    "support filtering by installed packages on VIN" in {

      // Delete the previous filter and add another one which uses
      // has_package instead.

      deletePackageFilterOK(pkgName, "0.0.1", "0xfilter")
      addPackageOK("apa",  "1.0.0", None, None)
      addPackageOK("bepa", "1.0.0", None, None)
      installPackageOK("10RESOLVEVIN12345", "apa", "1.0.0")
      installPackageOK("11RESOLVEVIN12345", "apa", "1.0.0")
      installPackageOK("00RESOLVEVIN12345", "bepa", "1.0.0")
      addFilterOK("1xfilter", s"""has_package "^a.*" "1.*"""")
      addPackageFilterOK(pkgName, "0.0.1", "1xfilter")
      resolveOK(pkgName, "0.0.1",
        List("10RESOLVEVIN12345", "11RESOLVEVIN12345"))
    }

    "support filtering by hardware components on VIN" in {

      // Delete the previous filter and add another one which uses
      // has_component instead.

      deletePackageFilterOK(pkgName, "0.0.1", "1xfilter")
      addComponentOK(Refined("jobby0"), "nice")
      addComponentOK(Refined("jobby1"), "nice")
      installComponentOK(Refined("00RESOLVEVIN12345"), Refined("jobby0"))
      installComponentOK(Refined("01RESOLVEVIN12345"), Refined("jobby0"))
      installComponentOK(Refined("11RESOLVEVIN12345"), Refined("jobby1"))
      addFilterOK("components", s"""has_component "^.*y0"""")
      addPackageFilterOK(pkgName, "0.0.1", "components")
      resolveOK(pkgName, "0.0.1",
        List("00RESOLVEVIN12345", "01RESOLVEVIN12345"))
    }

    "return no VINs if the filter is trivially false" in {

      // Add trivially false filter.
      addFilterOK("falsefilter", "FALSE")
      addPackageFilterOK(pkgName, "0.0.1", "falsefilter")

      resolveOK(pkgName, "0.0.1", List())
    }

    "fail if a non-existing package name is given" in {

      resolve("resolvePkg2", "0.0.1") ~> route ~> check {
        status shouldBe StatusCodes.NotFound
        responseAs[ErrorRepresentation].code shouldBe Codes.PackageNotFound
      }
    }

    "fail if a non-existing package version is given" in {

      resolve(pkgName, "0.0.2") ~> route ~> check {
        status shouldBe StatusCodes.NotFound
        responseAs[ErrorRepresentation].code shouldBe Codes.PackageNotFound
      }
    }

    "return a string that the core server can parse" in {

      deletePackageFilter(pkgName, "0.0.1", "falseFilter") ~> route ~> check {
        status shouldBe StatusCodes.OK
        resolve(pkgName, "0.0.1") ~> route ~> check {
          status shouldBe StatusCodes.OK

          responseAs[io.circe.Json].noSpaces shouldBe
            s"""[["00RESOLVEVIN12345",[{"version":"0.0.1","name":"resolvePkg"}]],["01RESOLVEVIN12345",[{"version":"0.0.1","name":"resolvePkg"}]]]"""

          responseAs[Map[Vehicle.Vin, Set[Package.Id]]] shouldBe
            Map(Refined("00RESOLVEVIN12345") -> Set(Package.Id(Refined(pkgName), Refined("0.0.1"))),
                Refined("01RESOLVEVIN12345") -> Set(Package.Id(Refined(pkgName), Refined("0.0.1"))))

        }
      }
    }
  }
}

class ResolveResourcePropSpec extends ResourcePropSpec {

  import ArbitraryFilter.arbFilter
  import ArbitraryVehicle.arbVehicle
  import akka.http.scaladsl.model.StatusCodes
  import org.genivi.sota.resolver.resolve.ResolveFunctions.makeFakeDependencyMap
  import org.genivi.sota.resolver.filters._
  import org.genivi.sota.resolver.filters.FilterAST.{parseValidFilter, query}
  import org.scalacheck.Prop.{True => _, _}
  import io.circe.generic.auto._

  ignore("Resolve should give back the same thing as if we filtered with the filters") {

    forAll() { (
      vs: Seq[Vehicle],   // The available vehicles.
      p : Package,        // The package we want to install.
      ps: Seq[Package],   // Other packages in the system.
      fs: Seq[Filter])    // Filters to be associated to the package we
                          // want to install.
        => {

          // Add some new vehicles.
          vs map (v => addVehicleOK(v.vin.get))

          // Add some new packages.
          (p +: ps) map (q => addPackageOK(q.id.name.get, q.id.version.get, q.description, q.vendor))

          // Add some new filters.
          fs map (f => addFilterOK(f.name.get, f.expression.get))

          // Associate the filters to the package we want to install.
          fs map (f => addPackageFilterOK(p.id.name.get, p.id.version.get, f.name.get))

          // The resolver should give back the same VINs as if...
          listVehicles ~> route ~> check {
            status === StatusCodes.OK
            val allVehicles = responseAs[Seq[Vehicle]]

            resolve(p.id.name.get, p.id.version.get) ~> route ~> check {
              status === StatusCodes.OK
              val result = responseAs[Map[Vehicle.Vin, Seq[Package.Id]]]
              classify(result.toList.length > 0, "more than zero", "zero") {
                result === makeFakeDependencyMap(Package.Id(p.id.name, p.id.version),

                    // ... we filtered the list of all VINs by the boolean
                    // predicate that arises from the combined filter
                    // queries.

                    // XXX: Deal with installed packages and components properly.
                    allVehicles.map(v => (v, (List[Package.Id](), List[Component.PartNumber]()))).filter(query
                      (fs.map(_.expression).map(parseValidFilter)
                         .foldLeft[FilterAST](True)(And))).map(_._1))
              }
            }
          }

        }
    }

  }

}