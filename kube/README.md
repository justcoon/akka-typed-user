https://www.lightbend.com/blog/how-to-distribute-application-state-with-akka-cluster-part-3-kubernetes-monitoring
https://github.com/michael-read/akka-typed-distributed-state-blog

https://github.com/Yolean/kubernetes-kafka
kubectl create namespace kafka && \
kubectl apply -k https://github.com/Yolean/kubernetes-kafka/variants/dev-small/?ref=v6.0.3

https://lernentec.com/post/running-simple-elasticsearch-kibana-minikube/

https://www.elastic.co/blog/getting-started-with-elastic-cloud-on-kubernetes-deployment



operator

https://www.elastic.co/guide/en/cloud-on-k8s/master/k8s-deploy-eck.html
kubectl apply -f https://download.elastic.co/downloads/eck/1.2.0/all-in-one.yaml

https://www.elastic.co/guide/en/cloud-on-k8s/master/k8s-deploy-elasticsearch.html

kubectl apply -k elasticsearch.yaml

get password
PASSWORD=$(kubectl get secret es-es-elastic-user -o go-template='{{.data.elastic | base64decode}}')