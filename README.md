# OpenShift MCP Server

**Enterprise MCP Server for OpenShift Lightspeed**

MCP (Model Context Protocol) server built with **Quarkus** that provides 19 operational tools for OpenShift cluster monitoring, deployment management, and performance testing. Designed to integrate with **OpenShift Lightspeed** as an enterprise-grade AI assistant backend.

## Architecture

```
┌─────────────────────┐     ┌──────────────────────────────┐     ┌─────────────────┐
│  OpenShift Console  │────>│  OpenShift Lightspeed (OLS)  │────>│  LLM Provider   │
│  (User Interface)   │     │  + MCP Client                │     │  (Granite/etc)  │
└─────────────────────┘     └──────────┬───────────────────┘     └─────────────────┘
                                       │ MCP Protocol (HTTP)
                                       ▼
                            ┌──────────────────────────────┐
                            │   OpenShift MCP Server       │
                            │   (Quarkus + Fabric8)        │
                            │                              │
                            │   19 Tools:                  │
                            │   • 9 Monitoring             │
                            │   • 5 Deployment             │
                            │   • 5 Performance Testing    │
                            └──────────┬───────────────────┘
                                       │ Kubernetes API
                                       ▼
                            ┌──────────────────────────────┐
                            │   OpenShift Cluster          │
                            │   (Nodes, Pods, Services)    │
                            └──────────────────────────────┘
```

## MCP Tools

### Monitoring (9 tools)

| Tool | Description |
|------|-------------|
| `checkClusterHealth` | Overall cluster health, node/pod status, and critical issues |
| `getPerformanceMetrics` | Node and pod CPU/memory metrics via kubectl top |
| `detectResourceIssues` | Pods with high CPU/memory or excessive restarts |
| `analyzePodDisruptions` | Evictions, OOM kills, and restart patterns |
| `checkNodeConditions` | Node conditions, taints, and allocatable resources |
| `monitorDeployments` | Deployment rollout health and replica status |
| `checkKubeletStatus` | Kubelet service status and journal logs |
| `checkCrioStatus` | CRI-O container runtime status and logs |
| `analyzeJournalctlPodErrors` | Journal log analysis with pod/service filters |

### Deployment (5 tools)

| Tool | Description |
|------|-------------|
| `createDeployment` | Create deployments with custom images, replicas, and resources |
| `deployDatabase` | Deploy databases (PostgreSQL, MySQL, MongoDB, Redis) with RHEL images |
| `createHpa` | Configure horizontal pod autoscalers |
| `createService` | Create ClusterIP/NodePort/LoadBalancer services |
| `createNetworkPolicy` | Create network policies (deny-all, allow-same-namespace) |

### Performance Testing (5 tools)

| Tool | Description |
|------|-------------|
| `runKubeBurner` | Cluster density testing with pod scheduling benchmarks |
| `runStorageBenchmark` | Storage I/O benchmarks with FIO |
| `runNetworkTest` | Network throughput tests with iperf3 |
| `runCpuStressTest` | CPU/memory stress testing with stress-ng |
| `runDatabaseBenchmark` | Database benchmarks with pgbench/sysbench |

## Quick Start

### 1. Deploy with Helm (recommended)

```bash
helm install openshift-mcp-server ./helm/openshift-mcp-server \
  --namespace openshift-lightspeed \
  --create-namespace \
  --set image.pullPolicy=Always
```

### 2. Configure OLSConfig

Apply the provided `cluster-ols.yml` or add the MCP server to your existing OLSConfig:

```bash
# Option A: Use the provided sample (edit LLM provider as needed)
oc apply -f k8s/cluster-ols.yml

# Option B: Add to your existing OLSConfig
```

```yaml
apiVersion: ols.openshift.io/v1alpha1
kind: OLSConfig
metadata:
  name: cluster
spec:
  featureGates:
    - MCPServer
  mcpServers:
    - name: openshift-mcp-server
      timeout: 30
      url: 'http://openshift-mcp-server.openshift-lightspeed.svc.cluster.local:8080/mcp'
  # ... rest of your OLS configuration
```

> **Important**: Use `/mcp` (Streamable HTTP), not `/mcp/sse`. OLS uses POST requests which require the Streamable HTTP endpoint.

### 3. Deploy without Helm

```bash
oc apply -f k8s/deployment.yaml
oc apply -f k8s/cluster-ols.yml
```

### 4. Verify

```bash
# Check MCP pod is running
oc get pods -n openshift-lightspeed -l app=openshift-mcp-server

# Check OLS loaded the tools
oc logs -n openshift-lightspeed deploy/lightspeed-app-server \
  -c lightspeed-service-api | grep "tools from MCP"
# Expected: Loaded 19 tools from MCP server 'openshift-mcp-server'
```

### 5. Test in Lightspeed

Open the OpenShift console and ask Lightspeed:

- *"Check the cluster health"*
- *"Show me performance metrics for namespace my-app"*
- *"Deploy a PostgreSQL database called mydb"*
- *"Create an HPA for my-deployment with max 5 replicas"*
- *"Run a storage benchmark"*
- *"What nodes have issues?"*

## Local Development

```bash
./mvnw quarkus:dev
# MCP at http://localhost:8080/mcp
# Health at http://localhost:8080/q/health
```

## Build

```bash
# Build container image
podman build -t quay.io/maximilianopizarro/openshift-mcp-server:latest -f Containerfile .

# Push to registry
podman push quay.io/maximilianopizarro/openshift-mcp-server:latest
```

## Technology Stack

- **Runtime**: [Quarkus](https://quarkus.io/) 3.27.3 (Java 21)
- **MCP**: [Quarkiverse MCP Server](https://github.com/quarkiverse/quarkus-mcp-server) 1.8.1 (HTTP transport)
- **Kubernetes Client**: [Fabric8](https://github.com/fabric8io/kubernetes-client) via `quarkus-kubernetes-client`
- **Container**: UBI9 OpenJDK 21 Runtime (`registry.access.redhat.com/ubi9/openjdk-21-runtime`)
- **Health**: SmallRye Health (`/q/health/ready`, `/q/health/live`, `/q/health/started`)

## Database Images

The `deployDatabase` tool uses official Red Hat catalog images:

| Database   | Image |
|-----------|-------|
| PostgreSQL | `registry.redhat.io/rhel9/postgresql-16:latest` |
| MySQL      | `registry.redhat.io/rhel9/mysql-80:latest` |
| MongoDB    | `registry.redhat.io/rhel9/mongodb-70:latest` |
| Redis      | `registry.redhat.io/rhel9/redis-7:latest` |

## Cursor MCP Configuration

To use this server with Cursor IDE locally:

```json
{
  "mcpServers": {
    "openshift-mcp-server": {
      "url": "http://localhost:8080/mcp"
    }
  }
}
```

## License

Apache License 2.0
