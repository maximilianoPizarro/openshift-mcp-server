---
layout: default
title: OpenShift MCP Server
---

<section class="hero">
  <div class="hero-content">
    <img src="{{ site.baseurl }}/openshift-logo.png" alt="OpenShift">
    <h2>OpenShift MCP Server</h2>
    <p>Enterprise MCP Server for OpenShift Lightspeed. Dual server deployment combining custom operational tools with the official Kubernetes MCP server.</p>
    <div class="badge-row">
      <span class="badge badge-red">Quarkus</span>
      <span class="badge badge-blue">MCP Protocol</span>
      <span class="badge badge-dark">40+ Tools</span>
      <span class="badge badge-green">Helm Chart</span>
    </div>
    <div class="install-box">
      <div class="label">Install via Helm</div>
      <code>helm repo add openshift-mcp https://maximilianoPizarro.github.io/openshift-mcp-server/charts<br>
helm install openshift-mcp-server openshift-mcp/openshift-mcp-server -n openshift-lightspeed</code>
    </div>
  </div>
</section>

<div class="main">

<section>
<h2>Architecture</h2>
<div class="cards">
  <div class="card">
    <h3><span class="icon icon-red">Q</span> Quarkus MCP Server</h3>
    <p>Custom Java server with 19 operational tools for monitoring, deployment, and performance testing. Built with Fabric8 Kubernetes Client.</p>
    <ul>
      <li>checkClusterHealth</li>
      <li>getPerformanceMetrics</li>
      <li>createDeployment</li>
      <li>deployDatabase</li>
      <li>runKubeBurner</li>
      <li>+ 14 more tools</li>
    </ul>
  </div>
  <div class="card">
    <h3><span class="icon icon-blue">K</span> Kubernetes MCP Server</h3>
    <p>Official Go-based server from <a href="https://github.com/openshift/openshift-mcp-server">openshift/openshift-mcp-server</a>. Generic CRUD, pod management, Helm operations.</p>
    <ul>
      <li>resources_list / get / create / delete</li>
      <li>pods_exec / pods_log</li>
      <li>helm_install / helm_list</li>
      <li>events_list / namespaces_list</li>
      <li>nodes_top / pods_top</li>
      <li>+ 10 more tools</li>
    </ul>
  </div>
  <div class="card">
    <h3><span class="icon icon-green">+</span> Supporting Services</h3>
    <p>Included in the Helm chart for a complete AI-assisted operations stack.</p>
    <ul>
      <li>MCP Inspector (testing UI)</li>
      <li>LiteLLM Proxy (OpenAI compat)</li>
      <li>PostgreSQL (LiteLLM backend)</li>
    </ul>
  </div>
</div>
</section>

<section>
<h2>Helm Chart</h2>

| Version | App Version | Description |
|---------|-------------|-------------|
| `1.0.0` | `1.0.0` | Dual MCP server + Inspector + LiteLLM |

### Add the Helm Repository

```bash
helm repo add openshift-mcp https://maximilianoPizarro.github.io/openshift-mcp-server/charts
helm repo update
```

### Install

```bash
helm install openshift-mcp-server openshift-mcp/openshift-mcp-server \
  --namespace openshift-lightspeed \
  --set namespace=openshift-lightspeed \
  --set serviceAccount.create=true
```

### Configure with an existing RHOAI model

```bash
helm install openshift-mcp-server openshift-mcp/openshift-mcp-server \
  --namespace openshift-lightspeed \
  --set namespace=openshift-lightspeed \
  --set litellm.model.name=llama-32-3b-instruct \
  --set litellm.model.modelId=llama-32-3b-instruct \
  --set litellm.model.apiBase=http://llama-32-3b-instruct-openai.my-first-model.svc.cluster.local/v1
```

</section>

<section>
<h2>Custom Tools (Quarkus Server)</h2>

<h3>Monitoring (9 tools)</h3>

| Tool | Description |
|------|-------------|
| `checkClusterHealth` | Overall cluster health, node/pod status, critical issues |
| `getPerformanceMetrics` | Node and pod CPU/memory metrics |
| `detectResourceIssues` | Pods with high CPU/memory or excessive restarts |
| `analyzePodDisruptions` | Evictions, OOM kills, restart patterns |
| `checkNodeConditions` | Node conditions, taints, allocatable resources |
| `monitorDeployments` | Deployment rollout health and replica status |
| `checkKubeletStatus` | Kubelet service status and journal logs |
| `checkCrioStatus` | CRI-O container runtime status |
| `analyzeJournalctlPodErrors` | Journal log analysis with filters |

<h3>Deployment (5 tools)</h3>

| Tool | Description |
|------|-------------|
| `createDeployment` | Create deployments with custom config |
| `deployDatabase` | Deploy PostgreSQL/MySQL/MongoDB/Redis (RHEL images) |
| `createHpa` | Configure horizontal pod autoscalers |
| `createService` | Create ClusterIP/NodePort/LoadBalancer services |
| `createNetworkPolicy` | Create network policies |

<h3>Performance Testing (5 tools)</h3>

| Tool | Description |
|------|-------------|
| `runKubeBurner` | Cluster density testing |
| `runStorageBenchmark` | Storage I/O benchmarks with FIO |
| `runNetworkTest` | Network throughput with iperf3 |
| `runCpuStressTest` | CPU/memory stress testing |
| `runDatabaseBenchmark` | Database benchmarks |

</section>

<section>
<h2>OLSConfig Integration</h2>

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
    - name: kubernetes-mcp-server
      timeout: 30
      url: 'http://openshift-mcp-server-k8s-mcp.openshift-lightspeed.svc.cluster.local:8085/mcp'
```

</section>

<section>
<h2>Technology Stack</h2>

| Component | Technology |
|-----------|-----------|
| Custom MCP Server | Quarkus 3.27.3 + Fabric8 Kubernetes Client |
| Official MCP Server | Go (openshift/openshift-mcp-server) |
| MCP Protocol | Streamable HTTP (POST /mcp) |
| Container Runtime | UBI9 OpenJDK 21 Runtime |
| Helm Chart | v2 with 5 deployments |
| LLM Integration | LiteLLM proxy (OpenAI compatible) |

</section>

</div>
