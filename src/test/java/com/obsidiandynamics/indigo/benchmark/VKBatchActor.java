/*
Copyright 2012-2017 Viktor Klang
   Licensed under the Apache License, Version 2.0 (the "License");
   you may not use this file except in compliance with the License.
   You may obtain a copy of the License at
       http://www.apache.org/licenses/LICENSE-2.0
   Unless required by applicable law or agreed to in writing, software
   distributed under the License is distributed on an "AS IS" BASIS,
   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
   See the License for the specific language governing permissions and
   limitations under the License.
 */

package com.obsidiandynamics.indigo.benchmark;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

public class VKBatchActor { // Visibility is achieved by volatile-piggybacking of reads+writes to "on"
  public static interface Effect extends Function<Behavior, Behavior> { }; // An Effect returns a Behavior given a Behavior
  public static interface Behavior extends Function<Object, Effect> { }; // A Behavior is a message (Object) which returns the behavior for the next message

  public static interface Address { 
    Address tell(Object msg); 
  }; // An Address is somewhere you can send messages

  static abstract class AtomicRunnableAddress implements Runnable, Address { 
    protected final AtomicInteger on = new AtomicInteger(); 
  }; // Defining a composite of AtomcInteger, Runnable and Address

  public final static Effect become(final Behavior behavior) { 
    return new Effect() { 
      @Override public Behavior apply(Behavior old) { 
        return behavior; 
      } 
    }; 
  } // Become is an Effect that returns a captured Behavior no matter what the old Behavior is

  public final static Effect stay = new Effect() { 
    @Override public Behavior apply(Behavior old) { 
      return old; 
    } 
  }; // Stay is an Effect that returns the old Behavior when applied.

  public final static Effect die = become(new Behavior() { 
    @Override public Effect apply(Object msg) { 
      return stay; 
    } 
  }); // Die is an Effect which replaces the old Behavior with a new one which does nothing, forever.

  public static Address create(final Function<Address, Behavior> initial, final Executor e) {
    final Address a = new AtomicRunnableAddress() {
      private final Queue<Object> mb = new ConcurrentLinkedQueue<>();

      private Behavior behavior = new Behavior() { 
        @Override public Effect apply(Object msg) { 
          return (msg instanceof Address) ? become(initial.apply((Address)msg)) : stay; 
        } 
      };

      @Override public final Address tell(Object msg) { 
        if (mb.add(msg)) {
          async(); 
        }
        return this; 
      }

      @Override public final void run() { 
        if(on.get() == 1) { 
          try {
            while (true) {
              final Object m = mb.poll();
              if(m != null) {
                behavior = behavior.apply(m).apply(behavior); 
              } else {
                break;
              }
            }
          } finally { 
            on.set(0); 
            async(); 
          } 
        }
      }

      private final void async() { 
        if(!mb.isEmpty() && on.compareAndSet(0, 1)) {
          try { 
            e.execute(this); 
          } catch(RuntimeException re) { 
            on.set(0); 
            throw re; 
          } 
        }
      }
    };
    return a.tell(a); // Make self-aware
  }
}
