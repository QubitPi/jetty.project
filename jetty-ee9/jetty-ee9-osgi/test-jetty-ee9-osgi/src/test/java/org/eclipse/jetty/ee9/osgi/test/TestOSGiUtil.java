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

package org.eclipse.jetty.ee9.osgi.test;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.ListIterator;
import java.util.Map;

import org.eclipse.jetty.osgi.OSGiServerConstants;
import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.ops4j.pax.exam.CoreOptions;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.options.MavenArtifactProvisionOption;
import org.ops4j.pax.exam.options.extra.VMOption;
import org.ops4j.pax.tinybundles.TinyBundle;
import org.ops4j.pax.tinybundles.TinyBundles;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleActivator;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.Assert.assertTrue;
import static org.ops4j.pax.exam.Constants.START_LEVEL_SYSTEM_BUNDLES;
import static org.ops4j.pax.exam.CoreOptions.mavenBundle;
import static org.ops4j.pax.exam.CoreOptions.systemProperty;

/**
 * Helper methods for pax-exam tests
 */
public class TestOSGiUtil
{
    public static final String BUNDLE_DEBUG = "bundle.debug";

    private static final Logger LOGGER = LoggerFactory.getLogger(TestOSGiUtil.class);
    
    /**
     * Null FragmentActivator for the fake bundle
     * that exposes src/test/resources/jetty-logging.properties in
     * the osgi container
     */
    public static class FragmentActivator implements BundleActivator
    {
        @Override
        public void start(BundleContext context) throws Exception
        {
        }

        @Override
        public void stop(BundleContext context) throws Exception
        {
        }
    }

    public static List<Option> configureJettyHomeAndPort(boolean ssl, String jettySelectorFileName)
    {
        File etc = new File(FS.separators("src/test/config/etc"));

        List<Option> options = new ArrayList<>();
        StringBuffer xmlConfigs = new StringBuffer();
        xmlConfigs.append(new File(etc, "jetty.xml").toURI());
        xmlConfigs.append(";");
        if (ssl)
        {
            options.add(CoreOptions.systemProperty("jetty.ssl.port").value("0"));
            xmlConfigs.append(new File(etc, "jetty-ssl.xml").toURI());
            xmlConfigs.append(";");
            xmlConfigs.append(new File(etc, "jetty-alpn.xml").toURI());
            xmlConfigs.append(";");
            xmlConfigs.append(new File(etc, "jetty-https.xml").toURI());
            xmlConfigs.append(";");
        }
        xmlConfigs.append(new File(etc, jettySelectorFileName).toURI());
        xmlConfigs.append(";");
        xmlConfigs.append(new File(etc, "jetty-deploy.xml").toURI());
        xmlConfigs.append(";");
        xmlConfigs.append(new File(etc, "jetty-testrealm.xml").toURI());

        options.add(systemProperty(OSGiServerConstants.MANAGED_JETTY_XML_CONFIG_URLS).value(xmlConfigs.toString()));
        options.add(systemProperty("jetty.http.port").value("0"));
        options.add(systemProperty("jetty.home").value(etc.getParentFile().getAbsolutePath()));
        options.add(systemProperty("jetty.base").value(etc.getParentFile().getAbsolutePath()));
        return options;
    }
    
    public static List<Option> configurePaxExamLogging()
    {
        //sort out logging from the pax-exam environment
        List<Option> options = new ArrayList<>();
        options.add(systemProperty("pax.exam.logging").value("none"));
        String paxExamLogLevel = System.getProperty("pax.exam.LEVEL", "WARN");
        options.add(systemProperty("org.ops4j.pax.logging.DefaultServiceLog.level").value(paxExamLogLevel));
        int debugPort = Integer.getInteger("pax.exam.debug.port", -1);
        if (debugPort >= 0)
        {
          options.add(new VMOption("-Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=" + debugPort));
        }
        return options;
    }

    public static Option optionalRemoteDebug()
    {
        return CoreOptions.when(Boolean.getBoolean("pax.exam.debug.remote"))
            .useOptions(CoreOptions.vmOption("-Xrunjdwp:transport=dt_socket,server=y,suspend=y,address=5005"));
    }

    public static void coreJettyDependencies(List<Option> res)
    {
        //enables a dump of the status of all deployed bundles
        res.add(systemProperty("bundle.debug").value(Boolean.toString(Boolean.getBoolean(TestOSGiUtil.BUNDLE_DEBUG))));

        //add locations to look for jars to deploy
        String mavenRepoPath = System.getProperty("mavenRepoPath");
        if (!StringUtil.isBlank(mavenRepoPath))
        {
            res.add(systemProperty("org.ops4j.pax.url.mvn.localRepository").value(mavenRepoPath));
            res.add(systemProperty("org.ops4j.pax.url.mvn.defaultRepositories").value("file://" + mavenRepoPath + "@id=local.repo"));
            res.add(systemProperty("org.ops4j.pax.url.mvn.useFallbackRepositories").value(Boolean.FALSE.toString()));
            res.add(systemProperty("org.ops4j.pax.url.mvn.repositories").value("+https://repo1.maven.org/maven2@id=maven.central.repo"));
        }
        String settingsFilePath = System.getProperty("settingsFilePath");
        if (!StringUtil.isBlank(settingsFilePath))
        {
            res.add(systemProperty("org.ops4j.pax.url.mvn.settings").value(System.getProperty("settingsFilePath")));
        }

        res.add(mavenBundle().groupId("org.slf4j").artifactId("slf4j-api").version("1.7.36").startLevel(START_LEVEL_SYSTEM_BUNDLES)); //.versionAsInProject().noStart());

        /*
         * Jetty 12 uses slf4j 2.0.0 by default, however we want to test with slf4j 1.7.30 for backwards compatibility.
         * To do that, we need to use slf4j-simple as the logging implementation. We make a simplelogger.properties
         * file available so that jetty logging can be configured
         */
        // BEGIN - slf4j 1.7.x
        /* slf4j-simple conflicts with both slf4j 1.7.x, and jetty-slf4j-impl. (but in different ways) */

        TinyBundle simpleLoggingPropertiesBundle = TinyBundles.bundle();
        simpleLoggingPropertiesBundle.addResource("simplelogger.properties", ClassLoader.getSystemResource("simplelogger.properties"));
        simpleLoggingPropertiesBundle.setHeader(Constants.BUNDLE_SYMBOLICNAME, "simple-logger-properties");
        simpleLoggingPropertiesBundle.setHeader(Constants.FRAGMENT_HOST, "slf4j-simple");
        simpleLoggingPropertiesBundle.addClass(FragmentActivator.class);
        res.add(CoreOptions.streamBundle(simpleLoggingPropertiesBundle.build()).noStart());
        res.add(mavenBundle().groupId("org.slf4j").artifactId("slf4j-simple").version("1.7.36").startLevel(START_LEVEL_SYSTEM_BUNDLES)); // .versionAsInProject()

        // END - slf4j 1.7.x

        /*
         * When running with slf4j >= 2.0.0, remove the slf4j simple logger above and uncomment the following lines
         */
        // BEGIN - slf4j 2.x
//        TinyBundle loggingPropertiesBundle = TinyBundles.bundle();
//        loggingPropertiesBundle.add("jetty-logging.properties", ClassLoader.getSystemResource("jetty-logging.properties"));
//        loggingPropertiesBundle.set(Constants.BUNDLE_SYMBOLICNAME, "jetty-logging-properties");
//        loggingPropertiesBundle.set(Constants.FRAGMENT_HOST, "org.eclipse.jetty.logging");
//        loggingPropertiesBundle.add(FragmentActivator.class);
//        res.add(CoreOptions.streamBundle(loggingPropertiesBundle.build()).noStart());
//        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-slf4j-impl").versionAsInProject().start());
        // END - slf4j 2.x
        String servletGroupId = System.getProperty("servlet.groupId", "org.eclipse.jetty.toolchain");
        String servletArtifactId = System.getProperty("servlet.artifactId", "jetty-jakarta-servlet-api");
        res.add(mavenBundle().groupId(servletGroupId).artifactId(servletArtifactId).versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.platform").artifactId("org.eclipse.osgi.util").versionAsInProject());
        res.add(mavenBundle().groupId("org.osgi").artifactId("org.osgi.service.cm").versionAsInProject());
        res.add(mavenBundle().groupId("org.osgi").artifactId("org.osgi.service.device").versionAsInProject());
        res.add(mavenBundle().groupId("org.osgi").artifactId("org.osgi.service.component").versionAsInProject());
        res.add(mavenBundle().groupId("org.osgi").artifactId("org.osgi.service.device").versionAsInProject());
        res.add(mavenBundle().groupId("org.osgi").artifactId("org.osgi.service.event").versionAsInProject());
        res.add(mavenBundle().groupId("org.osgi").artifactId("org.osgi.service.metatype").versionAsInProject());
        res.add(mavenBundle().groupId("org.osgi").artifactId("org.osgi.service.provisioning").versionAsInProject());
        res.add(mavenBundle().groupId("org.osgi").artifactId("org.osgi.service.upnp").versionAsInProject());
        res.add(mavenBundle().groupId("org.osgi").artifactId("org.osgi.service.useradmin").versionAsInProject());
        res.add(mavenBundle().groupId("org.osgi").artifactId("org.osgi.service.wireadmin").versionAsInProject());
        res.add(mavenBundle().groupId("org.osgi").artifactId("org.osgi.util.function").versionAsInProject());
        res.add(mavenBundle().groupId("org.osgi").artifactId("org.osgi.util.function").versionAsInProject());
        res.add(mavenBundle().groupId("org.osgi").artifactId("org.osgi.util.promise").versionAsInProject());
        res.add(mavenBundle().groupId("org.osgi").artifactId("org.osgi.util.measurement").versionAsInProject());
        res.add(mavenBundle().groupId("org.osgi").artifactId("org.osgi.util.position").versionAsInProject());
        res.add(mavenBundle().groupId("org.osgi").artifactId("org.osgi.util.tracker").versionAsInProject());
        res.add(mavenBundle().groupId("org.osgi").artifactId("org.osgi.util.xml").versionAsInProject());
        res.add(mavenBundle().groupId("org.eclipse.platform").artifactId("org.eclipse.osgi.services").versionAsInProject());
        res.add(mavenBundle().groupId("org.osgi").artifactId("org.osgi.service.repository").versionAsInProject());
        res.add(mavenBundle().groupId("org.eclipse.platform").artifactId("org.eclipse.equinox.http.service.api").versionAsInProject());
        res.add(mavenBundle().groupId("org.ow2.asm").artifactId("asm").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.ow2.asm").artifactId("asm-commons").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.ow2.asm").artifactId("asm-tree").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.ow2.asm").artifactId("asm-analysis").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.ow2.asm").artifactId("asm-util").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.apache.aries.spifly").artifactId("org.apache.aries.spifly.dynamic.bundle").versionAsInProject().start());
        String injectGroupId = System.getProperty("inject.groupId", "jakarta.inject");
        String injectArtifactId = System.getProperty("inject.artifactId", "jakarta.inject-api");
        res.add(mavenBundle().groupId(injectGroupId).artifactId(injectArtifactId).versionAsInProject().start());
        String annotationGroupId = System.getProperty("annotation.groupId", "jakarta.annotation");
        String annotationArtifactId = System.getProperty("annotation.artifactId", "jakarta.annotation-api");
        res.add(mavenBundle().groupId(annotationGroupId).artifactId(annotationArtifactId).versionAsInProject().start());
        String enterpriseGroupId = System.getProperty("enterprise.groupId", "jakarta.enterprise");
        String enterpriseArtifactId = System.getProperty("enterprise.artifactId", "jakarta.enterprise.cdi-api");
        res.add(mavenBundle().groupId(enterpriseGroupId).artifactId(enterpriseArtifactId).versionAsInProject().start());
        String interceptorGroupId = System.getProperty("interceptor.groupId", "jakarta.interceptor");
        String interceptorArtifactId = System.getProperty("interceptor.artifactId", "jakarta.interceptor-api");
        res.add(mavenBundle().groupId(interceptorGroupId).artifactId(interceptorArtifactId).versionAsInProject().start());
        String transactionGroupId = System.getProperty("transaction.groupId", "jakarta.transaction");
        String transactionArtifactId = System.getProperty("transaction.artifactId", "jakarta.transaction-api");
        res.add(mavenBundle().groupId(transactionGroupId).artifactId(transactionArtifactId).versionAsInProject().start());
        String elGroupId = System.getProperty("el.groupId", "jakarta.el");
        String elArtifactId = System.getProperty("el.artifactId", "jakarta.el-api");
        res.add(mavenBundle().groupId(elGroupId).artifactId(elArtifactId).versionAsInProject().start());

        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-util").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-io").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-jndi").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-plus").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-security").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-server").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-session").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-deploy").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-http").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-xml").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-jndi").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-osgi").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-client").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-ee").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty.ee9").artifactId("jetty-ee9-security").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty.ee9").artifactId("jetty-ee9-servlet").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty.ee9").artifactId("jetty-ee9-nested").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty.ee9").artifactId("jetty-ee9-webapp").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty.ee9").artifactId("jetty-ee9-servlets").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty.ee9").artifactId("jetty-ee9-jndi").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty.ee9").artifactId("jetty-ee9-plus").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty.ee9").artifactId("jetty-ee9-annotations").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty.websocket").artifactId("jetty-websocket-core-server").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty.websocket").artifactId("jetty-websocket-core-client").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty.websocket").artifactId("jetty-websocket-core-common").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty.ee9.websocket").artifactId("jetty-ee9-websocket-servlet").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty.ee9.websocket").artifactId("jetty-ee9-websocket-jetty-api").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty.ee9.websocket").artifactId("jetty-ee9-websocket-jetty-server").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty.ee9.websocket").artifactId("jetty-ee9-websocket-jetty-client").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty.ee9.websocket").artifactId("jetty-ee9-websocket-jetty-common").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty.toolchain").artifactId("jetty-jakarta-websocket-api").versionAsInProject().noStart());
        res.add(mavenBundle().groupId("org.eclipse.jetty.ee9.websocket").artifactId("jetty-ee9-websocket-jakarta-server").versionAsInProject().noStart());
        res.add(mavenBundle().groupId("org.eclipse.jetty.ee9.websocket").artifactId("jetty-ee9-websocket-jakarta-client").versionAsInProject().noStart());
        res.add(mavenBundle().groupId("org.eclipse.jetty.ee9.websocket").artifactId("jetty-ee9-websocket-jakarta-common").versionAsInProject().noStart());
        res.add(mavenBundle().groupId("org.eclipse.jetty.ee9.osgi").artifactId("jetty-ee9-osgi-boot").versionAsInProject().start());
    }

    public static void coreJspDependencies(List<Option> res)
    {
        //jetty jsp bundles

        /* The coreJettyDependencies() method needs to configure jakarta.el-api to satisfy the jakarta.transaction-api bundle.
         * However, as we are now configuring the full jsp bundle set, we need to remove the jakarta.el-api
         * bundle because the org.mortbay.jasper.apache-el bundle will be providing both the api and the impl.
         */
        String elGroupId = System.getProperty("el.groupId", "jakarta.el");
        String elArtifactId = System.getProperty("el.artifactId", "jakarta.el-api");
        MavenArtifactProvisionOption option = mavenBundle().groupId(elGroupId).artifactId(elArtifactId).versionAsInProject();
        
        ListIterator<Option> iter = res.listIterator();
        while (iter.hasNext())
        {
            Option o = iter.next();
            if (o instanceof MavenArtifactProvisionOption)
            {
                if (((MavenArtifactProvisionOption)o).getURL().contains("jakarta.el-api"))
                {
                    iter.remove();
                }
            }
        }

        res.add(mavenBundle().groupId("org.mortbay.jasper").artifactId("apache-el").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.mortbay.jasper").artifactId("apache-jsp").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty.ee9").artifactId("jetty-ee9-apache-jsp").versionAsInProject().start());
        String jstlGroupId = System.getProperty("jstl.groupId", "jakarta.servlet.jsp.jstl");
        String jstlArtifactId = System.getProperty("jstl.artifactId", "jakarta.servlet.jsp.jstl-api");
        res.add(mavenBundle().groupId(jstlGroupId).artifactId(jstlArtifactId).versionAsInProject());
        res.add(mavenBundle().groupId("org.glassfish.web").artifactId("jakarta.servlet.jsp.jstl").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jdt").artifactId("ecj").versionAsInProject().start());
        res.add(mavenBundle().groupId("org.eclipse.jetty.ee9.osgi").artifactId("jetty-ee9-osgi-boot-jsp").versionAsInProject().noStart());
    }
    
    protected static Bundle getBundle(BundleContext bundleContext, String symbolicName)
    {
        Map<String, Bundle> bundles = new HashMap<>();
        for (Bundle b : bundleContext.getBundles())
        {
            Bundle prevBundle = bundles.put(b.getSymbolicName(), b);
            String err = prevBundle != null ? "2 versions of the bundle " + b.getSymbolicName() +
                " " +
                b.getHeaders().get("Bundle-Version") +
                " and " +
                prevBundle.getHeaders().get("Bundle-Version") : "";
            if (prevBundle != null)
            {
                LOGGER.warn(err);
            }
            // we can't fail for this anymore as inject is adding automatically by pax exam framework
            // but with lower version
            //assertNull(err, prevBundle);
        }
        return bundles.get(symbolicName);
    }

    protected static void diagnoseBundles(BundleContext bundleContext)
    {
        System.err.println("ACTIVE: " + Bundle.ACTIVE);
        System.err.println("RESOLVED: " + Bundle.RESOLVED);
        System.err.println("INSTALLED: " + Bundle.INSTALLED);
        for (Bundle b : bundleContext.getBundles())
        {
            switch (b.getState())
            {
                case Bundle.INSTALLED:
                {
                    //can't start a fragment bundle
                    if (b.getHeaders().get("Fragment-Host") == null)
                    {
                        diagnoseNonActiveOrNonResolvedBundle(b);
                    }
                    dumpBundle(b);
                    break;
                }
                default:
                {
                    dumpBundle(b);
                }
            }
        }
    }
    
    protected static void dumpBundle(Bundle b)
    {
        System.err.println("    " + b.getBundleId() + " " + b.getSymbolicName() + " " + b.getLocation() + " " + b.getVersion() + " " + b.getState());
    }

    protected static void diagnoseNonActiveOrNonResolvedBundle(Bundle b)
    {        
        if (b.getState() != Bundle.ACTIVE && b.getHeaders().get("Fragment-Host") == null)
        {
            try
            {
                System.err.println("Trying to start the bundle " + b.getSymbolicName() + " that was supposed to be active or resolved.");
                b.start();
                System.err.println(b.getSymbolicName() + " did start");
            }
            catch (Throwable t)
            {
                System.err.println(b.getSymbolicName() + " failed to start");
                t.printStackTrace(System.err);
            }
        }
    }

    protected static void dumpBundles(BundleContext bundleContext)
    {
        System.err.println("ACTIVE: " + Bundle.ACTIVE);
        System.err.println("RESOLVED: " + Bundle.RESOLVED);
        System.err.println("INSTALLED: " + Bundle.INSTALLED);
        for (Bundle b : bundleContext.getBundles())
            dumpBundle(b);
    }

    @SuppressWarnings("rawtypes")
    protected static ServiceReference[] getServices(String service, BundleContext bundleContext) throws Exception
    {
        return bundleContext.getAllServiceReferences(service, null);
    }

    protected static SslContextFactory.Client newClientSslContextFactory()
    {
        SslContextFactory.Client sslContextFactory = new SslContextFactory.Client(true);
        sslContextFactory.setEndpointIdentificationAlgorithm(null);
        return sslContextFactory;
    }

    public static void assertContains(String message, String haystack, String needle)
    {
        assertTrue(message + "\nContains: <" + needle + ">\nIn:\n" + haystack, haystack.contains(needle));
    }
}
