import com.lightbend.sbt.javaagent.Modules
import com.typesafe.sbt.packager.docker.DockerPlugin.autoImport.dockerEntrypoint
import com.typesafe.sbt.packager.docker.{ Cmd, ExecCmd }
import sbt.Keys.javaOptions

scalaVersion in Scope.Global := "2.13.3"

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
          library.akkaProjectionEventsourced,
          library.akkaProjectionCassandra,
          library.akkaProjectionKafka,
          library.circeGeneric,
          library.circeRefined,
          library.logbackCore,
          library.logbackClassic,
          library.bcrypt,
          library.pureconfig,
          library.refinedPureconfig,
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
    .settings(settings ++ dockerSettings ++ javaAgentsSettings)
    .settings(
      akkaGrpcCodeGeneratorSettings += "server_power_apis",
      guardrailTasks.in(Compile) := List(
          ScalaServer(file("modules/user-svc/src/main/openapi/UserOpenApi.yaml"), pkg = "com.jc.user.api.openapi", tracing = false, customExtraction = true)
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
          library.akkaHttpSprayJson,
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
      val akka                     = "2.6.10"
      val akkaHttp                 = "10.2.1"
      val akkaHttpJson             = "1.35.2"
      val akkaPersistenceCassandra = "1.0.3"
      val akkaStreamKafka          = "2.0.5"
      val akkaProjection           = "1.0.0"
      val akkaManagement           = "1.0.9"
      val circe                    = "0.13.0"
      val logback                  = "1.2.3"
      val scalaTest                = "3.2.2"
      val bcrypt                   = "4.3.0"
      val elastic4s                = "7.9.1"
      val pureconfig               = "0.14.0"
      val chimney                  = "0.6.0"
      val akkaKryo                 = "1.1.5"
      val pauldijouJwt             = "4.3.0"
      val refined                  = "0.9.17"

      val kamonPrometheus = "2.1.8"
      val kamonAkka       = "2.1.8"
      val kamonAkkaHttp   = "2.1.8"
      val kamonKanela     = "1.0.5"
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
  gitSettings

lazy val commonSettings =
  Seq(
    organization := "c",
    licenses += ("Apache 2.0", url("http://www.apache.org/licenses/LICENSE-2.0")),
    mappings.in(Compile, packageBin) += baseDirectory.in(ThisBuild).value / "LICENSE" -> "LICENSE",
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

// TODO
lazy val dockerSettings =
  Seq(
    daemonUser.in(Docker) := "root",
    maintainer.in(Docker) := "justcoon",
    version.in(Docker) := "latest",
    dockerBaseImage := "openjdk:11",
    dockerExposedPorts := Vector(2551, 8000),
    dockerRepository := Some("justcoon"),
    dockerEntrypoint ++= Seq(
        """-Drest-api.port="$REST_API_PORT"""",
        """-Dgrpc-api.port="$GRPC_API_PORT"""",
        """-Delasticsearch.addresses.0="$ELASTICSEARCH_URL"""",
        """-Dkafka.addresses.0="$KAFKA_URL"""",
        """-Dcmn.notifier.topology.node-size="$AKKA_SEED_NUMBER"""",
        """-Dakka.cluster.role.processor.min-nr-of-members="$AKKA_SEED_NUMBER"""",
        """-Dakka.cluster.min-nr-of-members="$AKKA_SEED_NUMBER"""",
        """-Dakka.remote.artery.canonical.hostname="$(eval "echo $AKKA_REMOTING_BIND_HOST")"""",
        """-Dakka.remote.artery.canonical.port="$AKKA_REMOTING_BIND_PORT"""",
        """-Dkamon.prometheus.embedded-server.port="$PROMETHEUS_METRICS_PORT"""",
        """$(IFS=','; I=0; for NODE in $AKKA_SEED_NODES; do echo "-Dakka.cluster.seed-nodes.$I=akka://user@$NODE"; I=$(expr $I + 1); done)""",
        """akka.remote.use-passive-connections=off"""
        //      "-Dakka.io.dns.resolver=async-dns",
        //      "-Dakka.io.dns.async-dns.resolve-srv=true",
        //      "-Dakka.io.dns.async-dns.resolv-conf=on"
      ),
    dockerCommands :=
      dockerCommands.value.flatMap {
        case ExecCmd("ENTRYPOINT", args @ _*) => Seq(Cmd("ENTRYPOINT", args.mkString(" ")))
        case c @ Cmd("FROM", _)               => Seq(c, ExecCmd("RUN", "/bin/sh", "-c", "apk add --no-cache bash && ln -sf /bin/bash /bin/sh"))
        case v                                => Seq(v)
      }
  )

// https://github.com/kamon-io/kamon-bundle/blob/master/build.sbt
// http://kamon-io.github.io/kanela/
lazy val javaAgentsSettings =
  Seq(
//    javaAgents += library.kamonKanelaAgent, // FIXME scala 2.13
    javaOptions in Universal += "-Dorg.aspectj.tracing.factory=default"
  )
