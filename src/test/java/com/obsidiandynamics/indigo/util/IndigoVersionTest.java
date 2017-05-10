package com.obsidiandynamics.indigo.util;

import static org.junit.Assert.*;

import java.io.*;

import org.junit.*;

import com.obsidiandynamics.indigo.*;

public class IndigoVersionTest implements TestSupport {
  @Test
  public void testValid() throws IOException {
    assertNotNull(IndigoVersion.get());
  }
  
  @Test(expected=IOException.class)
  public void testInvalid() throws IOException {
    IndigoVersion.get("wrong.file");
  }
}
