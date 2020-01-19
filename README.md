# akka typed user

ddd

https://medium.com/bestmile/domain-driven-event-sourcing-with-akka-typed-5f5b8bbfb823

# required

elasticsearch
cassandra


### application setup

single node VM arguments

```
-Dakka.remote.artery.canonical.port=2551 -Drest-api.port=8000 -Dakka.cluster.seed-nodes.0=akka://user@127.0.0.1:2551
```

nodes VMs arguments


1. node 1

```
 -Dakka.remote.artery.canonical.port=2551 -Drest-api.port=8000
```

1. node 2

``` 
 -Dakka.remote.artery.canonical.port=2552 -Drest-api.port=8001
```

1. node 3

```
 -Dakka.remote.artery.canonical.port=2553 -Drest-api.port=8002
```