package com.obsidiandynamics.indigo.ws.jetty;

import java.io.*;
import java.nio.*;
import java.util.*;
import java.util.concurrent.atomic.*;

import org.eclipse.jetty.websocket.api.*;

import com.obsidiandynamics.indigo.*;
import com.obsidiandynamics.indigo.ws.*;

public final class JettyServerHarness extends ServerHarness<JettyEndpoint> implements TestSupport {
  private final AtomicBoolean ping = new AtomicBoolean(true);
  
  private final JettyServer server;
  
  private final JettyEndpointManager manager;
  private final WriteCallback writeCallback;
  
  JettyServerHarness(int port, int idleTimeout) throws Exception {
    final JettyMessageListener serverListener = new JettyMessageListener() {
      @Override public void onConnect(JettyEndpoint endpoint, Session session) {
        log("s: connected: %s\n", session.getRemoteAddress());
        connected.incrementAndGet();
        keepAlive(endpoint, ping, idleTimeout);
      }

      @Override public void onText(Session session, String message) {
        log("s: received: %s\n", message);
        received.incrementAndGet();
      }

      @Override
      public void onBinary(Session session, byte[] payload, int offset, int len) {
        log("s: received %d bytes\n", len);
        received.incrementAndGet();
      }
      
      @Override public void onClose(Session session, int statusCode, String reason) {
        log("s: disconnected: statusCode=%d, reason=%s\n", statusCode, reason);
        closed.incrementAndGet();
        ping.set(false);
      }
      
      @Override public void onError(Session session, Throwable cause) {
        log("s: socket error\n");
        System.err.println("server socket error");
        cause.printStackTrace();
      }
    };
    
    manager = new JettyEndpointManager(idleTimeout, new JettyEndpointConfig() {{
      highWaterMark = Long.MAX_VALUE;
    }}, serverListener);
    server = new JettyServer(port, "/", manager);
    
    writeCallback = new WriteCallback() {
      @Override public void writeSuccess() {
        sent.incrementAndGet();
      }
      
      @Override public void writeFailed(Throwable x) {
        System.err.println("server write error");
        x.printStackTrace();
      }
    };
  }

  @Override
  public List<JettyEndpoint> getEndpoints() {
    return new ArrayList<>(manager.getEndpoints());
  }

  @Override
  public void broadcast(List<JettyEndpoint> endpoints, byte[] payload) {
    for (JettyEndpoint endpoint : endpoints) {
      endpoint.send(ByteBuffer.wrap(payload), writeCallback);
    }
  }

  @Override
  public void flush(List<JettyEndpoint> endpoints) throws IOException {
    for (JettyEndpoint endpoint : endpoints) {
      endpoint.flush();
    }
  }

  @Override
  public void close() throws Exception {
    server.close();
  }

  @Override
  public void sendPong(JettyEndpoint endpoint) throws IOException {
    try {
      endpoint.sendPong();
    } catch (WebSocketException e) {
      log("ping skipped\n");
      return;
    }
  }
  
  public static ThrowingFactory<JettyServerHarness> factory(int port, int idleTimeout) {
    return () -> new JettyServerHarness(port, idleTimeout);
  }
}
