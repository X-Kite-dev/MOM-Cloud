//package com.kitex.mom.iot.network.http;
//
//import com.kitex.mom.iot.network.AbstractNetwork;
//import com.kitex.mom.iot.core.defaults.DefaultNetworkType;
//import io.netty.buffer.ByteBuf;
//import io.netty.buffer.Unpooled;
//import io.netty.channel.*;
//import io.netty.channel.socket.SocketChannel;
//import io.netty.handler.codec.http.*;
//import io.netty.util.CharsetUtil;
//import io.netty.util.ReferenceCountUtil;
//
//import java.io.IOException;
//import java.io.InputStream;
//import java.io.OutputStream;
//import java.util.HashMap;
//import java.util.Map;
//import java.util.concurrent.CompletableFuture;
//import java.util.function.BiConsumer;
//
/// **
// * HTTP服务器网络组件（基于Netty实现）
// */
//public class HttpServer extends AbstractNetwork {
//
//    private final int port;
//    private final String contextPath;
//    private final int bossThreads;
//    private final int workerThreads;
//    // 路径与处理器的映射
//    private final Map<String, HttpRequestHandler> handlers = new HashMap<>();
//    private final Map<String, BiConsumer<FullHttpRequest, HttpResponseWriter>> nettyHandlers = new HashMap<>();
//    // Netty组件
//    private EventLoopGroup bossGroup;
//    private EventLoopGroup workerGroup;
//    private Channel serverChannel;
//
//    /**
//     * 构造函数
//     *
//     * @param id            HTTP服务器ID
//     * @param port          端口号
//     * @param contextPath   上下文路径
//     * @param bossThreads   Boss线程数
//     * @param workerThreads Worker线程数
//     */
//    public HttpServer(String id, int port, String contextPath, int bossThreads, int workerThreads) {
//        super(id, DefaultNetworkType.HTTP_SERVER);
//        this.port = port;
//        this.contextPath = contextPath != null && !contextPath.isEmpty() ? contextPath : "/";
//        this.bossThreads = bossThreads > 0 ? bossThreads : 1;
//        this.workerThreads = workerThreads > 0 ? workerThreads : Runtime.getRuntime().availableProcessors() * 2;
//    }
//
//    /**
//     * 构造函数（使用默认值）
//     *
//     * @param id   HTTP服务器ID
//     * @param port 端口号
//     */
//    public HttpServer(String id, int port) {
//        this(id, port, "/", 1, Runtime.getRuntime().availableProcessors() * 2);
//    }
//
//    /**
//     * 构造函数（向后兼容，忽略useNetty参数）
//     *
//     * @param id       HTTP服务器ID
//     * @param port     端口号
//     * @param useNetty 已忽略，始终使用Netty
//     */
//    public HttpServer(String id, int port, boolean useNetty) {
//        this(id, port);
//    }
//
//    /**
//     * 构造函数（向后兼容，忽略useNetty参数）
//     *
//     * @param id          HTTP服务器ID
//     * @param port        端口号
//     * @param contextPath 上下文路径
//     * @param backlogSize 等待队列大小
//     * @param useNetty    已忽略，始终使用Netty
//     */
//    public HttpServer(String id, int port, String contextPath, int backlogSize, boolean useNetty) {
//        this(id, port, contextPath, 1, Runtime.getRuntime().availableProcessors() * 2);
//    }
//
//    /**
//     * 异步执行HTTP请求处理
//     *
//     * @param request        HTTP请求
//     * @param responseWriter 响应写入器
//     * @param handler        处理器
//     * @return 执行结果的Future
//     */
//    public static CompletableFuture<Void> executeAsync(FullHttpRequest request, HttpResponseWriter responseWriter, BiConsumer<FullHttpRequest, HttpResponseWriter> handler) {
//
//        CompletableFuture<Void> future = new CompletableFuture<>();
//
//        try {
//            // 异步执行处理器
//            CompletableFuture.runAsync(() -> {
//                try {
//                    handler.accept(request, responseWriter);
//                    future.complete(null);
//                } catch (Exception e) {
//                    future.completeExceptionally(e);
//                }
//            });
//        } catch (Exception e) {
//            future.completeExceptionally(e);
//        }
//
//        return future;
//    }
//
//    @Override
//    protected void doShutdown() {
//        if (serverChannel != null) {
//            serverChannel.close();
//            serverChannel = null;
//        }
//
//        if (bossGroup != null) {
//            bossGroup.shutdownGracefully();
//            bossGroup = null;
//        }
//
//        if (workerGroup != null) {
//            workerGroup.shutdownGracefully();
//            workerGroup = null;
//        }
//
//        System.out.println("HTTP服务器已关闭");
//    }
//
//    /**
//     * 添加HTTP处理器
//     *
//     * @param path    路径
//     * @param handler 处理器
//     */
//    public void addHandler(String path, HttpRequestHandler handler) {
//        if (isAlive()) {
//            // 如果服务器已启动，添加处理器适配器
//            addNettyHandler(path, new RequestHandlerAdapter(handler));
//        } else {
//            // 如果服务器未启动，先保存处理器
//            handlers.put(path, handler);
//        }
//    }
//
//    /**
//     * 添加Netty HTTP处理器
//     *
//     * @param path    路径
//     * @param handler 处理器
//     */
//    public void addNettyHandler(String path, BiConsumer<FullHttpRequest, HttpResponseWriter> handler) {
//        nettyHandlers.put(path, handler);
//    }
//
//    /**
//     * 移除HTTP处理器
//     *
//     * @param path 路径
//     */
//    public void removeHandler(String path) {
//        handlers.remove(path);
//        nettyHandlers.remove(path);
//    }
//
//    /**
//     * 获取端口号
//     *
//     * @return 端口号
//     */
//    public int getPort() {
//        return port;
//    }
//
//    /**
//     * 获取上下文路径
//     *
//     * @return 上下文路径
//     */
//    public String getContextPath() {
//        return contextPath;
//    }
//
//    /**
//     * 检查是否使用Netty引擎（为了向后兼容）
//     *
//     * @return 总是返回true
//     */
//    public boolean isUsingNetty() {
//        return true;
//    }
//
//    /**
//     * 设置线程组
//     *
//     * @param bossGroup   Boss线程组
//     * @param workerGroup Worker线程组
//     */
//    public void setEventLoopGroups(EventLoopGroup bossGroup, EventLoopGroup workerGroup) {
//        this.bossGroup = bossGroup;
//        this.workerGroup = workerGroup;
//    }
//
//    /**
//     * 设置服务器通道
//     *
//     * @param serverChannel 服务器通道
//     */
//    public void setServerChannel(Channel serverChannel) {
//        this.serverChannel = serverChannel;
//    }
//
//    /**
//     * 获取HTTP服务器初始化器，用于配置子通道处理器
//     *
//     * @return HTTP服务器初始化器
//     */
//    public HttpServerInitializer getHttpServerInitializer() {
//        return new HttpServerInitializer();
//    }
//
//    /**
//     * 获取Boss线程数
//     *
//     * @return Boss线程数
//     */
//    public int getBossThreads() {
//        return bossThreads;
//    }
//
//    /**
//     * 获取Worker线程数
//     *
//     * @return Worker线程数
//     */
//    public int getWorkerThreads() {
//        return workerThreads;
//    }
//
//    /**
//     * HTTP响应写入器接口
//     */
//    public interface HttpResponseWriter {
//        /**
//         * 写入HTTP响应
//         *
//         * @param status      状态码
//         * @param contentType 内容类型
//         * @param content     响应内容
//         */
//        void writeResponse(HttpResponseStatus status, String contentType, ByteBuf content);
//
//        /**
//         * 写入HTTP响应（使用字符串内容）
//         *
//         * @param status      状态码
//         * @param contentType 内容类型
//         * @param content     响应内容
//         */
//        default void writeResponse(HttpResponseStatus status, String contentType, String content) {
//            writeResponse(status, contentType, Unpooled.copiedBuffer(content, CharsetUtil.UTF_8));
//        }
//    }
//
//    /**
//     * HTTP请求接口，封装HTTP请求信息
//     */
//    public interface HttpRequest {
//        /**
//         * 获取请求方法
//         *
//         * @return 请求方法（GET, POST等）
//         */
//        String getMethod();
//
//        /**
//         * 获取请求路径
//         *
//         * @return 请求路径
//         */
//        String getPath();
//
//        /**
//         * 获取请求URI
//         *
//         * @return 完整URI（包含查询参数）
//         */
//        String getUri();
//
//        /**
//         * 获取请求头
//         *
//         * @param name 头名称
//         * @return 头值
//         */
//        String getHeader(String name);
//
//        /**
//         * 获取所有请求头名称
//         *
//         * @return 所有头名称
//         */
//        Iterable<String> getHeaderNames();
//
//        /**
//         * 获取请求内容
//         *
//         * @return 请求内容输入流
//         */
//        InputStream getInputStream();
//    }
//
//    /**
//     * HTTP响应接口，封装HTTP响应信息
//     */
//    public interface HttpResponse {
//        /**
//         * 设置响应状态码
//         *
//         * @param statusCode 状态码
//         */
//        void setStatusCode(int statusCode);
//
//        /**
//         * 设置响应内容类型
//         *
//         * @param contentType 内容类型
//         */
//        void setContentType(String contentType);
//
//        /**
//         * 设置响应头
//         *
//         * @param name  头名称
//         * @param value 头值
//         */
//        void setHeader(String name, String value);
//
//        /**
//         * 获取响应输出流
//         *
//         * @return 响应输出流
//         */
//        OutputStream getOutputStream();
//    }
//
//    /**
//     * HTTP请求处理器接口
//     */
//    public interface HttpRequestHandler {
//        /**
//         * 处理HTTP请求
//         *
//         * @param request  HTTP请求
//         * @param response HTTP响应
//         * @throws IOException 如果处理过程中发生IO异常
//         */
//        void handle(HttpRequest request, HttpResponse response) throws IOException;
//    }
//
//    /**
//     * 基于Channel的HTTP响应写入器实现
//     */
//    private static class ChannelHttpResponseWriter implements HttpResponseWriter {
//        private final ChannelHandlerContext ctx;
//        private final HttpRequest request;
//
//        public ChannelHttpResponseWriter(ChannelHandlerContext ctx, HttpRequest request) {
//            this.ctx = ctx;
//            this.request = request;
//        }
//
//        @Override
//        public void writeResponse(HttpResponseStatus status, String contentType, ByteBuf content) {
//            try {
//                // 创建响应对象
//                FullHttpResponse response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, content);
//
//                // 设置响应头
//                response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
//                response.headers().set(HttpHeaderNames.CONTENT_LENGTH, content.readableBytes());
//
//                // 检查是否为长连接
//                boolean keepAlive = HttpUtil.isKeepAlive((HttpMessage) request);
//                if (keepAlive) {
//                    response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.KEEP_ALIVE);
//                } else {
//                    response.headers().set(HttpHeaderNames.CONNECTION, HttpHeaderValues.CLOSE);
//                }
//
//                // 发送响应
//                ChannelFuture future = ctx.writeAndFlush(response);
//
//                // 如果不是长连接，发送完成后关闭连接
//                if (!keepAlive) {
//                    future.addListener(ChannelFutureListener.CLOSE);
//                }
//            } catch (Exception e) {
//                // 发生异常，释放内容缓冲区
//                ReferenceCountUtil.release(content);
//                throw e;
//            }
//        }
//    }
//
//    /**
//     * Netty请求适配器，将Netty请求适配到自定义HTTP请求接口
//     */
//    private static class NettyRequestAdapter implements HttpRequest {
//        private final FullHttpRequest request;
//
//        public NettyRequestAdapter(FullHttpRequest request) {
//            this.request = request;
//        }
//
//        @Override
//        public String getMethod() {
//            return request.method().name();
//        }
//
//        @Override
//        public String getPath() {
//            String uri = request.uri();
//            int queryIndex = uri.indexOf('?');
//            return queryIndex > 0 ? uri.substring(0, queryIndex) : uri;
//        }
//
//        @Override
//        public String getUri() {
//            return request.uri();
//        }
//
//        @Override
//        public String getHeader(String name) {
//            return request.headers().get(name);
//        }
//
//        @Override
//        public Iterable<String> getHeaderNames() {
//            return request.headers().names();
//        }
//
//        @Override
//        public InputStream getInputStream() {
//            ByteBuf content = request.content();
//            byte[] bytes = new byte[content.readableBytes()];
//            content.getBytes(content.readerIndex(), bytes);
//            return new java.io.ByteArrayInputStream(bytes);
//        }
//    }
//
//    /**
//     * Netty响应适配器，将自定义HTTP响应接口适配到Netty响应
//     */
//    private static class NettyResponseAdapter implements HttpResponse {
//        private final HttpResponseWriter responseWriter;
//        private final java.io.ByteArrayOutputStream outputStream = new java.io.ByteArrayOutputStream();
//        private final Map<String, String> headers = new HashMap<>();
//        private int statusCode = 200;
//        private String contentType = "text/plain; charset=utf-8";
//        private boolean committed = false;
//
//        public NettyResponseAdapter(HttpResponseWriter responseWriter) {
//            this.responseWriter = responseWriter;
//        }
//
//        @Override
//        public void setStatusCode(int statusCode) {
//            checkCommitted();
//            this.statusCode = statusCode;
//        }
//
//        @Override
//        public void setContentType(String contentType) {
//            checkCommitted();
//            this.contentType = contentType;
//            headers.put("Content-Type", contentType);
//        }
//
//        @Override
//        public void setHeader(String name, String value) {
//            checkCommitted();
//            headers.put(name, value);
//        }
//
//        @Override
//        public OutputStream getOutputStream() {
//            return outputStream;
//        }
//
//        /**
//         * 提交响应
//         */
//        public void commit() {
//            if (committed) {
//                return;
//            }
//
//            try {
//                ByteBuf content = Unpooled.copiedBuffer(outputStream.toByteArray());
//                HttpResponseStatus status = HttpResponseStatus.valueOf(statusCode);
//                responseWriter.writeResponse(status, contentType, content);
//                committed = true;
//            } catch (Exception e) {
//                throw new RuntimeException("提交响应时发生错误", e);
//            }
//        }
//
//        private void checkCommitted() {
//            if (committed) {
//                throw new IllegalStateException("响应已提交，无法修改");
//            }
//        }
//    }
//
//    /**
//     * 请求处理器适配器，将自定义HTTP请求处理器适配到Netty处理器
//     */
//    private static class RequestHandlerAdapter implements BiConsumer<FullHttpRequest, HttpResponseWriter> {
//        private final HttpRequestHandler handler;
//
//        public RequestHandlerAdapter(HttpRequestHandler handler) {
//            this.handler = handler;
//        }
//
//        @Override
//        public void accept(FullHttpRequest request, HttpResponseWriter responseWriter) {
//            try {
//                NettyRequestAdapter requestAdapter = new NettyRequestAdapter(request);
//                NettyResponseAdapter responseAdapter = new NettyResponseAdapter(responseWriter);
//
//                handler.handle(requestAdapter, responseAdapter);
//
//                // 提交响应
//                responseAdapter.commit();
//            } catch (Exception e) {
//                // 处理异常
//                String errorMessage = "处理请求时发生内部错误: " + e.getMessage();
//                responseWriter.writeResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR, "text/plain; charset=utf-8", errorMessage);
//            }
//        }
//    }
//
//    /**
//     * HTTP服务器初始化器
//     */
//    private class HttpServerInitializer extends ChannelInitializer<SocketChannel> {
//        @Override
//        protected void initChannel(SocketChannel ch) {
//            ChannelPipeline pipeline = ch.pipeline();
//
//            // HTTP 编解码器
//            pipeline.addLast(new HttpServerCodec());
//
//            // HTTP 消息聚合器
//            pipeline.addLast(new HttpObjectAggregator(1024 * 1024)); // 最大内容长度：1MB
//
//            // 自定义处理器
//            pipeline.addLast(new HttpServerHandler());
//        }
//    }
//
//    /**
//     * HTTP服务器处理器
//     */
//    private class HttpServerHandler extends SimpleChannelInboundHandler<FullHttpRequest> {
//        @Override
//        protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
//            // 获取请求URI
//            String uri = request.uri();
//
//            // 创建响应写入器
//            HttpResponseWriter responseWriter = new ChannelHttpResponseWriter(ctx, (HttpRequest) request);
//
//            // 查找并执行处理器
//            boolean handled = false;
//            for (Map.Entry<String, BiConsumer<FullHttpRequest, HttpResponseWriter>> entry : nettyHandlers.entrySet()) {
//                String path = entry.getKey();
//                if (uri.startsWith(path)) {
//                    try {
//                        entry.getValue().accept(request, responseWriter);
//                        handled = true;
//                        break;
//                    } catch (Exception e) {
//                        System.err.println("处理HTTP请求时发生异常: " + e.getMessage());
//                        e.printStackTrace();
//                        String errorMessage = "处理请求时发生内部错误";
//                        responseWriter.writeResponse(HttpResponseStatus.INTERNAL_SERVER_ERROR, "text/plain; charset=utf-8", errorMessage);
//                        handled = true;
//                        break;
//                    }
//                }
//            }
//
//            // 如果没有处理器处理请求，返回404
//            if (!handled) {
//                String errorMessage = "找不到请求的资源: " + uri;
//                responseWriter.writeResponse(HttpResponseStatus.NOT_FOUND, "text/plain; charset=utf-8", errorMessage);
//            }
//        }
//
//        @Override
//        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
//            System.err.println("HTTP服务器异常: " + cause.getMessage());
//            cause.printStackTrace();
//            ctx.close();
//        }
//    }
//}