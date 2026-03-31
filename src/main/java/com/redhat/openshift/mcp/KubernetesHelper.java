package com.redhat.openshift.mcp;

import io.fabric8.kubernetes.client.KubernetesClient;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@ApplicationScoped
public class KubernetesHelper {

    private static final Logger LOG = Logger.getLogger(KubernetesHelper.class);

    @Inject
    KubernetesClient client;

    public KubernetesClient getClient() {
        return client;
    }

    public String executeCommand(String... command) {
        return executeCommand(60, command);
    }

    public String executeCommand(int timeoutSeconds, String... command) {
        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);

            String kubeconfig = System.getenv("KUBECONFIG");
            if (kubeconfig != null && !kubeconfig.isBlank()) {
                pb.environment().put("KUBECONFIG", kubeconfig);
            }

            Process process = pb.start();
            String output;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                output = reader.lines().collect(Collectors.joining("\n"));
            }

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return "Command timed out after " + timeoutSeconds + " seconds";
            }

            return output;
        } catch (Exception e) {
            LOG.warn("Command execution failed: " + String.join(" ", command), e);
            return "Error executing command: " + e.getMessage();
        }
    }

    public String formatAge(Instant creationTimestamp) {
        if (creationTimestamp == null) return "unknown";
        Duration age = Duration.between(creationTimestamp, Instant.now());
        long days = age.toDays();
        long hours = age.toHours() % 24;
        long minutes = age.toMinutes() % 60;

        if (days > 0) return days + "d" + hours + "h";
        if (hours > 0) return hours + "h" + minutes + "m";
        return minutes + "m";
    }

    public long parseResourceValue(String value) {
        if (value == null || value.isBlank()) return 0;
        value = value.trim();
        try {
            if (value.endsWith("m")) {
                return Long.parseLong(value.replace("m", ""));
            } else if (value.endsWith("Mi")) {
                return Long.parseLong(value.replace("Mi", "")) * 1024 * 1024;
            } else if (value.endsWith("Gi")) {
                return Long.parseLong(value.replace("Gi", "")) * 1024 * 1024 * 1024;
            } else if (value.endsWith("Ki")) {
                return Long.parseLong(value.replace("Ki", "")) * 1024;
            } else if (value.endsWith("%")) {
                return Long.parseLong(value.replace("%", ""));
            } else {
                return Long.parseLong(value);
            }
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}
