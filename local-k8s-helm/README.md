# XTDB Helm Chart (Local/Minikube Setup)

This Helm chart deploys a local XTDB cluster for development, using:

- **Minikube** as the Kubernetes environment
- **MinIO** as a local S3-compatible object store
- **Bitnami Kafka** as the Kafka broker

---

## Prerequisites

- [Minikube](https://minikube.sigs.k8s.io/docs/start/)
- [Helm 3](https://helm.sh/)
- `kubectl` configured for your local Minikube cluster

---

## Deployment

Run everything (starts minikube, makes helm deployments):
```bash
./scripts/deploy-all.sh --start-minikube
```

Clear up everything (including minikube):
```bash
./scripts/clear-minikube.sh --destroy-minikube
```

## Connecting to Services

To portforward any of the local XTDB ports (ie, to localhost), run the following:

```bash
kubectl port-forward svc/xtdb-service --namespace xtdb-deployment <servicePort>:<localHostPort>
```

The ports are as follows:
- 5432 - pgwire.
- 3000 - http server.
- 8080 - healthz server

To connect to the minio console:

```bash
minikube service minio --namespace xtdb-deployment
```

Can then login with the root user/password set within the deploy script (`minioadmin` for both)

## Testing a Migration

By default, if having ran deploy-all.sh, we will be running (via helm):
- Minio - could likely do without it, see below.
- Kafka
- XTDB, currently using Kafka as the log and local buffer pool for the storage.
  - This would be using minio except we have issues writing multipart files on beta6.4.

Prior to running a migration, will need to have sent sufficient data to the running XTDB nodes (ie, via portforwarding).

You can observe what data has been written to storage either:

- Via the Minio console if using minio based storage.
- By looking at the minikube storage if using local bufferpool storage:
  - Can run `minikube ssh` to get a shell, and XTDB local bufferpool will be located under `ls /data`.
  - Currently - should be files nested under v05.

We can then use helm to run the migration job itself:

```bash
helm install xtdb-minio helm --namespace xtdb-deployment \
  --set nodeCount=1 \
  --set migrationJob.enabled=true
```

This will scale down from two XTDB nodes to 1, and run a Job that will use the migration-from argument.

We can track the logs of this using the following:

```bash
kubectl logs -f job/xtdb-migration-job --namespace xtdb-deployment
```

Once this has completed, we can again check the contents of storage (see above):

- We should now see that there is a `v06` folder.

Finally, we can use helm to spin up both XTDB nodes and upgrade them to use the `edge` image/beta7:

```bash
helm install xtdb-minio helm --namespace xtdb-deployment \
  --set nodeCount=2 \
  --set image.tag=edge \
  --set migrationJob.enabled=false
```



