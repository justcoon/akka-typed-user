# akka typed user

ddd

https://medium.com/bestmile/domain-driven-event-sourcing-with-akka-typed-5f5b8bbfb823

# required

elasticsearch
cassandra


### application setup

kanela agent https://kamon-io.github.io/kanela/

```bash
wget -O kanela-agent.jar 'https://search.maven.org/remote_content?g=io.kamon&a=kanela-agent&v=LATEST'
```

single node VM arguments

```
-Dakka.remote.artery.canonical.port=2551 -Drest-api.port=8000 -Dgrpc-api.port=8010 -Dakka.cluster.seed-nodes.0=akka://user@127.0.0.1:2551 -Dkamon.prometheus.embedded-server.port=9090 -javaagent:kanela-agent.jar
```

nodes VMs arguments


1. node 1

```
 -Dakka.remote.artery.canonical.port=2551 -Drest-api.port=8000 -Dgrpc-api.port=8010 -Dkamon.prometheus.embedded-server.port=9090 -javaagent:kanela-agent.jar
```

1. node 2

``` 
 -Dakka.remote.artery.canonical.port=2552 -Drest-api.port=8001 -Dgrpc-api.port=8011 -Dkamon.prometheus.embedded-server.port=9091 -javaagent:kanela-agent.jar
```

1. node 3

```
 -Dakka.remote.artery.canonical.port=2553 -Drest-api.port=8002 -Dgrpc-api.port=8012 -Dkamon.prometheus.embedded-server.port=9092 -javaagent:kanela-agent.jar
```