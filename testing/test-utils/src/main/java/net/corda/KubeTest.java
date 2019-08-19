package net.corda;

import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.ConfigBuilder;
import io.fabric8.kubernetes.client.*;
import io.fabric8.kubernetes.client.dsl.LogWatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.math.BigInteger;
import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class KubeTest {

    String dockerTag;

    static class KubePodResult {

        private final Pod createdPod;
        private Thread outputThread;

        public KubePodResult(Pod createdPod, Thread outputThread) {
            this.createdPod = createdPod;
            this.outputThread = outputThread;
        }
    }

    private static Logger logger = LoggerFactory.getLogger(KubeTest.class);

    public static void main(String[] args) {

        String runId = new BigInteger(64, new Random()).toString(36).toLowerCase();

        Config config = new ConfigBuilder().build();
        try (final KubernetesClient client = new DefaultKubernetesClient(config)) {

            String namespace = "thisisatest";
            client.apps().deployments().inNamespace(namespace).list().getItems().forEach(deploymentToDelete -> {
                client.resource(deploymentToDelete).delete();
            });

            client.pods().inNamespace(namespace).list().getItems().forEach(podToDelete -> {
                System.out.println("deleting: " + podToDelete.getMetadata().getName());
                client.resource(podToDelete).delete();
            });

            Namespace ns = new NamespaceBuilder().withNewMetadata().withName(namespace).addToLabels("this", "rocks").endMetadata().build();
            client.namespaces().createOrReplace(ns);

            int numberOfNodes = client.nodes().list().getItems().size();

            List<KubePodResult> createdPods = IntStream.range(0, numberOfNodes).mapToObj(i -> {
                String podName = "test" + runId + i;
                Pod podRequest = new PodBuilder().withNewMetadata().withName(podName).endMetadata()
                        .withNewSpec()
                        .addNewVolume()
                        .withName("azurefiles")
                        .withNewAzureFile()
                        .withSecretName("azure-secret")
                        .withShareName("gradle")
                        .endAzureFile()
                        .endVolume()
                        .addNewContainer()
                        .withImage("stefanotestingcr.azurecr.io/bd7e38437234")
                        .withCommand("bash")
                        .withArgs("-c", "cd /tmp/source && ./gradlew -PdockerFork=" + i + " -PdockerForks=" + numberOfNodes + " node:test --info")
                        .withName("testing-hello-world" + i)
                        .withNewResources()
                        .addToRequests("cpu", new Quantity("2"))
                        .endResources()
                        .addNewVolumeMount()
                        .withName("azurefiles")
                        .withMountPath("/tmp/gradle")
                        .endVolumeMount()
                        .endContainer()
                        .withImagePullSecrets(new LocalObjectReference("regcred"))
                        .withRestartPolicy("Never")
                        .endSpec()
                        .build();

                System.out.println("created pod: " + podName);
                Pod createdPod = client.pods().inNamespace(namespace).create(podRequest);

                Thread outputThread = new Thread(() -> {

                    try {
                        PipedInputStream input = new PipedInputStream();
                        PipedOutputStream output = new PipedOutputStream(input);
                        client.pods().inNamespace(namespace).withName(podName).waitUntilReady(10, TimeUnit.MINUTES);
                        LogWatch logWatch = client.pods().inNamespace(namespace).withName(podName).watchLog();
                        Watch eventWatch = client.pods().inNamespace(namespace).withName(podName).watch(new Watcher<Pod>() {
                            @Override
                            public void eventReceived(Action action, Pod resource) {
                                ContainerState state = resource.getStatus().getContainerStatuses().get(0).getState();
                                System.out.println("[StatusChange]  pod " + resource.getMetadata().getName() + " " + action.name());
                                if (state.getTerminated() != null) {
                                    System.out.println("[StatusChange] pod: " + resource.getMetadata().getName() + " has exited with code: " + state.getTerminated().getExitCode());
                                    logWatch.close();
                                }
                            }

                            @Override
                            public void onClose(KubernetesClientException cause) {
                            }
                        });
                        System.out.println("Pod: " + podName + " has started ");
                        try (BufferedReader br = new BufferedReader(new InputStreamReader(logWatch.getOutput()))) {
                            String line;
                            while ((line = br.readLine()) != null) {
                                System.out.println("Container" + i + ":   " + line);
                            }
                        } catch (IOException e) {
                            e.printStackTrace();
                            throw new RuntimeException(e);
                        }
                    } catch (InterruptedException | IOException ignored) {
                        //we were interrupted whilst waiting for container
                    }

                });

                outputThread.setDaemon(true);
                outputThread.start();
                return new KubePodResult(createdPod, outputThread);
            }).collect(Collectors.toList());

            System.out.println("Pods created, waiting for exit");

            createdPods.forEach(pod -> {
                try {
                    pod.outputThread.join();
                    System.out.println("Successfully terminated log streaming for " + pod.createdPod.getMetadata().getName() + " still waiting for " + createdPods.stream().filter(cp -> cp.outputThread.isAlive()).map(cp -> cp.createdPod.getMetadata().getName()).collect(Collectors.toSet()));
                } catch (InterruptedException e) {
                }
            });

            System.out.println("All pods have completed! preparing to gather test results");

            createdPods.forEach(cp -> {
                System.out.println("creating gzip of test xml results on pod " + cp.createdPod.getMetadata().getName());
                client.pods().inNamespace(namespace).withName(cp.createdPod.getMetadata().getName()).writingOutput(System.out).exec("sh", "-c", "cd " + "/tmp/source/node/build" + " && " +
                        "tar -zcvf testResults.tar.gz $(find . | grep xml$)");
            });

            client.pods().inNamespace(namespace).list().getItems().forEach(podToDelete -> {
                System.out.println("deleting: " + podToDelete.getMetadata().getName());
                client.resource(podToDelete).delete();
            });

        } catch (Exception e) {
            e.printStackTrace();
            logger.error(e.getMessage(), e);
            Throwable[] suppressed = e.getSuppressed();
            if (suppressed != null) {
                for (Throwable t : suppressed) {
                    logger.error(t.getMessage(), t);
                }
            }
        }
    }

}
