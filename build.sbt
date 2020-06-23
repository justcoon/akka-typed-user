import com.lightbend.sbt.javaagent.Modules
import sbt.Keys.javaOptions

scalaVersion in Scope.Global := "2.13.2"

// *****************************************************************************
// Projects
// *****************************************************************************
//
lazy val `akka-typed-user` =
  project
    .in(file("."))
    .enablePlugins(GitVersioning)
    .aggregate(`core`, `user-svc`)
    .settings(settings)
    .settings(
      unmanagedSourceDirectories.in(Compile) := Seq.empty,
      unmanagedSourceDirectories.in(Test) := Seq.empty,
      publishArtifact := false
    )

lazy val `core` =
  (project in file("modules/core"))
    .enablePlugins(AkkaGrpcPlugin)
    .settings(settings)
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
          library.akkaKryo,
          library.akkaSlf4j,
          library.akkaPersistenceQuery,
          library.akkaPersistenceCassandra,
          library.akkaStreamKafka,
          library.circeGeneric,
          library.circeRefined,
          library.logbackCore,
          library.logbackClassic,
          library.bcrypt,
          library.pureconfig,
          library.pauldijouJwtCirce,
          library.chimney,
          library.akkaHttpTestkit % Test,
          library.akkaTestkit     % Test,
          library.scalaTest       % Test
        )
    )

lazy val `user-svc` =
  (project in file("modules/user-svc"))
    .enablePlugins(JavaAppPackaging, DockerPlugin)
    .enablePlugins(AkkaGrpcPlugin)
    .settings(settings ++ javaAgentsSettings)
    .settings(
      akkaGrpcCodeGeneratorSettings += "server_power_apis",
      guardrailTasks.in(Compile) := List(
          ScalaServer(file("modules/user-svc/src/main/openapi/UserOpenApi.yaml"), pkg = "com.jc.user.api.openapi", tracing = false)
        )
    )
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
          library.akkaKryo,
          library.akkaSlf4j,
          library.akkaPersistenceQuery,
          library.akkaPersistenceCassandra,
          library.akkaStreamKafka,
          library.circeGeneric,
          library.circeRefined,
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
          library.chimney,
          library.akkaHttpTestkit % Test,
          library.akkaTestkit     % Test,
          library.scalaTest       % Test
        )
    )
    .dependsOn(`core`)

// *****************************************************************************
// Library dependencies
// *****************************************************************************

lazy val library =
  new {

    object Version {
      val akka                     = "2.6.6"
      val akkaHttp                 = "10.1.12"
      val akkaHttpJson             = "1.33.0"
      val akkaPersistenceCassandra = "1.0.1"
      val akkaStreamKafka          = "2.0.3"
      val circe                    = "0.13.0"
      val logback                  = "1.2.3"
      val scalaTest                = "3.2.0"
      val bcrypt                   = "4.1"
      val elastic4s                = "7.8.0"
      val pureconfig               = "0.12.3"
      val chimney                  = "0.5.2"
      val akkaKryo                 = "1.1.5"
      val pauldijouJwt             = "4.3.0"
      val refined                  = "0.9.14"

      val kamonPrometheus = "2.1.1"
      val kamonAkka       = "2.1.1"
      val kamonAkkaHttp   = "2.1.1"
      val kamonKanela     = "1.0.5"
    }

    val akkaPersistenceQuery     = "com.typesafe.akka" %% "akka-persistence-query"     % Version.akka
    val akkaPersistenceCassandra = "com.typesafe.akka" %% "akka-persistence-cassandra" % Version.akkaPersistenceCassandra
    val akkaDiscovery            = "com.typesafe.akka" %% "akka-discovery"             % Version.akka

    val akkaClusterTyped         = "com.typesafe.akka" %% "akka-cluster-typed"          % Version.akka
    val akkaPersistenceTyped     = "com.typesafe.akka" %% "akka-persistence-typed"      % Version.akka
    val akkaClusterShardingTyped = "com.typesafe.akka" %% "akka-cluster-sharding-typed" % Version.akka

    val akkaKryo         = "io.altoo"          %% "akka-kryo-serialization" % Version.akkaKryo
    val akkaHttp         = "com.typesafe.akka" %% "akka-http"               % Version.akkaHttp
    val akkaHttp2Support = "com.typesafe.akka" %% "akka-http2-support"      % Version.akkaHttp
    val akkaHttpCirce    = "de.heikoseeberger" %% "akka-http-circe"         % Version.akkaHttpJson
    val akkaHttpTestkit  = "com.typesafe.akka" %% "akka-http-testkit"       % Version.akkaHttp
    val akkaSlf4j        = "com.typesafe.akka" %% "akka-slf4j"              % Version.akka

    val akkaStreamKafka = "com.typesafe.akka" %% "akka-stream-kafka" % Version.akkaStreamKafka
    val akkaTestkit     = "com.typesafe.akka" %% "akka-testkit"      % Version.akka
    val circeGeneric    = "io.circe"          %% "circe-generic"     % Version.circe
    val circeRefined    = "io.circe"          %% "circe-refined"     % Version.circe

    val logbackCore    = "ch.qos.logback" % "logback-core"    % Version.logback
    val logbackClassic = "ch.qos.logback" % "logback-classic" % Version.logback

    val scalaTest           = "org.scalatest"          %% "scalatest"             % Version.scalaTest
    val bcrypt              = "com.github.t3hnar"      %% "scala-bcrypt"          % Version.bcrypt
    val elastic4sClientAkka = "com.sksamuel.elastic4s" %% "elastic4s-client-akka" % Version.elastic4s
    val elastic4sCirce      = "com.sksamuel.elastic4s" %% "elastic4s-json-circe"  % Version.elastic4s
    val elastic4sEmbedded   = "com.sksamuel.elastic4s" %% "elastic4s-embedded"    % Version.elastic4s
    val pureconfig          = "com.github.pureconfig"  %% "pureconfig"            % Version.pureconfig
    val refinedPureconfig   = "eu.timepit"             %% "refined-pureconfig"    % Version.refined
    val pauldijouJwtCirce   = "com.pauldijou"          %% "jwt-circe"             % Version.pauldijouJwt
    val chimney             = "io.scalaland"           %% "chimney"               % Version.chimney

    val kamonAkka        = "io.kamon" %% "kamon-akka"           % Version.kamonAkka
    val kamonAkkaHttp    = "io.kamon" %% "kamon-akka-http"      % Version.kamonAkkaHttp
    val kamonPrometheus  = "io.kamon" %% "kamon-prometheus"     % Version.kamonPrometheus
    val kamonSystem      = "io.kamon" %% "kamon-system-metrics" % Version.kamonPrometheus
    val kamonKanelaAgent = "io.kamon" % "kanela-agent"          % Version.kamonKanela

    val scalaPbRuntime = "com.thesamet.scalapb" %% "scalapb-runtime" % scalapb.compiler.Version.scalapbVersion % "protobuf"
  }

// *****************************************************************************
// Settings
// *****************************************************************************        |

lazy val settings =
  commonSettings ++
  gitSettings ++
  dockerSettings

lazy val commonSettings =
  Seq(
    organization := "c",
    licenses += ("Apache 2.0", url("http://www.apache.org/licenses/LICENSE-2.0")),
    mappings.in(Compile, packageBin) += baseDirectory.in(ThisBuild).value / "LICENSE" -> "LICENSE",
    scalacOptions ++= Seq(
        "-unchecked",
        "-deprecation",
        "-language:_",
        "-target:jvm-1.8",
        "-encoding",
        "UTF-8"
      ),
    javacOptions ++= Seq(
        "-source",
        "1.8",
        "-target",
        "1.8"
      ),
    unmanagedSourceDirectories.in(Compile) := Seq(scalaSource.in(Compile).value),
    unmanagedSourceDirectories.in(Test) := Seq(scalaSource.in(Test).value)
  )

lazy val gitSettings =
  Seq(
    git.useGitDescribe := true
  )

//lazy val headerSettings =
//  Seq(
//    headers := Map("scala" -> Apache2_0("2016", "justcoon"))
//  )

lazy val dockerSettings =
  Seq(
    daemonUser.in(Docker) := "root",
    maintainer.in(Docker) := "justcoon",
    version.in(Docker) := "latest",
    dockerBaseImage := "openjdk:8",
    dockerExposedPorts := Vector(2551, 8000),
    dockerRepository := Some("justcoon")
  )

// https://github.com/kamon-io/kamon-bundle/blob/master/build.sbt
// http://kamon-io.github.io/kanela/
lazy val javaAgentsSettings =
  Seq(
//    javaAgents += library.kamonKanelaAgent, // FIXME scala 2.13
    javaOptions in Universal += "-Dorg.aspectj.tracing.factory=default"
  )
