/*
 * Copyright 2017-2018, Strimzi authors.
 * License: Apache License 2.0 (see the file LICENSE or http://apache.org/licenses/LICENSE-2.0.html).
 */
package io.strimzi.operator.cluster.model;

import io.fabric8.kubernetes.api.model.Affinity;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ContainerPort;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.LabelSelector;
import io.fabric8.kubernetes.api.model.LifecycleBuilder;
import io.fabric8.kubernetes.api.model.LocalObjectReference;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServicePort;
import io.fabric8.kubernetes.api.model.Toleration;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.networking.NetworkPolicy;
import io.fabric8.kubernetes.api.model.networking.NetworkPolicyBuilder;
import io.fabric8.kubernetes.api.model.networking.NetworkPolicyIngressRule;
import io.fabric8.kubernetes.api.model.networking.NetworkPolicyIngressRuleBuilder;
import io.fabric8.kubernetes.api.model.networking.NetworkPolicyPeer;
import io.fabric8.kubernetes.api.model.networking.NetworkPolicyPort;
import io.fabric8.kubernetes.api.model.policy.PodDisruptionBudget;
import io.strimzi.api.kafka.model.InlineLogging;
import io.strimzi.api.kafka.model.Kafka;
import io.strimzi.api.kafka.model.KafkaClusterSpec;
import io.strimzi.api.kafka.model.KafkaResources;
import io.strimzi.api.kafka.model.Logging;
import io.strimzi.api.kafka.model.Probe;
import io.strimzi.api.kafka.model.ProbeBuilder;
import io.strimzi.api.kafka.model.TlsSidecar;
import io.strimzi.api.kafka.model.ZookeeperClusterSpec;
import io.strimzi.api.kafka.model.storage.EphemeralStorage;
import io.strimzi.api.kafka.model.storage.PersistentClaimStorage;
import io.strimzi.api.kafka.model.storage.Storage;
import io.strimzi.api.kafka.model.template.ZookeeperClusterTemplate;
import io.strimzi.certs.CertAndKey;
import io.strimzi.operator.common.model.Labels;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static io.strimzi.operator.cluster.model.ModelUtils.parseImageMap;
import static java.util.Arrays.asList;

public class ZookeeperCluster extends AbstractModel {

    protected static final int CLIENT_PORT = 2181;
    protected static final String CLIENT_PORT_NAME = "clients";
    protected static final int CLUSTERING_PORT = 2888;
    protected static final String CLUSTERING_PORT_NAME = "clustering";
    protected static final int LEADER_ELECTION_PORT = 3888;
    protected static final String LEADER_ELECTION_PORT_NAME = "leader-election";

    protected static final String ZOOKEEPER_NAME = "zookeeper";
    protected static final String TLS_SIDECAR_NAME = "tls-sidecar";
    protected static final String TLS_SIDECAR_NODES_VOLUME_NAME = "zookeeper-nodes";
    protected static final String TLS_SIDECAR_NODES_VOLUME_MOUNT = "/etc/tls-sidecar/zookeeper-nodes/";
    protected static final String TLS_SIDECAR_CLUSTER_CA_VOLUME_NAME = "cluster-ca-certs";
    protected static final String TLS_SIDECAR_CLUSTER_CA_VOLUME_MOUNT = "/etc/tls-sidecar/cluster-ca-certs/";
    private static final String NAME_SUFFIX = "-zookeeper";
    private static final String SERVICE_NAME_SUFFIX = NAME_SUFFIX + "-client";
    private static final String HEADLESS_SERVICE_NAME_SUFFIX = NAME_SUFFIX + "-nodes";
    private static final String LOG_CONFIG_SUFFIX = NAME_SUFFIX + "-logging";
    private static final String NODES_CERTS_SUFFIX = NAME_SUFFIX + "-nodes";

    // Zookeeper configuration
    private TlsSidecar tlsSidecar;

    public static final Probe DEFAULT_HEALTHCHECK_OPTIONS = new ProbeBuilder()
            .withTimeoutSeconds(5)
            .withInitialDelaySeconds(15)
            .build();
    private static final boolean DEFAULT_ZOOKEEPER_METRICS_ENABLED = false;

    // Zookeeper configuration keys (EnvVariables)
    public static final String ENV_VAR_ZOOKEEPER_NODE_COUNT = "ZOOKEEPER_NODE_COUNT";
    public static final String ENV_VAR_ZOOKEEPER_METRICS_ENABLED = "ZOOKEEPER_METRICS_ENABLED";
    public static final String ENV_VAR_ZOOKEEPER_CONFIGURATION = "ZOOKEEPER_CONFIGURATION";

    public static final Map<String, String> IMAGE_MAP = parseImageMap(System.getenv().get("STRIMZI_ZOOKEEPER_IMAGE_MAP"));

    public static String zookeeperClusterName(String cluster) {
        return KafkaResources.zookeeperStatefulSetName(cluster);
    }

    public static String zookeeperMetricAndLogConfigsName(String cluster) {
        return KafkaResources.zookeeperMetricsAndLogConfigMapName(cluster);
    }

    public static String logConfigsName(String cluster) {
        return cluster + ZookeeperCluster.LOG_CONFIG_SUFFIX;
    }

    public static String serviceName(String cluster) {
        return cluster + ZookeeperCluster.SERVICE_NAME_SUFFIX;
    }

    public static String headlessServiceName(String cluster) {
        return cluster + ZookeeperCluster.HEADLESS_SERVICE_NAME_SUFFIX;
    }

    public static String podDnsName(String namespace, String cluster, int podId) {
        return String.format("%s.%s.%s.svc.%s",
                ZookeeperCluster.zookeeperPodName(cluster, podId),
                ZookeeperCluster.headlessServiceName(cluster),
                namespace,
                ModelUtils.KUBERNETES_SERVICE_DNS_DOMAIN);
    }

    public static String zookeeperPodName(String cluster, int pod) {
        return KafkaResources.zookeeperPodName(cluster, pod);
    }

    public static String getPersistentVolumeClaimName(String clusterName, int podId) {
        return VOLUME_NAME + "-" + clusterName + "-" + podId;
    }

    public static String nodesSecretName(String cluster) {
        return cluster + ZookeeperCluster.NODES_CERTS_SUFFIX;
    }

    /**
     * Constructor
     *
     * @param namespace Kubernetes/OpenShift namespace where Zookeeper cluster resources are going to be created
     * @param cluster   overall cluster name
     * @param labels    labels to add to the cluster
     */
    private ZookeeperCluster(String namespace, String cluster, Labels labels) {

        super(namespace, cluster, labels);
        this.name = zookeeperClusterName(cluster);
        this.serviceName = serviceName(cluster);
        this.headlessServiceName = headlessServiceName(cluster);
        this.ancillaryConfigName = zookeeperMetricAndLogConfigsName(cluster);
        this.image = null;
        this.replicas = ZookeeperClusterSpec.DEFAULT_REPLICAS;
        this.readinessPath = "/opt/kafka/zookeeper_healthcheck.sh";
        this.readinessProbeOptions = DEFAULT_HEALTHCHECK_OPTIONS;
        this.livenessPath = "/opt/kafka/zookeeper_healthcheck.sh";
        this.livenessProbeOptions = DEFAULT_HEALTHCHECK_OPTIONS;
        this.isMetricsEnabled = DEFAULT_ZOOKEEPER_METRICS_ENABLED;

        this.mountPath = "/var/lib/zookeeper";

        this.logAndMetricsConfigVolumeName = "zookeeper-metrics-and-logging";
        this.logAndMetricsConfigMountPath = "/opt/kafka/custom-config/";
    }

    public static ZookeeperCluster fromCrd(Kafka kafkaAssembly, KafkaVersion.Lookup versions) {
        return fromCrd(kafkaAssembly, versions, null);
    }

    @SuppressWarnings("checkstyle:CyclomaticComplexity")
    public static ZookeeperCluster fromCrd(Kafka kafkaAssembly, KafkaVersion.Lookup versions, Storage oldStorage) {
        ZookeeperCluster zk = new ZookeeperCluster(kafkaAssembly.getMetadata().getNamespace(), kafkaAssembly.getMetadata().getName(),
                Labels.fromResource(kafkaAssembly).withKind(kafkaAssembly.getKind()));
        zk.setOwnerReference(kafkaAssembly);
        ZookeeperClusterSpec zookeeperClusterSpec = kafkaAssembly.getSpec().getZookeeper();

        int replicas = zookeeperClusterSpec.getReplicas();
        if (replicas <= 0) {
            replicas = ZookeeperClusterSpec.DEFAULT_REPLICAS;
        }
        zk.setReplicas(replicas);

        String version = versions.defaultVersion().version();

        String image = zookeeperClusterSpec.getImage();
        if (image == null) {
            image = IMAGE_MAP.get(version);
        }
        if (image == null) {
            KafkaClusterSpec kafkaClusterSpec = kafkaAssembly.getSpec().getKafka();
            image = versions.kafkaImage(kafkaClusterSpec != null ? kafkaClusterSpec.getImage() : null,
                    kafkaClusterSpec != null ? kafkaClusterSpec.getVersion() : null);
        }
        zk.setImage(image);

        if (zookeeperClusterSpec.getReadinessProbe() != null) {
            zk.setReadinessProbe(zookeeperClusterSpec.getReadinessProbe());
        }
        if (zookeeperClusterSpec.getLivenessProbe() != null) {
            zk.setLivenessProbe(zookeeperClusterSpec.getLivenessProbe());
        }

        Logging logging = zookeeperClusterSpec.getLogging();
        zk.setLogging(logging == null ? new InlineLogging() : logging);
        zk.setGcLoggingEnabled(zookeeperClusterSpec.getJvmOptions() == null ? true : zookeeperClusterSpec.getJvmOptions().isGcLoggingEnabled());

        Map<String, Object> metrics = zookeeperClusterSpec.getMetrics();
        if (metrics != null) {
            zk.setMetricsEnabled(true);
            zk.setMetricsConfig(metrics.entrySet());
        }

        if (oldStorage != null) {
            Storage newStorage = zookeeperClusterSpec.getStorage();
            StorageDiff diff = new StorageDiff(oldStorage, newStorage);

            if (!diff.isEmpty()) {
                log.warn("Only the following changes to Zookeeper storage are allowed: changing the deleteClaim flag and increasing size of persistent claim volumes (depending on the volume type and used storage class).");
                log.warn("Your desired Zookeeper storage configuration contains changes which are not allowed. As a result, all storage changes will be ignored. Use DEBUG level logging for more information about the detected changes.");
                zk.setStorage(oldStorage);
            } else {
                zk.setStorage(newStorage);
            }
        } else {
            zk.setStorage(zookeeperClusterSpec.getStorage());
        }

        zk.setConfiguration(new ZookeeperConfiguration(zookeeperClusterSpec.getConfig().entrySet()));

        zk.setResources(zookeeperClusterSpec.getResources());

        zk.setJvmOptions(zookeeperClusterSpec.getJvmOptions());

        zk.setUserAffinity(affinity(zookeeperClusterSpec));

        zk.setTolerations(tolerations(zookeeperClusterSpec));

        TlsSidecar tlsSidecar = zookeeperClusterSpec.getTlsSidecar();
        if (tlsSidecar == null) {
            tlsSidecar = new TlsSidecar();
        }
        if (tlsSidecar.getImage() == null) {
            KafkaClusterSpec kafkaClusterSpec = kafkaAssembly.getSpec().getKafka();
            tlsSidecar.setImage(versions.kafkaImage(kafkaClusterSpec.getImage(), versions.defaultVersion().version()));
        }
        zk.setTlsSidecar(tlsSidecar);

        if (zookeeperClusterSpec.getTemplate() != null) {
            ZookeeperClusterTemplate template = zookeeperClusterSpec.getTemplate();

            if (template.getStatefulset() != null && template.getStatefulset().getMetadata() != null)  {
                zk.templateStatefulSetLabels = template.getStatefulset().getMetadata().getLabels();
                zk.templateStatefulSetAnnotations = template.getStatefulset().getMetadata().getAnnotations();
            }

            ModelUtils.parsePodTemplate(zk, template.getPod());

            if (template.getClientService() != null && template.getClientService().getMetadata() != null)  {
                zk.templateServiceLabels = template.getClientService().getMetadata().getLabels();
                zk.templateServiceAnnotations = template.getClientService().getMetadata().getAnnotations();
            }

            if (template.getNodesService() != null && template.getNodesService().getMetadata() != null)  {
                zk.templateHeadlessServiceLabels = template.getNodesService().getMetadata().getLabels();
                zk.templateHeadlessServiceAnnotations = template.getNodesService().getMetadata().getAnnotations();
            }

            ModelUtils.parsePodDisruptionBudgetTemplate(zk, template.getPodDisruptionBudget());
        }

        return zk;
    }

    @SuppressWarnings("deprecation")
    static List<Toleration> tolerations(ZookeeperClusterSpec zookeeperClusterSpec) {
        if (zookeeperClusterSpec.getTemplate() != null
                && zookeeperClusterSpec.getTemplate().getPod() != null
                && zookeeperClusterSpec.getTemplate().getPod().getTolerations() != null) {
            if (zookeeperClusterSpec.getAffinity() != null) {
                log.warn("Tolerations given on both spec.zookeeper.tolerations and spec.zookeeper.template.statefulset.tolerations; latter takes precedence");
            }
            return zookeeperClusterSpec.getTemplate().getPod().getTolerations();
        } else {
            return zookeeperClusterSpec.getTolerations();
        }
    }

    @SuppressWarnings("deprecation")
    static Affinity affinity(ZookeeperClusterSpec zookeeperClusterSpec) {
        if (zookeeperClusterSpec.getTemplate() != null
                && zookeeperClusterSpec.getTemplate().getPod() != null
                && zookeeperClusterSpec.getTemplate().getPod().getAffinity() != null) {
            if (zookeeperClusterSpec.getAffinity() != null) {
                log.warn("Affinity given on both spec.zookeeper.affinity and spec.zookeeper.template.statefulset.affinity; latter takes precedence");
            }
            return zookeeperClusterSpec.getTemplate().getPod().getAffinity();
        } else {
            return zookeeperClusterSpec.getAffinity();
        }
    }

    public Service generateService() {
        List<ServicePort> ports = new ArrayList<>(2);
        if (isMetricsEnabled()) {
            ports.add(createServicePort(METRICS_PORT_NAME, METRICS_PORT, METRICS_PORT, "TCP"));
        }
        ports.add(createServicePort(CLIENT_PORT_NAME, CLIENT_PORT, CLIENT_PORT, "TCP"));

        return createService("ClusterIP", ports, mergeAnnotations(getPrometheusAnnotations(), templateServiceAnnotations));
    }

    public static String policyName(String cluster) {
        return cluster + NETWORK_POLICY_KEY_SUFFIX + NAME_SUFFIX;
    }

    public NetworkPolicy generateNetworkPolicy(boolean namespaceAndPodSelectorNetworkPolicySupported) {
        List<NetworkPolicyIngressRule> rules = new ArrayList<>(2);

        NetworkPolicyPort clientsPort = new NetworkPolicyPort();
        clientsPort.setPort(new IntOrString(CLIENT_PORT));

        NetworkPolicyPort clusteringPort = new NetworkPolicyPort();
        clusteringPort.setPort(new IntOrString(CLUSTERING_PORT));

        NetworkPolicyPort leaderElectionPort = new NetworkPolicyPort();
        leaderElectionPort.setPort(new IntOrString(LEADER_ELECTION_PORT));

        NetworkPolicyPeer zookeeperClusterPeer = new NetworkPolicyPeer();
        LabelSelector labelSelector2 = new LabelSelector();
        Map<String, String> expressions2 = new HashMap<>();
        expressions2.put(Labels.STRIMZI_NAME_LABEL, zookeeperClusterName(cluster));
        labelSelector2.setMatchLabels(expressions2);
        zookeeperClusterPeer.setPodSelector(labelSelector2);

        // Zookeeper only ports - 2888 & 3888 which need to be accessed by the Zookeeper cluster members only
        NetworkPolicyIngressRule zookeeperClusteringIngressRule = new NetworkPolicyIngressRuleBuilder()
                .withPorts(clusteringPort, leaderElectionPort)
                .withFrom(zookeeperClusterPeer)
                .build();

        rules.add(zookeeperClusteringIngressRule);

        // Clients port - needs ot be access from outside the Zookeeper cluster as well
        NetworkPolicyIngressRule clientsIngressRule = new NetworkPolicyIngressRuleBuilder()
                .withPorts(clientsPort)
                .withFrom()
                .build();

        if (namespaceAndPodSelectorNetworkPolicySupported) {
            NetworkPolicyPeer kafkaClusterPeer = new NetworkPolicyPeer();
            LabelSelector labelSelector = new LabelSelector();
            Map<String, String> expressions = new HashMap<>();
            expressions.put(Labels.STRIMZI_NAME_LABEL, KafkaCluster.kafkaClusterName(cluster));
            labelSelector.setMatchLabels(expressions);
            kafkaClusterPeer.setPodSelector(labelSelector);

            NetworkPolicyPeer entityOperatorPeer = new NetworkPolicyPeer();
            LabelSelector labelSelector3 = new LabelSelector();
            Map<String, String> expressions3 = new HashMap<>();
            expressions3.put(Labels.STRIMZI_NAME_LABEL, EntityOperator.entityOperatorName(cluster));
            labelSelector3.setMatchLabels(expressions3);
            entityOperatorPeer.setPodSelector(labelSelector3);

            NetworkPolicyPeer clusterOperatorPeer = new NetworkPolicyPeer();
            LabelSelector labelSelector4 = new LabelSelector();
            Map<String, String> expressions4 = new HashMap<>();
            expressions4.put(Labels.STRIMZI_KIND_LABEL, "cluster-operator");
            labelSelector4.setMatchLabels(expressions4);
            clusterOperatorPeer.setPodSelector(labelSelector4);
            clusterOperatorPeer.setNamespaceSelector(new LabelSelector());

            // This is a hack because we have no guarantee that the CO namespace has some particular labels
            List<NetworkPolicyPeer> clientsPortPeers = new ArrayList<>(4);
            clientsPortPeers.add(kafkaClusterPeer);
            clientsPortPeers.add(zookeeperClusterPeer);
            clientsPortPeers.add(entityOperatorPeer);
            clientsPortPeers.add(clusterOperatorPeer);

            clientsIngressRule.setFrom(clientsPortPeers);
        }

        rules.add(clientsIngressRule);

        if (isMetricsEnabled) {
            NetworkPolicyPort metricsPort = new NetworkPolicyPort();
            metricsPort.setPort(new IntOrString(METRICS_PORT));

            NetworkPolicyIngressRule metricsRule = new NetworkPolicyIngressRuleBuilder()
                    .withPorts(metricsPort)
                    .withFrom()
                    .build();

            rules.add(metricsRule);
        }

        NetworkPolicy networkPolicy = new NetworkPolicyBuilder()
                .withNewMetadata()
                    .withName(policyName(cluster))
                    .withNamespace(namespace)
                    .withLabels(labels.toMap())
                    .withOwnerReferences(createOwnerReference())
                .endMetadata()
                .withNewSpec()
                    .withPodSelector(labelSelector2)
                    .withIngress(rules)
                .endSpec()
                .build();

        log.trace("Created network policy {}", networkPolicy);
        return networkPolicy;
    }

    public Service generateHeadlessService() {
        return createHeadlessService(getServicePortList());
    }

    public StatefulSet generateStatefulSet(boolean isOpenShift, ImagePullPolicy imagePullPolicy, List<LocalObjectReference> imagePullSecrets) {

        return createStatefulSet(
                Collections.singletonMap(ANNO_STRIMZI_IO_STORAGE, ModelUtils.encodeStorageToJson(storage)),
                getVolumes(isOpenShift),
                getVolumeClaims(),
                getMergedAffinity(),
                getInitContainers(imagePullPolicy),
                getContainers(imagePullPolicy),
                imagePullSecrets,
                isOpenShift);
    }

    /**
     * Generate the Secret containing the Zookeeper nodes certificates signed by the cluster CA certificate used for TLS based
     * internal communication with Kafka.
     * It also contains the related Zookeeper nodes private keys.
     *
     * @param clusterCa The cluster CA.
     * @param kafka The Kafka resource.
     * @return The generated Secret.
     */
    public Secret generateNodesSecret(ClusterCa clusterCa, Kafka kafka) {

        Map<String, String> data = new HashMap<>();

        log.debug("Generating certificates");
        Map<String, CertAndKey> certs;
        try {
            log.debug("Cluster communication certificates");
            certs = clusterCa.generateZkCerts(kafka);
            log.debug("End generating certificates");
            for (int i = 0; i < replicas; i++) {
                CertAndKey cert = certs.get(ZookeeperCluster.zookeeperPodName(cluster, i));
                data.put(ZookeeperCluster.zookeeperPodName(cluster, i) + ".key", cert.keyAsBase64String());
                data.put(ZookeeperCluster.zookeeperPodName(cluster, i) + ".crt", cert.certAsBase64String());
            }

        } catch (IOException e) {
            log.warn("Error while generating certificates", e);
        }

        return createSecret(ZookeeperCluster.nodesSecretName(cluster), data);
    }

    @Override
    protected List<Container> getContainers(ImagePullPolicy imagePullPolicy) {

        List<Container> containers = new ArrayList<>();

        Container container = new ContainerBuilder()
                .withName(ZOOKEEPER_NAME)
                .withImage(getImage())
                .withCommand("/opt/kafka/zookeeper_run.sh")
                .withEnv(getEnvVars())
                .withVolumeMounts(getVolumeMounts())
                .withPorts(getContainerPortList())
                .withLivenessProbe(ModelUtils.createExecProbe(Collections.singletonList(livenessPath), livenessProbeOptions))
                .withReadinessProbe(ModelUtils.createExecProbe(Collections.singletonList(readinessPath), readinessProbeOptions))
                .withResources(getResources())
                .withImagePullPolicy(determineImagePullPolicy(imagePullPolicy, getImage()))
                .build();

        String tlsSidecarImage = getImage();
        if (tlsSidecar != null && tlsSidecar.getImage() != null) {
            tlsSidecarImage = tlsSidecar.getImage();
        }

        Container tlsSidecarContainer = new ContainerBuilder()
                .withName(TLS_SIDECAR_NAME)
                .withImage(tlsSidecarImage)
                .withCommand("/opt/stunnel/zookeeper_stunnel_run.sh")
                .withLivenessProbe(ModelUtils.tlsSidecarLivenessProbe(tlsSidecar))
                .withReadinessProbe(ModelUtils.tlsSidecarReadinessProbe(tlsSidecar))
                .withResources(tlsSidecar != null ? tlsSidecar.getResources() : null)
                .withEnv(asList(ModelUtils.tlsSidecarLogEnvVar(tlsSidecar),
                        buildEnvVar(ENV_VAR_ZOOKEEPER_NODE_COUNT, Integer.toString(replicas))))
                .withVolumeMounts(createVolumeMount(TLS_SIDECAR_NODES_VOLUME_NAME, TLS_SIDECAR_NODES_VOLUME_MOUNT),
                        createVolumeMount(TLS_SIDECAR_CLUSTER_CA_VOLUME_NAME, TLS_SIDECAR_CLUSTER_CA_VOLUME_MOUNT))
                .withPorts(asList(createContainerPort(CLUSTERING_PORT_NAME, CLUSTERING_PORT, "TCP"),
                                createContainerPort(LEADER_ELECTION_PORT_NAME, LEADER_ELECTION_PORT, "TCP"),
                                createContainerPort(CLIENT_PORT_NAME, CLIENT_PORT, "TCP")))
                .withLifecycle(new LifecycleBuilder().withNewPreStop()
                        .withNewExec().withCommand("/opt/stunnel/zookeeper_stunnel_pre_stop.sh",
                                String.valueOf(templateTerminationGracePeriodSeconds))
                        .endExec().endPreStop().build())
                .withImagePullPolicy(determineImagePullPolicy(imagePullPolicy, tlsSidecarImage))
                .build();

        containers.add(container);
        containers.add(tlsSidecarContainer);

        return containers;
    }

    @Override
    protected List<EnvVar> getEnvVars() {
        List<EnvVar> varList = new ArrayList<>();
        varList.add(buildEnvVar(ENV_VAR_ZOOKEEPER_NODE_COUNT, Integer.toString(replicas)));
        varList.add(buildEnvVar(ENV_VAR_ZOOKEEPER_METRICS_ENABLED, String.valueOf(isMetricsEnabled)));
        varList.add(buildEnvVar(ENV_VAR_STRIMZI_KAFKA_GC_LOG_ENABLED, String.valueOf(gcLoggingEnabled)));

        heapOptions(varList, 0.75, 2L * 1024L * 1024L * 1024L);
        jvmPerformanceOptions(varList);
        varList.add(buildEnvVar(ENV_VAR_ZOOKEEPER_CONFIGURATION, configuration.getConfiguration()));
        return varList;
    }

    private List<ServicePort> getServicePortList() {
        List<ServicePort> portList = new ArrayList<>();
        portList.add(createServicePort(CLIENT_PORT_NAME, CLIENT_PORT, CLIENT_PORT, "TCP"));
        portList.add(createServicePort(CLUSTERING_PORT_NAME, CLUSTERING_PORT, CLUSTERING_PORT, "TCP"));
        portList.add(createServicePort(LEADER_ELECTION_PORT_NAME, LEADER_ELECTION_PORT, LEADER_ELECTION_PORT, "TCP"));

        return portList;
    }

    private List<ContainerPort> getContainerPortList() {
        List<ContainerPort> portList = new ArrayList<>();
        if (isMetricsEnabled) {
            portList.add(createContainerPort(METRICS_PORT_NAME, METRICS_PORT, "TCP"));
        }

        return portList;
    }

    private List<Volume> getVolumes(boolean isOpenShift) {
        List<Volume> volumeList = new ArrayList<>();
        if (storage instanceof EphemeralStorage) {
            volumeList.add(createEmptyDirVolume(VOLUME_NAME));
        }
        volumeList.add(createConfigMapVolume(logAndMetricsConfigVolumeName, ancillaryConfigName));
        volumeList.add(createSecretVolume(TLS_SIDECAR_NODES_VOLUME_NAME, ZookeeperCluster.nodesSecretName(cluster), isOpenShift));
        volumeList.add(createSecretVolume(TLS_SIDECAR_CLUSTER_CA_VOLUME_NAME, AbstractModel.clusterCaCertSecretName(cluster), isOpenShift));
        return volumeList;
    }

    /* test */ List<PersistentVolumeClaim> getVolumeClaims() {
        List<PersistentVolumeClaim> pvcList = new ArrayList<>();
        if (storage instanceof PersistentClaimStorage) {
            pvcList.add(createPersistentVolumeClaimTemplate(VOLUME_NAME, (PersistentClaimStorage) storage));
        }
        return pvcList;
    }

    public List<PersistentVolumeClaim> generatePersistentVolumeClaims() {
        List<PersistentVolumeClaim> pvcList = new ArrayList<>();
        if (storage instanceof PersistentClaimStorage) {
            for (int i = 0; i < replicas; i++) {
                pvcList.add(createPersistentVolumeClaim(i, "data-" + name + "-" + i, (PersistentClaimStorage) storage));
            }
        }
        return pvcList;
    }

    private List<VolumeMount> getVolumeMounts() {
        List<VolumeMount> volumeMountList = new ArrayList<>();
        volumeMountList.add(createVolumeMount(VOLUME_NAME, mountPath));
        volumeMountList.add(createVolumeMount(logAndMetricsConfigVolumeName, logAndMetricsConfigMountPath));

        return volumeMountList;
    }

    /**
     * Generates the PodDisruptionBudget.
     *
     * @return The PodDisruptionBudget.
     */
    public PodDisruptionBudget generatePodDisruptionBudget() {
        return createPodDisruptionBudget();
    }

    protected void setTlsSidecar(TlsSidecar tlsSidecar) {
        this.tlsSidecar = tlsSidecar;
    }

    @Override
    protected String getDefaultLogConfigFileName() {
        return "zookeeperDefaultLoggingProperties";
    }

    /**
     * Get the name of the zookeeper service account given the name of the {@code zookeeperResourceName}.
     * @param zookeeperResourceName The resource name.
     * @return The service account name.
     */
    public static String containerServiceAccountName(String zookeeperResourceName) {
        return zookeeperClusterName(zookeeperResourceName);
    }

    @Override
    protected String getServiceAccountName() {
        return containerServiceAccountName(cluster);
    }
}
