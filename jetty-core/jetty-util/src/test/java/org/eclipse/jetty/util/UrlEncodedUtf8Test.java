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

package org.eclipse.jetty.util;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;

public class UrlEncodedUtf8Test
{
    @ParameterizedTest
    @CsvSource(delimiter = '|', useHeadersInDisplayName = false,
        textBlock = """
        # query         | expectedName | expectedValue
        a=bad_%e0%b     | a            | bad_��
        b=bad_%e0%ba    | b            | bad_�
        c=short%a       | c            | short�
        d=b%aam         | d            | b�m
        e=%%TOK%%       | e            | �OK�
        f=%aardvark     | f            | �rdvark
        g=b%ar          | g            | b�
        h=end%          | h            | end�
        # This shows how the '&' symbol gets swallowed by a pct-encoding effort.
        i=%&z=2         | i            | �=2
        """)
    public void testDecodeAllowBadSequence(String query, String expectedName, String expectedValue)
    {
        Fields fields = new Fields();
        UrlEncoded.decodeUtf8To(query, 0, query.length(), fields::add, true);
        Fields.Field field = fields.get(expectedName);
        assertThat("Name exists", field, notNullValue());
        assertThat("Value", field.getValue(), is(expectedValue));
    }

    @Test
    public void testIncompleteSequestAtTheEnd() throws Exception
    {
        byte[] bytes = {97, 98, 61, 99, -50};
        String test = new String(bytes, StandardCharsets.UTF_8);
        String expected = "c" + Utf8StringBuilder.REPLACEMENT;

        fromString(test, test, "ab", expected);
        fromInputStream(test, bytes, "ab", expected);
    }

    @Test
    public void testIncompleteSequestAtTheEnd2() throws Exception
    {
        byte[] bytes = {97, 98, 61, -50};
        String test = new String(bytes, StandardCharsets.UTF_8);
        String expected = "" + Utf8StringBuilder.REPLACEMENT;

        fromString(test, test, "ab", expected);
        fromInputStream(test, bytes, "ab", expected);
    }

    @Test
    public void testIncompleteSequestInName() throws Exception
    {
        byte[] bytes = {101, -50, 61, 102, 103, 38, 97, 98, 61, 99, 100};
        String test = new String(bytes, StandardCharsets.UTF_8);
        String name = "e" + Utf8StringBuilder.REPLACEMENT;
        String value = "fg";

        fromString(test, test, name, value);
        fromInputStream(test, bytes, name, value);
    }

    @Test
    public void testIncompleteSequestInValue() throws Exception
    {
        byte[] bytes = {101, 102, 61, 103, -50, 38, 97, 98, 61, 99, 100};
        String test = new String(bytes, StandardCharsets.UTF_8);
        String name = "ef";
        String value = "g" + Utf8StringBuilder.REPLACEMENT;

        fromString(test, test, name, value);
        fromInputStream(test, bytes, name, value);
    }

    static void fromString(String test, String s, String field, String expected)
    {
        Fields values = new Fields();
        UrlEncoded.decodeUtf8To(s, 0, s.length(), values);
        assertThat(test, values.getValue(field), is(expected));
    }

    static void fromInputStream(String test, byte[] b, String field, String expected) throws Exception
    {
        try (InputStream is = new ByteArrayInputStream(b))
        {
            Fields values = new Fields();
            UrlEncoded.decodeUtf8To(is, values, 1000000, -1);
            assertThat(test, values.getValue(field), is(expected));
        }
    }
}
