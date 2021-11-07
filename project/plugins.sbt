addSbtPlugin("com.timushev.sbt"        % "sbt-updates"          % "0.5.1")
addSbtPlugin("org.scalameta"           % "sbt-scalafmt"         % "2.4.3")
addSbtPlugin("com.typesafe.sbt"        % "sbt-git"              % "1.0.0")
addSbtPlugin("com.typesafe.sbt"        % "sbt-native-packager"  % "1.8.0")
addSbtPlugin("net.virtual-void"        % "sbt-dependency-graph" % "0.9.2")
addSbtPlugin("com.lightbend.akka.grpc" % "sbt-akka-grpc"        % "2.1.0")
addSbtPlugin("dev.guardrail"           % "sbt-guardrail"        % "0.66.0")
addSbtPlugin("io.gatling"              % "gatling-sbt"          % "3.2.2")
addSbtPlugin("com.47deg" % "sbt-embedded-cassandra" % "0.0.7")

//FIXME scala plugin 2.13
addSbtPlugin("com.lightbend.sbt" % "sbt-javaagent" % "0.1.6")

resolvers += Resolver.bintrayRepo("kamon-io", "sbt-plugins")
addSbtPlugin("io.kamon" % "sbt-aspectj-runner" % "1.1.2")
