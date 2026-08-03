# Data service platform on Kubernetes

Create the runtime secret before applying the manifests:

```bash
kubectl apply -f deploy/k8s/data-service-platform/namespace.yaml
kubectl -n data-platform create secret generic data-service-platform-secrets \
  --from-literal=MYSQL_USERNAME=data_service \
  --from-literal=MYSQL_PASSWORD='replace-me' \
  --from-literal=REDIS_PASSWORD='replace-me' \
  --from-literal=DATA_SERVICE_MASTER_KEY='replace-with-a-long-random-key'
kubectl apply -k deploy/k8s/data-service-platform
```

The monitoring resources require Prometheus Operator CRDs. Replace the sample
GHCR image tags with immutable release tags in production.
