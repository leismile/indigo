package com.obsidiandynamics.indigo.ws;

import java.util.*;
import java.util.concurrent.*;

import org.slf4j.*;

public final class Scanner<E extends WSEndpoint> extends Thread implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(Scanner.class);
  
  private final int scanIntervalMillis;
  private final int pingIntervalMillis;
  private final Set<E> endpoints = new CopyOnWriteArraySet<>();
  
  private volatile boolean running = true;
  
  public Scanner(int scanIntervalMillis, int pingIntervalMillis) {
    super(String.format("Scanner[scanInterval=%dms,pingInterval=%dms]", 
                        scanIntervalMillis, pingIntervalMillis));
    this.scanIntervalMillis = scanIntervalMillis;
    this.pingIntervalMillis = pingIntervalMillis;
    start();
  }
  
  @Override
  public void run() {
    while (running) {
      try {
        final long now = System.currentTimeMillis();
        for (E endpoint : endpoints) {
          if (! endpoint.isOpen()) {
            if (LOG.isDebugEnabled()) LOG.debug("Closing defunct endpoint {}", endpoint);
            endpoint.close();
          } else if (pingIntervalMillis != 0) {
            final long lastActivity = endpoint.getLastActivityTime();
            if (now - lastActivity > pingIntervalMillis) {
              if (LOG.isTraceEnabled()) LOG.trace("Pinging {}", endpoint);
              endpoint.sendPing();
            }
          }
        }
      } catch (Exception e) {
        LOG.warn("Unexpected error", e);
      }
      
      try {
        Thread.sleep(scanIntervalMillis);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        continue;
      }
    }
  }
  
  public void addEndpoint(E endpoint) {
    endpoints.add(endpoint);
  }
  
  public void removeEndpoint(E endpoint) {
    endpoints.remove(endpoint);
  }
  
  public Collection<E> getEndpoints() {
    return Collections.unmodifiableSet(endpoints);
  }
  
  @Override
  public void close() throws InterruptedException {
    running = false;
    interrupt();
    join();
  }
}
