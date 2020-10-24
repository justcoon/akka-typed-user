//addSbtPlugin("com.dwijnand" % "sbt-travisci" % "1.0.0")
addSbtPlugin("com.timushev.sbt" % "sbt-updates" % "0.5.1")
addSbtPlugin("org.scalameta" % "sbt-scalafmt" % "2.4.2")
addSbtPlugin("com.typesafe.sbt" % "sbt-git" % "1.0.0")
addSbtPlugin("com.typesafe.sbt" % "sbt-native-packager" % "1.5.1")
//addSbtPlugin("de.heikoseeberger" % "sbt-header"          % "1.7.0")
//addSbtPlugin("io.spray" % "sbt-revolver" % "0.8.0")
addSbtPlugin("net.virtual-void" % "sbt-dependency-graph" % "0.9.2")
addSbtPlugin("com.lightbend.akka.grpc" % "sbt-akka-grpc" % "1.0.2")
addSbtPlugin("com.twilio" % "sbt-guardrail" % "0.61.0.1")

//FIXME scala plugin 2.13
addSbtPlugin("com.lightbend.sbt" % "sbt-javaagent" % "0.1.5")

resolvers += Resolver.bintrayRepo("kamon-io", "sbt-plugins")
addSbtPlugin("io.kamon" % "sbt-aspectj-runner" % "1.1.2")