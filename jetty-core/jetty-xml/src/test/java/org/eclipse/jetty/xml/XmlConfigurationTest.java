//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.xml;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.lang.reflect.Executable;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.temporal.Temporal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import javax.net.ssl.SSLSocketFactory;

import org.eclipse.jetty.logging.JettyLevel;
import org.eclipse.jetty.logging.JettyLogger;
import org.eclipse.jetty.logging.StdErrAppender;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.annotation.Name;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.component.Environment;
import org.eclipse.jetty.util.resource.FileSystemPool;
import org.eclipse.jetty.util.resource.Resource;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.eclipse.jetty.xml.XmlConfiguration.EXECUTABLE_COMPARATOR;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WorkDirExtension.class)
public class XmlConfigurationTest
{
    private static final String STRING_ARRAY_XML = "<Array type=\"String\"><Item type=\"String\">String1</Item><Item type=\"String\">String2</Item></Array>";
    private static final String INT_ARRAY_XML = "<Array type=\"int\"><Item type=\"int\">1</Item><Item type=\"int\">2</Item></Array>";

    public WorkDir workDir;

    @BeforeEach
    public void beforeEach()
    {
        assertThat(FileSystemPool.INSTANCE.mounts(), empty());
    }

    @AfterEach
    public void afterEach()
    {
        assertThat(FileSystemPool.INSTANCE.mounts(), empty());
    }

    @Test
    public void testMortBay() throws Exception
    {
        URL url = XmlConfigurationTest.class.getResource("mortbay.xml");
        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            Resource resource = resourceFactory.newResource(url);
            assertNotNull(resource);
            XmlConfiguration configuration = new XmlConfiguration(resource);
            configuration.configure();
        }
    }

    public static Stream<Arguments> xmlConfigs()
    {
        return Stream.of(
            Arguments.of("org/eclipse/jetty/xml/configureWithAttr.xml"),
            Arguments.of("org/eclipse/jetty/xml/configureWithElements.xml")
        );
    }

    @ParameterizedTest
    @MethodSource("xmlConfigs")
    public void testPassedObject(String configure) throws Exception
    {
        Map<String, String> properties = new HashMap<>();
        properties.put("whatever", "xxx");
        ExampleConfiguration.VALUE = 77;
        URL url = XmlConfigurationTest.class.getClassLoader().getResource(configure);
        assertNotNull(url);
        XmlConfiguration configuration;
        ExampleConfiguration tc;
        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            configuration = new XmlConfiguration(resourceFactory.newResource(url));
            tc = new ExampleConfiguration("tc");
            configuration.getProperties().putAll(properties);
            configuration.configure(tc);
        }

        assertEquals("SetValue", tc.testObject, "Set String");
        assertEquals(2, tc.testInt, "Set Type");

        assertEquals(18080, tc.propValue);

        assertEquals("PutValue", tc.get("Test"), "Put");
        assertEquals("2", tc.get("TestDft"), "Put dft");
        assertEquals(2, tc.get("TestInt"), "Put type");

        assertEquals("PutValue", tc.get("Trim"), "Trim");
        assertNull(tc.get("Null"), "Null");
        assertNull(tc.get("NullTrim"), "NullTrim");

        assertEquals(1.2345, tc.get("ObjectTrim"), "ObjectTrim");
        assertEquals("-1String", tc.get("Objects"), "Objects");
        assertEquals("-1String", tc.get("ObjectsTrim"), "ObjectsTrim");
        assertEquals("\n    PutValue\n  ", tc.get("String"), "String");
        assertEquals("", tc.get("NullString"), "NullString");
        assertEquals("\n  ", tc.get("WhiteSpace"), "WhiteSpace");
        assertEquals("\n    1.2345\n  ", tc.get("ObjectString"), "ObjectString");
        assertEquals("-1String", tc.get("ObjectsString"), "ObjectsString");
        assertEquals("-1\n  String", tc.get("ObjectsWhiteString"), "ObjectsWhiteString");

        assertEquals(System.getProperty("user.dir") + "/stuff", tc.get("SystemProperty"), "SystemProperty");
        assertEquals(System.getenv("HOME"), tc.get("Env"), "Env");

        assertEquals("xxx", tc.get("Property"), "Property");

        assertEquals("Yes", tc.get("Called"), "Called");

        assertTrue(ExampleConfiguration.called);

        assertEquals("Blah", tc.oa[0], "oa[0]");
        assertEquals("1.2.3.4:5678", tc.oa[1], "oa[1]");
        assertEquals(1.2345, tc.oa[2], "oa[2]");
        assertNull(tc.oa[3], "oa[3]");

        assertEquals(1, tc.ia[0], "ia[0]");
        assertEquals(2, tc.ia[1], "ia[1]");
        assertEquals(3, tc.ia[2], "ia[2]");
        assertEquals(0, tc.ia[3], "ia[3]");

        ExampleConfiguration tc2 = tc.nested;
        assertNotNull(tc2);
        assertEquals(true, tc2.get("Arg"), "Called(bool)");

        assertNull(tc.get("Arg"), "nested config");
        assertEquals(true, tc2.get("Arg"), "nested config");

        assertEquals("Call1", tc2.testObject, "nested config");
        assertEquals(4, tc2.testInt, "nested config");
        assertEquals("http://www.eclipse.com/", tc2.url.toString(), "nested call");

        assertEquals(tc.testField1, 77, "static to field");
        assertEquals(tc.testField2, 2, "field to field");
        assertEquals(ExampleConfiguration.VALUE, 42, "literal to static");

        @SuppressWarnings("unchecked")
        Map<String, String> map = (Map<String, String>)configuration.getIdMap().get("map");
        assertEquals(map.get("key0"), "value0");
        assertEquals(map.get("key1"), "value1");

        @SuppressWarnings("unchecked")
        Map<String, String> concurrentMap = (Map<String, String>)configuration.getIdMap().get("concurrentMap");
        assertThat(concurrentMap, instanceOf(ConcurrentMap.class));
        assertEquals(concurrentMap.get("KEY"), "ITEM");
    }

    @ParameterizedTest
    @MethodSource("xmlConfigs")
    public void testNewObject(String configure) throws Exception
    {
        ExampleConfiguration.VALUE = 71;
        Map<String, String> properties = new HashMap<>();
        properties.put("whatever", "xxx");

        URL url = XmlConfigurationTest.class.getClassLoader().getResource(configure);
        assertNotNull(url);
        AtomicInteger count = new AtomicInteger(0);
        ExampleConfiguration tc;
        try (ResourceFactory.Closeable resourceFactory = ResourceFactory.closeable())
        {
            XmlConfiguration configuration = new XmlConfiguration(resourceFactory.newResource(url))
            {
                @Override
                public void initializeDefaults(Object object)
                {
                    if (object instanceof ExampleConfiguration)
                    {
                        count.incrementAndGet();
                        ((ExampleConfiguration)object).setNested(null);
                        ((ExampleConfiguration)object).setTestString("NEW DEFAULT");
                    }
                }
            };
            configuration.getProperties().putAll(properties);
            tc = (ExampleConfiguration)configuration.configure();
        }

        assertEquals(3, count.get());

        assertEquals("NEW DEFAULT", tc.getTestString());
        assertEquals("nested", tc.getNested().getTestString());
        assertEquals("NEW DEFAULT", tc.getNested().getNested().getTestString());

        assertEquals("SetValue", tc.testObject, "Set String");
        assertEquals(2, tc.testInt, "Set Type");

        assertEquals(18080, tc.propValue);

        assertEquals("PutValue", tc.get("Test"), "Put");
        assertEquals("2", tc.get("TestDft"), "Put dft");
        assertEquals(2, tc.get("TestInt"), "Put type");

        assertEquals("PutValue", tc.get("Trim"), "Trim");
        assertNull(tc.get("Null"), "Null");
        assertNull(tc.get("NullTrim"), "NullTrim");

        assertEquals(1.2345, tc.get("ObjectTrim"), "ObjectTrim");
        assertEquals("-1String", tc.get("Objects"), "Objects");
        assertEquals("-1String", tc.get("ObjectsTrim"), "ObjectsTrim");
        assertEquals("\n    PutValue\n  ", tc.get("String"), "String");
        assertEquals("", tc.get("NullString"), "NullString");
        assertEquals("\n  ", tc.get("WhiteSpace"), "WhiteSpace");
        assertEquals("\n    1.2345\n  ", tc.get("ObjectString"), "ObjectString");
        assertEquals("-1String", tc.get("ObjectsString"), "ObjectsString");
        assertEquals("-1\n  String", tc.get("ObjectsWhiteString"), "ObjectsWhiteString");

        assertEquals(System.getProperty("user.dir") + "/stuff", tc.get("SystemProperty"), "SystemProperty");
        assertEquals("xxx", tc.get("Property"), "Property");

        assertEquals("Yes", tc.get("Called"), "Called");

        assertTrue(ExampleConfiguration.called);

        assertEquals("Blah", tc.oa[0], "oa[0]");
        assertEquals("1.2.3.4:5678", tc.oa[1], "oa[1]");
        assertEquals(1.2345, tc.oa[2], "oa[2]");
        assertNull(tc.oa[3], "oa[3]");

        assertEquals(1, tc.ia[0], "ia[0]");
        assertEquals(2, tc.ia[1], "ia[1]");
        assertEquals(3, tc.ia[2], "ia[2]");
        assertEquals(0, tc.ia[3], "ia[3]");

        ExampleConfiguration tc2 = tc.nested;
        assertNotNull(tc2);
        assertEquals(true, tc2.get("Arg"), "Called(bool)");

        assertNull(tc.get("Arg"), "nested config");
        assertEquals(true, tc2.get("Arg"), "nested config");

        assertEquals("Call1", tc2.testObject, "nested config");
        assertEquals(4, tc2.testInt, "nested config");
        assertEquals("http://www.eclipse.com/", tc2.url.toString(), "nested call");

        assertEquals(71, tc.testField1, "static to field");
        assertEquals(2, tc.testField2, "field to field");
        assertEquals(42, ExampleConfiguration.VALUE, "literal to static");
    }

    public XmlConfiguration asXmlConfiguration(String rawXml) throws IOException, SAXException
    {
        if (!rawXml.contains("!DOCTYPE"))
            rawXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<!DOCTYPE Configure PUBLIC \"-//Jetty//Configure//EN\" \"https://www.eclipse.org/jetty/configure_10_0.dtd\">\n" +
                rawXml;
        return asXmlConfiguration("raw.xml", rawXml);
    }

    public XmlConfiguration asXmlConfiguration(String filename, String rawXml) throws IOException, SAXException
    {
        if (!rawXml.contains("!DOCTYPE"))
            rawXml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n" +
                "<!DOCTYPE Configure PUBLIC \"-//Jetty//Configure//EN\" \"https://www.eclipse.org/jetty/configure_10_0.dtd\">\n" +
                rawXml;
        Path testFile = workDir.getEmptyPathDir().resolve(filename);
        try (BufferedWriter writer = Files.newBufferedWriter(testFile, UTF_8))
        {
            writer.write(rawXml);
        }
        return new XmlConfiguration(ResourceFactory.root().newResource(testFile));
    }

    @Test
    public void testGetClass() throws Exception
    {
        XmlConfiguration configuration = asXmlConfiguration("<Configure class=\"org.eclipse.jetty.xml.ExampleConfiguration\"><Set name=\"Test\"><Get name=\"class\"/></Set></Configure>");
        ExampleConfiguration tc = new ExampleConfiguration();
        configuration.configure(tc);
        assertEquals(ExampleConfiguration.class, tc.testObject);

        configuration =
            asXmlConfiguration("<Configure class=\"org.eclipse.jetty.xml.ExampleConfiguration\"><Set name=\"Test\"><Get class=\"java.lang.String\" name=\"class\"><Get id=\"simple\" name=\"simpleName\"/></Get></Set></Configure>");
        configuration.configure(tc);
        assertEquals(String.class, tc.testObject);
        assertEquals("String", configuration.getIdMap().get("simple"));
    }

    @Test
    public void testStringConfiguration() throws Exception
    {
        XmlConfiguration configuration = asXmlConfiguration("<Configure class=\"org.eclipse.jetty.xml.ExampleConfiguration\"><Set name=\"Test\">SetValue</Set><Set name=\"Test\" type=\"int\">2</Set></Configure>");
        ExampleConfiguration tc = new ExampleConfiguration();
        configuration.configure(tc);
        assertEquals("SetValue", tc.testObject, "Set String 3");
        assertEquals(2, tc.testInt, "Set Type 3");
    }

    @Test
    public void testSetWithProperty() throws Exception
    {
        XmlConfiguration configuration = asXmlConfiguration("<Configure class=\"org.eclipse.jetty.xml.ExampleConfiguration\"><Set name=\"TestString\" property=\"prop\" id=\"test\"/></Configure>");
        configuration.getProperties().put("prop", "This is a property value");
        ExampleConfiguration tc = new ExampleConfiguration();
        tc.setTestString("default");
        configuration.configure(tc);
        assertEquals("This is a property value", tc.getTestString());
        assertEquals(configuration.getIdMap().get("test"), "This is a property value");
    }

    @Test
    public void testSetFieldWithProperty() throws Exception
    {
        XmlConfiguration configuration = asXmlConfiguration("<Configure class=\"org.eclipse.jetty.xml.ExampleConfiguration\"><Set name=\"testField1\" property=\"prop\" id=\"test\"/></Configure>");
        configuration.getProperties().put("prop", "42");
        ExampleConfiguration tc = new ExampleConfiguration();
        tc.testField1 = -1;
        configuration.configure(tc);
        assertEquals(42, tc.testField1);
        assertEquals(configuration.getIdMap().get("test"), "42");
    }

    @Test
    public void testNotSetFieldWithNullProperty() throws Exception
    {
        XmlConfiguration configuration = asXmlConfiguration("<Configure class=\"org.eclipse.jetty.xml.ExampleConfiguration\"><Set name=\"testField1\" property=\"prop\" id=\"test\"/></Configure>");
        configuration.getProperties().remove("prop");
        ExampleConfiguration tc = new ExampleConfiguration();
        tc.testField1 = -1;
        configuration.configure(tc);
        assertEquals(-1, tc.testField1);
        assertNull(configuration.getIdMap().get("test"));
    }

    @Test
    public void testSetWithNullProperty() throws Exception
    {
        XmlConfiguration configuration = asXmlConfiguration("<Configure class=\"org.eclipse.jetty.xml.ExampleConfiguration\"><Set name=\"TestString\" property=\"prop\" id=\"test\"/></Configure>");
        configuration.getProperties().remove("prop");
        ExampleConfiguration tc = new ExampleConfiguration();
        tc.setTestString("default");
        configuration.configure(tc);
        assertEquals("default", tc.getTestString());
        assertNull(configuration.getIdMap().get("test"));
    }

    @Test
    public void testSetWithPropertyAndValue() throws Exception
    {
        XmlConfiguration configuration = asXmlConfiguration("<Configure class=\"org.eclipse.jetty.xml.ExampleConfiguration\"><Set name=\"TestString\" property=\"prop\" id=\"test\">Value</Set></Configure>");
        configuration.getProperties().put("prop", "This is a property value");
        ExampleConfiguration tc = new ExampleConfiguration();
        tc.setTestString("default");
        configuration.configure(tc);
        assertEquals("Value", tc.getTestString());
        assertEquals(configuration.getIdMap().get("test"), "Value");
    }

    @Test
    public void testSetWithNullPropertyAndValue() throws Exception
    {
        XmlConfiguration configuration = asXmlConfiguration("<Configure class=\"org.eclipse.jetty.xml.ExampleConfiguration\"><Set name=\"TestString\" property=\"prop\" id=\"test\">Value</Set></Configure>");
        configuration.getProperties().remove("prop");
        ExampleConfiguration tc = new ExampleConfiguration();
        tc.setTestString("default");
        configuration.configure(tc);
        assertEquals("default", tc.getTestString());
        assertNull(configuration.getIdMap().get("test"));
    }

    @Test
    public void testSetWithWrongNameAndProperty() throws Exception
    {
        XmlConfiguration configuration = asXmlConfiguration("<Configure class=\"org.eclipse.jetty.xml.ExampleConfiguration\"><Set name=\"WrongName\" property=\"prop\" id=\"test\"/></Configure>");
        configuration.getProperties().put("prop", "This is a property value");
        ExampleConfiguration tc = new ExampleConfiguration();
        tc.setTestString("default");

        NoSuchMethodException e = assertThrows(NoSuchMethodException.class, () -> configuration.configure(tc));
        assertThat(e.getMessage(), containsString("setWrongName"));
        assertEquals("default", tc.getTestString());
    }
    
    @Test
    public void testSetWithWrongNameAndNullProperty() throws Exception
    {
        XmlConfiguration configuration = asXmlConfiguration("<Configure class=\"org.eclipse.jetty.xml.ExampleConfiguration\"><Set name=\"WrongName\" property=\"prop\" id=\"test\"/></Configure>");
        configuration.getProperties().remove("prop");
        ExampleConfiguration tc = new ExampleConfiguration();
        tc.setTestString("default");

        NoSuchMethodException e = assertThrows(NoSuchMethodException.class, () -> configuration.configure(tc));
        assertThat(e.getMessage(), containsString("setWrongName"));
        assertThat(e.getSuppressed()[0], instanceOf(NoSuchFieldException.class));
        assertEquals("default", tc.getTestString());
    }

    @Test
    public void testMeaningfullSetException() throws Exception
    {
        XmlConfiguration configuration = asXmlConfiguration("<Configure class=\"org.eclipse.jetty.xml.ExampleConfiguration\"><Set name=\"PropertyTest\"><Property name=\"null\"/></Set></Configure>");
        ExampleConfiguration tc = new ExampleConfiguration();

        NoSuchMethodException e = assertThrows(NoSuchMethodException.class, () -> configuration.configure(tc));

        assertThat(e.getMessage(), containsString("Found setters for int"));
    }

    @Test
    public void testListConstructorArg() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration("<Configure class=\"org.eclipse.jetty.xml.ExampleConfiguration\">" +
            "<Set name=\"constructorArgTestClass\"><New class=\"org.eclipse.jetty.xml.ConstructorArgTestClass\"><Arg type=\"List\">" +
            STRING_ARRAY_XML + "</Arg></New></Set></Configure>");
        ExampleConfiguration tc = new ExampleConfiguration();
        assertThat("tc.getList() returns null as it's not configured yet", tc.getList(), is(nullValue()));
        xmlConfiguration.configure(tc);
        assertThat("tc.getList() returns not null", tc.getList(), not(nullValue()));
        assertThat("tc.getList() has two entries as specified in the xml", tc.getList().size(), is(2));
    }

    @Test
    public void testTwoArgumentListConstructorArg() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration("<Configure class=\"org.eclipse.jetty.xml.ExampleConfiguration\">" +
            "<Set name=\"constructorArgTestClass\"><New class=\"org.eclipse.jetty.xml.ConstructorArgTestClass\">" +
            "<Arg type=\"List\">" + STRING_ARRAY_XML + "</Arg>" +
            "<Arg type=\"List\">" + STRING_ARRAY_XML + "</Arg>" +
            "</New></Set></Configure>");
        ExampleConfiguration tc = new ExampleConfiguration();
        assertThat("tc.getList() returns null as it's not configured yet", tc.getList(), is(nullValue()));
        xmlConfiguration.configure(tc);
        assertThat("tc.getList() returns not null", tc.getList(), not(nullValue()));
        assertThat("tc.getList() has two entries as specified in the xml", tc.getList().size(), is(2));
    }

    @Test
    public void testListNotContainingArray() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration("<Configure class=\"org.eclipse.jetty.xml.ExampleConfiguration\">" +
            "<New class=\"org.eclipse.jetty.xml.ConstructorArgTestClass\"><Arg type=\"List\">Some String</Arg></New></Configure>");
        ExampleConfiguration tc = new ExampleConfiguration();

        assertThrows(IllegalArgumentException.class, () -> xmlConfiguration.configure(tc));
    }

    @Test
    public void testSetConstructorArg() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration("<Configure class=\"org.eclipse.jetty.xml.ExampleConfiguration\">" +
            "<Set name=\"constructorArgTestClass\"><New class=\"org.eclipse.jetty.xml.ConstructorArgTestClass\"><Arg type=\"Set\">" +
            STRING_ARRAY_XML + "</Arg></New></Set></Configure>");
        ExampleConfiguration tc = new ExampleConfiguration();
        assertThat("tc.getList() returns null as it's not configured yet", tc.getSet(), is(nullValue()));
        xmlConfiguration.configure(tc);
        assertThat("tc.getList() returns not null", tc.getSet(), not(nullValue()));
        assertThat("tc.getList() has two entries as specified in the xml", tc.getSet().size(), is(2));
    }

    @Test
    public void testSetNotContainingArray() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration("<Configure class=\"org.eclipse.jetty.xml.ExampleConfiguration\">" +
            "<New class=\"org.eclipse.jetty.xml.ConstructorArgTestClass\"><Arg type=\"Set\">Some String</Arg></New></Configure>");
        ExampleConfiguration tc = new ExampleConfiguration();
        assertThrows(IllegalArgumentException.class, () -> xmlConfiguration.configure(tc));
    }

    @Test
    public void testListSetterWithStringArray() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration("<Configure class=\"org.eclipse.jetty.xml.ExampleConfiguration\"><Set name=\"List\">" +
            STRING_ARRAY_XML + "</Set></Configure>");
        ExampleConfiguration tc = new ExampleConfiguration();
        assertThat("tc.getList() returns null as it's not configured yet", tc.getList(), is(nullValue()));
        xmlConfiguration.configure(tc);
        assertThat("tc.getList() has two entries as specified in the xml", tc.getList().size(), is(2));
    }

    @Test
    public void testListSetterWithPrimitiveArray() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration("<Configure class=\"org.eclipse.jetty.xml.ExampleConfiguration\"><Set name=\"List\">" +
            INT_ARRAY_XML + "</Set></Configure>");
        ExampleConfiguration tc = new ExampleConfiguration();
        assertThat("tc.getList() returns null as it's not configured yet", tc.getList(), is(nullValue()));
        xmlConfiguration.configure(tc);
        assertThat("tc.getList() has two entries as specified in the xml", tc.getList().size(), is(2));
    }

    @Test
    public void testNotSupportedLinkedListSetter() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration("<Configure class=\"org.eclipse.jetty.xml.ExampleConfiguration\"><Set name=\"LinkedList\">" +
            INT_ARRAY_XML + "</Set></Configure>");
        ExampleConfiguration tc = new ExampleConfiguration();
        assertThat("tc.getSet() returns null as it's not configured yet", tc.getList(), is(nullValue()));
        assertThrows(NoSuchMethodException.class, () -> xmlConfiguration.configure(tc));
    }

    @Test
    public void testArrayListSetter() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration("<Configure class=\"org.eclipse.jetty.xml.ExampleConfiguration\"><Set name=\"ArrayList\">" +
            INT_ARRAY_XML + "</Set></Configure>");
        ExampleConfiguration tc = new ExampleConfiguration();
        assertThat("tc.getSet() returns null as it's not configured yet", tc.getList(), is(nullValue()));
        xmlConfiguration.configure(tc);
        assertThat("tc.getSet() has two entries as specified in the xml", tc.getList().size(), is(2));
    }

    @Test
    public void testSetSetter() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration("<Configure class=\"org.eclipse.jetty.xml.ExampleConfiguration\"><Set name=\"Set\">" +
            STRING_ARRAY_XML + "</Set></Configure>");
        ExampleConfiguration tc = new ExampleConfiguration();
        assertThat("tc.getSet() returns null as it's not configured yet", tc.getSet(), is(nullValue()));
        xmlConfiguration.configure(tc);
        assertThat("tc.getSet() has two entries as specified in the xml", tc.getSet().size(), is(2));
    }

    @Test
    public void testSetSetterWithPrimitiveArray() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration("<Configure class=\"org.eclipse.jetty.xml.ExampleConfiguration\"><Set name=\"Set\">" +
            INT_ARRAY_XML + "</Set></Configure>");
        ExampleConfiguration tc = new ExampleConfiguration();
        assertThat("tc.getSet() returns null as it's not configured yet", tc.getSet(), is(nullValue()));
        xmlConfiguration.configure(tc);
        assertThat("tc.getSet() has two entries as specified in the xml", tc.getSet().size(), is(2));
    }

    @Test
    public void testMap() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.ExampleConfiguration\">" +
                "    <Set name=\"map\">" +
                "        <Map>" +
                "            <Entry>" +
                "                <Item>key1</Item>" +
                "                <Item>value1</Item>" +
                "            </Entry>" +
                "            <Entry>" +
                "                <Item>key2</Item>" +
                "                <Item>value2</Item>" +
                "            </Entry>" +
                "        </Map>" +
                "    </Set>" +
                "</Configure>");
        ExampleConfiguration tc = new ExampleConfiguration();
        assertNull(tc.map, "tc.map is null as it's not configured yet");
        xmlConfiguration.configure(tc);
        assertEquals(2, tc.map.size(), "tc.map is has two entries as specified in the XML");
    }

    @Test
    public void testConstructorNamedInjection() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.AnnotatedTestConfiguration\">" +
                "  <Arg>arg1</Arg>  " +
                "  <Arg>arg2</Arg>  " +
                "  <Arg>arg3</Arg>  " +
                "</Configure>");

        AnnotatedTestConfiguration atc = (AnnotatedTestConfiguration)xmlConfiguration.configure();

        assertEquals("arg1", atc.getFirst(), "first parameter not wired correctly");
        assertEquals("arg2", atc.getSecond(), "second parameter not wired correctly");
        assertEquals("arg3", atc.getThird(), "third parameter not wired correctly");
    }

    @Test
    public void testConstructorNamedInjectionOrdered() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.AnnotatedTestConfiguration\">" +
                "  <Arg name=\"first\">arg1</Arg>  " +
                "  <Arg name=\"second\">arg2</Arg>  " +
                "  <Arg name=\"third\">arg3</Arg>  " +
                "</Configure>");

        AnnotatedTestConfiguration atc = (AnnotatedTestConfiguration)xmlConfiguration.configure();

        assertEquals("arg1", atc.getFirst(), "first parameter not wired correctly");
        assertEquals("arg2", atc.getSecond(), "second parameter not wired correctly");
        assertEquals("arg3", atc.getThird(), "third parameter not wired correctly");
    }

    @Test
    public void testConstructorNamedInjectionUnOrdered() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.AnnotatedTestConfiguration\">" +
                "  <Arg name=\"first\">arg1</Arg>  " +
                "  <Arg name=\"third\">arg3</Arg>  " +
                "  <Arg name=\"second\">arg2</Arg>  " +
                "</Configure>");

        AnnotatedTestConfiguration atc = (AnnotatedTestConfiguration)xmlConfiguration.configure();

        assertEquals("arg1", atc.getFirst(), "first parameter not wired correctly");
        assertEquals("arg2", atc.getSecond(), "second parameter not wired correctly");
        assertEquals("arg3", atc.getThird(), "third parameter not wired correctly");
    }

    @Test
    public void testConstructorNamedInjectionOrderedMixed() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.AnnotatedTestConfiguration\">" +
                "  <Arg name=\"first\">arg1</Arg>  " +
                "  <Arg>arg2</Arg>  " +
                "  <Arg name=\"third\">arg3</Arg>  " +
                "</Configure>");

        AnnotatedTestConfiguration atc = (AnnotatedTestConfiguration)xmlConfiguration.configure();

        assertEquals("arg1", atc.getFirst(), "first parameter not wired correctly");
        assertEquals("arg2", atc.getSecond(), "second parameter not wired correctly");
        assertEquals("arg3", atc.getThird(), "third parameter not wired correctly");
    }

    @Test
    public void testConstructorNamedInjectionUnorderedMixed() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.AnnotatedTestConfiguration\">" +
                "  <Arg name=\"third\">arg3</Arg>  " +
                "  <Arg>arg2</Arg>  " +
                "  <Arg name=\"first\">arg1</Arg>  " +
                "</Configure>");

        AnnotatedTestConfiguration atc = (AnnotatedTestConfiguration)xmlConfiguration.configure();

        assertEquals("arg1", atc.getFirst(), "first parameter not wired correctly");
        assertEquals("arg2", atc.getSecond(), "second parameter not wired correctly");
        assertEquals("arg3", atc.getThird(), "third parameter not wired correctly");
    }

    @Test
    public void testNestedConstructorNamedInjection() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.AnnotatedTestConfiguration\">" +
                "  <Arg>arg1</Arg>  " +
                "  <Arg>arg2</Arg>  " +
                "  <Arg>arg3</Arg>  " +
                "  <Set name=\"nested\">  " +
                "    <New class=\"org.eclipse.jetty.xml.AnnotatedTestConfiguration\">" +
                "      <Arg>arg1</Arg>  " +
                "      <Arg>arg2</Arg>  " +
                "      <Arg>arg3</Arg>  " +
                "    </New>" +
                "  </Set>" +
                "</Configure>");

        AnnotatedTestConfiguration atc = (AnnotatedTestConfiguration)xmlConfiguration.configure();

        assertEquals("arg1", atc.getFirst(), "first parameter not wired correctly");
        assertEquals("arg2", atc.getSecond(), "second parameter not wired correctly");
        assertEquals("arg3", atc.getThird(), "third parameter not wired correctly");
        assertEquals("arg1", atc.getNested().getFirst(), "nested first parameter not wired correctly");
        assertEquals("arg2", atc.getNested().getSecond(), "nested second parameter not wired correctly");
        assertEquals("arg3", atc.getNested().getThird(), "nested third parameter not wired correctly");
    }

    @Test
    public void testNestedConstructorNamedInjectionOrdered() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.AnnotatedTestConfiguration\">" +
                "  <Arg name=\"first\">arg1</Arg>  " +
                "  <Arg name=\"second\">arg2</Arg>  " +
                "  <Arg name=\"third\">arg3</Arg>  " +
                "  <Set name=\"nested\">  " +
                "    <New class=\"org.eclipse.jetty.xml.AnnotatedTestConfiguration\">" +
                "      <Arg name=\"first\">arg1</Arg>  " +
                "      <Arg name=\"second\">arg2</Arg>  " +
                "      <Arg name=\"third\">arg3</Arg>  " +
                "    </New>" +
                "  </Set>" +
                "</Configure>");

        AnnotatedTestConfiguration atc = (AnnotatedTestConfiguration)xmlConfiguration.configure();

        assertEquals("arg1", atc.getFirst(), "first parameter not wired correctly");
        assertEquals("arg2", atc.getSecond(), "second parameter not wired correctly");
        assertEquals("arg3", atc.getThird(), "third parameter not wired correctly");
        assertEquals("arg1", atc.getNested().getFirst(), "nested first parameter not wired correctly");
        assertEquals("arg2", atc.getNested().getSecond(), "nested second parameter not wired correctly");
        assertEquals("arg3", atc.getNested().getThird(), "nested third parameter not wired correctly");
    }

    private static class TestOrder
    {
        public void call()
        {
        }

        public void call(int o)
        {
        }

        public void call(Aaa aaa)
        {
        }

        public void call(Bbb aaa)
        {
        }

        public void call(Ccc aaa)
        {
        }

        public void call(Abc abc)
        {
        }

        public void call(Object o)
        {
        }

        public void call(String s)
        {
        }

        public void call(String... ss)
        {
        }

        public void call(String s, String... ss)
        {
        }
    }

    @RepeatedTest(100)
    public void testMethodOrdering() throws Exception
    {
        List<Method> methods = Arrays.stream(TestOrder.class.getMethods()).filter(m -> "call".equals(m.getName())).collect(Collectors.toList());
        Collections.shuffle(methods);
        methods.sort(EXECUTABLE_COMPARATOR);
        assertThat(methods, Matchers.contains(
            TestOrder.class.getMethod("call"),
            TestOrder.class.getMethod("call", int.class),
            TestOrder.class.getMethod("call", Abc.class),
            TestOrder.class.getMethod("call", Aaa.class),
            TestOrder.class.getMethod("call", String.class),
            TestOrder.class.getMethod("call", Bbb.class),
            TestOrder.class.getMethod("call", Ccc.class),
            TestOrder.class.getMethod("call", Object.class),
            TestOrder.class.getMethod("call", String[].class),
            TestOrder.class.getMethod("call", String.class, String[].class)
        ));
    }

    @Test
    public void testOverloadedCall() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.AnnotatedTestConfiguration\">" +
                "  <Call name=\"call\">" +
                "    <Arg type=\"int\">1</Arg>" +
                "  </Call>" +
                "  <Call name=\"call\">" +
                "    <Arg>2</Arg>" +
                "  </Call>" +
                "  <Call name=\"call\">" +
                "    <Arg type=\"Long\">3</Arg>" +
                "  </Call>" +
                "</Configure>");

        AnnotatedTestConfiguration atc = (AnnotatedTestConfiguration)xmlConfiguration.configure();

        assertEquals("1", atc.getFirst());
        assertEquals("2", atc.getSecond());
        assertEquals("3", atc.getThird());
    }

    @Test
    public void testNestedConstructorNamedInjectionUnOrdered() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.AnnotatedTestConfiguration\">" +
                "  <Arg name=\"first\">arg1</Arg>  " +
                "  <Arg name=\"third\">arg3</Arg>  " +
                "  <Arg name=\"second\">arg2</Arg>  " +
                "  <Set name=\"nested\">  " +
                "    <New class=\"org.eclipse.jetty.xml.AnnotatedTestConfiguration\">" +
                "      <Arg name=\"first\">arg1</Arg>  " +
                "      <Arg name=\"third\">arg3</Arg>  " +
                "      <Arg name=\"second\">arg2</Arg>  " +
                "    </New>" +
                "  </Set>" +
                "</Configure>");

        AnnotatedTestConfiguration atc = (AnnotatedTestConfiguration)xmlConfiguration.configure();

        assertEquals("arg1", atc.getFirst(), "first parameter not wired correctly");
        assertEquals("arg2", atc.getSecond(), "second parameter not wired correctly");
        assertEquals("arg3", atc.getThird(), "third parameter not wired correctly");
        assertEquals("arg1", atc.getNested().getFirst(), "nested first parameter not wired correctly");
        assertEquals("arg2", atc.getNested().getSecond(), "nested second parameter not wired correctly");
        assertEquals("arg3", atc.getNested().getThird(), "nested third parameter not wired correctly");
    }

    @Test
    public void testNestedConstructorNamedInjectionOrderedMixed() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.AnnotatedTestConfiguration\">" +
                "  <Arg name=\"first\">arg1</Arg>  " +
                "  <Arg>arg2</Arg>  " +
                "  <Arg name=\"third\">arg3</Arg>  " +
                "  <Set name=\"nested\">  " +
                "    <New class=\"org.eclipse.jetty.xml.AnnotatedTestConfiguration\">" +
                "      <Arg name=\"first\">arg1</Arg>  " +
                "      <Arg>arg2</Arg>  " +
                "      <Arg name=\"third\">arg3</Arg>  " +
                "    </New>" +
                "  </Set>" +
                "</Configure>");

        AnnotatedTestConfiguration atc = (AnnotatedTestConfiguration)xmlConfiguration.configure();

        assertEquals("arg1", atc.getFirst(), "first parameter not wired correctly");
        assertEquals("arg2", atc.getSecond(), "second parameter not wired correctly");
        assertEquals("arg3", atc.getThird(), "third parameter not wired correctly");
        assertEquals("arg1", atc.getNested().getFirst(), "nested first parameter not wired correctly");
        assertEquals("arg2", atc.getNested().getSecond(), "nested second parameter not wired correctly");
        assertEquals("arg3", atc.getNested().getThird(), "nested third parameter not wired correctly");
    }

    @Test
    public void testCallNamedInjection() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.AnnotatedTestConfiguration\">" +
                " <Call name=\"setAll\">" +
                "  <Arg>arg1</Arg>  " +
                "  <Arg>arg2</Arg>  " +
                "  <Arg>arg3</Arg>  " +
                " </Call>" +
                "</Configure>");

        AnnotatedTestConfiguration atc = (AnnotatedTestConfiguration)xmlConfiguration.configure();

        assertEquals("arg1", atc.getFirst(), "first parameter not wired correctly");
        assertEquals("arg2", atc.getSecond(), "second parameter not wired correctly");
        assertEquals("arg3", atc.getThird(), "third parameter not wired correctly");
    }

    @Test
    public void testCallNamedInjectionOrdered() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.AnnotatedTestConfiguration\">" +
                " <Call name=\"setAll\">" +
                "  <Arg name=\"first\">arg1</Arg>  " +
                "  <Arg name=\"second\">arg2</Arg>  " +
                "  <Arg name=\"third\">arg3</Arg>  " +
                " </Call>" +
                "</Configure>");

        AnnotatedTestConfiguration atc = (AnnotatedTestConfiguration)xmlConfiguration.configure();

        assertEquals("arg1", atc.getFirst(), "first parameter not wired correctly");
        assertEquals("arg2", atc.getSecond(), "second parameter not wired correctly");
        assertEquals("arg3", atc.getThird(), "third parameter not wired correctly");
    }

    @Test
    public void testCallNamedInjectionUnOrdered() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.AnnotatedTestConfiguration\">" +
                " <Call name=\"setAll\">" +
                "  <Arg name=\"first\">arg1</Arg>  " +
                "  <Arg name=\"third\">arg3</Arg>  " +
                "  <Arg name=\"second\">arg2</Arg>  " +
                " </Call>" +
                "</Configure>");

        AnnotatedTestConfiguration atc = (AnnotatedTestConfiguration)xmlConfiguration.configure();

        assertEquals("arg1", atc.getFirst(), "first parameter not wired correctly");
        assertEquals("arg2", atc.getSecond(), "second parameter not wired correctly");
        assertEquals("arg3", atc.getThird(), "third parameter not wired correctly");
    }

    @Test
    public void testCallNamedInjectionOrderedMixed() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.AnnotatedTestConfiguration\">" +
                " <Call name=\"setAll\">" +
                "  <Arg name=\"first\">arg1</Arg>  " +
                "  <Arg>arg2</Arg>  " +
                "  <Arg name=\"third\">arg3</Arg>  " +
                " </Call>" +
                "</Configure>");

        AnnotatedTestConfiguration atc = (AnnotatedTestConfiguration)xmlConfiguration.configure();

        assertEquals("arg1", atc.getFirst(), "first parameter not wired correctly");
        assertEquals("arg2", atc.getSecond(), "second parameter not wired correctly");
        assertEquals("arg3", atc.getThird(), "third parameter not wired correctly");
    }

    @Test
    public void testCallNamedInjectionUnorderedMixed() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.AnnotatedTestConfiguration\">" +
                " <Call name=\"setAll\">" +
                "  <Arg name=\"third\">arg3</Arg>  " +
                "  <Arg>arg2</Arg>  " +
                "  <Arg name=\"first\">arg1</Arg>  " +
                " </Call>" +
                "</Configure>");

        AnnotatedTestConfiguration atc = (AnnotatedTestConfiguration)xmlConfiguration.configure();

        assertEquals("arg1", atc.getFirst(), "first parameter not wired correctly");
        assertEquals("arg2", atc.getSecond(), "second parameter not wired correctly");
        assertEquals("arg3", atc.getThird(), "third parameter not wired correctly");
    }

    @Test
    public void testCallVarArgs() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.AnnotatedTestConfiguration\">" +
                " <Call name=\"setVarArgs\">" +
                "  <Arg>one</Arg>  " +
                "  <Arg><Array type=\"String\"><Item type=\"String\">two</Item><Item type=\"String\">three</Item></Array></Arg>  " +
                " </Call>" +
                "</Configure>");

        AnnotatedTestConfiguration atc = (AnnotatedTestConfiguration)xmlConfiguration.configure();

        assertEquals("one", atc.getFirst(), "first parameter not wired correctly");
        assertEquals("two", atc.getSecond(), "second parameter not wired correctly");
        assertEquals("three", atc.getThird(), "third parameter not wired correctly");
    }

    @Test
    public void testCallMissingVarArgs() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.AnnotatedTestConfiguration\">" +
                "  <Arg name=\"first\">arg1</Arg>  " +
                "  <Arg name=\"second\">arg2</Arg>  " +
                "  <Arg name=\"third\">arg3</Arg>  " +
                " <Call name=\"setVarArgs\">" +
                "  <Arg>one</Arg>" +
                " </Call>" +
                "</Configure>");

        AnnotatedTestConfiguration atc = (AnnotatedTestConfiguration)xmlConfiguration.configure();

        assertEquals("one", atc.getFirst(), "first parameter not wired correctly");
        assertNull(atc.getSecond());
        assertNull(atc.getThird());
    }

    public static List<String> typeTestData()
    {
        return Arrays.asList(
            "byte",
            "int",
            "short",
            "long",
            "float",
            "double",
            "Byte",
            "Integer",
            "Short",
            "Long",
            "Float",
            "Double");
    }

    @ParameterizedTest
    @MethodSource("typeTestData")
    public void testCallNumberConversion(String type) throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.ExampleConfiguration\">" +
                " <Call name=\"setNumber\">" +
                "  <Arg type=\"" + type + "\">42</Arg>" +
                " </Call>" +
                "</Configure>");

        ExampleConfiguration tc = (ExampleConfiguration)xmlConfiguration.configure();
        assertEquals(42.0D, tc.number);
    }

    @Test
    public void testArgumentsGetIgnoredMissingDTD() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.AnnotatedTestConfiguration\">" +
                "  <Arg>arg1</Arg>  " +
                "  <Arg>arg2</Arg>  " +
                "  <Arg>arg3</Arg>  " +
                "  <Set name=\"nested\">  " +
                "    <New class=\"org.eclipse.jetty.xml.AnnotatedTestConfiguration\">\n" +
                "      <Arg>arg1</Arg>\n" +
                "      <Arg>arg2</Arg>\n" +
                "      <Arg>arg3</Arg>\n" +
                "    </New>" +
                "  </Set>" +
                "</Configure>");

        AnnotatedTestConfiguration atc = (AnnotatedTestConfiguration)xmlConfiguration.configure();

        assertEquals("arg1", atc.getFirst(), "first parameter not wired correctly");
        assertEquals("arg2", atc.getSecond(), "second parameter not wired correctly");
        assertEquals("arg3", atc.getThird(), "third parameter not wired correctly");
        assertEquals("arg1", atc.getNested().getFirst(), "nested first parameter not wired correctly");
        assertEquals("arg2", atc.getNested().getSecond(), "nested second parameter not wired correctly");
        assertEquals("arg3", atc.getNested().getThird(), "nested third parameter not wired correctly");
    }

    @Test
    public void testSetGetIgnoredMissingDTD() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.DefaultTestConfiguration\">" +
                "  <Set name=\"first\">arg1</Set>  " +
                "  <Set name=\"second\">arg2</Set>  " +
                "  <Set name=\"third\">arg3</Set>  " +
                "  <Set name=\"nested\">  " +
                "    <New class=\"org.eclipse.jetty.xml.DefaultTestConfiguration\">\n" +
                "      <Set name=\"first\">arg1</Set>  " +
                "      <Set name=\"second\">arg2</Set>  " +
                "      <Set name=\"third\">arg3</Set>  " +
                "    </New>" +
                "  </Set>" +
                "</Configure>");

        DefaultTestConfiguration atc = (DefaultTestConfiguration)xmlConfiguration.configure();

        assertEquals("arg1", atc.getFirst(), "first parameter not wired correctly");
        assertEquals("arg2", atc.getSecond(), "second parameter not wired correctly");
        assertEquals("arg3", atc.getThird(), "third parameter not wired correctly");
        assertEquals("arg1", atc.getNested().getFirst(), "nested first parameter not wired correctly");
        assertEquals("arg2", atc.getNested().getSecond(), "nested second parameter not wired correctly");
        assertEquals("arg3", atc.getNested().getThird(), "nested third parameter not wired correctly");
    }

    @Test
    public void testNestedConstructorNamedInjectionUnorderedMixed() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.AnnotatedTestConfiguration\">" +
                "  <Arg name=\"third\">arg3</Arg>  " +
                "  <Arg>arg2</Arg>  " +
                "  <Arg name=\"first\">arg1</Arg>  " +
                "  <Set name=\"nested\">  " +
                "    <New class=\"org.eclipse.jetty.xml.AnnotatedTestConfiguration\">" +
                "      <Arg name=\"third\">arg3</Arg>  " +
                "      <Arg>arg2</Arg>  " +
                "      <Arg name=\"first\">arg1</Arg>  " +
                "    </New>" +
                "  </Set>" +
                "</Configure>");

        AnnotatedTestConfiguration atc = (AnnotatedTestConfiguration)xmlConfiguration.configure();

        assertEquals("arg1", atc.getFirst(), "first parameter not wired correctly");
        assertEquals("arg2", atc.getSecond(), "second parameter not wired correctly");
        assertEquals("arg3", atc.getThird(), "third parameter not wired correctly");
        assertEquals("arg1", atc.getNested().getFirst(), "nested first parameter not wired correctly");
        assertEquals("arg2", atc.getNested().getSecond(), "nested second parameter not wired correctly");
        assertEquals("arg3", atc.getNested().getThird(), "nested third parameter not wired correctly");
    }

    public static class NativeHolder
    {
        public boolean _boolean;
        public int _integer;
        public float _float;

        public boolean getBoolean()
        {
            return _boolean;
        }

        public void setBoolean(boolean value)
        {
            this._boolean = value;
        }

        public int getInteger()
        {
            return _integer;
        }

        public void setInteger(int integer)
        {
            _integer = integer;
        }

        public float getFloat()
        {
            return _float;
        }

        public void setFloat(float f)
        {
            _float = f;
        }
    }

    @Test
    public void testSetBooleanTrue() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.XmlConfigurationTest$NativeHolder\">" +
                "  <Set name=\"boolean\">true</Set>" +
                "</Configure>");

        NativeHolder bh = (NativeHolder)xmlConfiguration.configure();
        assertTrue(bh.getBoolean());
    }

    @Test
    public void testSetBooleanFalse() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.XmlConfigurationTest$NativeHolder\">" +
                "  <Set name=\"boolean\">false</Set>" +
                "</Configure>");

        NativeHolder bh = (NativeHolder)xmlConfiguration.configure();
        assertFalse(bh.getBoolean());
    }

    @Test
    public void testSetBadBoolean() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.XmlConfigurationTest$NativeHolder\">" +
                "  <Set name=\"boolean\">tru</Set>" +
                "</Configure>");

        //Any string other than "true" (case insensitive) will be false
        //according to Boolean constructor.
        NativeHolder bh = (NativeHolder)xmlConfiguration.configure();
        assertFalse(bh.getBoolean(), "boolean['tru']");
    }

    @Test
    public void testSetBadInteger() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.XmlConfigurationTest$NativeHolder\">" +
                "  <Set name=\"integer\">bad</Set>" +
                "</Configure>");

        assertThrows(InvocationTargetException.class, xmlConfiguration::configure);
    }

    @Test
    public void testSetBadExtraInteger() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.XmlConfigurationTest$NativeHolder\">" +
                "  <Set name=\"integer\">100 bas</Set>" +
                "</Configure>");

        assertThrows(InvocationTargetException.class, xmlConfiguration::configure);
    }

    @Test
    public void testSetTrimmedSetterInt() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.XmlConfigurationTest$NativeHolder\">" +
                "  <Set name=\"integer\">" +
                "    42  " +
                "  </Set>" +
                "</Configure>");

        NativeHolder holder = (NativeHolder)xmlConfiguration.configure();
        assertThat(holder._integer, is(42));
    }

    @Test
    public void testSetTrimmedIntSetterWithType() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.XmlConfigurationTest$NativeHolder\">" +
                "  <Set name=\"integer\" type=\"int\">" +
                "    42  " +
                "  </Set>" +
                "</Configure>");

        NativeHolder holder = (NativeHolder)xmlConfiguration.configure();
        assertThat(holder._integer, is(42));
    }

    @Test
    public void testSetTrimmedFieldInt() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.XmlConfigurationTest$NativeHolder\">" +
                "  <Set name=\"_integer\">" +
                "    42  " +
                "  </Set>" +
                "</Configure>");

        NativeHolder holder = (NativeHolder)xmlConfiguration.configure();
        assertThat(holder._integer, is(42));
    }

    @Test
    public void testSetTrimmedIntFieldWithType() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.XmlConfigurationTest$NativeHolder\">" +
                "  <Set name=\"integer\" type=\"int\">" +
                "    42  " +
                "  </Set>" +
                "</Configure>");

        NativeHolder holder = (NativeHolder)xmlConfiguration.configure();
        assertThat(holder._integer, is(42));
    }

    @Test
    public void testSetBadFloatInteger() throws Exception
    {
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.XmlConfigurationTest$NativeHolder\">" +
                "  <Set name=\"integer\">1.5</Set>" +
                "</Configure>");

        assertThrows(InvocationTargetException.class, xmlConfiguration::configure);
    }

    @Test
    public void testWithMultiplePropertyNamesWithNoPropertyThenDefaultIsChosen() throws Exception
    {
        // No properties
        String defolt = "baz";
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.DefaultTestConfiguration\">" +
                "  <Set name=\"first\"><Property name=\"wibble\" deprecated=\"foo,bar\" default=\"" + defolt + "\"/></Set>  " +
                "</Configure>");
        DefaultTestConfiguration config = (DefaultTestConfiguration)xmlConfiguration.configure();
        assertEquals(defolt, config.getFirst());
    }

    @Test
    public void testWithMultiplePropertyNamesWithFirstPropertyThenFirstIsChosen() throws Exception
    {
        String name = "foo";
        String value = "foo";
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.DefaultTestConfiguration\">" +
                "  <Set name=\"first\"><Property name=\"" + name + "\" deprecated=\"other,bar\" default=\"baz\"/></Set>  " +
                "</Configure>");
        xmlConfiguration.getProperties().put(name, value);
        DefaultTestConfiguration config = (DefaultTestConfiguration)xmlConfiguration.configure();
        assertEquals(value, config.getFirst());
    }

    @Test
    public void testWithMultiplePropertyNamesWithSecondPropertyThenSecondIsChosen() throws Exception
    {
        String name = "bar";
        String value = "bar";
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.DefaultTestConfiguration\">" +
                "  <Set name=\"first\"><Property name=\"foo\" deprecated=\"" + name + "\" default=\"baz\"/></Set>  " +
                "</Configure>");
        xmlConfiguration.getProperties().put(name, value);
        DefaultTestConfiguration config = (DefaultTestConfiguration)xmlConfiguration.configure();
        assertEquals(value, config.getFirst());
    }

    @Test
    public void testWithMultiplePropertyNamesWithDeprecatedThenThirdIsChosen() throws Exception
    {
        String name = "bar";
        String value = "bar";
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.DefaultTestConfiguration\">" +
                "  <Set name=\"first\"><Property name=\"foo\" deprecated=\"other," + name + "\" default=\"baz\"/></Set>  " +
                "</Configure>");
        xmlConfiguration.getProperties().put(name, value);
        DefaultTestConfiguration config = (DefaultTestConfiguration)xmlConfiguration.configure();
        assertEquals(value, config.getFirst());
    }

    @Test
    public void testWithMultiplePropertyNameElementsWithDeprecatedThenThirdIsChosen() throws Exception
    {
        String name = "bar";
        String value = "bar";
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.DefaultTestConfiguration\">" +
                "  <Set name=\"first\">" +
                "  <Property>  " +
                "    <Name>foo</Name>" +
                "    <Deprecated>foo</Deprecated>" +
                "    <Deprecated>" + name + "</Deprecated>" +
                "    <Default>baz</Default>" +
                "  </Property>  " +
                "  </Set>  " +
                "</Configure>");
        xmlConfiguration.getProperties().put(name, value);
        DefaultTestConfiguration config = (DefaultTestConfiguration)xmlConfiguration.configure();
        assertEquals(value, config.getFirst());
    }

    @Test
    public void testPropertyNotFoundWithPropertyInDefaultValue() throws Exception
    {
        String name = "bar";
        String value = "bar";
        String defaultValue = "_<Property name=\"bar\"/>_<Property name=\"bar\"/>_";
        String expectedValue = "_" + value + "_" + value + "_";
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.DefaultTestConfiguration\">" +
                "  <Set name=\"first\">" +
                "    <Property>" +
                "      <Name>not_found</Name>" +
                "      <Default>" + defaultValue + "</Default>" +
                "    </Property>" +
                "  </Set>  " +
                "</Configure>");
        xmlConfiguration.getProperties().put(name, value);
        DefaultTestConfiguration config = (DefaultTestConfiguration)xmlConfiguration.configure();
        assertEquals(expectedValue, config.getFirst());
    }

    @Test
    public void testPropertyNotFoundWithPropertyInDefaultValueNotFoundWithDefault() throws Exception
    {
        String value = "bar";
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.DefaultTestConfiguration\">" +
                "  <Set name=\"first\">" +
                "    <Property name=\"not_found\">" +
                "      <Default><Property name=\"also_not_found\" default=\"" + value + "\"/></Default>" +
                "    </Property>" +
                "  </Set>  " +
                "</Configure>");
        DefaultTestConfiguration config = (DefaultTestConfiguration)xmlConfiguration.configure();
        assertEquals(value, config.getFirst());
    }

    @Test
    public void testJettyStandardIdsAndPropertiesAndJettyHomeAndJettyBase() throws Exception
    {
        String[] propNames = new String[]
            {
                "jetty.base",
                "jetty.home"
            };

        for (String propName : propNames)
        {
            XmlConfiguration configuration =
                asXmlConfiguration(
                    "<Configure class=\"org.eclipse.jetty.xml.ExampleConfiguration\">" +
                        "  <Set name=\"TestString\">" +
                        "    <Property name=\"" + propName + "\"/>" +
                        "  </Set>" +
                        "</Configure>");

            configuration.setJettyStandardIdsAndProperties(null, null);

            ExampleConfiguration tc = new ExampleConfiguration();
            configuration.configure(tc);

            assertThat(propName, tc.getTestString(), is(notNullValue()));
            assertThat(propName, tc.getTestString(), not(startsWith("file:")));
        }
    }

    @Test
    public void testJettyStandardIdsAndPropertiesAndJettyHomeUriAndJettyBaseUri() throws Exception
    {
        String[] propNames = new String[]
            {
                "jetty.base.uri",
                "jetty.home.uri"
            };

        for (String propName : propNames)
        {
            XmlConfiguration configuration =
                asXmlConfiguration(
                    "<Configure class=\"org.eclipse.jetty.xml.ExampleConfiguration\">" +
                        "  <Set name=\"TestString\">" +
                        "    <Property name=\"" + propName + "\"/>" +
                        "  </Set>" +
                        "</Configure>");

            configuration.setJettyStandardIdsAndProperties(null, null);

            ExampleConfiguration tc = new ExampleConfiguration();
            configuration.configure(tc);

            assertThat(propName, tc.getTestString(), is(notNullValue()));
            assertThat(propName, tc.getTestString(), startsWith("file:"));
        }
    }

    public static class BarNamed
    {
        private String foo;
        private List<String> zeds;

        public BarNamed(@Name("foo") String foo)
        {
            this.foo = foo;
        }

        public void addZed(String zed)
        {
            if (zeds == null)
                zeds = new ArrayList<>();
            zeds.add(zed);
        }

        public List<String> getZeds()
        {
            return zeds;
        }

        public String getFoo()
        {
            return foo;
        }
    }

    @Test
    public void testConfiguredWithNamedArg() throws Exception
    {
        XmlConfiguration xmlFoo = asXmlConfiguration("foo.xml",
            "<Configure>\n" +
                "  <New id=\"foo\" class=\"java.lang.String\">\n" +
                "    <Arg>foozball</Arg>\n" +
                "  </New>\n" +
                "</Configure>");
        XmlConfiguration xmlBar = asXmlConfiguration("bar.xml",
            "<Configure id=\"bar\" class=\"" + BarNamed.class.getName() + "\">\n" +
                "  <Arg name=\"foo\"><Ref refid=\"foo\"/></Arg>\n" +
                "</Configure>");

        ByteArrayOutputStream logBytes = captureLoggingBytes(() ->
        {
            Map<String, Object> idMap = mimicXmlConfigurationMain(xmlFoo, xmlBar);
            Object obj = idMap.get("bar");
            assertThat("BarNamed instance created", obj, instanceOf(BarNamed.class));
            BarNamed bar = (BarNamed)obj;
            assertThat("BarNamed has foo", bar.getFoo(), is("foozball"));
        });

        List<String> warnings = Arrays.stream(logBytes.toString(UTF_8).split(System.lineSeparator()))
            .filter(line -> line.contains(":WARN"))
            .collect(Collectors.toList());

        assertThat("WARN logs size", warnings.size(), is(0));
    }

    @Test
    public void testConfiguredWithArgNotUsingName() throws Exception
    {
        XmlConfiguration xmlFoo = asXmlConfiguration("foo.xml",
            "<Configure>\n" +
                "  <New id=\"foo\" class=\"java.lang.String\">\n" +
                "    <Arg>foozball</Arg>\n" +
                "  </New>\n" +
                "</Configure>");
        XmlConfiguration xmlBar = asXmlConfiguration("bar.xml",
            "<Configure id=\"bar\" class=\"" + BarNamed.class.getName() + "\">\n" +
                "  <Arg><Ref refid=\"foo\"/></Arg>\n" + // no name specified
                "</Configure>");

        ByteArrayOutputStream logBytes = captureLoggingBytes(() ->
        {
            Map<String, Object> idMap = mimicXmlConfigurationMain(xmlFoo, xmlBar);
            Object obj = idMap.get("bar");
            assertThat("BarNamed instance created", obj, instanceOf(BarNamed.class));
            BarNamed bar = (BarNamed)obj;
            assertThat("BarNamed has foo", bar.getFoo(), is("foozball"));
        });

        List<String> warnings = Arrays.stream(logBytes.toString(UTF_8).split(System.lineSeparator()))
            .filter(line -> line.contains(":WARN :"))
            .collect(Collectors.toList());

        assertThat("WARN logs size", warnings.size(), is(0));
    }

    @Test
    public void testConfiguredWithBadNamedArg() throws Exception
    {
        XmlConfiguration xmlBar = asXmlConfiguration("bar.xml",
            "<Configure id=\"bar\" class=\"" + BarNamed.class.getName() + "\">\n" +
                "  <Arg name=\"foozball\">foozball</Arg>\n" + // wrong name specified
                "</Configure>");

        IllegalStateException cause = assertThrows(IllegalStateException.class, () ->
            mimicXmlConfigurationMain(xmlBar));

        assertThat("Cause message", cause.getMessage(), containsString("No matching constructor"));
    }

    @Test
    public void testConfiguredWithTooManyNamedArgs() throws Exception
    {
        XmlConfiguration xmlBar = asXmlConfiguration("bar.xml",
            "<Configure id=\"bar\" class=\"" + BarNamed.class.getName() + "\">\n" +
                "  <Arg name=\"foo\">foozball</Arg>\n" +
                "  <Arg name=\"foo\">soccer</Arg>\n" + // neither should win
                "</Configure>");

        IllegalStateException cause = assertThrows(IllegalStateException.class, () ->
            mimicXmlConfigurationMain(xmlBar));

        assertThat("Cause message", cause.getMessage(), containsString("No matching constructor"));
    }

    @Test
    public void testConfiguredSameWithNamedArgTwice() throws Exception
    {
        XmlConfiguration xmlFoo = asXmlConfiguration("foo.xml",
            "<Configure>\n" +
                "  <New id=\"foo\" class=\"java.lang.String\">\n" +
                "    <Arg>foozball</Arg>\n" +
                "  </New>\n" +
                "</Configure>");
        XmlConfiguration xmlBar = asXmlConfiguration("bar.xml",
            "<Configure id=\"bar\" class=\"" + BarNamed.class.getName() + "\">\n" +
                "  <Arg name=\"foo\"><Ref refid=\"foo\"/></Arg>\n" +
                "</Configure>");
        XmlConfiguration xmlAddZed = asXmlConfiguration("zed.xml",
            "<Configure id=\"bar\" class=\"" + BarNamed.class.getName() + "\">\n" +
                "  <Arg name=\"foo\">baz</Arg>\n" + // the invalid line
                "  <Call name=\"addZed\">\n" +
                "    <Arg>plain-zero</Arg>\n" +
                "  </Call>\n" +
                "</Configure>");

        ByteArrayOutputStream logBytes = captureLoggingBytes(() ->
        {
            Map<String, Object> idMap = mimicXmlConfigurationMain(xmlFoo, xmlBar, xmlAddZed);
            Object obj = idMap.get("bar");
            assertThat("BarNamed instance created", obj, instanceOf(BarNamed.class));
            BarNamed bar = (BarNamed)obj;
            assertThat("BarNamed has foo", bar.getFoo(), is("foozball"));
            List<String> zeds = bar.getZeds();
            assertThat("BarNamed has zeds", zeds, not(empty()));
            assertThat("Zeds[0]", zeds.get(0), is("plain-zero"));
        });

        List<String> warnings = Arrays.stream(logBytes.toString(UTF_8).split(System.lineSeparator()))
            .filter(line -> line.contains(":WARN :"))
            .collect(Collectors.toList());

        assertThat("WARN logs count", warnings.size(), is(1));

        String actualWarn = warnings.get(0);
        assertThat("WARN logs", actualWarn,
            allOf(containsString("Ignored arg <Arg name="),
                containsString("zed.xml")
            ));
    }

    /**
     * This mimics the XML load behavior in XmlConfiguration.main(String ... args)
     */
    private Map<String, Object> mimicXmlConfigurationMain(XmlConfiguration... configurations) throws Exception
    {
        XmlConfiguration last = null;
        for (XmlConfiguration configuration : configurations)
        {
            if (last != null)
                configuration.getIdMap().putAll(last.getIdMap());
            configuration.configure();
            last = configuration;
        }
        assertNotNull(last);
        return last.getIdMap();
    }

    @Test
    public void testJettyStandardIdsAndPropertiesJettyWebappsUri() throws Exception
    {
        Path war = MavenTestingUtils.getTargetPath("no.war");
        XmlConfiguration configuration =
            asXmlConfiguration(
                "<Configure class=\"org.eclipse.jetty.xml.ExampleConfiguration\">" +
                    "  <Set name=\"TestString\">" +
                    "    <Property name=\"" + "jetty.webapps.uri" + "\"/>" +
                    "  </Set>" +
                    "</Configure>");

        configuration.setJettyStandardIdsAndProperties(null, war);

        ExampleConfiguration tc = new ExampleConfiguration();
        configuration.configure(tc);

        assertThat("jetty.webapps.uri", tc.getTestString(), is(XmlConfiguration.normalizeURI(war.getParent().toUri().toString())));
    }

    @Test
    public void testDeprecated() throws Exception
    {
        Class<?> testClass = AnnotatedTestConfiguration.class;
        XmlConfiguration xmlConfiguration = asXmlConfiguration(
            "<Configure class=\"" + testClass.getName() + "\">" +
                "  <Set name=\"deprecated\">foo</Set>" +
                "  <Set name=\"obsolete\">" +
                "    <Call name=\"setDeprecated\"><Arg><Get name=\"deprecated\" /></Arg></Call>" +
                "  </Set>" +
                "  <Get name=\"obsolete\" />" +
                "</Configure>");

        ByteArrayOutputStream logBytes = captureLoggingBytes(xmlConfiguration::configure);

        String[] lines = logBytes.toString(UTF_8).split(System.lineSeparator());
        List<String> warnings = Arrays.stream(lines)
            .filter(line -> line.contains(":WARN :"))
            .filter(line -> line.contains(testClass.getSimpleName()))
            .collect(Collectors.toList());
        // 1. Deprecated constructor
        // 2. Deprecated <Set> method
        // 3. Deprecated <Get> method
        // 4. Deprecated <Call> method
        // 5. Deprecated <Set> field
        // 6. Deprecated <Get> field
        assertEquals(6, warnings.size());
    }

    public static Stream<Arguments> resolvePathCases()
    {
        String resolvePathCasesJettyBase;

        ArrayList<Arguments> cases = new ArrayList<>();
        if (OS.WINDOWS.isCurrentOs())
        {
            resolvePathCasesJettyBase = "C:\\web\\jetty-base";

            // Not configured, default (in xml) is used.
            cases.add(Arguments.of(resolvePathCasesJettyBase, null, "C:\\web\\jetty-base\\etc\\keystore.p12"));
            // Configured using normal relative path
            cases.add(Arguments.of(resolvePathCasesJettyBase, "alt/keystore", "C:\\web\\jetty-base\\alt\\keystore"));
            // Configured using navigated path segments
            cases.add(Arguments.of(resolvePathCasesJettyBase, "../corp/etc/keystore", "C:\\web\\corp\\etc\\keystore"));
            // Configured using relative to drive-root path
            cases.add(Arguments.of(resolvePathCasesJettyBase, "/included/keystore", "C:\\included\\keystore"));
            cases.add(Arguments.of(resolvePathCasesJettyBase, "\\included\\keystore", "C:\\included\\keystore"));
            // Configured using absolute path
            cases.add(Arguments.of(resolvePathCasesJettyBase, "D:\\main\\config\\keystore", "D:\\main\\config\\keystore"));
            cases.add(Arguments.of(resolvePathCasesJettyBase, "E:other\\keystore", "E:other\\keystore"));
            cases.add(Arguments.of(resolvePathCasesJettyBase, "F:\\\\other\\keystore", "F:\\other\\keystore"));
            cases.add(Arguments.of(resolvePathCasesJettyBase, "G:///another/keystore", "G:\\another\\keystore"));
            cases.add(Arguments.of(resolvePathCasesJettyBase, "H:///prod/app/keystore", "H:\\prod\\app\\keystore"));

            resolvePathCasesJettyBase = "\\\\machine\\share\\apps\\jetty-base";

            // Not configured, default (in xml) is used.
            cases.add(Arguments.of(resolvePathCasesJettyBase, null, "\\\\machine\\share\\apps\\jetty-base\\etc\\keystore.p12"));
            // Configured using normal relative path
            cases.add(Arguments.of(resolvePathCasesJettyBase, "alt/keystore", "\\\\machine\\share\\apps\\jetty-base\\alt\\keystore"));
            // Configured using navigated path segments
            cases.add(Arguments.of(resolvePathCasesJettyBase, "../corp/etc/keystore", "\\\\machine\\share\\apps\\corp\\etc\\keystore"));
            // Configured using relative to drive-root path
            cases.add(Arguments.of(resolvePathCasesJettyBase, "/included/keystore", "\\\\machine\\share\\included\\keystore"));
            cases.add(Arguments.of(resolvePathCasesJettyBase, "\\included\\keystore", "\\\\machine\\share\\included\\keystore"));
            // Configured using absolute path
            cases.add(Arguments.of(resolvePathCasesJettyBase, "D:\\main\\config\\keystore", "D:\\main\\config\\keystore"));
            cases.add(Arguments.of(resolvePathCasesJettyBase, "E:other\\keystore", "E:other\\keystore"));
            cases.add(Arguments.of(resolvePathCasesJettyBase, "F:\\\\other\\keystore", "F:\\other\\keystore"));
            cases.add(Arguments.of(resolvePathCasesJettyBase, "G:///another/keystore", "G:\\another\\keystore"));
            cases.add(Arguments.of(resolvePathCasesJettyBase, "H:///prod/app/keystore", "H:\\prod\\app\\keystore"));
        }
        else
        {
            resolvePathCasesJettyBase = "/var/lib/jetty-base";

            // Not configured, default (in xml) is used.
            cases.add(Arguments.of(resolvePathCasesJettyBase, null, "/var/lib/jetty-base/etc/keystore.p12"));
            // Configured using normal relative path
            cases.add(Arguments.of(resolvePathCasesJettyBase, "alt/keystore", "/var/lib/jetty-base/alt/keystore"));
            // Configured using navigated path segments
            cases.add(Arguments.of(resolvePathCasesJettyBase, "../corp/etc/keystore", "/var/lib/corp/etc/keystore"));
            // Configured using absolute path
            cases.add(Arguments.of(resolvePathCasesJettyBase, "/opt/jetty/etc/keystore", "/opt/jetty/etc/keystore"));
        }

        return cases.stream();
    }

    @ParameterizedTest
    @MethodSource("resolvePathCases")
    public void testCallResolvePath(String jettyBasePath, String configValue, String expectedPath) throws Exception
    {
        Path war = MavenTestingUtils.getTargetPath("no.war");
        XmlConfiguration configuration =
            asXmlConfiguration(
                "<Configure class=\"org.eclipse.jetty.xml.ExampleConfiguration\">" +
                    "  <Set name=\"TestString\">" +
                    "    <Call name=\"resolvePath\" class=\"org.eclipse.jetty.xml.XmlConfiguration\">" +
                    "     <Arg><Property name=\"jetty.base\"/></Arg>" +
                    "     <Arg><Property name=\"jetty.sslContext.keyStorePath\" default=\"etc/keystore.p12\" /></Arg>" +
                    "    </Call>" +
                    "  </Set>" +
                    "</Configure>");

        try
        {
            configuration.setJettyStandardIdsAndProperties(null, war);
            configuration.getProperties().put("jetty.base", jettyBasePath);
            if (configValue != null)
                configuration.getProperties().put("jetty.sslContext.keyStorePath", configValue);

            ExampleConfiguration tc = new ExampleConfiguration();
            configuration.configure(tc);

            assertThat(tc.getTestString(), is(expectedPath));
        }
        finally
        {
            // cleanup after myself
            configuration.getProperties().remove("jetty.base");
        }
    }

    @Test
    public void testResolvePathRelative()
    {
        Path testPath = MavenTestingUtils.getTargetTestingPath("testResolvePathRelative");
        FS.ensureDirExists(testPath);
        String resolved = XmlConfiguration.resolvePath(testPath.toString(), "etc/keystore");
        assertEquals(testPath.resolve("etc/keystore").toString(), resolved);
    }

    @Test
    public void testResolvePathAbsolute()
    {
        Path testPath = MavenTestingUtils.getTargetTestingPath("testResolvePathRelative");
        FS.ensureDirExists(testPath);
        String resolved = XmlConfiguration.resolvePath(testPath.toString(), "/tmp/etc/keystore");
        assertEquals(testPath.resolve("/tmp/etc/keystore").toString(), resolved);
    }

    @Test
    public void testResolvePathInvalidBase()
    {
        Path testPath = MavenTestingUtils.getTargetTestingPath("testResolvePathRelative");
        FS.ensureDeleted(testPath);
        Path baseDir = testPath.resolve("bogus");
        String resolved = XmlConfiguration.resolvePath(baseDir.toString(), "etc/keystore");
        assertEquals(baseDir.resolve("etc/keystore").toString(), resolved);
    }

    public static class Base extends AbstractLifeCycle
    {
        static AtomicReference<Base> base = new AtomicReference<>();

        private Map<String, Object> _map = new HashMap<>();

        public Base()
        {
            base.set(this);
        }

        public Map<String, Object> getMap()
        {
            return _map;
        }

        public String getResource() throws IOException
        {
            return IO.toString(Thread.currentThread().getContextClassLoader().getResourceAsStream("resource.txt"));
        }
    }

    @Test
    public void testMain() throws Exception
    {
        Path dir = MavenTestingUtils.getBasePath();
        Path base = dir.resolve("src").resolve("test").resolve("base");
        XmlConfiguration.main(
            "baseA=commandline",
            "baseB=x",
            base.resolve("base.properties").toString(),
            base.resolve("base.xml").toString(),
            base.resolve("map.xml").toString(),
            "--env",
            "envA",
            "-cp",
            base.resolve("envA").toString(),
            base.resolve("envA.properties").toString(),
            "env1=AAA",
            base.resolve("envA.xml").toString(),
            "--env",
            "envB",
            "-cp",
            base.resolve("envB").toString(),
            base.resolve("envB.properties").toString(),
            "env1=BBB",
            base.resolve("envB.xml").toString()
        );

        Base b = Base.base.get();
        assertThat(b, notNullValue());
        assertTrue(b.isStarted());

        Map<String, Object> m = b.getMap();
        assertThat(m.get("baseA"), is("commandline"));
        assertThat(m.get("baseB"), is("overridden"));
        assertThat(m.get("baseC"), is("42"));
        assertThat(m.get("baseD"), is("ordered"));

        Environment envA = Environment.get("envA");
        assertThat(envA, notNullValue());
        assertThat(envA.getAttribute("attr"), is("AttrA"));
        assertThat(m.get("envA1"), is("AAA"));
        assertThat(m.get("envA2"), is("A2"));
        assertThat(m.get("envA3"), is("for envA"));

        Environment envB = Environment.get("envB");
        assertThat(envB, notNullValue());
        assertThat(envB.getAttribute("attr"), is("AttrB"));
        assertThat(m.get("envB1"), is("BBB"));
        assertThat(m.get("envB2"), is("B2"));
        assertThat(m.get("envB3"), is("for envB"));
    }

    @Test
    public void testMainWithDuplicateEnvironment()
    {
        Path base = MavenTestingUtils.getBasePath().resolve("src").resolve("test").resolve("base");
        // Don't use environment names used in other tests, as they are stored in a static map.
        assertThrows(IllegalArgumentException.class, () -> XmlConfiguration.main(
            "--env",
            "env1",
            "-cp",
            base.resolve("envA").toString(),
            "--env",
            "env2",
            "-cp",
            base.resolve("envB").toString(),
            // Duplicate --env envA section is not allowed.
            "--env",
            "env1",
            "-cp",
            base.resolve("envB").toString()
        ));
    }

    @Test
    public void testPropertyNoNameAttributeWithDeprecatedAttribute() throws Exception
    {
        XmlConfiguration configuration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.ExampleConfiguration\">" +
            "  <Set name=\"TestString\">" +
            "    <Property deprecated=\"jetty.deprecated\" />" +
            "  </Set>" +
            "</Configure>"
        );

        String value = "deprecated";
        configuration.getProperties().put("jetty.deprecated", value);

        ExampleConfiguration tc = new ExampleConfiguration();
        configuration.configure(tc);

        assertThat(tc.getTestString(), is(value));
    }

    @Test
    public void testVirtualGetWithClassAttribute() throws Exception
    {
        // SocketChannel.open() returns an internal class
        // that cannot be accessed, so <Get> must specify the
        // class to use to lookup the 'getLocalAddress' method.
        XmlConfiguration configuration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.ExampleConfiguration\">" +
            "  <Set name=\"Test\">" +
            "    <Call class=\"java.nio.channels.SocketChannel\" name=\"open\">" +
            "      <Get class=\"java.nio.channels.SocketChannel\" name=\"localAddress\" />" +
            "    </Call>" +
            "  </Set>" +
            "</Configure>"
        );

        ExampleConfiguration tc = new ExampleConfiguration();
        configuration.configure(tc);

        assertThat(tc.testObject, instanceOf(SocketChannel.class));
    }

    @Test
    public void testVirtualSetWithClassAttribute() throws Exception
    {
        // SSLSocketFactory.getDefault().createSocket() returns an internal
        // class that cannot be accessed, so <Set> must specify the
        // class to use to lookup the 'setNeedClientAuth' method.
        XmlConfiguration configuration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.ExampleConfiguration\">" +
            "  <Set name=\"Test\">" +
            "    <Call class=\"javax.net.ssl.SSLSocketFactory\" name=\"getDefault\">" +
            "      <Call class=\"javax.net.ssl.SSLSocketFactory\" name=\"createSocket\">" +
            "        <Set class=\"javax.net.ssl.SSLSocket\" name=\"needClientAuth\">true</Set>" +
            "      </Call>" +
            "    </Call>" +
            "  </Set>" +
            "</Configure>"
        );

        ExampleConfiguration tc = new ExampleConfiguration();
        configuration.configure(tc);

        assertThat(tc.testObject, instanceOf(SSLSocketFactory.class));
    }

    @Test
    public void testVirtualCallWithClassAttribute() throws Exception
    {
        // Executors.newSingleThreadScheduledExecutor() returns an
        // internal class that cannot be accessed, so <Call> must
        // specify the class to use to lookup the 'shutdown' method.
        XmlConfiguration configuration = asXmlConfiguration(
            "<Configure class=\"org.eclipse.jetty.xml.ExampleConfiguration\">" +
            "  <Set name=\"Test\">" +
            "    <Call class=\"java.util.concurrent.Executors\" name=\"newSingleThreadScheduledExecutor\">" +
            "      <Call class=\"java.util.concurrent.ExecutorService\" name=\"shutdown\" />" +
            "    </Call>" +
            "  </Set>" +
            "</Configure>"
        );

        ExampleConfiguration tc = new ExampleConfiguration();
        configuration.configure(tc);

        assertThat(tc.testObject, instanceOf(ScheduledExecutorService.class));
    }

    private static List<String> systemIdVariants()
    {
        List<String> ids = new ArrayList<>();
        List<String> schemes = List.of("http", "https");
        List<String> contexts = List.of("eclipse.org/jetty",
            "www.eclipse.org/jetty",
            "eclipse.dev/jetty",
            "www.eclipse.dev/jetty",
            "jetty.org");
        List<String> paths = List.of("configure.dtd", // version-less
                          "configure_9_0.dtd", // 9.0
                          "configure_9_3.dtd", // 9.3
                          "configure_10_0.dtd"); // 10.0

        for (String scheme: schemes)
        {
            for (String host: contexts)
            {
                for (String path: paths)
                {
                    ids.add(String.format("%s://%s/%s", scheme, host, path));
                }
            }
        }
        return ids;
    }

    public static Stream<Arguments> xmlSystemIdSource()
    {
        return systemIdVariants().stream().map(Arguments::of);
    }

    /**
     * Test to ensure that all required XML System ID variants are covered in the
     * {@link XmlConfiguration} internals.
     */
    @ParameterizedTest
    @MethodSource("xmlSystemIdSource")
    public void testSystemIdVariants(String xmlSystemId) throws IOException, SAXException
    {
        // empty raw xml, just to instantiate XmlConfiguration, so we can access the XmlParser / ConfigurationParser.
        XmlConfiguration xmlConfiguration = asXmlConfiguration("<Configure class=\"org.eclipse.jetty.xml.TestConfiguration\" />");
        XmlParser configurationProcessor = xmlConfiguration.getXmlParser();
        InputSource inputSource = configurationProcessor.resolveEntity(null, xmlSystemId);
        assertNotNull(inputSource, "SystemID: " + xmlSystemId + " does not exist");
    }

    public static Stream<Arguments> xmlPublicIdSource()
    {
        List<Arguments> cases = new ArrayList<>();

        // XML Public IDs seen in use with Jetty.
        List<String> publicIds = List.of("-//Jetty//Configure//EN",
            "-//Mort Bay Consulting//DTD Configure//EN");

        for (String systemId: systemIdVariants())
        {
            for (String publicId: publicIds)
            {
                cases.add(Arguments.of(publicId, systemId));
            }
        }
        return cases.stream();
    }

    /**
     * Test to ensure that all required XML PUBLIC ID variants are covered in the
     * {@link XmlConfiguration} internals.
     */
    @ParameterizedTest
    @MethodSource("xmlPublicIdSource")
    public void testPublicIdVariants(String xmlPublicId, String xmlSystemId) throws IOException, SAXException
    {
        // empty raw xml, just to instantiate XmlConfiguration, so we can access the XmlParser / ConfigurationParser.
        XmlConfiguration xmlConfiguration = asXmlConfiguration("<Configure class=\"org.eclipse.jetty.xml.TestConfiguration\" />");
        XmlParser configurationProcessor = xmlConfiguration.getXmlParser();
        InputSource inputSource = configurationProcessor.resolveEntity(xmlPublicId, xmlSystemId);
        assertNotNull(inputSource, "reference: PUBLIC " + xmlPublicId + " SYSTEM " + xmlSystemId + " does not exist");
    }

    private ByteArrayOutputStream captureLoggingBytes(ThrowableAction action) throws Exception
    {
        Logger slf4jLogger = LoggerFactory.getLogger(XmlConfiguration.class);
        Assumptions.assumeTrue(slf4jLogger instanceof JettyLogger);

        ByteArrayOutputStream logBytes = new ByteArrayOutputStream();
        JettyLogger jettyLogger = (JettyLogger)slf4jLogger;
        StdErrAppender appender = (StdErrAppender)jettyLogger.getAppender();
        PrintStream oldStream = appender.getStream();
        JettyLevel oldLevel = jettyLogger.getLevel();
        try
        {
            // capture events
            appender.setStream(new PrintStream(logBytes, true));
            // make sure we are seeing WARN level events
            jettyLogger.setLevel(JettyLevel.WARN);

            action.run();
        }
        finally
        {
            appender.setStream(oldStream);
            jettyLogger.setLevel(oldLevel);
        }
        return logBytes;
    }

    private interface ThrowableAction
    {
        void run() throws Exception;
    }

    @Test
    public void testExecutableComparator()
    {
        aaa(null);
        bbb(null);
        ccc(null);

        Stream.of(XmlConfigurationTest.class.getMethods())
            .filter(m -> m.getName().length() == 3)
            .filter(m -> !m.getName().equals("foo"))
            .sorted(EXECUTABLE_COMPARATOR)
            .map(Executable::toGenericString)
            .forEach(System.out::println);

        List<Method> methods = Arrays.asList(Arrays.stream(XmlConfigurationTest.class.getMethods())
            .filter(m -> m.getName().length() == 3)
            .toArray(Method[]::new));

        // The implementor must also ensure that the relation is transitive: ((compare(x, y)>0) && (compare(y, z)>0)) implies compare(x, z)>0
        assertThat(EXECUTABLE_COMPARATOR.compare(methods.get(0), methods.get(1)), is(EXECUTABLE_COMPARATOR.compare(methods.get(1), methods.get(2))));
        assertThat(EXECUTABLE_COMPARATOR.compare(methods.get(0), methods.get(1)), is(EXECUTABLE_COMPARATOR.compare(methods.get(0), methods.get(2))));
    }

    public void aaa(Aaa ignored)
    {
    }

    public void bbb(Bbb ignored)
    {
    }

    public void ccc(Ccc ignored)
    {
    }

    public interface Aaa
    {
    }

    public interface Abc extends Aaa
    {
    }

    public static class Bbb
    {
    }

    public static class Ccc implements Aaa
    {
    }

    @Test
    public void testFooExecutableComparator()
    {
        List<String> orderedMethodIds = Stream.of(FooObj.class.getMethods())
            .filter(m -> m.getName().equals("foo"))
            .sorted(EXECUTABLE_COMPARATOR)
            .map(Executable::toGenericString)
            .collect(Collectors.toList());
        orderedMethodIds.forEach(System.out::println);
        String[] expectedOrder = {
            "public void org.eclipse.jetty.xml.XmlConfigurationTest$FooObj.foo()", // favor fewer args
            "public void org.eclipse.jetty.xml.XmlConfigurationTest$FooObj.foo(int)", // favor primitives over non-primitives
            "public void org.eclipse.jetty.xml.XmlConfigurationTest$FooObj.foo(java.time.temporal.Temporal)", // favor over Instant
            "public int org.eclipse.jetty.xml.XmlConfigurationTest$FooObj.foo(java.lang.String)",
            "public java.util.Locale org.eclipse.jetty.xml.XmlConfigurationTest$FooObj.foo(java.nio.charset.Charset)",
            "public void org.eclipse.jetty.xml.XmlConfigurationTest$FooObj.foo(java.time.Instant)",
            "public void org.eclipse.jetty.xml.XmlConfigurationTest$FooObj.foo(java.util.Locale)",
            "public void org.eclipse.jetty.xml.XmlConfigurationTest$FooObj.foo(int,java.lang.String)",
            "public void org.eclipse.jetty.xml.XmlConfigurationTest$FooObj.foo(java.lang.String,int)",
            "public void org.eclipse.jetty.xml.XmlConfigurationTest$FooObj.foo(int,java.lang.String,java.lang.String)",
            "public void org.eclipse.jetty.xml.XmlConfigurationTest$FooObj.foo(int,java.lang.String,java.lang.String,java.lang.Object)"
        };
        assertThat(orderedMethodIds, contains(expectedOrder));
    }

    public static class FooObj
    {
        public void foo()
        {
        }

        public int foo(String name)
        {
            return -1;
        }

        public void foo(int id)
        {
        }

        public void foo(Locale locale)
        {
        }

        public void foo(Instant timestamp) // Instant extends from Temporal
        {
        }

        public void foo(Temporal temporal)
        {
        }

        public Locale foo(Charset charset)
        {
            return null;
        }

        public void foo(String name, int id)
        {
        }

        public void foo(int id, String name)
        {
        }

        public void foo(int id, String name, String description)
        {
        }

        public void foo(int id, String name, String description, Object value)
        {
        }
    }
}
