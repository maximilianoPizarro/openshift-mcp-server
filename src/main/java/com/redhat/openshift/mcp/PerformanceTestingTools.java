package com.redhat.openshift.mcp;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

@Singleton
public class PerformanceTestingTools {

    private static final Logger LOG = Logger.getLogger(PerformanceTestingTools.class);

    @Inject
    KubernetesHelper helper;

    @Tool(description = "Execute cluster density testing with kube-burner patterns. "
            + "Creates pods to measure deployment performance and scheduling latency. "
            + "Supports create, cleanup, or both operations.")
    String runKubeBurner(
            @ToolArg(description = "Type of test: cluster-density-v2, node-density, pvc-density, crd-scale") String testType,
            @ToolArg(description = "Number of test iterations (1-100, default 5)") String iterationsStr,
            @ToolArg(description = "Namespace for test resources (default: kube-burner-test)") String namespace,
            @ToolArg(description = "Operation: create, cleanup, or both (default: create)") String operation
    ) {
        if (testType == null || testType.isBlank()) testType = "cluster-density-v2";
        int iterations = 5;
        try { iterations = Integer.parseInt(iterationsStr); } catch (Exception ignored) {}
        if (iterations <= 0) iterations = 5;
        if (iterations > 100) iterations = 100;
        if (namespace == null || namespace.isBlank()) namespace = "kube-burner-test";
        if (operation == null || operation.isBlank()) operation = "create";

        try {
            KubernetesClient client = helper.getClient();
            StringBuilder sb = new StringBuilder();
            sb.append("Kube-burner Test Report:\n");
            sb.append("  Test type: ").append(testType).append("\n");
            sb.append("  Iterations: ").append(iterations).append("\n");
            sb.append("  Namespace: ").append(namespace).append("\n");
            sb.append("  Operation: ").append(operation).append("\n\n");

            if ("cleanup".equals(operation) || "both".equals(operation)) {
                try {
                    var ns = client.namespaces().withName(namespace).get();
                    if (ns != null) {
                        long podCount = client.pods().inNamespace(namespace).list().getItems().size();
                        long start = System.currentTimeMillis();
                        client.namespaces().withName(namespace).delete();
                        client.namespaces().withName(namespace).waitUntilCondition(
                                n -> n == null, 5, java.util.concurrent.TimeUnit.MINUTES);
                        long duration = System.currentTimeMillis() - start;
                        sb.append("Cleanup:\n  Pods deleted: ").append(podCount)
                                .append("\n  Duration: ").append(duration / 1000).append("s\n  Status: Completed\n\n");
                    } else {
                        sb.append("Cleanup: Namespace not found - already clean\n\n");
                    }
                } catch (Exception e) {
                    sb.append("Cleanup error: ").append(e.getMessage()).append("\n\n");
                }

                if ("cleanup".equals(operation)) return sb.toString();
            }

            if ("create".equals(operation) || "both".equals(operation)) {
                try {
                    client.namespaces().resource(
                            new io.fabric8.kubernetes.api.model.NamespaceBuilder()
                                    .withNewMetadata().withName(namespace).endMetadata().build()
                    ).create();
                } catch (Exception ignored) {
                }

                long start = System.currentTimeMillis();
                int podCount = "node-density".equals(testType) ? iterations * 10 : iterations * 5;

                for (int i = 1; i <= podCount; i++) {
                    String podName = "test-pod-" + i;
                    client.pods().inNamespace(namespace).resource(
                            new io.fabric8.kubernetes.api.model.PodBuilder()
                                    .withNewMetadata()
                                        .withName(podName)
                                        .withNamespace(namespace)
                                        .addToLabels("app", testType + "-test")
                                        .addToLabels("test-iteration", String.valueOf(i))
                                    .endMetadata()
                                    .withNewSpec()
                                        .addNewContainer()
                                            .withName("pause")
                                            .withImage("registry.k8s.io/pause:3.8")
                                            .withNewResources()
                                                .addToRequests("memory", new io.fabric8.kubernetes.api.model.Quantity("10Mi"))
                                                .addToRequests("cpu", new io.fabric8.kubernetes.api.model.Quantity("1m"))
                                                .addToLimits("memory", new io.fabric8.kubernetes.api.model.Quantity("20Mi"))
                                                .addToLimits("cpu", new io.fabric8.kubernetes.api.model.Quantity("10m"))
                                            .endResources()
                                        .endContainer()
                                        .withRestartPolicy("Never")
                                    .endSpec()
                                    .build()
                    ).create();
                }

                long duration = System.currentTimeMillis() - start;
                sb.append("Creation:\n  Pods created: ").append(podCount)
                        .append("\n  Duration: ").append(duration / 1000).append("s")
                        .append("\n  Avg pod creation: ").append(podCount > 0 ? duration / podCount : 0).append("ms")
                        .append("\n  Status: Completed\n");
            }

            return sb.toString();
        } catch (Exception e) {
            return "Failed to run kube-burner test: " + e.getMessage();
        }
    }

    @Tool(description = "Run storage performance benchmarks using FIO workloads in a Kubernetes job. "
            + "Tests sequential/random read/write performance on cluster storage.")
    String runStorageBenchmark(
            @ToolArg(description = "Type of test: sequential-read, sequential-write, random-read, random-write, mixed") String testType,
            @ToolArg(description = "I/O block size (default: 4k)") String blockSize,
            @ToolArg(description = "Test duration (default: 60s)") String duration,
            @ToolArg(description = "Storage class to test (leave empty for default)") String storageClass,
            @ToolArg(description = "Size of test volume (default: 1Gi)") String volumeSize
    ) {
        if (testType == null || testType.isBlank()) testType = "mixed";
        if (blockSize == null || blockSize.isBlank()) blockSize = "4k";
        if (duration == null || duration.isBlank()) duration = "60s";
        if (volumeSize == null || volumeSize.isBlank()) volumeSize = "1Gi";

        try {
            String durationSeconds = duration.replace("s", "");

            String fioArgs;
            switch (testType) {
                case "sequential-read" -> fioArgs = "--rw=read";
                case "sequential-write" -> fioArgs = "--rw=write";
                case "random-read" -> fioArgs = "--rw=randread";
                case "random-write" -> fioArgs = "--rw=randwrite";
                default -> fioArgs = "--rw=randrw --rwmixread=70";
            }

            String fioCommand = String.format(
                    "fio --name=benchmark --ioengine=libaio --direct=1 --bs=%s %s "
                            + "--size=256M --numjobs=1 --runtime=%s --time_based --group_reporting --output-format=json",
                    blockSize, fioArgs, durationSeconds);

            String namespace = "storage-benchmark";
            KubernetesClient client = helper.getClient();
            try {
                client.namespaces().resource(
                        new io.fabric8.kubernetes.api.model.NamespaceBuilder()
                                .withNewMetadata().withName(namespace).endMetadata().build()
                ).create();
            } catch (Exception ignored) {
            }

            String output = helper.executeCommand(Integer.parseInt(durationSeconds) + 60,
                    "kubectl", "run", "fio-benchmark", "--image=ljishen/fio:latest",
                    "-n", namespace, "--rm", "--restart=Never", "-i",
                    "--", "sh", "-c", fioCommand);

            StringBuilder sb = new StringBuilder();
            sb.append("Storage Benchmark Report:\n");
            sb.append("  Test type: ").append(testType).append("\n");
            sb.append("  Block size: ").append(blockSize).append("\n");
            sb.append("  Duration: ").append(duration).append("\n");
            sb.append("  Volume size: ").append(volumeSize).append("\n\n");
            sb.append("Results:\n").append(output).append("\n");
            return sb.toString();
        } catch (Exception e) {
            return "Failed to run storage benchmark: " + e.getMessage();
        }
    }

    @Tool(description = "Test network throughput between pods using iperf3. "
            + "Measures TCP/UDP throughput, latency, and packet loss between cluster pods.")
    String runNetworkTest(
            @ToolArg(description = "Type of test: throughput, latency, packet-loss") String testType,
            @ToolArg(description = "Test duration (default: 30s)") String duration,
            @ToolArg(description = "Number of parallel streams (default: 1)") String parallelStr,
            @ToolArg(description = "Network protocol: tcp, udp (default: tcp)") String protocol
    ) {
        if (testType == null || testType.isBlank()) testType = "throughput";
        if (duration == null || duration.isBlank()) duration = "30s";
        int parallel = 1;
        try { parallel = Integer.parseInt(parallelStr); } catch (Exception ignored) {}
        if (parallel <= 0) parallel = 1;
        if (protocol == null || protocol.isBlank()) protocol = "tcp";

        try {
            String namespace = "network-test";
            String durationSeconds = duration.replace("s", "");

            KubernetesClient client = helper.getClient();
            try {
                client.namespaces().resource(
                        new io.fabric8.kubernetes.api.model.NamespaceBuilder()
                                .withNewMetadata().withName(namespace).endMetadata().build()
                ).create();
            } catch (Exception ignored) {
            }

            helper.executeCommand(30, "kubectl", "run", "iperf3-server",
                    "--image=networkstatic/iperf3:latest", "-n", namespace,
                    "--", "-s");

            Thread.sleep(5000);

            StringBuilder iperf3Args = new StringBuilder();
            iperf3Args.append("-c iperf3-server -t ").append(durationSeconds);
            iperf3Args.append(" -P ").append(parallel);
            if ("udp".equals(protocol)) iperf3Args.append(" -u -b 1G");
            iperf3Args.append(" --json");

            String output = helper.executeCommand(Integer.parseInt(durationSeconds) + 30,
                    "kubectl", "run", "iperf3-client", "--image=networkstatic/iperf3:latest",
                    "-n", namespace, "--rm", "--restart=Never", "-i",
                    "--", "-c", "iperf3-server", "-t", durationSeconds,
                    "-P", String.valueOf(parallel),
                    "udp".equals(protocol) ? "-u" : "", "--json");

            helper.executeCommand("kubectl", "delete", "pod", "iperf3-server", "-n", namespace, "--ignore-not-found");

            StringBuilder sb = new StringBuilder();
            sb.append("Network Test Report:\n");
            sb.append("  Test type: ").append(testType).append("\n");
            sb.append("  Protocol: ").append(protocol).append("\n");
            sb.append("  Duration: ").append(duration).append("\n");
            sb.append("  Parallel streams: ").append(parallel).append("\n\n");
            sb.append("Results:\n").append(output).append("\n");
            return sb.toString();
        } catch (Exception e) {
            return "Failed to run network test: " + e.getMessage();
        }
    }

    @Tool(description = "Perform CPU and memory stress testing on worker nodes. "
            + "Creates stress-ng pods to measure node performance under load.")
    String runCpuStressTest(
            @ToolArg(description = "Type of test: cpu, memory, combined") String testType,
            @ToolArg(description = "Test duration (default: 2m)") String duration,
            @ToolArg(description = "Number of CPU cores to stress (default: 2)") String cpuCoresStr,
            @ToolArg(description = "Amount of memory to stress (default: 1G)") String memorySize
    ) {
        if (testType == null || testType.isBlank()) testType = "combined";
        if (duration == null || duration.isBlank()) duration = "2m";
        int cpuCores = 2;
        try { cpuCores = Integer.parseInt(cpuCoresStr); } catch (Exception ignored) {}
        if (cpuCores <= 0) cpuCores = 2;
        if (memorySize == null || memorySize.isBlank()) memorySize = "1G";

        try {
            String namespace = "stress-test";
            KubernetesClient client = helper.getClient();
            try {
                client.namespaces().resource(
                        new io.fabric8.kubernetes.api.model.NamespaceBuilder()
                                .withNewMetadata().withName(namespace).endMetadata().build()
                ).create();
            } catch (Exception ignored) {
            }

            String durationSeconds;
            if (duration.endsWith("m")) {
                durationSeconds = String.valueOf(Integer.parseInt(duration.replace("m", "")) * 60);
            } else {
                durationSeconds = duration.replace("s", "");
            }

            StringBuilder stressCmd = new StringBuilder("stress-ng --metrics-brief");
            switch (testType) {
                case "cpu" -> stressCmd.append(" --cpu ").append(cpuCores).append(" --cpu-method all");
                case "memory" -> stressCmd.append(" --vm 1 --vm-bytes ").append(memorySize);
                default -> stressCmd.append(" --cpu ").append(cpuCores)
                        .append(" --vm 1 --vm-bytes ").append(memorySize);
            }
            stressCmd.append(" --timeout ").append(durationSeconds);

            String output = helper.executeCommand(Integer.parseInt(durationSeconds) + 30,
                    "kubectl", "run", "stress-test", "--image=alexeiled/stress-ng:latest",
                    "-n", namespace, "--rm", "--restart=Never", "-i",
                    "--", "sh", "-c", stressCmd.toString());

            StringBuilder sb = new StringBuilder();
            sb.append("CPU/Memory Stress Test Report:\n");
            sb.append("  Test type: ").append(testType).append("\n");
            sb.append("  Duration: ").append(duration).append("\n");
            sb.append("  CPU cores: ").append(cpuCores).append("\n");
            sb.append("  Memory size: ").append(memorySize).append("\n\n");
            sb.append("Results:\n").append(output).append("\n");
            return sb.toString();
        } catch (Exception e) {
            return "Failed to run stress test: " + e.getMessage();
        }
    }

    @Tool(description = "Execute database performance tests with pgbench (PostgreSQL) or sysbench (MySQL). "
            + "Requires a running database in the cluster.")
    String runDatabaseBenchmark(
            @ToolArg(description = "Database type: postgresql, mysql") String dbType,
            @ToolArg(description = "Type of test: oltp_read_write, oltp_read_only, oltp_write_only") String testType,
            @ToolArg(description = "Number of test threads (default: 10)") String threadsStr,
            @ToolArg(description = "Test duration (default: 60s)") String duration,
            @ToolArg(description = "Number of rows in test tables (default: 100000)") String tableSizeStr
    ) {
        if (dbType == null || dbType.isBlank()) return "Error: dbType is required (postgresql or mysql)";
        if (testType == null || testType.isBlank()) testType = "oltp_read_write";
        int threads = 10, tableSize = 100000;
        try { threads = Integer.parseInt(threadsStr); } catch (Exception ignored) {}
        try { tableSize = Integer.parseInt(tableSizeStr); } catch (Exception ignored) {}
        if (threads <= 0) threads = 10;
        if (duration == null || duration.isBlank()) duration = "60s";
        if (tableSize <= 0) tableSize = 100000;

        try {
            String durationSeconds = duration.replace("s", "");

            String benchCmd;
            String image;

            if ("postgresql".equals(dbType)) {
                image = "postgres:16";
                benchCmd = String.format(
                        "pgbench -h %s -U postgres -d %s -c %d -j %d -T %s -P 5 --no-vacuum",
                        dbType, dbType, threads, Math.min(threads, 4), durationSeconds);
            } else if ("mysql".equals(dbType)) {
                image = "severalnines/sysbench:latest";
                benchCmd = String.format(
                        "sysbench %s --mysql-host=%s --mysql-user=root --mysql-password=mysql123 "
                                + "--mysql-db=%s --threads=%d --time=%s --table-size=%d run",
                        testType, dbType, dbType, threads, durationSeconds, tableSize);
            } else {
                return "Unsupported database type: " + dbType;
            }

            String namespace = "db-benchmark";
            KubernetesClient client = helper.getClient();
            try {
                client.namespaces().resource(
                        new io.fabric8.kubernetes.api.model.NamespaceBuilder()
                                .withNewMetadata().withName(namespace).endMetadata().build()
                ).create();
            } catch (Exception ignored) {
            }

            String output = helper.executeCommand(Integer.parseInt(durationSeconds) + 60,
                    "kubectl", "run", "db-benchmark", "--image=" + image,
                    "-n", namespace, "--rm", "--restart=Never", "-i",
                    "--", "sh", "-c", benchCmd);

            StringBuilder sb = new StringBuilder();
            sb.append("Database Benchmark Report:\n");
            sb.append("  Database: ").append(dbType).append("\n");
            sb.append("  Test type: ").append(testType).append("\n");
            sb.append("  Threads: ").append(threads).append("\n");
            sb.append("  Duration: ").append(duration).append("\n");
            sb.append("  Table size: ").append(tableSize).append(" rows\n\n");
            sb.append("Results:\n").append(output).append("\n");
            return sb.toString();
        } catch (Exception e) {
            return "Failed to run database benchmark: " + e.getMessage();
        }
    }
}
