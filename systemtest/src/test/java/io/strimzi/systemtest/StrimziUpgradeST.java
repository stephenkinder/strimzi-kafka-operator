/*
 * Copyright 2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest;

import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.dsl.Resource;
import io.strimzi.api.kafka.Crds;
import io.strimzi.api.kafka.model.DoneableKafka;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.KafkaResources;
import io.strimzi.systemtest.utils.StUtils;
import io.strimzi.test.TestUtils;
import io.strimzi.test.k8s.KubeClusterException;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvFileSource;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import static io.strimzi.systemtest.Constants.REGRESSION;
import static io.strimzi.systemtest.Constants.UPGRADE;
import static org.junit.jupiter.api.Assertions.fail;

@Tag(REGRESSION)
@Tag(UPGRADE)
public class StrimziUpgradeST extends AbstractST {

    private static final Logger LOGGER = LogManager.getLogger(StrimziUpgradeST.class);

    public static final String NAMESPACE = "strimzi-upgrade-test";
    private String zkSsName = KafkaResources.zookeeperStatefulSetName(CLUSTER_NAME);
    private String kafkaSsName = KafkaResources.kafkaStatefulSetName(CLUSTER_NAME);
    private String eoDepName = KafkaResources.entityOperatorDeploymentName(CLUSTER_NAME);

    private Map<String, String> zkPods;
    private Map<String, String> kafkaPods;
    private Map<String, String> eoPods;

    @ParameterizedTest
    @CsvFileSource(resources = "/StrimziUpgradeST.csv")
    void upgradeStrimziVersion(String fromVersion, String toVersion, String urlFrom, String urlTo, String images, String procedures) throws IOException {
        setNamespace(NAMESPACE);
        File coDir = null;
        File kafkaEphemeralYaml = null;
        File kafkaTopicYaml = null;
        File kafkaUserYaml = null;

        try {
            String url = urlFrom;
            File dir = StUtils.downloadAndUnzip(url);

            coDir = new File(dir, "strimzi-" + fromVersion + "/install/cluster-operator/");
            // Modify + apply installation files
            copyModifyApply(coDir);

            LOGGER.info("Waiting for CO deployment");
            StUtils.waitForDeploymentReady("strimzi-cluster-operator", 1);

            // Deploy a Kafka cluster
            kafkaEphemeralYaml = new File(dir, "strimzi-" + fromVersion + "/examples/kafka/kafka-ephemeral.yaml");
            cmdKubeClient().create(kafkaEphemeralYaml);
            // Wait for readiness
            waitForClusterReadiness();

            // And a topic and a user
            kafkaTopicYaml = new File(dir, "strimzi-" + fromVersion + "/examples/topic/kafka-topic.yaml");
            cmdKubeClient().create(kafkaTopicYaml);
            kafkaUserYaml = new File(dir, "strimzi-" + fromVersion + "/examples/user/kafka-user.yaml");
            cmdKubeClient().create(kafkaUserYaml);

            makeSnapshots();

            List<Pod> pods = kubeClient().listPods(kubeClient().getStatefulSetSelectors(zkSsName));
            for (Pod pod : pods) {
                LOGGER.info("Pod {} has image {}", pod.getMetadata().getName(), pod.getSpec().getContainers().get(0).getImage());
            }

            // Execution of required procedures before upgrading CO
            if (!procedures.isEmpty()) {
                String[] proceduresArray = procedures.split("\\s*,\\s*");
                for (String procedure : proceduresArray) {
                    switch (procedure) {
                        case "set log message format version to 2.0": {
                            replaceKafka(CLUSTER_NAME, k -> k.getSpec().getKafka().getConfig().put("log.message.format.version", "2.0"));
                            StUtils.waitTillSsHasRolled(kafkaSsName, 3, kafkaPods);
                            makeSnapshots();
                            break;
                        }
                        case "set log message format version to 2.1": {
                            replaceKafka(CLUSTER_NAME, k -> k.getSpec().getKafka().getConfig().put("log.message.format.version", "2.1"));
                            StUtils.waitTillSsHasRolled(kafkaSsName, 3, kafkaPods);
                            makeSnapshots();
                            break;
                        }
                        case "set Kafka version to 2.0.0": {
                            replaceKafka(CLUSTER_NAME, k -> k.getSpec().getKafka().setVersion("2.0.0"));
                            makeSnapshots();
                            break;
                        }
                        case "set Kafka version to 2.1.0": {
                            replaceKafka(CLUSTER_NAME, k -> k.getSpec().getKafka().setVersion("2.1.0"));
                            StUtils.waitTillSsHasRolled(kafkaSsName, 3, kafkaPods);
                            makeSnapshots();
                            break;
                        }
                        case "set Kafka version to 2.1.1": {
                            replaceKafka(CLUSTER_NAME, k -> k.getSpec().getKafka().setVersion("2.1.1"));
                            StUtils.waitTillSsHasRolled(kafkaSsName, 3, kafkaPods);
                            makeSnapshots();
                            break;
                        }
                    }
                }
            }

            // Upgrade the CO
            // Modify + apply installation files
            if ("HEAD" .equals(toVersion)) {
                LOGGER.info("Updating");
                coDir = new File("../install/cluster-operator");
                copyModifyApply(coDir);
                LOGGER.info("Waiting for CO redeployment");
                StUtils.waitForDeploymentReady("strimzi-cluster-operator", 1);
                waitForRollingUpdate(images);
            } else {
                url = urlTo;
                dir = StUtils.downloadAndUnzip(url);
                coDir = new File(dir, "strimzi-" + toVersion + "/install/cluster-operator/");
                copyModifyApply(coDir);
                LOGGER.info("Waiting for CO deployment");
                StUtils.waitForDeploymentReady("strimzi-cluster-operator", 1);
                waitForRollingUpdate(images);
            }

            // Tidy up
        } catch (KubeClusterException e) {
            if (kafkaEphemeralYaml != null) {
                cmdKubeClient().delete(kafkaEphemeralYaml);
            }
            if (coDir != null) {
                cmdKubeClient().delete(coDir);
            }
            throw e;
        } finally {
            deleteInstalledYamls(coDir);
        }

    }

    private void copyModifyApply(File root) {
        Arrays.stream(Objects.requireNonNull(root.listFiles())).sorted().forEach(f -> {
            if (f.getName().matches(".*RoleBinding.*")) {
                cmdKubeClient().applyContent(TestUtils.changeRoleBindingSubject(f, NAMESPACE));
            } else if (f.getName().matches("050-Deployment.*")) {
                cmdKubeClient().applyContent(TestUtils.changeDeploymentNamespaceUpgrade(f, NAMESPACE));
            } else {
                cmdKubeClient().apply(f);
            }
        });
    }

    private void deleteInstalledYamls(File root) {
        if (root != null) {
            Arrays.stream(Objects.requireNonNull(root.listFiles())).sorted().forEach(f -> {
                if (f.getName().matches(".*RoleBinding.*")) {
                    cmdKubeClient().deleteContent(TestUtils.changeRoleBindingSubject(f, NAMESPACE));
                } else {
                    cmdKubeClient().delete(f);
                }
            });
        }
    }

    private void waitForClusterReadiness() {
        // Wait for readiness
        LOGGER.info("Waiting for Zookeeper StatefulSet");
        StUtils.waitForAllStatefulSetPodsReady("my-cluster-zookeeper", 3);
        LOGGER.info("Waiting for Kafka StatefulSet");
        StUtils.waitForAllStatefulSetPodsReady("my-cluster-kafka", 3);
        LOGGER.info("Waiting for EO Deployment");
        StUtils.waitForDeploymentReady("my-cluster-entity-operator", 1);
    }

    private void waitForRollingUpdate(String images) {
        if (images.isEmpty()) {
            fail("There are no expected images");
        }
        String[] imagesArray = images.split("\\s*,\\s*");
        String zkImage = imagesArray[0];
        String kafkaImage = imagesArray[1];
        String tOImage = imagesArray[2];
        String uOImage = imagesArray[3];

        LOGGER.info("Waiting for ZK SS roll");
        StUtils.waitTillSsHasRolled(zkSsName, 3, zkPods);
        LOGGER.info("Checking ZK pods using new image");
        waitTillAllPodsUseImage(kubeClient().getStatefulSet(zkSsName).getSpec().getSelector().getMatchLabels(),
                zkImage);

        LOGGER.info("Waiting for Kafka SS roll");
        StUtils.waitTillSsHasRolled(kafkaSsName, 3, kafkaPods);
        LOGGER.info("Checking Kafka pods using new image");
        waitTillAllPodsUseImage(kubeClient().getStatefulSet(kafkaSsName).getSpec().getSelector().getMatchLabels(),
                kafkaImage);
        LOGGER.info("Waiting for EO Dep roll");
        // Check the TO and UO also got upgraded
        StUtils.waitTillDepHasRolled(eoDepName, 1, eoPods);
        LOGGER.info("Checking EO pod using new image");
        waitTillAllContainersUseImage(
                kubeClient().getDeployment(eoDepName).getSpec().getSelector().getMatchLabels(),
                0,
                tOImage);
        waitTillAllContainersUseImage(
                kubeClient().getDeployment(eoDepName).getSpec().getSelector().getMatchLabels(),
                1,
                uOImage);
    }

    private void makeSnapshots() {
        zkPods = StUtils.ssSnapshot(zkSsName);
        kafkaPods = StUtils.ssSnapshot(kafkaSsName);
        eoPods = StUtils.depSnapshot(eoDepName);
    }

    private Kafka getKafka(String resourceName) {
        Resource<Kafka, DoneableKafka> namedResource = Crds.kafkaV1Alpha1Operation(kubeClient().getClient()).inNamespace(kubeClient().getNamespace()).withName(resourceName);
        return namedResource.get();
    }

    private void replaceKafka(String resourceName, Consumer<Kafka> editor) {
        Resource<Kafka, DoneableKafka> namedResource = Crds.kafkaV1Alpha1Operation(kubeClient().getClient()).inNamespace(kubeClient().getNamespace()).withName(resourceName);
        Kafka kafka = getKafka(resourceName);
        editor.accept(kafka);
        namedResource.replace(kafka);
    }

    private void waitTillAllPodsUseImage(Map<String, String> matchLabels, String image) {
        waitTillAllContainersUseImage(matchLabels, 0, image);
    }

    private void waitTillAllContainersUseImage(Map<String, String> matchLabels, int container, String image) {
        TestUtils.waitFor("All pods matching " + matchLabels + " to have image " + image, Constants.GLOBAL_POLL_INTERVAL, Constants.GLOBAL_TIMEOUT, () -> {
            List<Pod> pods1 = kubeClient().listPods(matchLabels);
            for (Pod pod : pods1) {
                if (!image.equals(pod.getSpec().getContainers().get(container).getImage())) {
                    LOGGER.info("Expected image: {} \nCurrent image: {}", image, pod.getSpec().getContainers().get(container).getImage());
                    return false;
                }
            }
            return true;
        });
    }

    @BeforeEach
    void setupEnvironment() {
        LOGGER.info("Creating namespace: {}", NAMESPACE);
        createNamespace(NAMESPACE);
    }

    @Override
    void tearDownEnvironmentAfterEach() {
        deleteNamespaces();
    }

    // There is no value of having teardown logic for class resources due to the fact that
    // CO was deployed by method StrimziUpgradeST.copyModifyApply() and removed by method StrimziUpgradeST.deleteInstalledYamls()
    @Override
    void tearDownEnvironmentAfterAll() {
    }

    @Override
    void recreateTestEnv(String coNamespace, List<String> bindingsNamespaces) {
        deleteNamespaces();
        createNamespace(NAMESPACE);
    }
}
