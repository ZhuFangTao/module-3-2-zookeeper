package com.lagou.nettyrpc.server;

import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelPipeline;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.string.StringDecoder;
import io.netty.handler.codec.string.StringEncoder;
import org.apache.curator.framework.CuratorFramework;
import org.apache.curator.framework.CuratorFrameworkFactory;
import org.apache.curator.retry.ExponentialBackoffRetry;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.data.Stat;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Enumeration;
import java.util.List;

/**
 * \* User: ZhuFangTao
 * \* Date: 2020/6/17 11:22 上午
 * \
 */

@Component
public class NettyRpcStater {


    private static final int PORT = 9999;

    public static CuratorFramework zkClient;

    //创建一个方法启动服务器
    @PostConstruct
    public void startServer() throws Exception {
        NioEventLoopGroup bossGroup = new NioEventLoopGroup();
        NioEventLoopGroup workGroup = new NioEventLoopGroup();
        ServerBootstrap serverBootstrap = new ServerBootstrap();
        serverBootstrap.group(bossGroup, workGroup)
                .channel(NioServerSocketChannel.class)
                .childHandler(new ChannelInitializer<NioSocketChannel>() {
                    protected void initChannel(NioSocketChannel nioSocketChannel) {
                        ChannelPipeline pipeline = nioSocketChannel.pipeline();
                        pipeline.addLast(new StringDecoder());
                        pipeline.addLast(new StringEncoder());
                        pipeline.addLast(new CustomRpcHandler());
                    }
                });

        serverBootstrap.bind(PORT).sync();
        registerInZooKeeper();
        System.out.println("Server one ready to access invoke at port:" + PORT);
        //新建一个线程处理响应的有效时间，如果超过5s则响应时间清0。防止某一次服务器响应慢导致一直不请求该服务器
        new Thread(() -> {
            while (true) {
                Stat stat = new Stat();
                try {
                    zkClient.getData().storingStatIn(stat).forPath("/server/" + getHostName());
                    if (System.currentTimeMillis() - stat.getMtime() > 5 * 1000) {
                        zkClient.setData().forPath("/server/" + getHostName(), "0".getBytes());
                        System.out.println("超时重置服务器响应时间" + getHostName());
                    }
                    Thread.sleep(1 * 1000);
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }).start();
    }

    public void registerInZooKeeper() throws Exception {
        zkClient = CuratorFrameworkFactory.builder()
                .connectString("127.0.0.1:2181")
                .sessionTimeoutMs(5000)
                .connectionTimeoutMs(3000)
                .retryPolicy(new ExponentialBackoffRetry(1000, 3))
                .build();

        zkClient.start();
        System.out.println("zookeeper服务连接成功");
        //创建临时路径。/server/host:port;
        zkClient.create().creatingParentContainersIfNeeded().withMode(CreateMode.EPHEMERAL).forPath("/server/" + getHostName(), "0".getBytes());
        List<String> serverList = zkClient.getChildren().forPath("/server");
        System.out.println("当前在线服务器：" + serverList);
    }


    public static String getHostName() {
        String hostIP = null;
        try {
            Enumeration<NetworkInterface> networkInterfaces = NetworkInterface.getNetworkInterfaces();
            while (networkInterfaces.hasMoreElements()) {
                NetworkInterface ni = networkInterfaces.nextElement();
                Enumeration<InetAddress> nias = ni.getInetAddresses();
                while (nias.hasMoreElements()) {
                    InetAddress ia = nias.nextElement();
                    if (!ia.isLinkLocalAddress() && !ia.isLoopbackAddress() && ia instanceof Inet4Address) {
                        hostIP = ia.getHostAddress();
                    }
                }
            }
        } catch (SocketException e) {
            e.printStackTrace();
        }
        return hostIP + ":" + PORT;
    }
}