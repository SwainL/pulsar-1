/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.pulsar.functions.worker;

import com.google.common.util.concurrent.AbstractService;
import java.io.IOException;
import java.net.URI;
import java.util.concurrent.LinkedBlockingQueue;

import lombok.extern.slf4j.Slf4j;
import org.apache.distributedlog.DistributedLogConfiguration;
import org.apache.distributedlog.api.namespace.Namespace;
import org.apache.distributedlog.api.namespace.NamespaceBuilder;
import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.PulsarClientException;
import org.apache.pulsar.client.api.Reader;
import org.apache.pulsar.client.api.ReaderConfiguration;
import org.apache.pulsar.functions.runtime.container.FunctionContainerFactory;
import org.apache.pulsar.functions.runtime.container.ProcessFunctionContainerFactory;
import org.apache.pulsar.functions.runtime.container.ThreadFunctionContainerFactory;
import org.apache.pulsar.functions.runtime.metrics.MetricsSink;
import org.apache.pulsar.functions.worker.request.ServiceRequestManager;
import org.apache.pulsar.functions.worker.rest.WorkerServer;

@Slf4j
public class Worker extends AbstractService {

    private final WorkerConfig workerConfig;
    private MetricsSink metricsSink;
    private PulsarClient client;
    private FunctionRuntimeManager functionRuntimeManager;
    private FunctionMetaDataTopicTailer functionMetaDataTopicTailer;
    private FunctionContainerFactory functionContainerFactory;
    private Thread serverThread;
    private Namespace dlogNamespace;
    private LinkedBlockingQueue<FunctionAction> actionQueue;
    private FunctionActioner functionActioner;

    public Worker(WorkerConfig workerConfig) {
        this.workerConfig = workerConfig;
    }

    @Override
    protected void doStart() {
        try {
            doStartImpl();
        } catch (Throwable t) {
            t.printStackTrace();
        }
    }

    protected void doStartImpl() {
        // initialize the dlog namespace
        // TODO: move this as part of pulsar cluster initialization later
        URI dlogUri;
        try {
            dlogUri = Utils.initializeDlogNamespace(workerConfig.getZookeeperServers());
        } catch (IOException ioe) {
            log.error("Failed to initialize dlog namespace at zookeeper {} for storing function packages",
                workerConfig.getZookeeperServers(), ioe);
            throw new RuntimeException(ioe);
        }

        // create the dlog namespace for storing function packages
        DistributedLogConfiguration dlogConf = Utils.getDlogConf(workerConfig);
        try {
            this.dlogNamespace = NamespaceBuilder.newBuilder()
                .conf(dlogConf)
                .clientId("function-worker-" + workerConfig.getWorkerId())
                .uri(dlogUri)
                .build();
        } catch (Exception e) {
            log.error("Failed to initialize dlog namespace {} for storing function packages",
                dlogUri, e);
            throw new RuntimeException(e);
        }

        // initialize the function metadata manager
        try {
            log.info("Initialize function metadata manager ...");

            this.client = PulsarClient.create(workerConfig.getPulsarServiceUrl());
            ServiceRequestManager reqMgr = new ServiceRequestManager(
                client.createProducer(workerConfig.getFunctionMetadataTopic()));

            if (workerConfig.getThreadContainerFactory() != null) {
                this.functionContainerFactory = new ThreadFunctionContainerFactory(
                        workerConfig.getThreadContainerFactory().getThreadGroupName(),
                        workerConfig.getLimitsConfig().getMaxBufferedTuples(),
                        workerConfig.getPulsarServiceUrl());
            } else if (workerConfig.getProcessContainerFactory() != null) {
                this.functionContainerFactory = new ProcessFunctionContainerFactory(
                        workerConfig.getLimitsConfig().getMaxBufferedTuples(),
                        workerConfig.getPulsarServiceUrl(),
                        workerConfig.getProcessContainerFactory().getJavaInstanceJarLocation(),
                        workerConfig.getProcessContainerFactory().getLogDirectory());
            } else {
                throw new RuntimeException("Either Thread or Process Container Factory need to be set");
            }

            this.actionQueue = new LinkedBlockingQueue<>();

            this.functionRuntimeManager = new FunctionRuntimeManager(workerConfig, reqMgr, actionQueue);

            this.metricsSink = createMetricsSink();
            metricsSink.init(workerConfig.getMetricsConfig().getMetricsSinkConfig());

            this.functionActioner = new FunctionActioner(workerConfig, functionContainerFactory,
                    metricsSink, workerConfig.getMetricsConfig().getMetricsCollectionInterval(),
                    dlogNamespace, actionQueue);
            this.functionActioner.start();

            ReaderConfiguration readerConf = new ReaderConfiguration();

            Reader reader = client.createReader(
                workerConfig.getFunctionMetadataTopic(),
                MessageId.earliest,
                readerConf);

            this.functionMetaDataTopicTailer = new FunctionMetaDataTopicTailer(
                    functionRuntimeManager,
                    reader);

            log.info("Start worker {}...", workerConfig.getWorkerId());
            log.info("Worker Configs: {}", workerConfig);
        } catch (Exception e) {
            log.error("Error Starting up in worker", e);
            throw new RuntimeException(e);
        }

        WorkerServer server = new WorkerServer(workerConfig, functionRuntimeManager, dlogNamespace);
        this.serverThread = new Thread(server, server.getThreadName());

        log.info("Start worker server on port {}...", workerConfig.getWorkerPort());
        serverThread.start();
        log.info("Start worker function state consumer ...");
        functionMetaDataTopicTailer.start();
    }

    @Override
    protected void doStop() {
        if (null != serverThread) {
            serverThread.interrupt();
            try {
                serverThread.join();
            } catch (InterruptedException e) {
                log.warn("Worker server thread is interrupted", e);
            }
        }
        if (null != functionMetaDataTopicTailer) {
            functionMetaDataTopicTailer.close();
        }
        if (null != functionRuntimeManager) {
            functionRuntimeManager.close();
        }
        if (null != client) {
            try {
                client.close();
            } catch (PulsarClientException e) {
                log.warn("Failed to close pulsar client", e);
            }
        }
        if (null != functionActioner) {
            functionActioner.close();
        }
    }

    private MetricsSink createMetricsSink() {
        String className = workerConfig.getMetricsConfig().getMetricsSinkClassName();
        try {
            MetricsSink sink = (MetricsSink) Class.forName(className).newInstance();
            return sink;
        } catch (InstantiationException e) {
            throw new RuntimeException(e + " IMetricsSink class must have a no-arg constructor.");
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e + " IMetricsSink class must be concrete.");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e + " IMetricsSink class must be a class path.");
        }
    }
}