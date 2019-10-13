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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;

/**
 * Connection pool
 *
 * @author xiaomin.cxm
 * @version $Id: ConnectionPool.java, v 0.1 Mar 8, 2016 11:04:54 AM xiaomin.cxm Exp $
 */
public class ConnectionPool implements Scannable {

    private static final Logger logger = BoltLoggerFactory.getLogger("CommonDefault");
    /**
     * 要用来缓存已经创建的连接；
     */
    private CopyOnWriteArrayList<Connection> connections;
    /**
     * 类型为ConnectionSelectStrategy，主要用来从缓存的连接中按照某种策略选择一个连接。通过实现ConnectionSelectStrategy接口，提供不同的连接选择策略。
     * 例如：RandomSelectStrategy，实现从conns中随机选择一个连接；
     */
    private ConnectionSelectStrategy strategy;
    /**
     * 类型为long，主要用来记录最后一次访问连接池的时间。同时声明为volatile，在多线程环境下，保证内存的可见性，但不能保证操作的原子性；
     */
    private volatile long lastAccessTimestamp;
    /**
     * 类型为boolean，表示是否异步创建连接。同时声明为volatile，意义同上。
     */
    private volatile boolean asyncCreationDone;

    /**
     * Constructor
     *
     * @param strategy ConnectionSelectStrategy
     */
    public ConnectionPool(ConnectionSelectStrategy strategy) {
        this.strategy = strategy;
        this.connections = new CopyOnWriteArrayList<Connection>();
        this.asyncCreationDone = true;
    }

    /**
     * add a connection
     *
     * @param connection Connection
     */
    public void add(Connection connection) {
        markAccess();
        if (null == connection) {
            return;
        }
        boolean res = connections.addIfAbsent(connection);
        if (res) {
            connection.increaseRef();
        }
    }

    /**
     * check weather a connection already added
     *
     * @param connection Connection
     * @return whether this pool contains the target connection
     */
    public boolean contains(Connection connection) {
        return connections.contains(connection);
    }

    /**
     * removeAndTryClose a connection
     *
     * @param connection Connection
     */
    public void removeAndTryClose(Connection connection) {
        if (null == connection) {
            return;
        }
        boolean res = connections.remove(connection);
        if (res) {
            connection.decreaseRef();
        }
        if (connection.noRef()) {
            connection.close();
        }
    }

    /**
     * remove all connections
     */
    public void removeAllAndTryClose() {
        for (Connection conn : connections) {
            removeAndTryClose(conn);
        }
        connections.clear();
    }

    /**
     * get a connection
     *
     * @return Connection
     */
    public Connection get() {
        markAccess();
        if (null != connections) {
            List<Connection> snapshot = new ArrayList<Connection>(connections);
            if (snapshot.size() > 0) {
                return strategy.select(snapshot);
            } else {
                return null;
            }
        } else {
            return null;
        }
    }

    /**
     * get all connections
     *
     * @return Connection List
     */
    public List<Connection> getAll() {
        markAccess();
        return new ArrayList<Connection>(connections);
    }

    /**
     * connection pool size
     *
     * @return pool size
     */
    public int size() {
        return connections.size();
    }

    /**
     * is connection pool empty
     *
     * @return true if this connection pool has no connection
     */
    public boolean isEmpty() {
        return connections.isEmpty();
    }

    /**
     * Getter method for property <tt>lastAccessTimestamp</tt>.
     *
     * @return property value of lastAccessTimestamp
     */
    public long getLastAccessTimestamp() {
        return lastAccessTimestamp;
    }

    /**
     * do mark the time stamp when access this pool
     */
    private void markAccess() {
        lastAccessTimestamp = System.currentTimeMillis();
    }

    /**
     * is async create connection done
     *
     * @return true if async create connection done
     */
    public boolean isAsyncCreationDone() {
        return asyncCreationDone;
    }

    /**
     * do mark async create connection done
     */
    public void markAsyncCreationDone() {
        asyncCreationDone = true;
    }

    /**
     * do mark async create connection start
     */
    public void markAsyncCreationStart() {
        asyncCreationDone = false;
    }

    @Override
    public void scan() {
        if (null != connections && !connections.isEmpty()) {
            for (Connection conn : connections) {
                //检查连接是否可用
                if (!conn.isFine()) {
                    logger.warn("Remove bad connection when scanning conns of ConnectionPool - {}:{}",
                            conn.getRemoteIP(), conn.getRemotePort());
                    conn.close();
                    removeAndTryClose(conn);
                }
            }
        }
    }
}
