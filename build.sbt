import com.typesafe.sbt.packager.docker.DockerPlugin.autoImport.dockerEntrypoint
import com.typesafe.sbt.packager.docker.{ Cmd, DockerChmodType }

Scope.Global / scalaVersion := "2.13.6"

// *****************************************************************************
// Projects
// *****************************************************************************
//
lazy val `akka-typed-user` =
  project
    .in(file("."))
    .enablePlugins(GitVersioning)
    .aggregate(`core`, `user-api`, `user-svc`, `user-bench`)
    .settings(settings)
    .settings(
      Compile / unmanagedSourceDirectories := Seq.empty,
      Test / unmanagedSourceDirectories := Seq.empty,
      publishArtifact := false
    )

lazy val `core` =
  (project in file("modules/core"))
    .settings(settings)
    .enablePlugins(AkkaGrpcPlugin)
    .settings(
      akkaGrpcCodeGeneratorSettings += "server_power_apis"
    )
    .settings(
      libraryDependencies ++= Seq(
        library.akkaDiscovery,
        library.akkaClusterTyped,
        library.akkaPersistenceTyped,
        library.akkaClusterShardingTyped,
        library.akkaHttp,
        library.akkaHttp2Support,
        library.akkaHttpCirce,
        library.akkaKryo,
        library.akkaSlf4j,
        library.akkaPersistenceQuery,
        library.akkaPersistenceCassandra,
        library.akkaStreamKafka,
        library.akkaProjectionEventsourced,
        library.akkaProjectionCassandra,
        library.akkaProjectionKafka,
        library.circeGeneric,
        library.circeGenericExtras,
        library.circeRefined,
        library.catsCore,
        library.logbackCore,
        library.logbackClassic,
        library.bcrypt,
        library.pureconfig,
        library.refinedPureconfig,
        library.pauldijouJwtCirce,
        library.chimney,
        library.scalapbRuntimeGrpc,
        library.akkaHttpTestkit % Test,
        library.akkaTestkit     % Test,
        library.scalaTest       % Test
      )
    )

lazy val `user-api` =
  (project in file("modules/user-api"))
    .enablePlugins(AkkaGrpcPlugin)
    .settings(settings)
    .settings(
      akkaGrpcCodeGeneratorSettings += "server_power_apis",
      akkaGrpcCodeGeneratorSettings += "grpc",
      Compile / guardrailTasks := List(
        ScalaServer(
          file(s"${baseDirectory.value}/src/main/openapi/UserOpenApi.yaml"),
          pkg = "com.jc.user.api.openapi",
          imports = List("com.jc.user.domain.circe._"),
          tracing = false,
          customExtraction = true
        )
      )
    )
    .settings(Compile / unmanagedResourceDirectories += baseDirectory.value / "src" / "main" / "openapi")
    .settings(
      libraryDependencies ++= Seq(
        library.scalaPbRuntime,
        library.akkaHttp,
        library.akkaHttp2Support,
        library.akkaHttpCirce,
        library.akkaHttpSprayJson,
        library.circeGeneric,
        library.circeRefined,
        library.catsCore,
        library.scalapbRuntimeGrpc
      )
    )
    .dependsOn(`core`)

lazy val `user-svc` =
  (project in file("modules/user-svc"))
    .enablePlugins(JavaAppPackaging, JavaAgent, DockerPlugin)
    .settings(settings ++ dockerSettings ++ javaAgentsSettings)
    .settings(
      libraryDependencies ++= Seq(
        library.scalaPbRuntime,
        library.akkaDiscovery,
        library.akkaClusterTyped,
        library.akkaPersistenceTyped,
        library.akkaClusterShardingTyped,
        library.akkaHttp,
        library.akkaHttp2Support,
        library.akkaHttpCirce,
        library.akkaHttpSprayJson,
        library.tapirSwaggerUiAkkaHttp,
        library.akkaKryo,
        library.akkaSlf4j,
        library.akkaPersistenceQuery,
        library.akkaPersistenceCassandra,
        library.akkaStreamKafka,
        library.akkaProjectionEventsourced,
        library.akkaProjectionCassandra,
        library.akkaProjectionKafka,
        library.akkaDiscoveryKubernetes,
        library.akkaManagementClusterBootstrap,
        library.akkaManagementClusterHttp,
        library.circeGeneric,
        library.circeRefined,
        library.catsCore,
        library.logbackCore,
        library.logbackClassic,
        library.bcrypt,
        library.pureconfig,
        library.refinedPureconfig,
        library.elastic4sClientAkka,
        library.elastic4sCirce,
        library.pauldijouJwtCirce,
        library.kamonAkka,
        library.kamonAkkaHttp,
        library.kamonPrometheus,
        library.kamonSystem,
        library.kamonCassandra,
        library.chimney,
        library.akkaHttpTestkit % Test,
        library.akkaTestkit     % Test,
        library.scalaTest       % Test
      )
    )
    .aggregate(`user-api`)
    .dependsOn(`user-api`)

lazy val `user-bench` =
  (project in file("modules/user-bench"))
    .settings(settings)
    .enablePlugins(GatlingPlugin)
    .settings(
      dependencyOverrides += library.scalapbRuntimeGrpc, // gatlig grpc issue
      libraryDependencies ++= Seq(
        library.randomDataGenerator,
        library.gatlingCharts,
        library.gatlingTest,
        library.gatlingGrpc
      )
    )
    .dependsOn(`user-api`)

// *****************************************************************************
// Library dependencies
// *****************************************************************************

lazy val library =
  new {

    object Version {
      val akka                     = "2.6.16"
      val akkaHttp                 = "10.2.6"
      val akkaHttpJson             = "1.37.0"
      val akkaPersistenceCassandra = "1.0.5"
      val akkaStreamKafka          = "2.1.1"
      val akkaProjection           = "1.2.2"
      val akkaManagement           = "1.1.1"
      val circe                    = "0.14.1"
      val logback                  = "1.2.5"
      val bcrypt                   = "4.3.0"
      val elastic4s                = "7.13.0"
      val pureconfig               = "0.16.0"
      val chimney                  = "0.6.1"
      val akkaKryo                 = "2.2.0"
      val pauldijouJwt             = "5.0.0"
      val refined                  = "0.9.27"
      val tapir                    = "0.18.3"
      val cats                     = "2.6.1"

      val kamon           = "2.2.3"
      val kamonPrometheus = kamon
      val kamonAkka       = kamon
      val kamonAkkaHttp   = kamon
      val kamonKanela     = "1.0.11"

      val randomDataGenerator = "2.9"
      val scalaTest           = "3.2.9"
      val gatling             = "3.5.1"
      val gatlingGrpc         = "0.11.1"
    }

    val akkaDiscoveryKubernetes        = "com.lightbend.akka.discovery"  %% "akka-discovery-kubernetes-api"     % Version.akkaManagement
    val akkaManagementClusterBootstrap = "com.lightbend.akka.management" %% "akka-management-cluster-bootstrap" % Version.akkaManagement
    val akkaManagementClusterHttp      = "com.lightbend.akka.management" %% "akka-management-cluster-http"      % Version.akkaManagement

    val akkaPersistenceQuery     = "com.typesafe.akka" %% "akka-persistence-query"     % Version.akka
    val akkaPersistenceCassandra = "com.typesafe.akka" %% "akka-persistence-cassandra" % Version.akkaPersistenceCassandra
    val akkaDiscovery            = "com.typesafe.akka" %% "akka-discovery"             % Version.akka

    val akkaClusterTyped         = "com.typesafe.akka" %% "akka-cluster-typed"          % Version.akka
    val akkaPersistenceTyped     = "com.typesafe.akka" %% "akka-persistence-typed"      % Version.akka
    val akkaClusterShardingTyped = "com.typesafe.akka" %% "akka-cluster-sharding-typed" % Version.akka

    val akkaProjectionCassandra    = "com.lightbend.akka" %% "akka-projection-cassandra"    % Version.akkaProjection
    val akkaProjectionEventsourced = "com.lightbend.akka" %% "akka-projection-eventsourced" % Version.akkaProjection
    val akkaProjectionKafka        = "com.lightbend.akka" %% "akka-projection-kafka"        % Version.akkaProjection

    val akkaKryo          = "io.altoo"          %% "akka-kryo-serialization" % Version.akkaKryo
    val akkaHttp          = "com.typesafe.akka" %% "akka-http"               % Version.akkaHttp
    val akkaHttp2Support  = "com.typesafe.akka" %% "akka-http2-support"      % Version.akkaHttp
    val akkaHttpSprayJson = "com.typesafe.akka" %% "akka-http-spray-json"    % Version.akkaHttp
    val akkaHttpCirce     = "de.heikoseeberger" %% "akka-http-circe"         % Version.akkaHttpJson
    val akkaHttpTestkit   = "com.typesafe.akka" %% "akka-http-testkit"       % Version.akkaHttp
    val akkaSlf4j         = "com.typesafe.akka" %% "akka-slf4j"              % Version.akka

    val akkaStreamKafka = "com.typesafe.akka" %% "akka-stream-kafka" % Version.akkaStreamKafka

    val akkaTestkit = "com.typesafe.akka" %% "akka-testkit" % Version.akka

    val circeGeneric       = "io.circe" %% "circe-generic"        % Version.circe
    val circeGenericExtras = "io.circe" %% "circe-generic-extras" % Version.circe
    val circeRefined       = "io.circe" %% "circe-refined"        % Version.circe

    val catsCore = "org.typelevel" %% "cats-core" % Version.cats

    val logbackCore    = "ch.qos.logback" % "logback-core"    % Version.logback
    val logbackClassic = "ch.qos.logback" % "logback-classic" % Version.logback

    val bcrypt              = "com.github.t3hnar"      %% "scala-bcrypt"          % Version.bcrypt
    val elastic4sClientAkka = "com.sksamuel.elastic4s" %% "elastic4s-client-akka" % Version.elastic4s
    val elastic4sCirce      = "com.sksamuel.elastic4s" %% "elastic4s-json-circe"  % Version.elastic4s
    val elastic4sEmbedded   = "com.sksamuel.elastic4s" %% "elastic4s-embedded"    % Version.elastic4s
    val pureconfig          = "com.github.pureconfig"  %% "pureconfig"            % Version.pureconfig
    val refinedPureconfig   = "eu.timepit"             %% "refined-pureconfig"    % Version.refined
    val pauldijouJwtCirce   = "com.pauldijou"          %% "jwt-circe"             % Version.pauldijouJwt
    val chimney             = "io.scalaland"           %% "chimney"               % Version.chimney

    val tapirSwaggerUiAkkaHttp = "com.softwaremill.sttp.tapir" %% "tapir-swagger-ui-akka-http" % Version.tapir

    val kamonAkka        = "io.kamon" %% "kamon-akka"           % Version.kamonAkka
    val kamonAkkaHttp    = "io.kamon" %% "kamon-akka-http"      % Version.kamonAkkaHttp
    val kamonPrometheus  = "io.kamon" %% "kamon-prometheus"     % Version.kamonPrometheus
    val kamonSystem      = "io.kamon" %% "kamon-system-metrics" % Version.kamonPrometheus
    val kamonCassandra        = "io.kamon" %% "kamon-cassandra"           % Version.kamon
    val kamonKanelaAgent = "io.kamon"  % "kanela-agent"         % Version.kamonKanela

    val scalapbRuntimeGrpc = "com.thesamet.scalapb" %% "scalapb-runtime-grpc" % scalapb.compiler.Version.scalapbVersion
    val scalaPbRuntime     = "com.thesamet.scalapb" %% "scalapb-runtime"      % scalapb.compiler.Version.scalapbVersion % "protobuf"

    val randomDataGenerator = "com.danielasfregola"  %% "random-data-generator"     % Version.randomDataGenerator
    val scalaTest           = "org.scalatest"        %% "scalatest"                 % Version.scalaTest
    val gatlingCharts       = "io.gatling.highcharts" % "gatling-charts-highcharts" % Version.gatling
    val gatlingTest         = "io.gatling"            % "gatling-test-framework"    % Version.gatling
    val gatlingGrpc         = "com.github.phisgr"     % "gatling-grpc"              % Version.gatlingGrpc
  }

// *****************************************************************************
// Settings
// *****************************************************************************        |

lazy val settings =
  commonSettings ++
    gitSettings

lazy val commonSettings =
  Seq(
    organization := "c",
    scalafmtOnCompile := true,
    licenses += ("Apache 2.0", url("http://www.apache.org/licenses/LICENSE-2.0")),
    Compile / packageBin / mappings += (ThisBuild / baseDirectory).value / "LICENSE" -> "LICENSE",
    scalacOptions ++= Seq(
      "-unchecked",
      "-deprecation",
      "-language:_",
      "-target:11",
      "-encoding",
      "UTF-8"
    ),
    javacOptions ++= Seq(
      "-source",
      "11",
      "-target",
      "11"
    ),
    Compile / unmanagedSourceDirectories := Seq((Compile / scalaSource).value),
    Test / unmanagedSourceDirectories := Seq((Test / scalaSource).value)
  )

lazy val gitSettings =
  Seq(
    git.useGitDescribe := true
  )

//lazy val headerSettings =
//  Seq(
//    headers := Map("scala" -> Apache2_0("2016", "justcoon"))
//  )

// TODO
lazy val dockerSettings =
  Seq(
    Docker / daemonUser := "root",
    Docker / maintainer := "justcoon",
//  Docker /   version := "latest",
    dockerUpdateLatest := true,
    dockerBaseImage := "openjdk:11-jre",
    dockerExposedPorts := Vector(2552, 8558, 8000, 8010, 9080),
//    dockerRepository := Some("justcoon"),
    dockerEntrypoint := Seq("/opt/docker/bin/user-svc"),
    dockerUpdateLatest := true,
    Docker / daemonUser := "daemon",
    Docker / daemonUserUid := None,
    dockerChmodType := DockerChmodType.UserGroupWriteExecute,
    dockerCommands ++= Seq(Cmd("USER", "root"))
  )

// https://github.com/kamon-io/kamon-bundle/blob/master/build.sbt
// http://kamon-io.github.io/kanela/
lazy val javaAgentsSettings = Seq(javaAgents += library.kamonKanelaAgent)
