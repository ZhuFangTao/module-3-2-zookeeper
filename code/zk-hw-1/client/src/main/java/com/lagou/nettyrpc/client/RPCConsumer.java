package com.lagou.nettyrpc.client;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.framework.recipes.cache.PathChildrenCache;
import org.apache.curator.framework.recipes.cache.PathChildrenCacheEvent;
import org.apache.curator.retry.ExponentialBackoffRetry;

import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 消费者
 */
public class RPCConsumer {

    //从zk获取所有存活的服务器地址
    public static CuratorFramework client;

    //存储上一次发送请求的时间，用于计算服务器响应时间 单位毫秒
    public static long lastInvokeTimeMil;

    private static EventLoopGroup group;
    private static Bootstrap bootstrap;

    public static Map<String, ChannelFuture> futureMap = new HashMap<>();

    public static void connect() throws Exception {
        //创建zookeeper连接并添加watch
        if (client == null) {
            connectZK();
            //作业三 初始化数据库连接
            DbPoolConnectUtil.initConnectWithZK(client);
        }
        //创建netty
        if (bootstrap == null) {
            getBootstrap();
        }

        //客户端启动时主动拉一次zk中服务器信息
        List<String> serverStrList = client.getChildren().forPath("/server");
        for (String serverStr : serverStrList) {
            if (serverStr == null || serverStr.isEmpty()) {
                continue;
            }
            try {
                addNewChannelFuture(serverStr);
            } catch (Exception e) {
                continue;
            }
        }
    }

    private static void connectZK() throws Exception {
        client = CuratorFrameworkFactory.builder()
                .connectString("127.0.0.1:2181")
                .sessionTimeoutMs(5000)
                .connectionTimeoutMs(3000)
                .retryPolicy(new ExponentialBackoffRetry(1000, 3))
                .build();
        client.start();
        PathChildrenCache pathChildrenCache = new PathChildrenCache(client, "/server", true);
        //调用start方法开始监听 ，设置启动模式为同步加载节点数据
        pathChildrenCache.start(PathChildrenCache.StartMode.BUILD_INITIAL_CACHE);
        //添加监听器
        pathChildrenCache.getListenable().addListener((client, event) -> {
            if (event.getType() == PathChildrenCacheEvent.Type.CHILD_REMOVED) {
                String serverAddress = event.getData().getPath().substring(event.getData().getPath().lastIndexOf("/") + 1);
                //当客户端下线，从本地连接中移除
                futureMap.remove(serverAddress);
            } else if (event.getType() == PathChildrenCacheEvent.Type.CHILD_ADDED) {
                String serverAddress = event.getData().getPath().substring(event.getData().getPath().lastIndexOf("/") + 1);
                addNewChannelFuture(serverAddress);
            }
        });
    }

    private static void addNewChannelFuture(String serverAddress) {
        //当有新的客户端上线连接并添加
        bootstrap.remoteAddress(serverAddress.split(":")[0], Integer.parseInt(serverAddress.split(":")[1]));
        ChannelFuture connect = bootstrap.connect();
        futureMap.put(serverAddress, connect);
        System.out.println("连接到服务器：" + serverAddress);
    }

    private static void getBootstrap() {
        group = new NioEventLoopGroup();
        bootstrap = new Bootstrap();
        bootstrap.group(group)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.TCP_NODELAY, true)
                .handler(new ChannelInitializer<SocketChannel>() {
                    protected void initChannel(SocketChannel socketChannel) throws Exception {
                        ChannelPipeline pipeline = socketChannel.pipeline();
                        pipeline.addLast(new StringEncoder());
                        pipeline.addLast(new StringDecoder());
                        pipeline.addLast(new ChannelInboundHandlerAdapter() {
                            @Override
                            public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
                                System.out.println("返回信息<<<<<<<<<<" + msg + ">>>>>>>>>>");
                                InetSocketAddress ipSocket = (InetSocketAddress) ctx.channel().remoteAddress();
                                String serverAddress = ipSocket.getAddress() + ":" + ipSocket.getPort();
                                Long costTime = System.currentTimeMillis() - lastInvokeTimeMil;
                                client.setData().forPath("/server" + serverAddress, costTime.toString().getBytes());
                            }
                        });
                    }
                });
    }

}
