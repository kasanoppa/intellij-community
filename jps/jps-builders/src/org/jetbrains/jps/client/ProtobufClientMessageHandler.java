/*
 * Copyright 2000-2012 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jetbrains.jps.client;

import com.google.protobuf.MessageLite;
import org.jboss.netty.channel.ChannelHandlerContext;
import org.jboss.netty.channel.ChannelStateEvent;
import org.jboss.netty.channel.MessageEvent;
import org.jboss.netty.channel.SimpleChannelHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.jps.api.RequestFuture;

import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;

/**
* @author Eugene Zhuravlev
*         Date: 1/22/12
*/
final class ProtobufClientMessageHandler<T extends ProtobufResponseHandler> extends SimpleChannelHandler {
  private final ConcurrentHashMap<UUID, RequestFuture<T>> myHandlers = new ConcurrentHashMap<UUID, RequestFuture<T>>();
  @NotNull
  private final UUIDGetter myUuidGetter;
  private final SimpleProtobufClient myClient;
  private final Executor myAsyncExec;

  public ProtobufClientMessageHandler(@NotNull UUIDGetter uuidGetter, SimpleProtobufClient client, Executor asyncExec) {
    myUuidGetter = uuidGetter;
    myClient = client;
    myAsyncExec = asyncExec;
  }

  public final void messageReceived(ChannelHandlerContext ctx, MessageEvent e) throws Exception {
    final UUID messageUUID = myUuidGetter.getSessionUUID(e);
    final RequestFuture<T> future = myHandlers.get(messageUUID);
    final T handler = future != null ? future.getMessageHandler() : null;
    if (handler == null) {
      terminateSession(messageUUID);
    }
    else {
      boolean terminateSession = false;
      try {
        terminateSession = handler.handleMessage((MessageLite)e.getMessage());
      }
      catch (Exception ex) {
        terminateSession = true;
        throw ex;
      }
      finally {
        if (terminateSession) {
          terminateSession(messageUUID);
        }
      }
    }
  }

  private void terminateSession(UUID sessionId) {
    final RequestFuture<T> future = removeFuture(sessionId);
    if (future != null) {
      final T handler = future.getMessageHandler();
      try {
        if (handler != null) {
          try {
            handler.sessionTerminated();
          }
          catch (Throwable ignored) {
            ignored.printStackTrace();
          }
        }
      }
      finally {
        future.setDone();
      }
    }
  }

  public void channelClosed(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
    try {
      super.channelClosed(ctx, e);
    }
    finally {
      for (UUID uuid : new ArrayList<UUID>(myHandlers.keySet())) {
        terminateSession(uuid);
      }
    }
  }

  @Override
  public void channelDisconnected(ChannelHandlerContext ctx, ChannelStateEvent e) throws Exception {
    try {
      super.channelDisconnected(ctx, e);
    }
    finally {
      // make sure the client is in disconnected state
      myAsyncExec.execute(new Runnable() {
        @Override
        public void run() {
          myClient.disconnect();
        }
      });
    }
  }

  public void registerFuture(UUID messageId, RequestFuture<T> requestFuture) {
    myHandlers.put(messageId, requestFuture);
  }

  public RequestFuture<T> removeFuture(UUID messageId) {
    return myHandlers.remove(messageId);
  }
}
