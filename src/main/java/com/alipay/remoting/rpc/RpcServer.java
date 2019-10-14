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
package com.alipay.remoting.rpc;

import com.alipay.remoting.AbstractRemotingServer;
import com.alipay.remoting.CommandCode;
import com.alipay.remoting.Connection;
import com.alipay.remoting.ConnectionEventHandler;
import com.alipay.remoting.ConnectionEventListener;
import com.alipay.remoting.ConnectionEventProcessor;
import com.alipay.remoting.ConnectionEventType;
import com.alipay.remoting.ConnectionSelectStrategy;
import com.alipay.remoting.DefaultConnectionManager;
import com.alipay.remoting.DefaultServerConnectionManager;
import com.alipay.remoting.InvokeCallback;
import com.alipay.remoting.InvokeContext;
import com.alipay.remoting.NamedThreadFactory;
import com.alipay.remoting.ProtocolCode;
import com.alipay.remoting.ProtocolManager;
import com.alipay.remoting.RandomSelectStrategy;
import com.alipay.remoting.RemotingAddressParser;
import com.alipay.remoting.RemotingProcessor;
import com.alipay.remoting.RemotingServer;
import com.alipay.remoting.ServerIdleHandler;
import com.alipay.remoting.Url;
import com.alipay.remoting.codec.Codec;
import com.alipay.remoting.config.ConfigManager;
import com.alipay.remoting.config.switches.GlobalSwitch;
import com.alipay.remoting.exception.RemotingException;
import com.alipay.remoting.log.BoltLoggerFactory;
import com.alipay.remoting.rpc.protocol.UserProcessor;
import com.alipay.remoting.rpc.protocol.UserProcessorRegisterHelper;
import com.alipay.remoting.util.NettyEventLoopUtil;
import com.alipay.remoting.util.RemotingUtil;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.buffer.UnpooledByteBufAllocator;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.WriteBufferWaterMark;
import io.netty.channel.epoll.EpollEventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.timeout.IdleStateHandler;

import java.net.InetSocketAddress;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;

/**
 * Server for Rpc.
 * <p>
 * Usage: You can initialize RpcServer with one of the three constructors: {@link #RpcServer(int)}, {@link
 * #RpcServer(int, boolean)}, {@link #RpcServer(int, boolean, boolean)} Then call start() to start a rpc server, and
 * call stop() to stop a rpc server.
 * <p>
 * Notice: Once rpc server has been stopped, it can never be start again. You should init another instance of RpcServer
 * to use.
 *
 * @author jiangping
 * @version $Id: RpcServer.java, v 0.1 2015-8-31 PM5:22:22 tao Exp $
 */
public class RpcServer extends AbstractRemotingServer {

    /**
     * logger
     */
    private static final Logger logger = BoltLoggerFactory
            .getLogger("RpcRemoting");
    /**
     * worker event loop group. Reuse I/O worker threads between rpc servers.
     */
    private static final EventLoopGroup workerGroup = NettyEventLoopUtil
            .newEventLoopGroup(
                    Runtime
                            .getRuntime()
                            .availableProcessors() * 2,
                    new NamedThreadFactory(
                            "Rpc-netty-server-worker",
                            true));

    static {
        if (workerGroup instanceof NioEventLoopGroup) {
            //默认值为 50，即 I/O 操作和用户自定义任务的执行时间比为 1：1。
            ((NioEventLoopGroup) workerGroup).setIoRatio(ConfigManager.netty_io_ratio());
        } else if (workerGroup instanceof EpollEventLoopGroup) {
            ((EpollEventLoopGroup) workerGroup).setIoRatio(ConfigManager.netty_io_ratio());
        }
    }

    /**
     * boss event loop group, boss group should not be daemon, need shutdown manually
     */
    private final EventLoopGroup bossGroup = NettyEventLoopUtil
            .newEventLoopGroup(1, new NamedThreadFactory("Rpc-netty-server-boss", false));
    /**
     * rpc remoting
     */
    protected RpcRemoting rpcRemoting;
    /**
     * server bootstrap
     */
    private ServerBootstrap bootstrap;
    /**
     * channelFuture
     */
    private ChannelFuture channelFuture;
    /**
     * connection event handler
     */
    private ConnectionEventHandler connectionEventHandler;
    /**
     * connection event listener
     */
    private ConnectionEventListener connectionEventListener = new ConnectionEventListener();
    /**
     * user processors of rpc server
     */
    private ConcurrentHashMap<String, UserProcessor<?>> userProcessors = new ConcurrentHashMap<String, UserProcessor<?>>(
            4);
    /**
     * address parser to get custom args
     */
    private RemotingAddressParser addressParser;
    /**
     * connection manager
     */
    private DefaultServerConnectionManager connectionManager;
    /**
     * rpc codec
     */
    private Codec codec = new RpcCodec();

    /**
     * Construct a rpc server. <br>
     * <p>
     * Note:<br> You can only use invoke methods with params {@link Connection}, for example {@link
     * #invokeSync(Connection, Object, int)} <br> Otherwise {@link UnsupportedOperationException} will be thrown.
     */
    public RpcServer(int port) {
        this(port, false);
    }

    /**
     * Construct a rpc server. <br>
     * <p>
     * Note:<br> You can only use invoke methods with params {@link Connection}, for example {@link
     * #invokeSync(Connection, Object, int)} <br> Otherwise {@link UnsupportedOperationException} will be thrown.
     */
    public RpcServer(String ip, int port) {
        this(ip, port, false);
    }

    /**
     * Construct a rpc server. <br>
     *
     * <ul>
     * <li>You can enable connection management feature by specify @param manageConnection true.</li>
     * <ul>
     * <li>When connection management feature enabled, you can use all invoke methods with params {@link String}, {@link
     * Url}, {@link Connection} methods.</li>
     * <li>When connection management feature disabled, you can only use invoke methods with params {@link Connection},
     * otherwise {@link UnsupportedOperationException} will be thrown.</li>
     * </ul>
     * </ul>
     *
     * @param port             listened port
     * @param manageConnection true to enable connection management feature
     */
    public RpcServer(int port, boolean manageConnection) {
        super(port);
        if (manageConnection) {
            this.switches().turnOn(GlobalSwitch.SERVER_MANAGE_CONNECTION_SWITCH);
        }
    }

    /**
     * Construct a rpc server. <br>
     *
     * <ul>
     * <li>You can enable connection management feature by specify @param manageConnection true.</li>
     * <ul>
     * <li>When connection management feature enabled, you can use all invoke methods with params {@link String}, {@link
     * Url}, {@link Connection} methods.</li>
     * <li>When connection management feature disabled, you can only use invoke methods with params {@link Connection},
     * otherwise {@link UnsupportedOperationException} will be thrown.</li>
     * </ul>
     * </ul>
     *
     * @param port             listened port
     * @param manageConnection true to enable connection management feature
     */
    public RpcServer(String ip, int port, boolean manageConnection) {
        super(ip, port);
        /* server connection management feature enabled or not, default value false, means disabled. */
        if (manageConnection) {
            this.switches().turnOn(GlobalSwitch.SERVER_MANAGE_CONNECTION_SWITCH);
        }
    }

    /**
     * Construct a rpc server. <br>
     * <p>
     * You can construct a rpc server with synchronous or asynchronous stop strategy by {@param syncStop}.
     *
     * @param port             listened port
     * @param manageConnection manage connection
     * @param syncStop         true to enable stop in synchronous way
     */
    public RpcServer(int port, boolean manageConnection, boolean syncStop) {
        this(port, manageConnection);
        if (syncStop) {
            this.switches().turnOn(GlobalSwitch.SERVER_SYNC_STOP);
        }
    }

    @Override
    protected void doInit() {
        if (this.addressParser == null) {
            this.addressParser = new RpcAddressParser();
        }
        if (this.switches().isOn(GlobalSwitch.SERVER_MANAGE_CONNECTION_SWITCH)) {
            // in server side, do not care the connection service state, so use null instead of global switch
            //在服务器端，不关心连接服务状态，所以使用null而不是全局开关 随机选择策略RandomSelectStrategy
            ConnectionSelectStrategy connectionSelectStrategy = new RandomSelectStrategy(null);
            this.connectionManager = new DefaultServerConnectionManager(connectionSelectStrategy);
            this.connectionManager.startup();
            this.connectionEventHandler = new RpcConnectionEventHandler(switches());
            this.connectionEventHandler.setConnectionManager(this.connectionManager);
            this.connectionEventHandler.setConnectionEventListener(this.connectionEventListener);
        } else {
            this.connectionEventHandler = new ConnectionEventHandler(switches());
            this.connectionEventHandler.setConnectionEventListener(this.connectionEventListener);
        }
        //初始化了协议配置
        initRpcRemoting();
        this.bootstrap = new ServerBootstrap();
        this.bootstrap.group(bossGroup, workerGroup)
                .channel(NettyEventLoopUtil.getServerSocketChannelClass())
                /**
                 * ChannelOption.SO_BACKLOG对应的是tcp/ip协议listen函数中的backlog参数，函数listen(int socketfd,int backlog)
                 * 用来初始化服务端可连接队列，服务端处理客户端连接请求是顺序处理的，所以同一时间只能处理一个客户端连接，多个客户端来的时候，
                 * 服务端将不能处理的客户端连接请求放在队列中等待处理，backlog参数指定了队列的大小
                 */
                .option(ChannelOption.SO_BACKLOG, ConfigManager.tcp_so_backlog())
                /**
                 * ChanneOption.SO_REUSEADDR对应于套接字选项中的SO_REUSEADDR，这个参数表示允许重复使用本地地址和端口， 比如，某个服务器进程占用了TCP的80端口进行监听，
                 * 此时再次监听该端口就会返回错误，使用该参数就可以解决问题，该参数允许共用该端口，这个在服务器程序中比较常使用， 比如某个进程非正常退出
                 * 该程序占用的端口可能要被占用一段时间才能允许其他进程使用，
                 * 而且程序死掉以后，内核一需要一定的时间才能够释放此端口，不设置SO_REUSEADDR就无法正常使用该端口。
                 */
                .option(ChannelOption.SO_REUSEADDR, ConfigManager.tcp_so_reuseaddr())
                /**
                 *   ChannelOption.TCP_NODELAY参数对应于套接字选项中的TCP_NODELAY,该参数的使用与Nagle算法有关,Nagle算法是将小的数据包组装为更大的帧然后进行发送，
                 *   而不是输入一次发送一次,因此在数据包不足的时候会等待其他数据的到了，组装成大的数据包进行发送，虽然该方式有效提高网络的有效负载，但是却造成了延时，
                 *   而该参数的作用就是禁止使用Nagle算法，使用于小数据即时传输，于TCP_NODELAY相对应的是TCP_CORK，该选项是需要等到发送的数据量最大的时候，一次性发送数据，适用于文件传输。
                 */
                .childOption(ChannelOption.TCP_NODELAY, ConfigManager.tcp_nodelay())
                /**
                 * Channeloption.SO_KEEPALIVE参数对应于套接字选项中的SO_KEEPALIVE，该参数用于设置TCP连接，当设置该选项以后，连接会测试链接的状态，这个选项用于可能长时间没有数据交流的连接。
                 * 当设置该选项以后，如果在两小时内没有数据的通信时，TCP会自动发送一个活动探测数据报文。
                 */
                .childOption(ChannelOption.SO_KEEPALIVE, ConfigManager.tcp_so_keepalive());

        // set write buffer water mark
        //这里就是设置 write的buffer的大小
        initWriteBufferWaterMark();

        // init byte buf allocator
        //初始化字节buf分配器 判断是否使用池化
        if (ConfigManager.netty_buffer_pooled()) {
            this.bootstrap.option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                    .childOption(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT);
        } else {
            this.bootstrap.option(ChannelOption.ALLOCATOR, UnpooledByteBufAllocator.DEFAULT)
                    .childOption(ChannelOption.ALLOCATOR, UnpooledByteBufAllocator.DEFAULT);
        }
        // enable trigger mode for epoll if need
        //如果是epoll,这里需要一个什么触发器
        NettyEventLoopUtil.enableTriggeredMode(bootstrap);
        //心跳检测开关
        final boolean idleSwitch = ConfigManager.tcp_idle_switch();
        //服务端心跳检测时间
        final int idleTime = ConfigManager.tcp_server_idle();
        //这里就是没有收到心疼触发的逻辑userEventTriggered
        final ChannelHandler serverIdleHandler = new ServerIdleHandler();
        //这里自定义了一个handler 注意这个rpcHandler
        final RpcHandler rpcHandler = new RpcHandler(true, this.userProcessors);
        this.bootstrap.childHandler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel channel) {
                System.out.println(Thread.currentThread().getName() + "server initChannel");
                ChannelPipeline pipeline = channel.pipeline();
                pipeline.addLast("decoder", codec.newDecoder());
                pipeline.addLast("encoder", codec.newEncoder());
                if (idleSwitch) {
                    pipeline.addLast("idleStateHandler", new IdleStateHandler(0, 0, idleTime,
                            TimeUnit.MILLISECONDS));
                    pipeline.addLast("serverIdleHandler", serverIdleHandler);
                }
                pipeline.addLast("connectionEventHandler", connectionEventHandler);
                pipeline.addLast("handler", rpcHandler);
                createConnection(channel);
            }

            /**
             * create connection operation<br>
             * <ul>
             * <li>If flag manageConnection be true, use {@link DefaultConnectionManager} to add a new connection, meanwhile bind it with the channel.</li>
             * <li>If flag manageConnection be false, just create a new connection and bind it with the channel.</li>
             * </ul>
             */
            private void createConnection(SocketChannel channel) {
                //RemotingUtil.parseRemoteAddress(channel) 解析出来就是 127.0.0.1:8080
                //parse 就是根据ip port 组装了参数
                Url url = addressParser.parse(RemotingUtil.parseRemoteAddress(channel));
                if (switches().isOn(GlobalSwitch.SERVER_MANAGE_CONNECTION_SWITCH)) {
                    connectionManager.add(new Connection(channel, url), url.getUniqueKey());
                } else {
                    new Connection(channel, url);
                }
                //这里就是去触发一下所有handler的fireUserEventTriggered 方法
                channel.pipeline().fireUserEventTriggered(ConnectionEventType.CONNECT);
            }
        });
    }

    @Override
    protected boolean doStart() throws InterruptedException {
        this.channelFuture = this.bootstrap.bind(new InetSocketAddress(ip(), port())).sync();
        return this.channelFuture.isSuccess();
    }

    /**
     * Notice: only {@link GlobalSwitch#SERVER_MANAGE_CONNECTION_SWITCH} switch on, will close all connections.
     *
     * @see AbstractRemotingServer#doStop()
     */
    @Override
    protected boolean doStop() {
        if (null != this.channelFuture) {
            this.channelFuture.channel().close();
        }
        if (this.switches().isOn(GlobalSwitch.SERVER_SYNC_STOP)) {
            this.bossGroup.shutdownGracefully().awaitUninterruptibly();
        } else {
            this.bossGroup.shutdownGracefully();
        }
        if (this.switches().isOn(GlobalSwitch.SERVER_MANAGE_CONNECTION_SWITCH)
                && null != this.connectionManager) {
            this.connectionManager.shutdown();
            logger.warn("Close all connections from server side!");
        }
        logger.warn("Rpc Server stopped!");
        return true;
    }

    /**
     * init rpc remoting
     */
    protected void initRpcRemoting() {
        this.rpcRemoting = new RpcServerRemoting(new RpcCommandFactory(), this.addressParser, this.connectionManager);
    }

    /**
     * @see RemotingServer#registerProcessor(byte, com.alipay.remoting.CommandCode, com.alipay.remoting.RemotingProcessor)
     */
    @Override
    public void registerProcessor(byte protocolCode, CommandCode cmd, RemotingProcessor<?> processor) {
        ProtocolManager.getProtocol(ProtocolCode.fromBytes(protocolCode)).getCommandHandler()
                .registerProcessor(cmd, processor);
    }

    /**
     * @see RemotingServer#registerDefaultExecutor(byte, ExecutorService)
     */
    @Override
    public void registerDefaultExecutor(byte protocolCode, ExecutorService executor) {
        ProtocolManager.getProtocol(ProtocolCode.fromBytes(protocolCode)).getCommandHandler()
                .registerDefaultExecutor(executor);
    }

    /**
     * Add processor to process connection event.
     *
     * @param type      connection event type
     * @param processor connection event processor
     */
    public void addConnectionEventProcessor(ConnectionEventType type,
                                            ConnectionEventProcessor processor) {
        this.connectionEventListener.addConnectionEventProcessor(type, processor);
    }

    /**
     * Use UserProcessorRegisterHelper{@link UserProcessorRegisterHelper} to help register user processor for server
     * side.
     *
     * @see AbstractRemotingServer#registerUserProcessor(com.alipay.remoting.rpc.protocol.UserProcessor)
     */
    @Override
    public void registerUserProcessor(UserProcessor<?> processor) {
        UserProcessorRegisterHelper.registerUserProcessor(processor, this.userProcessors);
    }

    /**
     * One way invocation using a string address, address format example - 127.0.0.1:12200?key1=value1&key2=value2 <br>
     * <p>
     * Notice:<br>
     * <ol>
     * <li><b>DO NOT modify the request object concurrently when this method is called.</b></li>
     * <li>When do invocation, use the string address to find a available client connection, if none then throw
     * exception</li>
     * <li>Unlike rpc client, address arguments takes no effect here, for rpc server will not create connection.</li>
     * </ol>
     */
    public void oneway(final String addr, final Object request) throws RemotingException,
            InterruptedException {
        check();
        this.rpcRemoting.oneway(addr, request, null);
    }

    /**
     * One way invocation with a {@link InvokeContext}, common api notice please see {@link #oneway(String, Object)}
     */
    public void oneway(final String addr, final Object request, final InvokeContext invokeContext)
            throws RemotingException,
            InterruptedException {
        check();
        this.rpcRemoting.oneway(addr, request, invokeContext);
    }

    /**
     * One way invocation using a parsed {@link Url} <br>
     * <p>
     * Notice:<br>
     * <ol>
     * <li><b>DO NOT modify the request object concurrently when this method is called.</b></li>
     * <li>When do invocation, use the parsed {@link Url} to find a available client connection, if none then throw
     * exception</li>
     * </ol>
     */
    public void oneway(final Url url, final Object request) throws RemotingException,
            InterruptedException {
        check();
        this.rpcRemoting.oneway(url, request, null);
    }

    /**
     * One way invocation with a {@link InvokeContext}, common api notice please see {@link #oneway(Url, Object)}
     */
    public void oneway(final Url url, final Object request, final InvokeContext invokeContext)
            throws RemotingException,
            InterruptedException {
        check();
        this.rpcRemoting.oneway(url, request, invokeContext);
    }

    /**
     * One way invocation using a {@link Connection} <br>
     * <p>
     * Notice:<br>
     * <b>DO NOT modify the request object concurrently when this method is called.</b>
     */
    public void oneway(final Connection conn, final Object request) throws RemotingException {
        this.rpcRemoting.oneway(conn, request, null);
    }

    /**
     * One way invocation with a {@link InvokeContext}, common api notice please see {@link #oneway(Connection,
     * Object)}
     */
    public void oneway(final Connection conn, final Object request,
                       final InvokeContext invokeContext) throws RemotingException {
        this.rpcRemoting.oneway(conn, request, invokeContext);
    }

    /**
     * Synchronous invocation using a string address, address format example - 127.0.0.1:12200?key1=value1&key2=value2
     * <br>
     * <p>
     * Notice:<br>
     * <ol>
     * <li><b>DO NOT modify the request object concurrently when this method is called.</b></li>
     * <li>When do invocation, use the string address to find a available client connection, if none then throw
     * exception</li>
     * <li>Unlike rpc client, address arguments takes no effect here, for rpc server will not create connection.</li>
     * </ol>
     *
     * @return Object
     */
    public Object invokeSync(final String addr, final Object request, final int timeoutMillis)
            throws RemotingException,
            InterruptedException {
        check();
        return this.rpcRemoting.invokeSync(addr, request, null, timeoutMillis);
    }

    /**
     * Synchronous invocation with a {@link InvokeContext}, common api notice please see {@link #invokeSync(String,
     * Object, int)}
     */
    public Object invokeSync(final String addr, final Object request,
                             final InvokeContext invokeContext, final int timeoutMillis)
            throws RemotingException,
            InterruptedException {
        check();
        return this.rpcRemoting.invokeSync(addr, request, invokeContext, timeoutMillis);
    }

    /**
     * Synchronous invocation using a parsed {@link Url} <br>
     * <p>
     * Notice:<br>
     * <ol>
     * <li><b>DO NOT modify the request object concurrently when this method is called.</b></li>
     * <li>When do invocation, use the parsed {@link Url} to find a available client connection, if none then throw
     * exception</li>
     * </ol>
     *
     * @return Object
     */
    public Object invokeSync(Url url, Object request, int timeoutMillis) throws RemotingException,
            InterruptedException {
        check();
        return this.rpcRemoting.invokeSync(url, request, null, timeoutMillis);
    }

    /**
     * Synchronous invocation with a {@link InvokeContext}, common api notice please see {@link #invokeSync(Url, Object,
     * int)}
     */
    public Object invokeSync(final Url url, final Object request,
                             final InvokeContext invokeContext, final int timeoutMillis)
            throws RemotingException,
            InterruptedException {
        check();
        return this.rpcRemoting.invokeSync(url, request, invokeContext, timeoutMillis);
    }

    /**
     * Synchronous invocation using a {@link Connection} <br>
     * <p>
     * Notice:<br>
     * <b>DO NOT modify the request object concurrently when this method is called.</b>
     *
     * @return Object
     */
    public Object invokeSync(final Connection conn, final Object request, final int timeoutMillis)
            throws RemotingException,
            InterruptedException {
        return this.rpcRemoting.invokeSync(conn, request, null, timeoutMillis);
    }

    /**
     * Synchronous invocation with a {@link InvokeContext}, common api notice please see {@link #invokeSync(Connection,
     * Object, int)}
     */
    public Object invokeSync(final Connection conn, final Object request,
                             final InvokeContext invokeContext, final int timeoutMillis)
            throws RemotingException,
            InterruptedException {
        return this.rpcRemoting.invokeSync(conn, request, invokeContext, timeoutMillis);
    }

    /**
     * Future invocation using a string address, address format example - 127.0.0.1:12200?key1=value1&key2=value2 <br>
     * You can get result use the returned {@link RpcResponseFuture}.
     * <p>
     * Notice:<br>
     * <ol>
     * <li><b>DO NOT modify the request object concurrently when this method is called.</b></li>
     * <li>When do invocation, use the string address to find a available client connection, if none then throw
     * exception</li>
     * <li>Unlike rpc client, address arguments takes no effect here, for rpc server will not create connection.</li>
     * </ol>
     *
     * @return RpcResponseFuture
     */
    public RpcResponseFuture invokeWithFuture(final String addr, final Object request,
                                              final int timeoutMillis) throws RemotingException,
            InterruptedException {
        check();
        return this.rpcRemoting.invokeWithFuture(addr, request, null, timeoutMillis);
    }

    /**
     * Future invocation with a {@link InvokeContext}, common api notice please see {@link #invokeWithFuture(String,
     * Object, int)}
     */
    public RpcResponseFuture invokeWithFuture(final String addr, final Object request,
                                              final InvokeContext invokeContext,
                                              final int timeoutMillis) throws RemotingException,
            InterruptedException {
        check();
        return this.rpcRemoting.invokeWithFuture(addr, request, invokeContext, timeoutMillis);
    }

    /**
     * Future invocation using a parsed {@link Url} <br> You can get result use the returned {@link RpcResponseFuture}.
     * <p>
     * Notice:<br>
     * <ol>
     * <li><b>DO NOT modify the request object concurrently when this method is called.</b></li>
     * <li>When do invocation, use the parsed {@link Url} to find a available client connection, if none then throw
     * exception</li>
     * </ol>
     *
     * @return RpcResponseFuture
     */
    public RpcResponseFuture invokeWithFuture(final Url url, final Object request,
                                              final int timeoutMillis) throws RemotingException,
            InterruptedException {
        check();
        return this.rpcRemoting.invokeWithFuture(url, request, null, timeoutMillis);
    }

    /**
     * Future invocation with a {@link InvokeContext}, common api notice please see {@link #invokeWithFuture(Url,
     * Object, int)}
     */
    public RpcResponseFuture invokeWithFuture(final Url url, final Object request,
                                              final InvokeContext invokeContext,
                                              final int timeoutMillis) throws RemotingException,
            InterruptedException {
        check();
        return this.rpcRemoting.invokeWithFuture(url, request, invokeContext, timeoutMillis);
    }

    /**
     * Future invocation using a {@link Connection} <br> You can get result use the returned {@link RpcResponseFuture}.
     * <p>
     * Notice:<br>
     * <b>DO NOT modify the request object concurrently when this method is called.</b>
     */
    public RpcResponseFuture invokeWithFuture(final Connection conn, final Object request,
                                              final int timeoutMillis) throws RemotingException {

        return this.rpcRemoting.invokeWithFuture(conn, request, null, timeoutMillis);
    }

    /**
     * Future invocation with a {@link InvokeContext}, common api notice please see {@link #invokeWithFuture(Connection,
     * Object, int)}
     */
    public RpcResponseFuture invokeWithFuture(final Connection conn, final Object request,
                                              final InvokeContext invokeContext,
                                              final int timeoutMillis) throws RemotingException {

        return this.rpcRemoting.invokeWithFuture(conn, request, invokeContext, timeoutMillis);
    }

    /**
     * Callback invocation using a string address, address format example - 127.0.0.1:12200?key1=value1&key2=value2 <br>
     * You can specify an implementation of {@link InvokeCallback} to get the result.
     * <p>
     * Notice:<br>
     * <ol>
     * <li><b>DO NOT modify the request object concurrently when this method is called.</b></li>
     * <li>When do invocation, use the string address to find a available client connection, if none then throw
     * exception</li>
     * <li>Unlike rpc client, address arguments takes no effect here, for rpc server will not create connection.</li>
     * </ol>
     */
    public void invokeWithCallback(final String addr, final Object request,
                                   final InvokeCallback invokeCallback, final int timeoutMillis)
            throws RemotingException,
            InterruptedException {
        check();
        this.rpcRemoting.invokeWithCallback(addr, request, null, invokeCallback, timeoutMillis);
    }

    /**
     * Callback invocation with a {@link InvokeContext}, common api notice please see {@link #invokeWithCallback(String,
     * Object, InvokeCallback, int)}
     */
    public void invokeWithCallback(final String addr, final Object request,
                                   final InvokeContext invokeContext,
                                   final InvokeCallback invokeCallback, final int timeoutMillis)
            throws RemotingException,
            InterruptedException {
        check();
        this.rpcRemoting.invokeWithCallback(addr, request, invokeContext, invokeCallback,
                timeoutMillis);
    }

    /**
     * Callback invocation using a parsed {@link Url} <br> You can specify an implementation of {@link InvokeCallback}
     * to get the result.
     * <p>
     * Notice:<br>
     * <ol>
     * <li><b>DO NOT modify the request object concurrently when this method is called.</b></li>
     * <li>When do invocation, use the parsed {@link Url} to find a available client connection, if none then throw
     * exception</li>
     * </ol>
     */
    public void invokeWithCallback(final Url url, final Object request,
                                   final InvokeCallback invokeCallback, final int timeoutMillis)
            throws RemotingException,
            InterruptedException {
        check();
        this.rpcRemoting.invokeWithCallback(url, request, null, invokeCallback, timeoutMillis);
    }

    /**
     * Callback invocation with a {@link InvokeContext}, common api notice please see {@link #invokeWithCallback(Url,
     * Object, InvokeCallback, int)}
     */
    public void invokeWithCallback(final Url url, final Object request,
                                   final InvokeContext invokeContext,
                                   final InvokeCallback invokeCallback, final int timeoutMillis)
            throws RemotingException,
            InterruptedException {
        check();
        this.rpcRemoting.invokeWithCallback(url, request, invokeContext, invokeCallback,
                timeoutMillis);
    }

    /**
     * Callback invocation using a {@link Connection} <br> You can specify an implementation of {@link InvokeCallback}
     * to get the result.
     * <p>
     * Notice:<br>
     * <b>DO NOT modify the request object concurrently when this method is called.</b>
     */
    public void invokeWithCallback(final Connection conn, final Object request,
                                   final InvokeCallback invokeCallback, final int timeoutMillis)
            throws RemotingException {
        this.rpcRemoting.invokeWithCallback(conn, request, null, invokeCallback, timeoutMillis);
    }

    /**
     * Callback invocation with a {@link InvokeContext}, common api notice please see {@link
     * #invokeWithCallback(Connection, Object, InvokeCallback, int)}
     */
    public void invokeWithCallback(final Connection conn, final Object request,
                                   final InvokeContext invokeContext,
                                   final InvokeCallback invokeCallback, final int timeoutMillis)
            throws RemotingException {
        this.rpcRemoting.invokeWithCallback(conn, request, invokeContext, invokeCallback,
                timeoutMillis);
    }

    /**
     * check whether a client address connected
     */
    public boolean isConnected(String remoteAddr) {
        Url url = this.rpcRemoting.addressParser.parse(remoteAddr);
        return this.isConnected(url);
    }

    /**
     * check whether a {@link Url} connected
     */
    public boolean isConnected(Url url) {
        Connection conn = this.rpcRemoting.connectionManager.get(url.getUniqueKey());
        if (null != conn) {
            return conn.isFine();
        }
        return false;
    }

    /**
     * check whether connection manage feature enabled
     */
    private void check() {
        if (!this.switches().isOn(GlobalSwitch.SERVER_MANAGE_CONNECTION_SWITCH)) {
            throw new UnsupportedOperationException(
                    "Please enable connection manage feature of Rpc Server before call this method! See comments in constructor RpcServer(int port, boolean manageConnection) to find how to enable!");
        }
    }

    /**
     * init netty write buffer water mark
     */
    private void initWriteBufferWaterMark() {
        int lowWaterMark = this.netty_buffer_low_watermark();
        int highWaterMark = this.netty_buffer_high_watermark();
        if (lowWaterMark > highWaterMark) {
            throw new IllegalArgumentException(
                    String.format("[server side] bolt netty high water mark {%s} should not be smaller than low water mark {%s} bytes)",
                            highWaterMark, lowWaterMark));
        } else {
            logger.warn("[server side] bolt netty low water mark is {} bytes, high water mark is {} bytes",
                    lowWaterMark, highWaterMark);
        }
        this.bootstrap.childOption(ChannelOption.WRITE_BUFFER_WATER_MARK, new WriteBufferWaterMark(
                lowWaterMark, highWaterMark));
    }

    // ~~~ getter and setter

    /**
     * Getter method for property <tt>addressParser</tt>.
     *
     * @return property value of addressParser
     */
    public RemotingAddressParser getAddressParser() {
        return this.addressParser;
    }

    /**
     * Setter method for property <tt>addressParser</tt>.
     *
     * @param addressParser value to be assigned to property addressParser
     */
    public void setAddressParser(RemotingAddressParser addressParser) {
        this.addressParser = addressParser;
    }

    /**
     * Getter method for property <tt>connectionManager</tt>.
     *
     * @return property value of connectionManager
     */
    public DefaultConnectionManager getConnectionManager() {
        return connectionManager;
    }
}
