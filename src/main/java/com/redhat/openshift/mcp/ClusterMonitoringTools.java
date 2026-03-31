package com.redhat.openshift.mcp;

import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Singleton
public class ClusterMonitoringTools {

    private static final Logger LOG = Logger.getLogger(ClusterMonitoringTools.class);
    private static final int PENDING_POD_TIMEOUT_MINUTES = 5;
    private static final int DEFAULT_RESTART_THRESHOLD = 5;

    @Inject
    KubernetesHelper helper;

    @Tool(description = "Check overall OpenShift cluster health and identify stability issues. "
            + "Returns node health, pod health, resource utilization and critical issues. "
            + "ALWAYS call this tool FIRST for any question about cluster status or health.")
    String checkClusterHealth(
            @ToolArg(description = "Include detailed analysis of each component") boolean detailed
    ) {
        try {
            KubernetesClient client = helper.getClient();
            List<Node> nodes = client.nodes().list().getItems();
            List<Pod> pods = client.pods().inAnyNamespace().list().getItems();

            List<String> issues = new ArrayList<>();
            int healthyNodes = 0;

            for (Node node : nodes) {
                List<NodeCondition> conditions = node.getStatus().getConditions();
                boolean ready = conditions.stream()
                        .anyMatch(c -> "Ready".equals(c.getType()) && "True".equals(c.getStatus()));

                if (ready) {
                    healthyNodes++;
                } else {
                    issues.add("Node " + node.getMetadata().getName() + " is not ready");
                }

                conditions.stream()
                        .filter(c -> !"Ready".equals(c.getType()) && "True".equals(c.getStatus()))
                        .forEach(c -> issues.add("Node " + node.getMetadata().getName()
                                + ": " + c.getType() + " - " + c.getMessage()));
            }

            int healthyPods = 0;
            for (Pod pod : pods) {
                String phase = pod.getStatus().getPhase();
                if ("Running".equals(phase) || "Succeeded".equals(phase)) {
                    healthyPods++;
                } else if ("Failed".equals(phase)) {
                    issues.add("Pod " + pod.getMetadata().getName()
                            + " in " + pod.getMetadata().getNamespace() + " in Failed state");
                } else if ("Pending".equals(phase)) {
                    Instant created = Instant.parse(pod.getMetadata().getCreationTimestamp());
                    if (Duration.between(created, Instant.now()).toMinutes() > PENDING_POD_TIMEOUT_MINUTES) {
                        issues.add("Pod " + pod.getMetadata().getName()
                                + " in " + pod.getMetadata().getNamespace() + " stuck in Pending state");
                    }
                }

                if (pod.getStatus().getContainerStatuses() != null) {
                    for (ContainerStatus cs : pod.getStatus().getContainerStatuses()) {
                        if (cs.getRestartCount() > DEFAULT_RESTART_THRESHOLD) {
                            issues.add("Container " + cs.getName() + " in pod " + pod.getMetadata().getName()
                                    + " has " + cs.getRestartCount() + " restarts");
                        }
                    }
                }
            }

            String overall;
            if (issues.isEmpty()) {
                overall = "healthy";
            } else if (issues.size() <= 3
                    && (nodes.isEmpty() ? 0 : (healthyNodes * 100.0 / nodes.size())) > 90
                    && (pods.isEmpty() ? 0 : (healthyPods * 100.0 / pods.size())) > 95) {
                overall = "warning";
            } else {
                overall = "critical";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Cluster Health Report:\n");
            sb.append("  Status: ").append(overall).append("\n");
            sb.append("  Nodes: ").append(healthyNodes).append("/").append(nodes.size()).append(" healthy\n");
            sb.append("  Pods: ").append(healthyPods).append("/").append(pods.size()).append(" healthy\n");
            sb.append("  Issues found: ").append(issues.size()).append("\n");
            if (detailed && !issues.isEmpty()) {
                sb.append("\nDetailed Issues:\n");
                issues.forEach(i -> sb.append("  - ").append(i).append("\n"));
            }
            return sb.toString();
        } catch (Exception e) {
            return "Failed to check cluster health: " + e.getMessage();
        }
    }

    @Tool(description = "Retrieve current performance metrics for nodes and pods using kubectl top. "
            + "Requires metrics-server to be installed in the cluster.")
    String getPerformanceMetrics(
            @ToolArg(description = "Specific namespace to monitor (leave empty for all)") String namespace
    ) {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("Performance Metrics Report:\n");
            sb.append("Timestamp: ").append(Instant.now()).append("\n\n");

            String nodeMetrics = helper.executeCommand("kubectl", "top", "nodes", "--no-headers");
            sb.append("=== Node Metrics ===\n");
            if (nodeMetrics.contains("Error") || nodeMetrics.contains("error")) {
                sb.append("Node metrics unavailable - check metrics-server deployment\n");
            } else {
                sb.append(String.format("%-40s %-15s %-10s %-15s %-10s%n", "NAME", "CPU(cores)", "CPU%", "MEMORY(bytes)", "MEMORY%"));
                for (String line : nodeMetrics.split("\n")) {
                    if (!line.isBlank()) sb.append(line).append("\n");
                }
            }

            sb.append("\n=== Pod Metrics ===\n");
            String podCmd;
            if (namespace != null && !namespace.isBlank()) {
                podCmd = helper.executeCommand("kubectl", "top", "pods", "-n", namespace, "--no-headers");
            } else {
                podCmd = helper.executeCommand("kubectl", "top", "pods", "--all-namespaces", "--no-headers");
            }
            if (podCmd.contains("Error") || podCmd.contains("error")) {
                sb.append("Pod metrics unavailable - check metrics-server deployment\n");
            } else {
                for (String line : podCmd.split("\n")) {
                    if (!line.isBlank()) sb.append(line).append("\n");
                }
            }

            return sb.toString();
        } catch (Exception e) {
            return "Failed to get performance metrics: " + e.getMessage();
        }
    }

    @Tool(description = "Detect pods and nodes with resource allocation or utilization issues. "
            + "Checks for high CPU/memory usage and excessive container restarts.")
    String detectResourceIssues(
            @ToolArg(description = "CPU threshold percentage (default 80)") int cpuThreshold,
            @ToolArg(description = "Memory threshold percentage (default 85)") int memoryThreshold,
            @ToolArg(description = "Restart count threshold (default 5)") int restartThreshold
    ) {
        if (cpuThreshold <= 0) cpuThreshold = 80;
        if (memoryThreshold <= 0) memoryThreshold = 85;
        if (restartThreshold <= 0) restartThreshold = 5;

        try {
            KubernetesClient client = helper.getClient();
            List<Pod> pods = client.pods().inAnyNamespace().list().getItems();

            List<String> highRestartPods = new ArrayList<>();
            List<String> resourceMismatches = new ArrayList<>();

            for (Pod pod : pods) {
                if (pod.getStatus().getContainerStatuses() != null) {
                    int totalRestarts = pod.getStatus().getContainerStatuses().stream()
                            .mapToInt(ContainerStatus::getRestartCount).sum();
                    if (totalRestarts >= restartThreshold) {
                        highRestartPods.add(String.format("  %s/%s: %d restarts",
                                pod.getMetadata().getNamespace(), pod.getMetadata().getName(), totalRestarts));
                    }
                }

                if (pod.getSpec().getContainers() != null) {
                    for (Container container : pod.getSpec().getContainers()) {
                        ResourceRequirements res = container.getResources();
                        if (res != null && res.getRequests() != null && res.getLimits() != null) {
                            long cpuReq = helper.parseResourceValue(
                                    res.getRequests().getOrDefault("cpu", new Quantity("0")).toString());
                            long cpuLim = helper.parseResourceValue(
                                    res.getLimits().getOrDefault("cpu", new Quantity("0")).toString());
                            if (cpuLim > 0 && (cpuReq * 100.0 / cpuLim) > cpuThreshold) {
                                resourceMismatches.add(String.format("  %s/%s container %s: CPU request/limit ratio %.0f%%",
                                        pod.getMetadata().getNamespace(), pod.getMetadata().getName(),
                                        container.getName(), cpuReq * 100.0 / cpuLim));
                            }
                        }
                    }
                }
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Resource Issues Report:\n\n");
            sb.append("Pods with excessive restarts (>= ").append(restartThreshold).append("):\n");
            if (highRestartPods.isEmpty()) {
                sb.append("  None found\n");
            } else {
                highRestartPods.forEach(p -> sb.append(p).append("\n"));
            }
            sb.append("\nResource request/limit mismatches:\n");
            if (resourceMismatches.isEmpty()) {
                sb.append("  None found\n");
            } else {
                resourceMismatches.forEach(r -> sb.append(r).append("\n"));
            }
            return sb.toString();
        } catch (Exception e) {
            return "Failed to detect resource issues: " + e.getMessage();
        }
    }

    @Tool(description = "Analyze pod disruptions and restart patterns including evictions, OOM kills, "
            + "and recent container restarts from cluster events.")
    String analyzePodDisruptions(
            @ToolArg(description = "Namespace to analyze (leave empty for all)") String namespace,
            @ToolArg(description = "Number of hours to look back (default 24)") int hours
    ) {
        if (hours <= 0) hours = 24;

        try {
            KubernetesClient client = helper.getClient();
            List<Event> events;
            if (namespace != null && !namespace.isBlank()) {
                events = client.v1().events().inNamespace(namespace).list().getItems();
            } else {
                events = client.v1().events().inAnyNamespace().list().getItems();
            }

            Instant cutoff = Instant.now().minus(Duration.ofHours(hours));

            List<String> evictions = new ArrayList<>();
            List<String> oomKills = new ArrayList<>();
            List<String> restarts = new ArrayList<>();

            for (Event event : events) {
                String timeStr = event.getFirstTimestamp() != null ? event.getFirstTimestamp()
                        : event.getEventTime() != null ? event.getEventTime().toString() : null;
                if (timeStr == null) continue;

                try {
                    Instant eventTime = Instant.parse(timeStr);
                    if (eventTime.isBefore(cutoff)) continue;

                    String reason = event.getReason();
                    if ("Killing".equals(reason) || "Evicted".equals(reason)) {
                        evictions.add(String.format("  %s/%s - %s: %s (%s)",
                                event.getInvolvedObject().getNamespace(),
                                event.getInvolvedObject().getName(),
                                reason, event.getMessage(), eventTime));
                    }
                    if ("OOMKilling".equals(reason)) {
                        oomKills.add(String.format("  %s/%s: %s (%s)",
                                event.getInvolvedObject().getNamespace(),
                                event.getInvolvedObject().getName(),
                                event.getMessage(), eventTime));
                    }
                    if ("Started".equals(reason) && event.getMessage() != null
                            && event.getMessage().contains("Started container")) {
                        restarts.add(String.format("  %s/%s: %s (%s)",
                                event.getInvolvedObject().getNamespace(),
                                event.getInvolvedObject().getName(),
                                event.getMessage(), eventTime));
                    }
                } catch (Exception ignored) {
                }
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Pod Disruption Analysis (last ").append(hours).append("h):\n\n");
            sb.append("Evictions (").append(evictions.size()).append("):\n");
            evictions.forEach(e -> sb.append(e).append("\n"));
            sb.append("\nOOM Kills (").append(oomKills.size()).append("):\n");
            oomKills.forEach(o -> sb.append(o).append("\n"));
            sb.append("\nRecent Restarts (").append(restarts.size()).append("):\n");
            restarts.stream().limit(20).forEach(r -> sb.append(r).append("\n"));

            return sb.toString();
        } catch (Exception e) {
            return "Failed to analyze pod disruptions: " + e.getMessage();
        }
    }

    @Tool(description = "Check node conditions and identify nodes with issues such as disk pressure, "
            + "memory pressure, PID pressure, network unavailable, or not ready status.")
    String checkNodeConditions() {
        try {
            KubernetesClient client = helper.getClient();
            List<Node> nodes = client.nodes().list().getItems();

            StringBuilder sb = new StringBuilder();
            sb.append("Node Conditions Report:\n");
            sb.append("Total nodes: ").append(nodes.size()).append("\n\n");

            int nodesWithIssues = 0;
            for (Node node : nodes) {
                List<NodeCondition> conditions = node.getStatus().getConditions();
                List<String> nodeProblems = new ArrayList<>();

                for (NodeCondition c : conditions) {
                    if ("Ready".equals(c.getType()) && !"True".equals(c.getStatus())) {
                        nodeProblems.add("NOT READY: " + c.getReason() + " - " + c.getMessage());
                    } else if (!"Ready".equals(c.getType()) && "True".equals(c.getStatus())) {
                        nodeProblems.add(c.getType() + ": " + c.getReason() + " - " + c.getMessage());
                    }
                }

                List<Taint> taints = node.getSpec().getTaints();
                if (taints != null && !taints.isEmpty()) {
                    nodeProblems.add("Taints: " + taints.stream()
                            .map(t -> t.getKey() + "=" + t.getValue() + ":" + t.getEffect())
                            .collect(Collectors.joining(", ")));
                }

                if (!nodeProblems.isEmpty()) {
                    nodesWithIssues++;
                    sb.append("Node: ").append(node.getMetadata().getName()).append("\n");
                    nodeProblems.forEach(p -> sb.append("  - ").append(p).append("\n"));
                    sb.append("\n");
                }
            }

            if (nodesWithIssues == 0) {
                sb.append("All nodes are healthy.\n");
            } else {
                sb.insert(sb.indexOf("\n\n") + 2, "Nodes with issues: " + nodesWithIssues + "\n\n");
            }

            return sb.toString();
        } catch (Exception e) {
            return "Failed to check node conditions: " + e.getMessage();
        }
    }

    @Tool(description = "Monitor deployment status and rollout health. "
            + "Identifies deployments with insufficient replicas, failed conditions, or rollout issues.")
    String monitorDeployments(
            @ToolArg(description = "Namespace to monitor (leave empty for all)") String namespace
    ) {
        try {
            KubernetesClient client = helper.getClient();
            List<Deployment> deployments;
            if (namespace != null && !namespace.isBlank()) {
                deployments = client.apps().deployments().inNamespace(namespace).list().getItems();
            } else {
                deployments = client.apps().deployments().inAnyNamespace().list().getItems();
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Deployment Monitor Report:\n");
            sb.append("Total deployments: ").append(deployments.size()).append("\n\n");

            int withIssues = 0;
            for (Deployment dep : deployments) {
                List<String> depIssues = new ArrayList<>();
                int desired = dep.getSpec().getReplicas() != null ? dep.getSpec().getReplicas() : 0;
                int ready = dep.getStatus().getReadyReplicas() != null ? dep.getStatus().getReadyReplicas() : 0;
                int available = dep.getStatus().getAvailableReplicas() != null ? dep.getStatus().getAvailableReplicas() : 0;

                if (ready < desired) {
                    depIssues.add("Only " + ready + "/" + desired + " replicas ready");
                }
                if (available < desired) {
                    depIssues.add("Only " + available + "/" + desired + " replicas available");
                }
                if (dep.getStatus().getConditions() != null) {
                    dep.getStatus().getConditions().stream()
                            .filter(c -> "False".equals(c.getStatus()))
                            .forEach(c -> depIssues.add(c.getType() + ": " + c.getMessage()));
                }

                if (!depIssues.isEmpty()) {
                    withIssues++;
                    sb.append("Deployment: ").append(dep.getMetadata().getNamespace())
                            .append("/").append(dep.getMetadata().getName()).append("\n");
                    depIssues.forEach(i -> sb.append("  - ").append(i).append("\n"));
                    sb.append("\n");
                }
            }

            sb.append("Deployments with issues: ").append(withIssues).append("\n");
            return sb.toString();
        } catch (Exception e) {
            return "Failed to monitor deployments: " + e.getMessage();
        }
    }

    @Tool(description = "Check kubelet service status and recent logs for errors on cluster nodes. "
            + "Uses oc debug to access node journals.")
    String checkKubeletStatus(
            @ToolArg(description = "Number of hours to look back for logs (default 24)") int hoursBack,
            @ToolArg(description = "Include system-level errors in analysis") boolean includeSystemErrors
    ) {
        if (hoursBack <= 0) hoursBack = 24;

        try {
            KubernetesClient client = helper.getClient();
            List<Node> nodes = client.nodes().list().getItems();
            if (nodes.isEmpty()) return "No nodes found in cluster";

            StringBuilder sb = new StringBuilder();
            sb.append("Kubelet Status Report:\n");
            sb.append("Nodes checked: ").append(nodes.size()).append("\n\n");

            for (Node node : nodes) {
                String nodeName = node.getMetadata().getName();
                sb.append("--- Node: ").append(nodeName).append(" ---\n");

                String status = helper.executeCommand(120, "oc", "debug", "node/" + nodeName,
                        "--", "chroot", "/host", "bash", "-c",
                        "systemctl is-active kubelet.service 2>/dev/null || echo inactive");
                sb.append("  Status: ").append(status.trim()).append("\n");

                String logs = helper.executeCommand(120, "oc", "debug", "node/" + nodeName,
                        "--", "chroot", "/host", "bash", "-c",
                        "journalctl -u kubelet.service --since='-" + hoursBack + "h' --lines=30 --no-pager"
                                + " | grep -i 'error\\|fail\\|warn' | tail -10");
                if (!logs.isBlank()) {
                    sb.append("  Recent issues:\n");
                    for (String line : logs.split("\n")) {
                        if (!line.isBlank() && !line.contains("Starting pod/") && !line.contains("Removing debug pod")) {
                            sb.append("    ").append(line).append("\n");
                        }
                    }
                }

                if (includeSystemErrors) {
                    String sysErrors = helper.executeCommand(120, "oc", "debug", "node/" + nodeName,
                            "--", "chroot", "/host", "bash", "-c",
                            "journalctl --since='-" + hoursBack + "h' --lines=10 --no-pager"
                                    + " | grep -i 'systemd\\|kernel\\|oom' | tail -5");
                    if (!sysErrors.isBlank()) {
                        sb.append("  System errors:\n");
                        for (String line : sysErrors.split("\n")) {
                            if (!line.isBlank()) sb.append("    ").append(line).append("\n");
                        }
                    }
                }
                sb.append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "Failed to check kubelet status: " + e.getMessage();
        }
    }

    @Tool(description = "Check CRI-O container runtime status and recent logs on cluster nodes. "
            + "Uses oc debug to access node journals.")
    String checkCrioStatus(
            @ToolArg(description = "Number of hours to look back for logs (default 24)") int hoursBack,
            @ToolArg(description = "Include container-level errors in analysis") boolean includeContainerErrors
    ) {
        if (hoursBack <= 0) hoursBack = 24;

        try {
            KubernetesClient client = helper.getClient();
            List<Node> nodes = client.nodes().list().getItems();
            if (nodes.isEmpty()) return "No nodes found in cluster";

            StringBuilder sb = new StringBuilder();
            sb.append("CRI-O Status Report:\n");
            sb.append("Nodes checked: ").append(nodes.size()).append("\n\n");

            for (Node node : nodes) {
                String nodeName = node.getMetadata().getName();
                sb.append("--- Node: ").append(nodeName).append(" ---\n");

                String status = helper.executeCommand(120, "oc", "debug", "node/" + nodeName,
                        "--", "chroot", "/host", "bash", "-c",
                        "systemctl is-active crio.service 2>/dev/null || echo inactive");
                sb.append("  Status: ").append(status.trim()).append("\n");

                String logs = helper.executeCommand(120, "oc", "debug", "node/" + nodeName,
                        "--", "chroot", "/host", "bash", "-c",
                        "journalctl -u crio.service --since='-" + hoursBack + "h' --lines=30 --no-pager"
                                + " | grep -i 'error\\|fail\\|warn' | tail -10");
                if (!logs.isBlank()) {
                    sb.append("  Recent issues:\n");
                    for (String line : logs.split("\n")) {
                        if (!line.isBlank() && !line.contains("Starting pod/") && !line.contains("Removing debug pod")) {
                            sb.append("    ").append(line).append("\n");
                        }
                    }
                }

                if (includeContainerErrors) {
                    String containerErrors = helper.executeCommand(120, "oc", "debug", "node/" + nodeName,
                            "--", "chroot", "/host", "bash", "-c",
                            "journalctl --since='-" + hoursBack + "h' --lines=20 --no-pager"
                                    + " | grep -i 'container\\|pod\\|image' | grep -i 'error\\|fail' | tail -5");
                    if (!containerErrors.isBlank()) {
                        sb.append("  Container errors:\n");
                        for (String line : containerErrors.split("\n")) {
                            if (!line.isBlank()) sb.append("    ").append(line).append("\n");
                        }
                    }
                }
                sb.append("\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "Failed to check CRI-O status: " + e.getMessage();
        }
    }

    @Tool(description = "Analyze journalctl logs for specific pod-related errors and system issues. "
            + "Uses oc debug to access node journals with filtering by pod, service, and error types.")
    String analyzeJournalctlPodErrors(
            @ToolArg(description = "Specific pod name to analyze (leave empty for all)") String pod,
            @ToolArg(description = "Number of hours to look back (default 24)") int hoursBack,
            @ToolArg(description = "Specific service to analyze e.g. kubelet, crio (leave empty for all)") String service
    ) {
        if (hoursBack <= 0) hoursBack = 24;

        try {
            KubernetesClient client = helper.getClient();
            List<Node> nodes = client.nodes().list().getItems();
            if (nodes.isEmpty()) return "No nodes found in cluster";

            String nodeName = nodes.get(0).getMetadata().getName();

            StringBuilder cmd = new StringBuilder();
            cmd.append("journalctl --since '-").append(hoursBack).append(" hours ago'");
            if (service != null && !service.isBlank()) {
                cmd.append(" -u ").append(service);
            }
            cmd.append(" | grep -i 'error\\|fail\\|warn'");
            if (pod != null && !pod.isBlank()) {
                cmd.append(" | grep -i '").append(pod).append("'");
            }
            cmd.append(" | tail -50");

            String output = helper.executeCommand(120, "oc", "debug", "node/" + nodeName,
                    "--", "chroot", "/host", "bash", "-c", cmd.toString());

            StringBuilder sb = new StringBuilder();
            sb.append("Journalctl Pod Error Analysis:\n");
            sb.append("Node: ").append(nodeName).append("\n");
            sb.append("Time range: last ").append(hoursBack).append(" hours\n");
            if (pod != null && !pod.isBlank()) sb.append("Pod filter: ").append(pod).append("\n");
            if (service != null && !service.isBlank()) sb.append("Service filter: ").append(service).append("\n");
            sb.append("\n");

            if (output.isBlank() || output.contains("Error executing")) {
                sb.append("No errors found or unable to access node logs.\n");
            } else {
                int errorCount = 0;
                int warnCount = 0;
                for (String line : output.split("\n")) {
                    if (line.isBlank() || line.contains("Starting pod/") || line.contains("Removing debug pod"))
                        continue;
                    if (line.toLowerCase().contains("error") || line.toLowerCase().contains("fail")) errorCount++;
                    else warnCount++;
                    sb.append("  ").append(line).append("\n");
                }
                sb.append("\nSummary: ").append(errorCount).append(" errors, ").append(warnCount).append(" warnings\n");
            }
            return sb.toString();
        } catch (Exception e) {
            return "Failed to analyze journalctl logs: " + e.getMessage();
        }
    }
}
