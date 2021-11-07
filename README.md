# akka typed user

ddd/cqrs

inspired by:

* https://github.com/akka/akka-samples/tree/2.6/akka-sample-cqrs-scala
* https://medium.com/bestmile/domain-driven-event-sourcing-with-akka-typed-5f5b8bbfb823
* https://blog.softwaremill.com/painlessly-passing-message-context-through-akka-streams-1615b11efc2c
* https://tudorzgureanu.com/define-topic-schema-for-kafka-using-protobuf-with-examples-in-scala/

# required

* elasticsearch
* cassandra
* kafka

### application setup

kanela agent https://kamon-io.github.io/kanela/

```bash
wget -O kanela-agent.jar 'https://search.maven.org/remote_content?g=io.kamon&a=kanela-agent&v=LATEST'
```

single node VM arguments

```
-Dakka.remote.artery.canonical.port=2551 -Drest-api.port=8000 -Dgrpc-api.port=8010 -Dakka.cluster.seed-nodes.0=akka://user@127.0.0.1:2551 -Dkamon.prometheus.embedded-server.port=9080 -javaagent:kanela-agent.jar
```

nodes VMs arguments


1. node 1

```
 -Dakka.remote.artery.canonical.port=2551 -Dakka.management.http.port=8551 -Drest-api.port=8000 -Dgrpc-api.port=8010 -Dkamon.prometheus.embedded-server.port=9080 -javaagent:kanela-agent.jar
```

1. node 2

``` 
 -Dakka.remote.artery.canonical.port=2552 -Dakka.management.http.port=8552 -Drest-api.port=8001 -Dgrpc-api.port=8011 -Dkamon.prometheus.embedded-server.port=9081 -javaagent:kanela-agent.jar
```

1. node 3

```
 -Dakka.remote.artery.canonical.port=2553 -Dakka.management.http.port=8553 -Drest-api.port=8002 -Dgrpc-api.port=8012 -Dkamon.prometheus.embedded-server.port=9082 -javaagent:kanela-agent.jar
```


## tests

tests using https://github.com/47degrees/sbt-embedded-cassandra

sbt task to execute cassandra:
```
embeddedCassandraStart
```
