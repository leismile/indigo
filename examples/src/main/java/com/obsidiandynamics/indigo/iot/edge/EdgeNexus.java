package com.obsidiandynamics.indigo.iot.edge;

import java.util.concurrent.*;
import java.util.function.*;

import com.obsidiandynamics.indigo.iot.*;
import com.obsidiandynamics.indigo.iot.frame.*;

public final class EdgeNexus implements AutoCloseable {
  private final EdgeNode node;
  
  private final Peer peer;
  
  private Session session;

  public EdgeNexus(EdgeNode node, Peer peer) {
    this.node = node;
    this.peer = peer;
  }
  
  public Session getSession() {
    return session;
  }

  final void setSession(Session session) {
    this.session = session;
  }

  public CompletableFuture<Void> sendAuto(Frame frame) {
    return SendHelper.sendAuto(frame, peer.getEndpoint(), node.getWire());
  }
  
  public void sendAuto(Frame frame, Consumer<Throwable> callback) {
    SendHelper.sendAuto(frame, peer.getEndpoint(), node.getWire(), callback);
  }
  
  public CompletableFuture<Void> send(TextEncodedFrame frame) {
    return SendHelper.send(frame, peer.getEndpoint(), node.getWire());
  }
  
  public void send(TextEncodedFrame frame, Consumer<Throwable> callback) {
    SendHelper.send(frame, peer.getEndpoint(), node.getWire(), callback);
  }
  
  public CompletableFuture<Void> send(BinaryEncodedFrame frame) {
    return SendHelper.send(frame, peer.getEndpoint(), node.getWire());
  }
  
  public void send(BinaryEncodedFrame frame, Consumer<Throwable> callback) {
    SendHelper.send(frame, peer.getEndpoint(), node.getWire(), callback);
  }
  
  public boolean isLocal() {
    return peer instanceof LocalPeer;
  }

  public Peer getPeer() {
    return peer;
  }

  @Override
  public void close() throws Exception {
    peer.close();
  }
  
  public boolean awaitClose(int waitMillis) throws InterruptedException {
    if (! peer.hasEndpoint()) throw new IllegalArgumentException("Cannot await close on a non-remote peer");
    return peer.getEndpoint().awaitClose(waitMillis);
  }

  @Override
  public String toString() {
    return "EdgeNexus [peer=" + peer + "]";
  }
}
