package com.lagou.nettyrpc.server;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import org.springframework.beans.factory.annotation.Autowired;

import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * \* User: ZhuFangTao
 * \* Date: 2020/6/17 10:43 上午
 * \
 */
public class CustomRpcHandler extends ChannelInboundHandlerAdapter {

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        ctx.writeAndFlush("你好" + ((String) msg).toUpperCase()
                + " 当前为您服务的地址为：" + NettyRpcStater.getHostName());
    }

}