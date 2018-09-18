package love.wangqi.filter.command;

import com.netflix.hystrix.HystrixCommand;
import com.netflix.hystrix.HystrixCommandGroupKey;
import com.netflix.hystrix.HystrixCommandKey;
import com.netflix.hystrix.HystrixCommandProperties;
import com.netflix.hystrix.exception.HystrixTimeoutException;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpRequest;
import io.netty.handler.codec.http.multipart.HttpPostRequestEncoder;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import love.wangqi.codec.RequestHolder;
import love.wangqi.context.HttpRequestContext;
import love.wangqi.exception.GatewayTimeoutException;
import love.wangqi.handler.BackendFilter;

import java.net.URL;

/**
 * @author: wangqi
 * @description:
 * @date: Created in 2018/7/27 下午3:10
 */
public class ForwardCommand extends HystrixCommand<Void> {
    private ChannelHandlerContext ctx;
    private RequestHolder requestHolder;
    private HttpRequestContext httpRequestContext = HttpRequestContext.getInstance();
    private final static Logger logger = LoggerFactory.getLogger(ForwardCommand.class);

    public ForwardCommand(ChannelHandlerContext ctx, RequestHolder requestHolder) {
        super(Setter.withGroupKey(HystrixCommandGroupKey.Factory.asKey("ForwardCommandGroup"))
                .andCommandKey(HystrixCommandKey.Factory.asKey(String.valueOf(requestHolder.route.getId())))
                .andCommandPropertiesDefaults(
                        HystrixCommandProperties.Setter()
                                .withExecutionTimeoutInMilliseconds(requestHolder.route.getTimeoutInMilliseconds())
                                .withExecutionIsolationStrategy(HystrixCommandProperties.ExecutionIsolationStrategy.SEMAPHORE)
                                .withFallbackIsolationSemaphoreMaxConcurrentRequests(10000)
                )
        );
        this.ctx = ctx;
        this.requestHolder = requestHolder;
    }


    @Override
    protected Void run() throws Exception {
        forward(ctx, requestHolder.url, requestHolder.request, requestHolder.bodyRequestEncoder);
        return null;
    }

    @Override
    protected Void getFallback() {
        Exception exception = getExceptionFromThrowable(getExecutionException());
        if (exception instanceof HystrixTimeoutException) {
            exception = new GatewayTimeoutException();
        }
        httpRequestContext.setException(ctx.channel(), exception);
        return null;
    }

    class UrlMetadata {
        String protocol;
        String host;
        int port;

        public UrlMetadata(String protocol, String host, int port) {
            this.protocol = protocol;
            this.host = host;
            this.port = port;
        }
    }

    private UrlMetadata getProtocol(URL url) {
        String protocol = url.getProtocol() == null ? "http" : url.getProtocol();
        String host = url.getHost() == null ? "127.0.0.1" : url.getHost();
        int port = url.getPort();
        if (port == -1) {
            if ("http".equalsIgnoreCase(protocol)) {
                port = 80;
            } else if ("https".equalsIgnoreCase(protocol)) {
                port = 443;
            }
        }

        if (!"http".equalsIgnoreCase(protocol) && !"https".equalsIgnoreCase(protocol)) {
            throw new RuntimeException("Only HTTP(S) is supported.");
        }
        return new UrlMetadata(protocol, host, port);
    }

    private void forward(ChannelHandlerContext ctx, URL url, HttpRequest request, HttpPostRequestEncoder bodyRequestEncoder) throws Exception {
        UrlMetadata urlMetadata = getProtocol(url);
        // Configure SSL context if necessary.
        final boolean ssl = "https".equalsIgnoreCase(urlMetadata.protocol);
        final SslContext sslCtx;
        if (ssl) {
            sslCtx = SslContextBuilder.forClient()
                    .trustManager(InsecureTrustManagerFactory.INSTANCE).build();
        } else {
            sslCtx = null;
        }

        EventLoopGroup group = new NioEventLoopGroup(1);
        try {
            Bootstrap b = new Bootstrap();
            b.group(group)
                    .channel(NioSocketChannel.class)
                    .handler(new BackendFilter(sslCtx, ctx));

            Channel ch = b.connect(urlMetadata.host, urlMetadata.port).sync().channel();

            ch.write(request);
            if (bodyRequestEncoder != null && bodyRequestEncoder.isChunked()) {
                ch.write(bodyRequestEncoder);
            }
            ch.flush();
            ch.closeFuture().sync();
        } finally {
            group.shutdownGracefully();
        }
    }
}