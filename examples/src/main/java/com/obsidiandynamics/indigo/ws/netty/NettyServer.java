package com.obsidiandynamics.indigo.ws.netty;

import io.netty.bootstrap.*;
import io.netty.channel.*;
import io.netty.channel.nio.*;
import io.netty.channel.socket.nio.*;
import io.netty.handler.logging.*;

public final class NettyServer implements AutoCloseable {
  private final EventLoopGroup bossGroup;
  private final EventLoopGroup workerGroup;
  
  private final Channel channel;
  
  public NettyServer(int port, String contextPath, NettyEndpointManager manager) throws InterruptedException {
    bossGroup = new NioEventLoopGroup(1);
    workerGroup = new NioEventLoopGroup();
    
    final ServerBootstrap b = new ServerBootstrap();
    b.group(bossGroup, workerGroup)
    .channel(NioServerSocketChannel.class)
    .handler(new LoggingHandler(LogLevel.INFO))
    .childHandler(new WebSocketServerInitializer(manager, contextPath, null));

    channel = b.bind(port).sync().channel();
  }
  
  public void awaitTermination() throws InterruptedException {
    channel.closeFuture().sync();
  }
  
  @Override
  public void close() throws Exception {
    bossGroup.shutdownGracefully();
    workerGroup.shutdownGracefully();
  }
}