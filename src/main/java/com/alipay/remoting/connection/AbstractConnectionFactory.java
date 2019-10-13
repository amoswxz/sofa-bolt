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
package com.alipay.remoting.connection;

import com.alipay.remoting.Connection;
import com.alipay.remoting.ConnectionEventHandler;
import com.alipay.remoting.ConnectionEventType;
import com.alipay.remoting.NamedThreadFactory;
import com.alipay.remoting.ProtocolCode;
import com.alipay.remoting.Url;
import com.alipay.remoting.codec.Codec;
import com.alipay.remoting.config.ConfigManager;
import com.alipay.remoting.config.ConfigurableInstance;
import com.alipay.remoting.log.BoltLoggerFactory;
import com.alipay.remoting.rpc.protocol.RpcProtocol;
import com.alipay.remoting.rpc.protocol.RpcProtocolV2;
import com.alipay.remoting.util.NettyEventLoopUtil;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.timeout.IdleStateHandler;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;

/**
 * ConnectionFactory to create connection.
 *
 * @author chengyi (mark.lx@antfin.com) 2018-06-20 14:29
 */
public abstract class AbstractConnectionFactory implements ConnectionFactory {

    private static final Logger logger = BoltLoggerFactory
            .getLogger(AbstractConnectionFactory.class);

    /**
     * 如果是CPU密集型应用，则线程池大小设置为N+1如果是IO密集型应用，则线程池大小设置为2N+1如果一台服务器上只部署这一个应用并且只有这一个线程池，那么这种估算或许合理，
     * 具体还需自行测试验证。但是，IO优化中，这样的估算公式可能更适合：最佳线程数目 = （（线程等待时间+线程CPU时间）/线程CPU时间 ）* CPU数目因为很显然，线程等待时间所占比例越高，需要越多线程。
     * 线程CPU时间所占比例越高，需要越少线程。下面举个例子：比如平均每个线程CPU运行时间为0.5s，而线程等待时间（非CPU运行时间，比如IO）为1.5s，CPU核心数为8，
     * 那么根据上面这个公式估算得到：((0.5+1.5)/0.5)*8=32。这个公式进一步转化为：最佳线程数目 = （线程等待时间与线程CPU时间之比 + 1）* CPU数目
     * 对于计算密集型的任务，在拥有N个处理器的系统上，当线程池的大小为N+1时，通常能实现最优的效率。(即使当计算密集型的线程偶尔由于缺失故障或者其他原因而暂停时，这个额外的线程也能确保CPU的时钟周期不会被浪费。)
     */
    private static final EventLoopGroup workerGroup = NettyEventLoopUtil.newEventLoopGroup(Runtime
                    .getRuntime().availableProcessors() + 1, new NamedThreadFactory("bolt-netty-client-worker", true));

    private final ConfigurableInstance confInstance;
    private final Codec codec;
    private final ChannelHandler heartbeatHandler;
    private final ChannelHandler handler;

    protected Bootstrap bootstrap;

    public AbstractConnectionFactory(Codec codec, ChannelHandler heartbeatHandler,
            ChannelHandler handler, ConfigurableInstance confInstance) {
        if (codec == null) {
            throw new IllegalArgumentException("null codec");
        }
        if (handler == null) {
            throw new IllegalArgumentException("null handler");
        }

        this.confInstance = confInstance;
        this.codec = codec;
        this.heartbeatHandler = heartbeatHandler;
        this.handler = handler;
    }

    /**
     *
     * @param connectionEventHandler 是RpcConnectionEventHandler
     */
    @Override
    public void init(final ConnectionEventHandler connectionEventHandler) {
        bootstrap = new Bootstrap();
        bootstrap.group(workerGroup).channel(NettyEventLoopUtil.getClientSocketChannelClass())
                .option(ChannelOption.TCP_NODELAY, ConfigManager.tcp_nodelay())
                .option(ChannelOption.SO_REUSEADDR, ConfigManager.tcp_so_reuseaddr())
                .option(ChannelOption.SO_KEEPALIVE, ConfigManager.tcp_so_keepalive());

        // init netty write buffer water mark
        //这里就是设置 write的buffer的大小 也可以说成设置水位大小
        initWriteBufferWaterMark();

        // init byte buf allocator
        //判断是否是用池化
        if (ConfigManager.netty_buffer_pooled()) {
            this.bootstrap.option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
        } else {
            this.bootstrap.option(ChannelOption.ALLOCATOR, UnpooledByteBufAllocator.DEFAULT);
        }
        bootstrap.handler(new ChannelInitializer<SocketChannel>() {

            @Override
            protected void initChannel(SocketChannel channel) {
                ChannelPipeline pipeline = channel.pipeline();
                pipeline.addLast("decoder", codec.newDecoder());
                pipeline.addLast("encoder", codec.newEncoder());
                //判断是否开启连接检测开关
                boolean idleSwitch = ConfigManager.tcp_idle_switch();
                if (idleSwitch) {
                    pipeline.addLast("idleStateHandler",
                            new IdleStateHandler(ConfigManager.tcp_idle(), ConfigManager.tcp_idle(), 0, TimeUnit.MILLISECONDS));
                    pipeline.addLast("heartbeatHandler", heartbeatHandler);
                }
                //todo 这里的RpcConnectionEventHandler 有什么作用
                pipeline.addLast("connectionEventHandler", connectionEventHandler);
                pipeline.addLast("handler", handler);
            }
        });
    }

    @Override
    public Connection createConnection(Url url) throws Exception {
        Channel channel = doCreateConnection(url.getIp(), url.getPort(), url.getConnectTimeout());
        Connection conn = new Connection(channel, ProtocolCode.fromBytes(url.getProtocol()),
                url.getVersion(), url);
        System.out.println("断点");
        channel.pipeline().fireUserEventTriggered(ConnectionEventType.CONNECT);
        return conn;
    }

    @Override
    public Connection createConnection(String targetIP, int targetPort, int connectTimeout)
            throws Exception {
        Channel channel = doCreateConnection(targetIP, targetPort, connectTimeout);
        Connection conn = new Connection(channel,
                ProtocolCode.fromBytes(RpcProtocol.PROTOCOL_CODE), RpcProtocolV2.PROTOCOL_VERSION_1,
                new Url(targetIP, targetPort));
        channel.pipeline().fireUserEventTriggered(ConnectionEventType.CONNECT);
        return conn;
    }

    @Override
    public Connection createConnection(String targetIP, int targetPort, byte version,
            int connectTimeout) throws Exception {
        Channel channel = doCreateConnection(targetIP, targetPort, connectTimeout);
        Connection conn = new Connection(channel,
                ProtocolCode.fromBytes(RpcProtocolV2.PROTOCOL_CODE), version, new Url(targetIP,
                targetPort));
        channel.pipeline().fireUserEventTriggered(ConnectionEventType.CONNECT);
        return conn;
    }

    /**
     * init netty write buffer water mark
     */
    private void initWriteBufferWaterMark() {
        int lowWaterMark = this.confInstance.netty_buffer_low_watermark();
        int highWaterMark = this.confInstance.netty_buffer_high_watermark();
        if (lowWaterMark > highWaterMark) {
            throw new IllegalArgumentException(
                    String
                            .format(
                                    "[client side] bolt netty high water mark {%s} should not be smaller than low water mark {%s} bytes)",
                                    highWaterMark, lowWaterMark));
        } else {
            logger.warn(
                    "[client side] bolt netty low water mark is {} bytes, high water mark is {} bytes",
                    lowWaterMark, highWaterMark);
        }
        this.bootstrap.option(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(
                lowWaterMark, highWaterMark));
    }

    protected Channel doCreateConnection(String targetIP, int targetPort, int connectTimeout)
            throws Exception {
        // prevent unreasonable value, at least 1000
        connectTimeout = Math.max(connectTimeout, 1000);
        String address = targetIP + ":" + targetPort;
        if (logger.isDebugEnabled()) {
            logger.debug("connectTimeout of address [{}] is [{}].", address, connectTimeout);
        }
        bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, connectTimeout);
        ChannelFuture future = bootstrap.connect(new InetSocketAddress(targetIP, targetPort));

        future.awaitUninterruptibly();
        if (!future.isDone()) {
            String errMsg = "Create connection to " + address + " timeout!";
            logger.warn(errMsg);
            throw new Exception(errMsg);
        }
        if (future.isCancelled()) {
            String errMsg = "Create connection to " + address + " cancelled by user!";
            logger.warn(errMsg);
            throw new Exception(errMsg);
        }
        if (!future.isSuccess()) {
            String errMsg = "Create connection to " + address + " error!";
            logger.warn(errMsg);
            throw new Exception(errMsg, future.cause());
        }
        return future.channel();
    }
}
