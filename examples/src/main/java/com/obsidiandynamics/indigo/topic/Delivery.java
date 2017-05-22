package com.obsidiandynamics.indigo.topic;

public final class Delivery {
  private final Topic topic;
  
  private final Object payload;

  public Delivery(Topic topic, Object payload) {
    this.topic = topic;
    this.payload = payload;
  }

  public Topic getTopic() {
    return topic;
  }

  public Object getPayload() {
    return payload;
  }

  @Override
  public String toString() {
    return "Delivery [topic=" + topic + ", payload=" + payload + "]";
  }
}