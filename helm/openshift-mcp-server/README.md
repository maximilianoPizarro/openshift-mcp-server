# OpenShift MCP Server - Helm Chart

> **⚠️ EXPERIMENTAL** — This chart is under active development. Values and tool definitions may change without notice.

Enterprise MCP Server for [OpenShift Lightspeed](https://www.redhat.com/en/technologies/cloud-computing/openshift/lightspeed). Dual server deployment combining a custom Quarkus server (19 operational tools) with the official [openshift/openshift-mcp-server](https://github.com/openshift/openshift-mcp-server) (20+ Kubernetes tools).

## Install

```bash
helm repo add openshift-mcp https://maximilianoPizarro.github.io/openshift-mcp-server
helm repo update

helm install openshift-mcp-server openshift-mcp/openshift-mcp-server \
  --namespace openshift-lightspeed \
  --create-namespace \
  --set namespace=openshift-lightspeed
```

## Components

| Component | Image | Port | Description |
|-----------|-------|------|-------------|
| **openshift-mcp-server** | `quay.io/maximilianopizarro/openshift-mcp-server` | 8080 | Custom Quarkus MCP (monitoring, deployment, perf testing) |
| **kubernetes-mcp-server** | `quay.io/redhat-user-workloads/.../openshift-mcp-server` | 8085 | Official K8s MCP (CRUD, pods, helm, events) |
| **mcp-inspector** | `mcpuse/inspector` | 8080 | MCP testing UI |
| **litellm** | `litellm/litellm-non_root` | 4000 | OpenAI-compatible LLM proxy |
| **litellm-db** | `registry.redhat.io/rhel9/postgresql-15` | 5432 | PostgreSQL backend |

## MCP Tools

### Quarkus Server (19 tools) — Port 8080

**Monitoring (9):** `checkClusterHealth`, `getPerformanceMetrics`, `detectResourceIssues`, `analyzePodDisruptions`, `checkNodeConditions`, `monitorDeployments`, `checkKubeletStatus`, `checkCrioStatus`, `analyzeJournalctlPodErrors`

**Deployment (5):** `createDeployment`, `deployDatabase`, `createHpa`, `createService`, `createNetworkPolicy`

**Performance Testing (5):** `runKubeBurner`, `runStorageBenchmark`, `runNetworkTest`, `runCpuStressTest`, `runDatabaseBenchmark`

### Official Kubernetes MCP Server (20+ tools) — Port 8085

**Config:** `configuration_contexts_list`, `targets_list`, `configuration_view`

**Core:** `resources_list`, `resources_get`, `resources_create_or_update`, `resources_delete`, `resources_scale`, `pods_list`, `pods_get`, `pods_delete`, `pods_top`, `pods_exec`, `pods_log`, `pods_run`, `namespaces_list`, `projects_list`, `events_list`, `nodes_top`, `nodes_log`

**Helm:** `helm_install`, `helm_list`, `helm_uninstall`

## OLSConfig Integration

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

> Use `/mcp` (Streamable HTTP), not `/mcp/sse`. OLS uses POST requests.

## Key Values

| Parameter | Default | Description |
|-----------|---------|-------------|
| `namespace` | `maximilianopizarro5-dev` | Target namespace |
| `serviceAccount.create` | `true` | Create SA with cluster-admin |
| `serviceAccount.name` | `openshift-mcp-server` | SA name |
| `inspector.enabled` | `true` | Deploy MCP Inspector UI |
| `litellm.enabled` | `true` | Deploy LiteLLM proxy |
| `litellm.model.name` | `qwen3` | Model name exposed by LiteLLM |
| `litellm.model.apiBase` | *(see values.yaml)* | vLLM inference endpoint |
| `kubernetesMcp.enabled` | `true` | Deploy official K8s MCP server |
| `kubernetesMcp.readOnly` | `true` | Read-only mode (no create/delete) |
| `kubernetesMcp.toolsets` | `core,config,helm` | Enabled toolsets |

## Technology Stack

- **Runtime**: Quarkus 3.27.3 (Java 21) + Fabric8 Kubernetes Client
- **MCP Protocol**: Streamable HTTP via Quarkiverse MCP Server 1.8.1
- **Container**: UBI9 OpenJDK 21 Runtime
- **LLM**: Qwen3 8B on vLLM with native tool calling (hermes parser)

## License

Apache License 2.0

Created by [maximilianoPizarro](https://maximilianopizarro.github.io)
