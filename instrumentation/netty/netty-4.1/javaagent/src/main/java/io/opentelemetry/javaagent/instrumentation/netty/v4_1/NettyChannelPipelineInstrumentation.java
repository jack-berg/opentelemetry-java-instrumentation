/*
 * Copyright The OpenTelemetry Authors
 * SPDX-License-Identifier: Apache-2.0
 */

package io.opentelemetry.javaagent.instrumentation.netty.v4_1;

import static io.opentelemetry.javaagent.instrumentation.netty.v4_1.NettyClientSingletons.sslInstrumenter;
import static net.bytebuddy.matcher.ElementMatchers.isMethod;
import static net.bytebuddy.matcher.ElementMatchers.nameStartsWith;
import static net.bytebuddy.matcher.ElementMatchers.named;
import static net.bytebuddy.matcher.ElementMatchers.takesArgument;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.http.HttpClientCodec;
import io.netty.handler.codec.http.HttpRequestDecoder;
import io.netty.handler.codec.http.HttpRequestEncoder;
import io.netty.handler.codec.http.HttpResponseDecoder;
import io.netty.handler.codec.http.HttpResponseEncoder;
import io.netty.handler.codec.http.HttpServerCodec;
import io.opentelemetry.instrumentation.api.util.VirtualField;
import io.opentelemetry.instrumentation.netty.v4.common.internal.client.NettySslInstrumentationHandler;
import io.opentelemetry.instrumentation.netty.v4_1.internal.client.HttpClientRequestTracingHandler;
import io.opentelemetry.instrumentation.netty.v4_1.internal.client.HttpClientResponseTracingHandler;
import io.opentelemetry.instrumentation.netty.v4_1.internal.client.HttpClientTracingHandler;
import io.opentelemetry.instrumentation.netty.v4_1.internal.server.HttpServerRequestTracingHandler;
import io.opentelemetry.instrumentation.netty.v4_1.internal.server.HttpServerResponseTracingHandler;
import io.opentelemetry.instrumentation.netty.v4_1.internal.server.HttpServerTracingHandler;
import io.opentelemetry.javaagent.bootstrap.CallDepth;
import io.opentelemetry.javaagent.extension.instrumentation.TypeTransformer;
import io.opentelemetry.javaagent.instrumentation.netty.v4.common.AbstractNettyChannelPipelineInstrumentation;
import net.bytebuddy.asm.Advice;

public class NettyChannelPipelineInstrumentation
    extends AbstractNettyChannelPipelineInstrumentation {

  @Override
  public void transform(TypeTransformer transformer) {
    super.transform(transformer);

    transformer.applyAdviceToMethod(
        isMethod()
            .and(nameStartsWith("add").or(named("replace")))
            .and(takesArgument(1, String.class))
            .and(takesArgument(2, named("io.netty.channel.ChannelHandler"))),
        NettyChannelPipelineInstrumentation.class.getName() + "$ChannelPipelineAddAdvice");
  }

  /**
   * When certain handlers are added to the pipeline, we want to add our corresponding tracing
   * handlers. If those handlers are later removed, we also remove our handlers. Support for
   * replacing handlers and removeFirst/removeLast is currently not implemented.
   */
  @SuppressWarnings("unused")
  public static class ChannelPipelineAddAdvice {

    @Advice.OnMethodEnter
    public static void trackCallDepth(
        @Advice.Argument(2) ChannelHandler handler,
        @Advice.Local("otelCallDepth") CallDepth callDepth) {
      // Previously we used one unique call depth tracker for all handlers, using
      // ChannelPipeline.class as a key.
      // The problem with this approach is that it does not work with netty's
      // io.netty.channel.ChannelInitializer which provides an `initChannel` that can be used to
      // `addLast` other handlers. In that case the depth would exceed 0 and handlers added from
      // initializers would not be considered.
      // Using the specific handler key instead of the generic ChannelPipeline.class will help us
      // both to handle such cases and avoid adding our additional handlers in case of internal
      // calls of `addLast` to other method overloads with a compatible signature.
      callDepth = CallDepth.forClass(handler.getClass());
      callDepth.getAndIncrement();
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class, suppress = Throwable.class)
    public static void addHandler(
        @Advice.This ChannelPipeline pipeline,
        @Advice.Argument(1) String handlerName,
        @Advice.Argument(2) ChannelHandler handler,
        @Advice.Local("otelCallDepth") CallDepth callDepth) {
      if (callDepth.decrementAndGet() > 0) {
        return;
      }

      VirtualField<ChannelHandler, ChannelHandler> instrumentationHandlerField =
          VirtualField.find(ChannelHandler.class, ChannelHandler.class);

      // don't add another instrumentation handler if there already is one attached
      if (instrumentationHandlerField.get(handler) != null) {
        return;
      }

      String name = handlerName;
      if (name == null) {
        ChannelHandlerContext context = pipeline.context(handler);
        if (context == null) {
          // probably a ChannelInitializer that was used and removed
          // see the comment above in @Advice.OnMethodEnter
          return;
        }
        name = context.name();
      }

      ChannelHandler ourHandler = null;
      // Server pipeline handlers
      if (handler instanceof HttpServerCodec) {
        ourHandler = new HttpServerTracingHandler(NettyServerSingletons.instrumenter());
      } else if (handler instanceof HttpRequestDecoder) {
        ourHandler = new HttpServerRequestTracingHandler(NettyServerSingletons.instrumenter());
      } else if (handler instanceof HttpResponseEncoder) {
        ourHandler = new HttpServerResponseTracingHandler(NettyServerSingletons.instrumenter());
        // Client pipeline handlers
      } else if (handler instanceof HttpClientCodec) {
        ourHandler = new HttpClientTracingHandler(NettyClientSingletons.instrumenter());
      } else if (handler instanceof HttpRequestEncoder) {
        ourHandler = new HttpClientRequestTracingHandler(NettyClientSingletons.instrumenter());
      } else if (handler instanceof HttpResponseDecoder) {
        ourHandler = new HttpClientResponseTracingHandler(NettyClientSingletons.instrumenter());
        // the SslHandler lives in the netty-handler module, using class name comparison to avoid
        // adding a dependency
      } else if (handler.getClass().getName().equals("io.netty.handler.ssl.SslHandler")) {
        ourHandler = new NettySslInstrumentationHandler(sslInstrumenter(), handler);
      }

      if (ourHandler != null) {
        try {
          pipeline.addAfter(name, ourHandler.getClass().getName(), ourHandler);
          // associate our handle with original handler so they could be removed together
          instrumentationHandlerField.set(handler, ourHandler);
        } catch (IllegalArgumentException e) {
          // Prevented adding duplicate handlers.
        }
      }
    }
  }
}
