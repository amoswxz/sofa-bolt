/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.alipay.remoting;

import com.alipay.remoting.log.BoltLoggerFactory;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import org.slf4j.Logger;

/**
 * Reconnect manager.
 *
 * @author yunliang.shi
 * @version $Id: ReconnectManager.java, v 0.1 Mar 11, 2016 5:20:50 PM yunliang.shi Exp $
 */
public class ReconnectManager extends AbstractLifeCycle implements Reconnector {

    private static final Logger logger = BoltLoggerFactory
            .getLogger("CommonDefault");

    private static final int HEAL_CONNECTION_INTERVAL = 1000;

    private final ConnectionManager connectionManager;
    //这个里面存的是url对应的task
    private final LinkedBlockingQueue<ReconnectTask> tasks;
    private final List<Url> canceled;

    private Thread healConnectionThreads;

    public ReconnectManager(ConnectionManager connectionManager) {
        this.connectionManager = connectionManager;
        this.tasks = new LinkedBlockingQueue<ReconnectTask>();
        this.canceled = new CopyOnWriteArrayList<Url>();
    }

    @Override
    public void reconnect(Url url) {
        tasks.add(new ReconnectTask(url));
    }

    @Override
    public void disableReconnect(Url url) {
        canceled.add(url);
    }

    @Override
    public void enableReconnect(Url url) {
        canceled.remove(url);
    }

    @Override
    public void startup() throws LifeCycleException {
        super.startup();
        this.healConnectionThreads = new Thread(new HealConnectionRunner());
        this.healConnectionThreads.start();
    }

    @Override
    public void shutdown() throws LifeCycleException {
        super.shutdown();

        healConnectionThreads.interrupt();
        this.tasks.clear();
        this.canceled.clear();
    }

    /**
     * please use {@link Reconnector#disableReconnect(Url)} instead
     */
    @Deprecated
    public void addCancelUrl(Url url) {
        disableReconnect(url);
    }

    /**
     * please use {@link Reconnector#enableReconnect(Url)} instead
     */
    @Deprecated
    public void removeCancelUrl(Url url) {
        enableReconnect(url);
    }

    /**
     * please use {@link Reconnector#reconnect(Url)} instead
     */
    @Deprecated
    public void addReconnectTask(Url url) {
        reconnect(url);
    }

    /**
     * please use {@link Reconnector#shutdown()} instead
     */
    @Deprecated
    public void stop() {
        shutdown();
    }

    /**
     * 创建修复连接线程healConnectionThreads，修复任务为HealConnectionRunner；
     */
    private final class HealConnectionRunner implements Runnable {

        private long lastConnectTime = -1;

        @Override
        public void run() {
            while (isStarted()) {
                long start = -1;
                ReconnectTask task = null;
                try {
                    //  修复连接间隔时间healConnectionInterval，单位毫米，默认1000），避免过度浪费CPU资源：
                    if (this.lastConnectTime < HEAL_CONNECTION_INTERVAL) {
                        Thread.sleep(HEAL_CONNECTION_INTERVAL);
                    }
                    try {
                        /**
                         *  从tasks中获取重连连接任务。如果有任务，则调用DefaultConnectionManager的createConnectionAndHealIfNeed()
                         *  为指定Url对应的连接池执行连接修复操作，以满足连接池的初始需求。如果没有任务，则阻塞该线程，等待任务。
                         */
                        task = ReconnectManager.this.tasks.take();
                    } catch (InterruptedException e) {
                        // ignore
                    }

                    if (task == null) {
                        continue;
                    }
                    start = System.currentTimeMillis();
                    if (!canceled.contains(task.url)) {
                        task.run();
                    } else {
                        logger.warn("Invalid reconnect request task {}, cancel list size {}",
                                task.url, canceled.size());
                    }
                    this.lastConnectTime = System.currentTimeMillis() - start;
                } catch (Exception e) {
                    if (start != -1) {
                        this.lastConnectTime = System.currentTimeMillis() - start;
                    }

                    if (task != null) {
                        logger.warn("reconnect target: {} failed.", task.url, e);
                        tasks.add(task);
                    }
                }
            }
        }
    }

    private class ReconnectTask implements Runnable {

        Url url;

        public ReconnectTask(Url url) {
            this.url = url;
        }

        @Override
        public void run() {
            try {
                connectionManager.createConnectionAndHealIfNeed(url);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }
}
