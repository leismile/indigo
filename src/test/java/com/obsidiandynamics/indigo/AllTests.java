package com.obsidiandynamics.indigo;

import org.junit.runner.*;
import org.junit.runners.*;
import org.junit.runners.Suite.*;

@RunWith(Suite.class)
@SuiteClasses({
  ParallelConsistencyTest.class,
  StatelessChainTest.class
})         
public class AllTests {}