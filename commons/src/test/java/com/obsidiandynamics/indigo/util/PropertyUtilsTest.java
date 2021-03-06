package com.obsidiandynamics.indigo.util;

import static junit.framework.TestCase.*;

import java.io.*;
import java.util.*;

import org.junit.*;

import com.obsidiandynamics.assertion.*;

public class PropertyUtilsTest {
  @Test
  public void testSystemGet() {
    assertEquals("bar", PropertyUtils.get("_foo", String::valueOf, "bar"));
  }
  
  @Test
  public void testExistingValue() {
    final Properties props = new Properties();
    props.put("foo", "bar");
    assertEquals("bar", PropertyUtils.get(props, "foo", String::valueOf, "baz"));
  }
  
  @Test
  public void testDefaultValue() {
    assertEquals("bar", PropertyUtils.get(new Properties(), "foo", String::valueOf, "bar"));
  }
  
  @Test
  public void testGetOrSetExisting() {
    final Properties props = new Properties();
    props.put("foo", "bar");
    assertEquals("bar", PropertyUtils.getOrSet(props, "foo", String::valueOf, "baz"));
    assertEquals("bar", props.getProperty("foo"));
  }
  
  @Test
  public void testGetOrSetDefault() {
    final Properties props = new Properties();
    assertEquals("baz", PropertyUtils.getOrSet(props, "foo", String::valueOf, "baz"));
    assertEquals("baz", props.getProperty("foo"));
  }
  
  @Test
  public void testLoadExisting() throws IOException {
    final Properties props = PropertyUtils.load("property-utils-test.properties");
    assertTrue(props.containsKey("foo"));
  }
  
  @Test
  public void testLoadExistingDefault() throws IOException {
    final Properties props = PropertyUtils.load("property-utils-test.properties", null);
    assertNotNull(props);
    assertTrue(props.containsKey("foo"));
  }
  
  @Test(expected=FileNotFoundException.class)
  public void testLoadNonExisting() throws IOException {
    PropertyUtils.load("non-existing.properties");
  }

  @Test
  public void testLoadNonExistingDefault() throws IOException {
    final Properties def = new Properties();
    def.put("foo", "bar");
    final Properties props = PropertyUtils.load("non-existing.properties", def);
    assertTrue(props.containsKey("foo"));
  }
  
  @Test
  public void testFilter() {
    final Properties props = new Properties();
    props.put("a.foo", "foo");
    props.put("a.bar", "bar");
    props.put("b.foo", "bar");
    final Properties filtered = PropertyUtils.filter("a.", props);
    assertEquals(2, filtered.size());
    assertTrue(filtered.containsKey("a.foo"));
    assertTrue(filtered.containsKey("a.bar"));
    assertFalse(filtered.containsKey("b.foo"));
  }
  
  @Test
  public void testFilterWithDefaults() {
    final Properties props = new Properties();
    props.put("a.foo", "foo");
    props.put("a.bar", "bar");
    props.put("b.foo", "bar");
    final Properties filtered = PropertyUtils.filter("a.", new Properties(props));
    assertEquals(2, filtered.size());
    assertTrue(filtered.containsKey("a.foo"));
    assertTrue(filtered.containsKey("a.bar"));
    assertFalse(filtered.containsKey("b.foo"));
  }
  
  @Test
  public void testFilterWithDefaultsNonStrings() {
    final Properties props = new Properties();
    props.put("a.foo", "foo");
    props.put("a.bar", false);
    props.put("b.foo", "bar");
    final Properties filtered = PropertyUtils.filter("a.", new Properties(props));
    assertEquals(1, filtered.size());
    assertTrue(filtered.containsKey("a.foo"));
    assertFalse(filtered.containsKey("a.bar"));
    assertFalse(filtered.containsKey("b.foo"));
  }
  
  @Test
  public void assertPrivateConstructor() throws Exception {
    Assertions.assertUtilityClassWellDefined(PropertyUtils.class);
  }
}
