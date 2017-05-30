package com.obsidiandynamics.indigo.iot.remote;

import java.nio.*;

public interface RemoteNexusHandler {
  void onConnect(RemoteNexus nexus);
  
  void onDisconnect(RemoteNexus nexus);
  
  void onText(RemoteNexus nexus, String topic, String payload);
  
  void onBinary(RemoteNexus nexus, String topic, ByteBuffer payload);
}
