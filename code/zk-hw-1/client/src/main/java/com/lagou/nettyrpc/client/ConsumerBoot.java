package com.lagou.nettyrpc.client;


import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.net.InetSocketAddress;

@SpringBootApplication
public class ConsumerBoot {

    public static void main(String[] args) throws Exception {
        SpringApplication.run(ConsumerBoot.class, args);
        int times = 0;
        RPCConsumer.connect();
        while (true) {
            sendMsg(chooseServer(), ++times + "");
            Thread.sleep(2000);
            DbPoolConnectUtil.queryForData();
        }
    }

    private static ChannelFuture chooseServer() throws Exception {
        String targetKey = null;
        Long shortResponseTime = null;
        for (String server : RPCConsumer.futureMap.keySet()) {
            long serverResponseTime = new Long(new String(RPCConsumer.client.getData().forPath("/server/" + server)));
            if (shortResponseTime == null || serverResponseTime < shortResponseTime) {
                shortResponseTime = serverResponseTime;
                targetKey = server;
            }
        }
        return targetKey == null ? null : RPCConsumer.futureMap.get(targetKey);
    }


    public static void sendMsg(ChannelFuture future, String msg) throws Exception {
        if (future != null && future.channel().isActive()) {
            RPCConsumer.lastInvokeTimeMil = System.currentTimeMillis();
            Channel channel = future.channel();
            InetSocketAddress ipSocket = (InetSocketAddress) channel.remoteAddress();
            String host = ipSocket.getHostString();
            int port = ipSocket.getPort();
            System.out.println("<<<<<<<<<<向设备" + host + ":" + port + "发送数据>>>>>>>>>>");
            byte[] msgBytes = msg.getBytes();
            ByteBuf buf = Unpooled.buffer();
            buf.writeBytes(msgBytes);
            future.channel().writeAndFlush(buf).sync();
        } else {
            System.out.println(">>>>>>>连接失败,取消发送");
        }
    }

}
