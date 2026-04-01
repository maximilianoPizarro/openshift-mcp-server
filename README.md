<p align="center">
  <img src="docs/openshift-logo.png" alt="OpenShift" width="120">
</p>

<h1 align="center">OpenShift MCP Server</h1>

<p align="center">
  <strong>Enterprise MCP Server for OpenShift Lightspeed</strong><br>
  <code>⚠️ EXPERIMENTAL — This project is under active development and not yet ready for production use.</code>
</p>

<p align="center">
  <a href="https://github.com/maximilianoPizarro/openshift-mcp-server/actions"><img src="https://img.shields.io/github/actions/workflow/status/maximilianoPizarro/openshift-mcp-server/pages.yml?branch=main&label=build" alt="Build Status"></a>
  <img src="https://img.shields.io/badge/version-0.1.0--alpha-orange" alt="Version">
  <img src="https://img.shields.io/badge/status-experimental-yellow" alt="Status">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-Apache%202.0-blue" alt="License"></a>
  <a href="https://artifacthub.io/packages/helm/openshift-mcp-server/openshift-mcp-server"><img src="https://img.shields.io/endpoint?url=https://artifacthub.io/badge/repository/openshift-mcp-server" alt="ArtifactHub"></a>
</p>

> **Warning**
> This is an **experimental** project in early alpha stage. APIs, tool definitions, and Helm chart values may change without notice between versions. Use at your own risk in non-production environments.

Dual MCP server deployment combining a custom **Quarkus** server (19 operational tools for monitoring, deployment, and performance testing) with the official **[openshift/openshift-mcp-server](https://github.com/openshift/openshift-mcp-server)** (generic Kubernetes CRUD, pod exec/logs, Helm management). Designed to integrate with **OpenShift Lightspeed** as an enterprise-grade AI assistant backend.

## Architecture

```
┌─────────────────────┐     ┌──────────────────────────────┐     ┌─────────────────┐
│  OpenShift Console  │────>│  OpenShift Lightspeed (OLS)  │────>│  LLM Provider   │
│  (User Interface)   │     │  + MCP Client                │     │  (Granite/etc)  │
└─────────────────────┘     └──────────┬───────────────────┘     └─────────────────┘
                                       │ MCP Protocol (HTTP)
                              ┌────────┴────────┐
                              ▼                 ▼
               ┌──────────────────────┐  ┌──────────────────────┐
               │  OpenShift MCP       │  │  Kubernetes MCP      │
               │  (Quarkus + Fabric8) │  │  (Official Go)       │
               │                      │  │                      │
               │  19 Custom Tools:    │  │  20+ Generic Tools:  │
               │  • 9 Monitoring      │  │  • Generic CRUD      │
               │  • 5 Deployment      │  │  • Pod exec/logs     │
               │  • 5 Perf Testing    │  │  • Helm management   │
               │  :8080/mcp           │  │  • Events/Namespaces │
               └──────────┬───────────┘  │  :8085/mcp           │
                          │              └──────────┬───────────┘
                          └────────┬───────────────┘
                                   ▼
                        ┌──────────────────────────────┐
                        │   OpenShift Cluster           │
                        │   (Nodes, Pods, Services)     │
                        └──────────────────────────────┘

  Supporting Services:
  ┌──────────────┐  ┌──────────────────┐  ┌──────────────┐
  │ MCP Inspector│  │ LiteLLM Proxy    │  │ PostgreSQL   │
  │ (Testing UI) │  │ (OpenAI compat)  │  │ (LiteLLM DB) │
  └──────────────┘  └──────────────────┘  └──────────────┘
```

## MCP Tools

### Custom Quarkus Server (19 tools) - Port 8080

#### Monitoring (9 tools)

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

#### Deployment (5 tools)

| Tool | Description |
|------|-------------|
| `createDeployment` | Create deployments with custom images, replicas, and resources |
| `deployDatabase` | Deploy databases (PostgreSQL, MySQL, MongoDB, Redis) with RHEL images |
| `createHpa` | Configure horizontal pod autoscalers |
| `createService` | Create ClusterIP/NodePort/LoadBalancer services |
| `createNetworkPolicy` | Create network policies (deny-all, allow-same-namespace) |

#### Performance Testing (5 tools)

| Tool | Description |
|------|-------------|
| `runKubeBurner` | Cluster density testing with pod scheduling benchmarks |
| `runStorageBenchmark` | Storage I/O benchmarks with FIO |
| `runNetworkTest` | Network throughput tests with iperf3 |
| `runCpuStressTest` | CPU/memory stress testing with stress-ng |
| `runDatabaseBenchmark` | Database benchmarks with pgbench/sysbench |

### Official Kubernetes MCP Server (20+ tools) - Port 8085

From [openshift/openshift-mcp-server](https://github.com/openshift/openshift-mcp-server), a Go-based native implementation that interacts directly with the Kubernetes API.

| Toolset | Tools |
|---------|-------|
| **Config** | `configuration_contexts_list`, `targets_list`, `configuration_view` |
| **Core** | `resources_list`, `resources_get`, `resources_create_or_update`, `resources_delete`, `resources_scale`, `pods_list`, `pods_get`, `pods_delete`, `pods_top`, `pods_exec`, `pods_log`, `pods_run`, `namespaces_list`, `projects_list`, `events_list`, `nodes_top`, `nodes_log` |
| **Helm** | `helm_install`, `helm_list`, `helm_uninstall` |

Additional toolsets available via `values.yaml`: `kubevirt`, `observability`, `ossm`, `kcp`.

## Quick Start

### 1. Deploy with Helm (recommended)

```bash
helm install openshift-mcp-server ./helm/openshift-mcp-server \
  --namespace openshift-lightspeed \
  --create-namespace \
  --set image.pullPolicy=Always
```

### 2. Configure OLSConfig

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
      url: 'http://openshift-mcp-server.<namespace>.svc.cluster.local:8080/mcp'
    - name: kubernetes-mcp-server
      timeout: 30
      url: 'http://openshift-mcp-server-k8s-mcp.<namespace>.svc.cluster.local:8085/mcp'
```

> **Important**: Use `/mcp` (Streamable HTTP), not `/mcp/sse`. OLS uses POST requests which require the Streamable HTTP endpoint.

### 3. Deploy without Helm

```bash
oc apply -f k8s/deployment.yaml
oc apply -f k8s/cluster-ols.yml
```

### 4. Verify

```bash
# Check all MCP pods are running
oc get pods -n <namespace> | grep openshift-mcp-server

# Expected: 5 pods (mcp-server, k8s-mcp, inspector, litellm, litellm-db)

# Check OLS loaded the tools
oc logs -n openshift-lightspeed deploy/lightspeed-app-server \
  -c lightspeed-service-api | grep "tools from MCP"
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

## Helm Chart Components

| Component | Image | Port | Description |
|-----------|-------|------|-------------|
| **openshift-mcp-server** | `quay.io/maximilianopizarro/openshift-mcp-server` | 8080 | Custom Quarkus MCP (monitoring, deployment, perf) |
| **kubernetes-mcp-server** | `quay.io/redhat-user-workloads/.../openshift-mcp-server` | 8085 | Official K8s MCP (CRUD, pods, helm, events) |
| **mcp-inspector** | `mcpuse/inspector` | 8080 | MCP testing UI |
| **litellm** | `litellm/litellm-non_root` | 4000 | OpenAI-compatible LLM proxy |
| **litellm-db** | `registry.redhat.io/rhel9/postgresql-15` | 5432 | LiteLLM PostgreSQL backend |

## Cursor MCP Configuration

To use this server with Cursor IDE locally:

```json
{
  "mcpServers": {
    "openshift-mcp-server": {
      "url": "http://localhost:8080/mcp"
    },
    "kubernetes-mcp-server": {
      "url": "http://localhost:8085/mcp"
    }
  }
}
```

## License

Apache License 2.0
