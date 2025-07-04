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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.ConcurrentHashMap;
import javax.inject.Inject;

import aQute.bnd.osgi.Constants;
import org.eclipse.jetty.ee9.annotations.ClassInheritanceHandler;
import org.eclipse.jetty.ee9.osgi.annotations.AnnotationParser;
import org.eclipse.jetty.util.resource.ResourceFactory;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.Configuration;
import org.ops4j.pax.exam.CoreOptions;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.junit.PaxExam;
import org.ops4j.pax.tinybundles.TinyBundle;
import org.ops4j.pax.tinybundles.TinyBundles;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleContext;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.io.FileMatchers.anExistingFile;
import static org.ops4j.pax.exam.CoreOptions.mavenBundle;

/**
 * TestJettyOSGiAnnotationParser
 *
 */

@RunWith(PaxExam.class)
public class TestJettyOSGiAnnotationParser
{
    @Inject
    BundleContext bundleContext = null;

    @Configuration
    public static Option[] configure() throws IOException
    {
        ArrayList<Option> options = new ArrayList<>();
        options.add(TestOSGiUtil.optionalRemoteDebug());
        options.add(CoreOptions.junitBundles());
        TestOSGiUtil.coreJettyDependencies(options);
        TestOSGiUtil.coreJspDependencies(options);
        //The jetty-alpn-client jars aren't used by this test, but as
        //TestOSGiUtil.coreJettyDependencies deploys the jetty-client,
        //we need them deployed to satisfy the dependency.
        options.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-alpn-java-client").versionAsInProject().start());
        options.add(mavenBundle().groupId("org.eclipse.jetty").artifactId("jetty-alpn-client").versionAsInProject().start());

        //get a reference to a pre-prepared module-info
        Path moduleInfo = Paths.get("target", "test-classes", "module-info.clazz");
        assertThat(moduleInfo.toFile(), anExistingFile());
        
        TinyBundle bundle = TinyBundles.bundle();
        bundle.setHeader(Constants.BUNDLE_SYMBOLICNAME, "bundle.with.module.info");
        bundle.addResource("module-info.class", Files.newInputStream(moduleInfo)); //copy it into the fake bundle
        options.add(CoreOptions.streamBundle(bundle.build()).startLevel(1));
        return options.toArray(new Option[options.size()]);
    }

    @Test
    public void testParse() throws Exception
    {
        if (Boolean.getBoolean(TestOSGiUtil.BUNDLE_DEBUG))
            TestOSGiUtil.diagnoseBundles(bundleContext);
        
        //test the osgi annotation parser ignore the module-info.class file in the fake bundle
        //Get a reference to the deployed fake bundle
        Bundle b = TestOSGiUtil.getBundle(bundleContext, "bundle.with.module.info");
        AnnotationParser parser = new AnnotationParser();
        parser.indexBundle(ResourceFactory.root(), b);
        ClassInheritanceHandler handler = new ClassInheritanceHandler(new ConcurrentHashMap<>());
        parser.parse(Collections.singleton(handler), b);

    }
}
