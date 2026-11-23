# Deployment design handoff

The System Designer owns topology and capacity decisions. GitHub Workflow Expert owns delivery automation. DevSecOps reviews credentials, isolation and artifact integrity. Record actual results on the task entry in tasks/.

- Use approved managed TLS ingress, health-based routing, connection draining and application replicas across availability zones. Keep application data services private.
- Scale workers independently by backlog and queue age. Bound API and worker connection pools against the database budget. Exercise partner rate limits, retries and duplicate prevention during scaling.
- Promote the same image digest. Verify staging smoke, load capacity, failover and rollback before authorized deployment. Use compatible migrations across releases and verify the specification's recovery targets through a restore drill.

The tagged test image is an all-in-one disposable stack. Its PostgreSQL listens only inside the container. For live deployment, use the independent managed data service and operational gates in the specification.
