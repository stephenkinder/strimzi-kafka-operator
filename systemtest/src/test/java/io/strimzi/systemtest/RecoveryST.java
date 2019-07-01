/*
 * Copyright 2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.systemtest;

import io.strimzi.systemtest.utils.StUtils;
import io.strimzi.test.timemeasuring.Operation;
import io.strimzi.test.timemeasuring.TimeMeasuringSystem;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static io.strimzi.systemtest.Constants.ACCEPTANCE;
import static io.strimzi.systemtest.Constants.REGRESSION;
import static io.strimzi.test.k8s.BaseCmdKubeClient.CM;
import static io.strimzi.test.k8s.BaseCmdKubeClient.SERVICE;

@Tag(REGRESSION)
class RecoveryST extends AbstractST {

    static final String NAMESPACE = "recovery-cluster-test";
    static final String CLUSTER_NAME = "recovery-cluster";

    private static final Logger LOGGER = LogManager.getLogger(RecoveryST.class);

    @Test
    void testRecoveryFromEntityOperatorDeletion() {
        operationID = startTimeMeasuring(Operation.CLUSTER_RECOVERY);
        // kafka cluster already deployed
        String entityOperatorDeploymentName = entityOperatorDeploymentName(CLUSTER_NAME);
        LOGGER.info("Running testRecoveryFromEntityOperatorDeletion with cluster {}", CLUSTER_NAME);

        kubeClient().deleteDeployment(entityOperatorDeploymentName);
        StUtils.waitForDeploymentDeletion(entityOperatorDeploymentName);

        LOGGER.info("Waiting for recovery {}", entityOperatorDeploymentName);
        StUtils.waitForDeploymentReady(entityOperatorDeploymentName, 1);

        TimeMeasuringSystem.stopOperation(operationID);
        //Test that CO doesn't have any exceptions in log
        assertNoCoErrorsLogged(TimeMeasuringSystem.getDurationInSecconds(testClass, testName, operationID));
    }

    @Test
    @Tag(ACCEPTANCE)
    void testRecoveryFromKafkaStatefulSetDeletion() {
        operationID = startTimeMeasuring(Operation.CLUSTER_RECOVERY);
        // kafka cluster already deployed
        String kafkaStatefulSetName = kafkaClusterName(CLUSTER_NAME);
        LOGGER.info("Running deleteKafkaStatefulSet with cluster {}", CLUSTER_NAME);

        kubeClient().deleteStatefulSet(kafkaStatefulSetName);
        StUtils.waitForStatefulSetDeletion(kafkaStatefulSetName);

        LOGGER.info("Waiting for recovery {}", kafkaStatefulSetName);
        StUtils.waitForAllStatefulSetPodsReady(kafkaStatefulSetName, 1);

        TimeMeasuringSystem.stopOperation(operationID);
        //Test that CO doesn't have any exceptions in log
        assertNoCoErrorsLogged(TimeMeasuringSystem.getDurationInSecconds(testClass, testName, operationID));
    }

    @Test
    @Tag(ACCEPTANCE)
    void testRecoveryFromZookeeperStatefulSetDeletion() {
        operationID = startTimeMeasuring(Operation.CLUSTER_RECOVERY);
        // kafka cluster already deployed
        String zookeeperStatefulSetName = zookeeperClusterName(CLUSTER_NAME);
        LOGGER.info("Running deleteZookeeperStatefulSet with cluster {}", CLUSTER_NAME);

        kubeClient().deleteStatefulSet(zookeeperStatefulSetName);
        StUtils.waitForStatefulSetDeletion(zookeeperStatefulSetName);

        LOGGER.info("Waiting for recovery {}", zookeeperStatefulSetName);
        StUtils.waitForAllStatefulSetPodsReady(zookeeperStatefulSetName, 3);

        TimeMeasuringSystem.stopOperation(operationID);
        //Test that CO doesn't have any exceptions in log
        assertNoCoErrorsLogged(TimeMeasuringSystem.getDurationInSecconds(testClass, testName, operationID));
    }

    @Test
    void testRecoveryFromKafkaServiceDeletion() {
        operationID = startTimeMeasuring(Operation.CLUSTER_RECOVERY);
        // kafka cluster already deployed
        String kafkaServiceName = kafkaServiceName(CLUSTER_NAME);
        LOGGER.info("Running deleteKafkaService with cluster {}", CLUSTER_NAME);

        kubeClient().deleteService(kafkaServiceName);

        LOGGER.info("Waiting for creation {}", kafkaServiceName);
        cmdKubeClient().waitForResourceCreation(SERVICE, kafkaServiceName);

        TimeMeasuringSystem.stopOperation(operationID);
        //Test that CO doesn't have any exceptions in log
        assertNoCoErrorsLogged(TimeMeasuringSystem.getDurationInSecconds(testClass, testName, operationID));
    }

    @Test
    void testRecoveryFromZookeeperServiceDeletion() {
        operationID = startTimeMeasuring(Operation.CLUSTER_RECOVERY);
        // kafka cluster already deployed
        String zookeeperServiceName = zookeeperServiceName(CLUSTER_NAME);

        LOGGER.info("Running deleteKafkaService with cluster {}", CLUSTER_NAME);

        kubeClient().deleteService(zookeeperServiceName);

        LOGGER.info("Waiting for creation {}", zookeeperServiceName);
        cmdKubeClient().waitForResourceCreation(SERVICE, zookeeperServiceName);

        TimeMeasuringSystem.stopOperation(operationID);
        //Test that CO doesn't have any exceptions in log
        assertNoCoErrorsLogged(TimeMeasuringSystem.getDurationInSecconds(testClass, testName, operationID));
    }

    @Test
    void testRecoveryFromKafkaHeadlessServiceDeletion() {
        operationID = startTimeMeasuring(Operation.CLUSTER_RECOVERY);
        // kafka cluster already deployed
        String kafkaHeadlessServiceName = kafkaHeadlessServiceName(CLUSTER_NAME);
        LOGGER.info("Running deleteKafkaHeadlessService with cluster {}", CLUSTER_NAME);

        kubeClient().deleteService(kafkaHeadlessServiceName);

        LOGGER.info("Waiting for creation {}", kafkaHeadlessServiceName);
        cmdKubeClient().waitForResourceCreation(SERVICE, kafkaHeadlessServiceName);

        TimeMeasuringSystem.stopOperation(operationID);
        //Test that CO doesn't have any exceptions in log
        assertNoCoErrorsLogged(TimeMeasuringSystem.getDurationInSecconds(testClass, testName, operationID));
    }

    @Test
    void testRecoveryFromZookeeperHeadlessServiceDeletion() {
        operationID = startTimeMeasuring(Operation.CLUSTER_RECOVERY);
        // kafka cluster already deployed
        String zookeeperHeadlessServiceName = zookeeperHeadlessServiceName(CLUSTER_NAME);
        LOGGER.info("Running deleteKafkaHeadlessService with cluster {}", CLUSTER_NAME);

        kubeClient().deleteService(zookeeperHeadlessServiceName);

        LOGGER.info("Waiting for creation {}", zookeeperHeadlessServiceName);
        cmdKubeClient().waitForResourceCreation(SERVICE, zookeeperHeadlessServiceName);

        TimeMeasuringSystem.stopOperation(operationID);
        //Test that CO doesn't have any exceptions in log
        assertNoCoErrorsLogged(TimeMeasuringSystem.getDurationInSecconds(testClass, testName, operationID));
    }

    @Test
    void testRecoveryFromKafkaMetricsConfigDeletion() {
        operationID = startTimeMeasuring(Operation.CLUSTER_RECOVERY);
        // kafka cluster already deployed
        String kafkaMetricsConfigName = kafkaMetricsConfigName(CLUSTER_NAME);
        LOGGER.info("Running deleteKafkaMetricsConfig with cluster {}", CLUSTER_NAME);

        kubeClient().deleteConfigMap(kafkaMetricsConfigName);
        StUtils.waitForConfigMapDeletion(kafkaMetricsConfigName);

        LOGGER.info("Waiting for creation {}", kafkaMetricsConfigName);
        cmdKubeClient().waitForResourceCreation(CM, kafkaMetricsConfigName);

        TimeMeasuringSystem.stopOperation(operationID);
        //Test that CO doesn't have any exceptions in log
        assertNoCoErrorsLogged(TimeMeasuringSystem.getDurationInSecconds(testClass, testName, operationID));
    }

    @Test
    void testRecoveryFromZookeeperMetricsConfigDeletion() {
        operationID = startTimeMeasuring(Operation.CLUSTER_RECOVERY);
        // kafka cluster already deployed
        String zookeeperMetricsConfigName = zookeeperMetricsConfigName(CLUSTER_NAME);
        LOGGER.info("Running deleteZookeeperMetricsConfig with cluster {}", CLUSTER_NAME);

        kubeClient().deleteConfigMap(zookeeperMetricsConfigName);
        StUtils.waitForConfigMapDeletion(zookeeperMetricsConfigName);

        LOGGER.info("Waiting for creation {}", zookeeperMetricsConfigName);
        cmdKubeClient().waitForResourceCreation(CM, zookeeperMetricsConfigName);

        TimeMeasuringSystem.stopOperation(operationID);
        //Test that CO doesn't have any exceptions in log
        assertNoCoErrorsLogged(TimeMeasuringSystem.getDurationInSecconds(testClass, testName, operationID));
    }

    @BeforeAll
    void setupEnvironment() {
        LOGGER.info("Creating resources before the test class");
        prepareEnvForOperator(NAMESPACE);

        createTestClassResources();
        applyRoleBindings(NAMESPACE);
        // 050-Deployment
        testClassResources.clusterOperator(NAMESPACE).done();

        deployTestSpecificResources();
    }

    void deployTestSpecificResources() {
        testClassResources.kafkaEphemeral(CLUSTER_NAME, 1).done();
    }

    @Override
    void recreateTestEnv(String coNamespace, List<String> bindingsNamespaces) {
        super.recreateTestEnv(coNamespace, bindingsNamespaces);
        deployTestSpecificResources();
    }
}
