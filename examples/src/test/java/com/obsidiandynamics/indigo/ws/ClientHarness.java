package com.obsidiandynamics.indigo.ws;

import java.util.concurrent.atomic.*;

public abstract class ClientHarness<E extends WSEndpoint> extends BaseHarness {
  public final AtomicBoolean connected = new AtomicBoolean();
  public final AtomicBoolean closed = new AtomicBoolean();
}
