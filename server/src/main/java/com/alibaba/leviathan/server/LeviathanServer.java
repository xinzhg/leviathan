package com.alibaba.leviathan.server;

import java.lang.management.ManagementFactory;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import javax.management.ObjectName;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jboss.netty.bootstrap.ServerBootstrap;
import org.jboss.netty.channel.Channel;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelPipeline;
import org.jboss.netty.channel.ChannelPipelineFactory;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.Channels;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelUpstreamHandler;
import org.jboss.netty.channel.socket.nio.NioServerSocketChannelFactory;

import com.alibaba.leviathan.message.codec.LeviathanDecoder;
import com.alibaba.leviathan.message.codec.LeviathanEncoder;

public class LeviathanServer implements LeviathanServerMBean {

    private static Log                    LOG               = LogFactory.getLog(LeviathanServer.class);

    private ServerBootstrap               bootstrap;
    private ThreadPoolExecutor            bossExecutor;
    private ThreadPoolExecutor            workerExecutor;

    private int                           workerThreadCount = Runtime.getRuntime().availableProcessors();

    private NioServerSocketChannelFactory channelFactory;

    private final AtomicLong              acceptedCount     = new AtomicLong();
    private final AtomicLong              closedCount       = new AtomicLong();
    private final AtomicLong              sessionCount      = new AtomicLong();
    private final AtomicLong              runningMax        = new AtomicLong();

    private LeviathanDecoder              decoder           = new LeviathanDecoder();
    private LeviathanEncoder              encoder           = new LeviathanEncoder();

    private int                           port              = 7002;

    public void start() {
        try {
            String prop = System.getProperty("leviathan.port");
            if (prop != null) {
                port = Integer.parseInt(prop);
            }
        } catch (Exception e) {
            LOG.error("illegal jvm argument leviathan.port", e);
        }

        bossExecutor = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS,
                                              new SynchronousQueue<Runnable>());
        workerExecutor = new ThreadPoolExecutor(0, Integer.MAX_VALUE, 60L, TimeUnit.SECONDS,
                                                new SynchronousQueue<Runnable>());

        channelFactory = new NioServerSocketChannelFactory(bossExecutor, workerExecutor, workerThreadCount);
        bootstrap = new ServerBootstrap(channelFactory);

        bootstrap.setPipelineFactory(new ChannelPipelineFactory() {

            public ChannelPipeline getPipeline() throws Exception {
                return Channels.pipeline(encoder, //
                                         decoder, //
                                         new NettyServerHanlder() //
                );
            }

        });

        SocketAddress address = new InetSocketAddress("0.0.0.0", port);
        bootstrap.bind(address);
        if (LOG.isInfoEnabled()) {
            LOG.info("Leviathan Server listening " + address);
        }

        if (LOG.isInfoEnabled()) {
            LOG.info("Leviathan Server started.");
        }
    }

    public void stop() {
        bootstrap.shutdown();
        if (LOG.isInfoEnabled()) {
            LOG.info("Leviathan Server stoped.");
        }
    }

    public class NettyServerHanlder extends SimpleChannelUpstreamHandler {

        public void channelOpen(ChannelHandlerContext ctx, ChannelStateEvent e) {
            long acceptedCount = LeviathanServer.this.acceptedCount.incrementAndGet();
            incrementSessionCount();

            if (LOG.isDebugEnabled()) {
                Channel channel = ctx.getChannel();
                LOG.debug("accepted " + channel.getRemoteAddress() + " " + acceptedCount);
            }
            ctx.sendUpstream(e);
        }

        public void channelClosed(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
            closedCount.incrementAndGet();
            decrementSessionCount();

            ctx.sendUpstream(e);
        }

        public void channelBound(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
            ctx.sendUpstream(e);
        }

        public void channelUnbound(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
            ctx.sendUpstream(e);
        }

        public void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
            ctx.sendUpstream(e);

            String message = (String) e.getMessage();
            ctx.getChannel().write(message);
        }
    }

    void decrementSessionCount() {
        this.sessionCount.decrementAndGet();
    }

    void incrementSessionCount() {
        long current = this.sessionCount.incrementAndGet();
        for (;;) {
            long max = this.runningMax.get();
            if (current > max) {
                boolean success = this.runningMax.compareAndSet(max, current);
                if (success) {
                    break;
                }
            } else {
                break;
            }
        }
    }

    public long getSessionCount() {
        return sessionCount.get();
    }

    public long getClosedCount() {
        return this.closedCount.get();
    }

    public long getAcceptedCount() {
        return this.acceptedCount.get();
    }

    public long getReceivedBytes() {
        return this.decoder.getRecevedBytes();
    }
    
    public long getReceivedMessageCount() {
        return this.decoder.getReceivedMessageCount();
    }
    
    public long getSentBytes() {
        return this.encoder.getSentBytes();
    }
    
    public long getSentMessageCount() {
        return this.encoder.getSentMessageCount();
    }
    
    public void resetStat() {
        this.encoder.resetStat();
        this.decoder.resetStat();
        
        this.acceptedCount.set(0);
        this.closedCount.set(0);
    }

    public static void main(String args[]) throws Exception {
        LeviathanServer server = new LeviathanServer();
        server.start();

        ManagementFactory.getPlatformMBeanServer() //
        .registerMBean(server, //
                       new ObjectName("com.alibaba.leviathan:type=LeviathanServer") //
        );
    }
}
