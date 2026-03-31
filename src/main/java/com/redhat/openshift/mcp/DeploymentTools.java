package com.redhat.openshift.mcp;

import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.autoscaling.v2.HorizontalPodAutoscalerBuilder;
import io.fabric8.kubernetes.api.model.autoscaling.v2.MetricSpecBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.quarkiverse.mcp.server.Tool;
import io.quarkiverse.mcp.server.ToolArg;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import org.jboss.logging.Logger;

import java.util.*;

@Singleton
public class DeploymentTools {

    private static final Logger LOG = Logger.getLogger(DeploymentTools.class);

    @Inject
    KubernetesHelper helper;

    @Tool(description = "Create a new deployment with specified configuration in the OpenShift cluster. "
            + "Supports custom images, replicas, resource limits, and container ports.")
    String createDeployment(
            @ToolArg(description = "Deployment name") String name,
            @ToolArg(description = "Container image") String image,
            @ToolArg(description = "Target namespace (default: default)") String namespace,
            @ToolArg(description = "Number of replicas (default: 1)") int replicas,
            @ToolArg(description = "CPU request (default: 100m)") String cpuRequest,
            @ToolArg(description = "Memory request (default: 128Mi)") String memoryRequest,
            @ToolArg(description = "CPU limit (default: 500m)") String cpuLimit,
            @ToolArg(description = "Memory limit (default: 512Mi)") String memoryLimit,
            @ToolArg(description = "Container port (default: 8080)") int containerPort
    ) {
        if (name == null || name.isBlank()) return "Error: name is required";
        if (image == null || image.isBlank()) return "Error: image is required";
        if (namespace == null || namespace.isBlank()) namespace = "default";
        if (replicas <= 0) replicas = 1;
        if (cpuRequest == null || cpuRequest.isBlank()) cpuRequest = "100m";
        if (memoryRequest == null || memoryRequest.isBlank()) memoryRequest = "128Mi";
        if (cpuLimit == null || cpuLimit.isBlank()) cpuLimit = "500m";
        if (memoryLimit == null || memoryLimit.isBlank()) memoryLimit = "512Mi";
        if (containerPort <= 0) containerPort = 8080;

        try {
            KubernetesClient client = helper.getClient();

            ensureNamespace(client, namespace);

            var deployment = new DeploymentBuilder()
                    .withNewMetadata()
                        .withName(name)
                        .withNamespace(namespace)
                        .addToLabels("app", name)
                        .addToLabels("managed-by", "openshift-mcp-server")
                    .endMetadata()
                    .withNewSpec()
                        .withReplicas(replicas)
                        .withNewSelector()
                            .addToMatchLabels("app", name)
                        .endSelector()
                        .withNewTemplate()
                            .withNewMetadata()
                                .addToLabels("app", name)
                            .endMetadata()
                            .withNewSpec()
                                .addNewContainer()
                                    .withName(name)
                                    .withImage(image)
                                    .addNewPort().withContainerPort(containerPort).withProtocol("TCP").endPort()
                                    .withNewResources()
                                        .addToRequests("cpu", new Quantity(cpuRequest))
                                        .addToRequests("memory", new Quantity(memoryRequest))
                                        .addToLimits("cpu", new Quantity(cpuLimit))
                                        .addToLimits("memory", new Quantity(memoryLimit))
                                    .endResources()
                                .endContainer()
                            .endSpec()
                        .endTemplate()
                    .endSpec()
                    .build();

            client.apps().deployments().inNamespace(namespace).resource(deployment).create();

            return String.format("Deployment \"%s\" created successfully in namespace \"%s\":\n"
                    + "  Image: %s\n  Replicas: %d\n  Port: %d\n  CPU: %s/%s\n  Memory: %s/%s\n  Status: Created",
                    name, namespace, image, replicas, containerPort,
                    cpuRequest, cpuLimit, memoryRequest, memoryLimit);
        } catch (Exception e) {
            return "Failed to create deployment: " + e.getMessage();
        }
    }

    @Tool(description = "Deploy a database with ephemeral storage. Supports postgresql, mysql, mongodb, redis. "
            + "Creates both Deployment and Service resources.")
    String deployDatabase(
            @ToolArg(description = "Database type: postgresql, mysql, mongodb, redis") String type,
            @ToolArg(description = "Database instance name") String name,
            @ToolArg(description = "Target namespace (default: default)") String namespace,
            @ToolArg(description = "CPU request (default: 250m)") String cpuRequest,
            @ToolArg(description = "Memory request (default: 512Mi)") String memoryRequest
    ) {
        if (type == null || type.isBlank()) return "Error: type is required (postgresql, mysql, mongodb, redis)";
        if (name == null || name.isBlank()) return "Error: name is required";
        if (namespace == null || namespace.isBlank()) namespace = "default";
        if (cpuRequest == null || cpuRequest.isBlank()) cpuRequest = "250m";
        if (memoryRequest == null || memoryRequest.isBlank()) memoryRequest = "512Mi";

        record DbConfig(String image, int port, Map<String, String> envVars, String mountPath) {}

        Map<String, DbConfig> configs = Map.of(
                "postgresql", new DbConfig("registry.redhat.io/rhel9/postgresql-16:latest", 5432,
                        Map.of("POSTGRESQL_DATABASE", name, "POSTGRESQL_USER", "postgres", "POSTGRESQL_PASSWORD", "postgres123"),
                        "/var/lib/pgsql/data"),
                "mysql", new DbConfig("registry.redhat.io/rhel9/mysql-80:latest", 3306,
                        Map.of("MYSQL_DATABASE", name, "MYSQL_ROOT_PASSWORD", "mysql123"),
                        "/var/lib/mysql"),
                "mongodb", new DbConfig("registry.redhat.io/rhel9/mongodb-70:latest", 27017,
                        Map.of("MONGODB_DATABASE", name, "MONGODB_ADMIN_PASSWORD", "mongo123"),
                        "/data/db"),
                "redis", new DbConfig("registry.redhat.io/rhel9/redis-7:latest", 6379,
                        Map.of(), "/data")
        );

        DbConfig config = configs.get(type.toLowerCase());
        if (config == null) {
            return "Unsupported database type: " + type + ". Supported: postgresql, mysql, mongodb, redis";
        }

        try {
            KubernetesClient client = helper.getClient();
            ensureNamespace(client, namespace);

            List<EnvVar> envVars = config.envVars.entrySet().stream()
                    .map(e -> new EnvVarBuilder().withName(e.getKey()).withValue(e.getValue()).build())
                    .toList();

            var deployment = new DeploymentBuilder()
                    .withNewMetadata()
                        .withName(name)
                        .withNamespace(namespace)
                        .addToLabels("app", name)
                        .addToLabels("db-type", type)
                        .addToLabels("managed-by", "openshift-mcp-server")
                    .endMetadata()
                    .withNewSpec()
                        .withReplicas(1)
                        .withNewSelector().addToMatchLabels("app", name).endSelector()
                        .withNewTemplate()
                            .withNewMetadata().addToLabels("app", name).endMetadata()
                            .withNewSpec()
                                .addNewContainer()
                                    .withName(type)
                                    .withImage(config.image)
                                    .addNewPort().withContainerPort(config.port).withProtocol("TCP").endPort()
                                    .withEnv(envVars)
                                    .addNewVolumeMount().withName("data").withMountPath(config.mountPath).endVolumeMount()
                                    .withNewResources()
                                        .addToRequests("cpu", new Quantity(cpuRequest))
                                        .addToRequests("memory", new Quantity(memoryRequest))
                                        .addToLimits("cpu", new Quantity("1"))
                                        .addToLimits("memory", new Quantity("1Gi"))
                                    .endResources()
                                .endContainer()
                                .addNewVolume().withName("data").withNewEmptyDir().endEmptyDir().endVolume()
                            .endSpec()
                        .endTemplate()
                    .endSpec()
                    .build();

            client.apps().deployments().inNamespace(namespace).resource(deployment).create();

            var service = new ServiceBuilder()
                    .withNewMetadata()
                        .withName(name)
                        .withNamespace(namespace)
                        .addToLabels("app", name)
                        .addToLabels("managed-by", "openshift-mcp-server")
                    .endMetadata()
                    .withNewSpec()
                        .addToSelector("app", name)
                        .addNewPort().withPort(config.port).withNewTargetPort(config.port).withProtocol("TCP").endPort()
                    .endSpec()
                    .build();

            client.services().inNamespace(namespace).resource(service).create();

            return String.format("Database \"%s\" (%s) deployed successfully in namespace \"%s\":\n"
                    + "  Image: %s\n  Port: %d\n  Service: %s:%d\n  Storage: ephemeral (emptyDir)\n  Status: Deployed",
                    name, type, namespace, config.image, config.port, name, config.port);
        } catch (Exception e) {
            return "Failed to deploy database: " + e.getMessage();
        }
    }

    @Tool(description = "Set up a horizontal pod autoscaler (HPA) for a deployment. "
            + "Configures CPU and memory based autoscaling with min/max replicas.")
    String createHpa(
            @ToolArg(description = "Target deployment name") String targetDeployment,
            @ToolArg(description = "Target namespace (default: default)") String namespace,
            @ToolArg(description = "Minimum number of replicas (default: 1)") int minReplicas,
            @ToolArg(description = "Maximum number of replicas (default: 10)") int maxReplicas,
            @ToolArg(description = "Target CPU utilization percentage (default: 70)") int cpuTarget,
            @ToolArg(description = "Target memory utilization percentage (default: 80)") int memoryTarget
    ) {
        if (targetDeployment == null || targetDeployment.isBlank()) return "Error: targetDeployment is required";
        if (namespace == null || namespace.isBlank()) namespace = "default";
        if (minReplicas <= 0) minReplicas = 1;
        if (maxReplicas <= 0) maxReplicas = 10;
        if (cpuTarget <= 0) cpuTarget = 70;
        if (memoryTarget <= 0) memoryTarget = 80;

        try {
            KubernetesClient client = helper.getClient();

            if (client.apps().deployments().inNamespace(namespace).withName(targetDeployment).get() == null) {
                return "Deployment \"" + targetDeployment + "\" not found in namespace \"" + namespace + "\"";
            }

            String hpaName = targetDeployment + "-hpa";
            var hpa = new HorizontalPodAutoscalerBuilder()
                    .withNewMetadata()
                        .withName(hpaName)
                        .withNamespace(namespace)
                        .addToLabels("managed-by", "openshift-mcp-server")
                    .endMetadata()
                    .withNewSpec()
                        .withNewScaleTargetRef()
                            .withApiVersion("apps/v1")
                            .withKind("Deployment")
                            .withName(targetDeployment)
                        .endScaleTargetRef()
                        .withMinReplicas(minReplicas)
                        .withMaxReplicas(maxReplicas)
                        .addToMetrics(new MetricSpecBuilder()
                                .withType("Resource")
                                .withNewResource()
                                    .withName("cpu")
                                    .withNewTarget().withType("Utilization").withAverageUtilization(cpuTarget).endTarget()
                                .endResource()
                                .build())
                        .addToMetrics(new MetricSpecBuilder()
                                .withType("Resource")
                                .withNewResource()
                                    .withName("memory")
                                    .withNewTarget().withType("Utilization").withAverageUtilization(memoryTarget).endTarget()
                                .endResource()
                                .build())
                    .endSpec()
                    .build();

            client.autoscaling().v2().horizontalPodAutoscalers()
                    .inNamespace(namespace).resource(hpa).create();

            return String.format("HPA \"%s\" created successfully for deployment \"%s\":\n"
                    + "  Namespace: %s\n  Min replicas: %d\n  Max replicas: %d\n"
                    + "  CPU target: %d%%\n  Memory target: %d%%\n  Status: Created",
                    hpaName, targetDeployment, namespace, minReplicas, maxReplicas, cpuTarget, memoryTarget);
        } catch (Exception e) {
            return "Failed to create HPA: " + e.getMessage();
        }
    }

    @Tool(description = "Create a Kubernetes service to expose an application. "
            + "Supports ClusterIP, NodePort, and LoadBalancer types.")
    String createService(
            @ToolArg(description = "Service name") String name,
            @ToolArg(description = "Target namespace (default: default)") String namespace,
            @ToolArg(description = "Pod selector label key (e.g. 'app')") String selectorKey,
            @ToolArg(description = "Pod selector label value") String selectorValue,
            @ToolArg(description = "Service port") int port,
            @ToolArg(description = "Target port on pods") int targetPort,
            @ToolArg(description = "Service type: ClusterIP, NodePort, LoadBalancer (default: ClusterIP)") String serviceType
    ) {
        if (name == null || name.isBlank()) return "Error: name is required";
        if (namespace == null || namespace.isBlank()) namespace = "default";
        if (selectorKey == null || selectorKey.isBlank()) selectorKey = "app";
        if (selectorValue == null || selectorValue.isBlank()) return "Error: selectorValue is required";
        if (port <= 0) port = 80;
        if (targetPort <= 0) targetPort = port;
        if (serviceType == null || serviceType.isBlank()) serviceType = "ClusterIP";

        try {
            KubernetesClient client = helper.getClient();
            ensureNamespace(client, namespace);

            var service = new ServiceBuilder()
                    .withNewMetadata()
                        .withName(name)
                        .withNamespace(namespace)
                        .addToLabels("managed-by", "openshift-mcp-server")
                    .endMetadata()
                    .withNewSpec()
                        .withType(serviceType)
                        .addToSelector(selectorKey, selectorValue)
                        .addNewPort()
                            .withPort(port)
                            .withNewTargetPort(targetPort)
                            .withProtocol("TCP")
                        .endPort()
                    .endSpec()
                    .build();

            client.services().inNamespace(namespace).resource(service).create();

            return String.format("Service \"%s\" created successfully in namespace \"%s\":\n"
                    + "  Type: %s\n  Port: %d -> %d\n  Selector: %s=%s\n  Status: Created",
                    name, namespace, serviceType, port, targetPort, selectorKey, selectorValue);
        } catch (Exception e) {
            return "Failed to create service: " + e.getMessage();
        }
    }

    @Tool(description = "Create network policies to secure pod-to-pod communication. "
            + "Supports ingress and egress rules with pod and namespace selectors.")
    String createNetworkPolicy(
            @ToolArg(description = "Network policy name") String name,
            @ToolArg(description = "Target namespace (default: default)") String namespace,
            @ToolArg(description = "Pod selector label key") String podSelectorKey,
            @ToolArg(description = "Pod selector label value") String podSelectorValue,
            @ToolArg(description = "Policy type: deny-all, allow-same-namespace, custom (default: deny-all)") String policyType
    ) {
        if (name == null || name.isBlank()) return "Error: name is required";
        if (namespace == null || namespace.isBlank()) namespace = "default";
        if (podSelectorKey == null || podSelectorKey.isBlank()) podSelectorKey = "app";
        if (podSelectorValue == null || podSelectorValue.isBlank()) return "Error: podSelectorValue is required";
        if (policyType == null || policyType.isBlank()) policyType = "deny-all";

        try {
            KubernetesClient client = helper.getClient();
            ensureNamespace(client, namespace);

            var npBuilder = new NetworkPolicyBuilder()
                    .withNewMetadata()
                        .withName(name)
                        .withNamespace(namespace)
                        .addToLabels("managed-by", "openshift-mcp-server")
                    .endMetadata()
                    .withNewSpec()
                        .withNewPodSelector()
                            .addToMatchLabels(podSelectorKey, podSelectorValue)
                        .endPodSelector();

            switch (policyType) {
                case "deny-all":
                    npBuilder.withPolicyTypes("Ingress", "Egress");
                    break;
                case "allow-same-namespace":
                    npBuilder.withPolicyTypes("Ingress")
                            .addNewIngress()
                                .addNewFrom()
                                    .withNewPodSelector()
                                    .endPodSelector()
                                .endFrom()
                            .endIngress();
                    break;
                default:
                    npBuilder.withPolicyTypes("Ingress", "Egress");
                    break;
            }

            var np = npBuilder.endSpec().build();
            client.network().networkPolicies().inNamespace(namespace).resource(np).create();

            return String.format("Network Policy \"%s\" created successfully in namespace \"%s\":\n"
                    + "  Pod selector: %s=%s\n  Policy type: %s\n  Status: Created",
                    name, namespace, podSelectorKey, podSelectorValue, policyType);
        } catch (Exception e) {
            return "Failed to create network policy: " + e.getMessage();
        }
    }

    private void ensureNamespace(KubernetesClient client, String namespace) {
        if (client.namespaces().withName(namespace).get() == null) {
            client.namespaces().resource(
                    new NamespaceBuilder().withNewMetadata().withName(namespace).endMetadata().build()
            ).create();
        }
    }
}
