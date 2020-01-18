
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
    .settings(settings)
    .settings(
      libraryDependencies ++= Seq(
        library.akkaClusterTyped,
        library.akkaPersistenceTyped,
        library.akkaClusterShardingTyped,
        library.akkaHttp,
        library.akkaHttpCirce,
        library.akkaKryo,
        library.akkaSlf4j,
        library.akkaPersistenceQuery,
        library.akkaPersistenceCassandra,
        library.circeGeneric,
        library.circeRefined,
        library.logbackCore,
        library.logbackClassic,
        library.bcrypt,
        library.pureconfig,
        library.elastic4sClientAkka,
        library.elastic4sCirce,
        //        library.elastic4sEmbedded,
        library.chimney,
        library.akkaHttpTestkit         % Test,
        library.akkaPersistenceInmemory % Test,
        library.akkaTestkit             % Test,
        library.scalaTest               % Test
      )
    )

lazy val `user-svc` =
  (project in file("modules/user-svc"))
    .enablePlugins(JavaAppPackaging, DockerPlugin)
    .settings(settings)
    .settings(
      libraryDependencies ++= Seq(
          library.akkaClusterTyped,
          library.akkaPersistenceTyped,
          library.akkaClusterShardingTyped,
          library.akkaHttp,
          library.akkaHttpCirce,
          library.akkaKryo,
          library.akkaSlf4j,
          library.akkaPersistenceQuery,
          library.akkaPersistenceCassandra,
          library.circeGeneric,
          library.circeRefined,
          library.logbackCore,
          library.logbackClassic,
          library.bcrypt,
          library.pureconfig,
          library.elastic4sClientAkka,
          library.elastic4sCirce,
          //        library.elastic4sEmbedded,
          library.chimney,
          library.akkaHttpTestkit         % Test,
          library.akkaPersistenceInmemory % Test,
          library.akkaTestkit             % Test,
          library.scalaTest               % Test
        )
    )
    .dependsOn(`core`)

// *****************************************************************************
// Library dependencies
// *****************************************************************************

lazy val library =
  new {

    object Version {
      val akka                     = "2.6.1"
      val akkaHttp                 = "10.1.11"
      val akkaHttpJson             = "1.29.1"
      val akkaPersistenceCassandra = "0.101" //https://doc.akka.io/docs/akka-persistence-cassandra/0.101/migrations.html#migrations-to-0-101
      val akkaPersistenceInmemory  = "2.5.15.2"
      val alpakka                  = "1.1.2"
      val circe                    = "0.12.3"
      val logback                  = "1.2.3"
      val scalaTest                = "3.1.0"
      val bcrypt                   = "4.1"
      val elastic4s                = "7.3.5"
      val pureconfig               = "0.12.1"
      val chimney                  = "0.3.5"
      val akkaKryo                 = "1.1.0"
    }

    val akkaPersistenceQuery     = "com.typesafe.akka"   %% "akka-persistence-query"     % Version.akka
    val akkaPersistenceCassandra = "com.typesafe.akka"   %% "akka-persistence-cassandra" % Version.akkaPersistenceCassandra
    val akkaPersistenceInmemory  = "com.github.dnvriend" %% "akka-persistence-inmemory"  % Version.akkaPersistenceInmemory

    val akkaClusterTyped         = "com.typesafe.akka" %% "akka-cluster-typed"          % Version.akka
    val akkaPersistenceTyped     = "com.typesafe.akka" %% "akka-persistence-typed"      % Version.akka
    val akkaClusterShardingTyped = "com.typesafe.akka" %% "akka-cluster-sharding-typed" % Version.akka

    val akkaKryo        = "io.altoo"          %% "akka-kryo-serialization" % Version.akkaKryo
    val akkaHttp        = "com.typesafe.akka" %% "akka-http"               % Version.akkaHttp
    val akkaHttpCirce   = "de.heikoseeberger" %% "akka-http-circe"         % Version.akkaHttpJson
    val akkaHttpTestkit = "com.typesafe.akka" %% "akka-http-testkit"       % Version.akkaHttp
    val akkaSlf4j       = "com.typesafe.akka" %% "akka-slf4j"              % Version.akka

    val alpakkaSse   = "com.lightbend.akka" %% "akka-stream-alpakka-sse" % Version.alpakka
    val akkaTestkit  = "com.typesafe.akka"  %% "akka-testkit"            % Version.akka
    val circeGeneric = "io.circe"           %% "circe-generic"           % Version.circe
    val circeRefined = "io.circe"           %% "circe-refined"           % Version.circe

    val logbackCore    = "ch.qos.logback" % "logback-core"    % Version.logback
    val logbackClassic = "ch.qos.logback" % "logback-classic" % Version.logback

    val scalaTest           = "org.scalatest"          %% "scalatest"             % Version.scalaTest
    val bcrypt              = "com.github.t3hnar"      %% "scala-bcrypt"          % Version.bcrypt
    val elastic4sClientAkka = "com.sksamuel.elastic4s" %% "elastic4s-client-akka" % Version.elastic4s
    val elastic4sCirce      = "com.sksamuel.elastic4s" %% "elastic4s-json-circe"  % Version.elastic4s
    val elastic4sEmbedded   = "com.sksamuel.elastic4s" %% "elastic4s-embedded"    % Version.elastic4s
    val pureconfig          = "com.github.pureconfig"  %% "pureconfig"            % Version.pureconfig

    val chimney = "io.scalaland" %% "chimney" % Version.chimney

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
    scalaVersion := "2.13.1",
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
