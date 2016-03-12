/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package reactor.io.netty.http;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketClientHandshaker;
import io.netty.handler.codec.http.websocketx.WebSocketFrame;
import io.netty.util.ReferenceCountUtil;
import org.reactivestreams.Subscriber;
import reactor.core.publisher.Flux;
import reactor.io.buffer.Buffer;
import reactor.io.buffer.StringBuffer;
import reactor.io.ipc.ChannelFlux;
import reactor.io.ipc.ChannelFluxHandler;
import reactor.io.netty.common.NettyChannel;

/**
 * @author Stephane Maldini
 */
final class NettyHttpWSClientHandler extends NettyHttpClientHandler {

	final WebSocketClientHandshaker handshaker;
	NettyHttpWSClientHandler(
			ChannelFluxHandler<Buffer, Buffer, ChannelFlux<Buffer, Buffer>> handler,
			NettyChannel tcpStream,
			WebSocketClientHandshaker handshaker) {
		super(handler, tcpStream);
		this.handshaker = handshaker;
	}

	@Override
	public void channelActive(final ChannelHandlerContext ctx) throws Exception {
		handshaker.handshake(ctx.channel()).addListener(future -> ctx.read());
	}

	@Override
	protected void writeLast(ChannelHandlerContext ctx) {
		ctx.writeAndFlush(new CloseWebSocketFrame());
	}

	@Override
	protected void postRead(ChannelHandlerContext ctx, Object msg) {
		if(CloseWebSocketFrame.class.isAssignableFrom(msg.getClass())){
			ctx.channel().close();
		}
	}

	@Override
	@SuppressWarnings("unchecked")
	public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
		Class<?> messageClass = msg.getClass();
		if (!handshaker.isHandshakeComplete()) {
			ctx.pipeline().remove(HttpObjectAggregator.class);
			handshaker.finishHandshake(ctx.channel(), (FullHttpResponse) msg);
			httpChannel = new NettyHttpClientHandler.PostHeaderPublisher() {
				@Override
				protected void doSubscribeHeaders(Subscriber<? super Void> s) {
					Flux.<Void>empty().subscribe(s);
				}
			};
			NettyHttpWSClientHandler.super.channelActive(ctx);
			super.channelRead(ctx, msg);
			return;
		}

		if (TextWebSocketFrame.class.isAssignableFrom(messageClass)) {
			try {
				//don't inflate the String bytes now
				if(channelSubscriber != null) {
					channelSubscriber.onNext(new StringBuffer(((TextWebSocketFrame) msg).content()
					                                                                    .nioBuffer()));
				}
			} finally {
				ReferenceCountUtil.release(msg);
			}
		} else if (CloseWebSocketFrame.class.isAssignableFrom(messageClass)) {
			ctx.close();
		} else {
			doRead(ctx, ((WebSocketFrame)msg).content());
		}
	}

	@Override
	protected ChannelFuture doOnWrite(Object data, ChannelHandlerContext ctx) {
		return NettyHttpWSServerHandler.writeWS(data, ctx);
	}

}
