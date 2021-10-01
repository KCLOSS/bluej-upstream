/*
 * Copyright (c) 2003, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 * 
 * --- end of original header ---
 * 
 * This file was modified for use in the BlueJ program on the 1st September 2011.
 * 
 */
package bluej.doclet.doclets.internal.toolkit.builders;

import bluej.doclet.doclets.internal.toolkit.*;
import bluej.doclet.doclets.internal.toolkit.util.*;

import java.io.*;
import java.util.*;
import org.xml.sax.*;
import org.xml.sax.helpers.DefaultHandler;
import javax.xml.parsers.*;

/**
 * Parse the XML that specified the order of operation for the builders.  This
 * Parser uses SAX parsing.
 *
 * @author Jamie Ho
 * @since 1.5
 * @see SAXParser
 */
public class LayoutParser extends DefaultHandler {

    /**
     * The map of XML elements that have been parsed.
     */
    private Map xmlElementsMap;

    private Configuration configuration;
    private static LayoutParser instance;
    private String currentRoot;
    private boolean isParsing;

    /**
     * This class is a singleton.
     */
    private LayoutParser(Configuration configuration) {
        xmlElementsMap = new HashMap();
        this.configuration = configuration;
    }

    /**
     * Return an instance of the BuilderXML.
     *
     * @param configuration the current configuration of the doclet.
     * @return an instance of the BuilderXML.
     */
    public static LayoutParser getInstance(Configuration configuration) {
        if (instance == null) {
            instance = new LayoutParser(configuration);
        }
        return instance;
    }

    /**
     * Parse the XML specifying the layout of the documentation.
     *
     * @return List the list of XML elements parsed.
     */
    public List parseXML(String root) {
        if (xmlElementsMap.containsKey(root)) {
            return (List) xmlElementsMap.get(root);
        }
        try {
            List xmlElements = new ArrayList();
            xmlElementsMap.put(root, xmlElements);
            currentRoot = root;
            isParsing = false;
            SAXParserFactory factory = SAXParserFactory.newInstance();
            SAXParser saxParser = factory.newSAXParser();
            InputStream in = configuration.getBuilderXML();
            saxParser.parse(in, this);
            return xmlElements;
        } catch (Throwable t) {
            t.printStackTrace();
            throw new DocletAbortException();
        }
    }

    /**
     * {@inheritDoc}
     */
    public void startElement(String namespaceURI, String sName, String qName,
        Attributes attrs)
    throws SAXException {
        if (isParsing || qName.equals(currentRoot)) {
            isParsing = true;
            List xmlElements = (List) xmlElementsMap.get(currentRoot);
            xmlElements.add(qName);
        }
    }

    /**
     * {@inheritDoc}
     */
    public void endElement(String namespaceURI, String sName, String qName)
    throws SAXException {
        if (! isParsing) {
            isParsing = false;
            return;
        }
        List xmlElements = (List) xmlElementsMap.get(currentRoot);
        if (xmlElements.get(xmlElements.size()-1).equals(qName)) {
            return;
        } else {
            List subElements = new ArrayList();
            int targetIndex = xmlElements.indexOf(qName);
            int size = xmlElements.size();
            for (int i = targetIndex; i < size; i++) {
                subElements.add(xmlElements.get(targetIndex));
                xmlElements.remove(targetIndex);
            }
            //Save the sub elements as a list.
            xmlElements.add(subElements);
        }
        isParsing = ! qName.equals(currentRoot);
    }
}
