addSbtPlugin("com.timushev.sbt"        % "sbt-updates"          % "0.5.1")
addSbtPlugin("org.scalameta"           % "sbt-scalafmt"         % "2.4.3")
addSbtPlugin("com.typesafe.sbt"        % "sbt-git"              % "1.0.0")
addSbtPlugin("com.github.sbt"          % "sbt-native-packager"  % "1.9.7")
addSbtPlugin("net.virtual-void"        % "sbt-dependency-graph" % "0.9.2")
addSbtPlugin("com.lightbend.akka.grpc" % "sbt-akka-grpc"        % "2.1.3")
addSbtPlugin("dev.guardrail"           % "sbt-guardrail"        % "0.69.0")
addSbtPlugin("io.gatling"              % "gatling-sbt"          % "4.0.0")

//FIXME scala plugin 2.13
addSbtPlugin("com.lightbend.sbt" % "sbt-javaagent" % "0.1.6")

//resolvers += Resolver.bintrayRepo("kamon-io", "sbt-plugins")
//addSbtPlugin("io.kamon" % "sbt-aspectj-runner" % "1.1.2")
addSbtPlugin("io.kamon" % "sbt-kanela-runner" % "2.0.12")

libraryDependencies += "com.thesamet.scalapb" %% "scalapb-validate-codegen" % "0.3.2"
