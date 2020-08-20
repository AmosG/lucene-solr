/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.solr.util;

import net.sf.saxon.dom.DocumentBuilderImpl;
import org.apache.solr.common.SolrException;
import org.apache.solr.common.util.XML;
import org.apache.solr.core.SolrXmlConfig;
import org.apache.solr.core.XmlConfigFile;
import org.apache.solr.rest.schema.FieldTypeXmlAdapter;
import org.apache.solr.schema.IndexSchema;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathExpressionException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;

abstract public class BaseTestHarness {

  protected final static ThreadLocal<DocumentBuilder> THREAD_LOCAL_DB = new ThreadLocal<>();

  public synchronized static DocumentBuilder getXmlDocumentBuilder() throws ParserConfigurationException {
    DocumentBuilder db = THREAD_LOCAL_DB.get();
    if (db != null) {
      return db;
    } else {
      try {
        db = FieldTypeXmlAdapter.dbf.newDocumentBuilder();
      } catch (ParserConfigurationException e) {
        throw new RuntimeException(e);
      }
      THREAD_LOCAL_DB.set(db);
    }
    return db;
  }

  public static XPath getXpath() {
    return XmlConfigFile.getXpath();
  }


  /**
   * A helper method which validates a String against an array of XPath test
   * strings.
   *
   * @param xml The xml String to validate
   * @param tests Array of XPath strings to test (in boolean mode) on the xml
   * @return null if all good, otherwise the first test that fails.
   */
  public static String validateXPath(String xml, String... tests)
      throws XPathExpressionException, SAXException {

    if (tests==null || tests.length == 0) return null;

    DocumentBuilderImpl b = new DocumentBuilderImpl();
    b.setConfiguration(XmlConfigFile.xpathFactory.getConfiguration());

    Document document;
    try {
      document = b.parse(new ByteArrayInputStream
          (xml.getBytes(StandardCharsets.UTF_8)));
    } catch (IOException e) {
     throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, e);
    }
    for (String xp : tests) {
      xp=xp.trim();
      Boolean bool = (Boolean) getXpath().evaluate(xp, document, XPathConstants.BOOLEAN);

      if (!bool) {
        return xp;
      }
    }
    return null;
  }

  public static Object evaluateXPath(String xml, String xpath, QName returnType)
    throws XPathExpressionException, SAXException {
    if (null == xpath) return null;

    DocumentBuilderImpl b = new DocumentBuilderImpl();
    b.setConfiguration(XmlConfigFile.xpathFactory.getConfiguration());
    Document document;
    try {
      document = b.parse(new ByteArrayInputStream
          (xml.getBytes(StandardCharsets.UTF_8)));
    } catch (IOException e) {
      throw new SolrException(SolrException.ErrorCode.SERVER_ERROR, e);
    }

    xpath = xpath.trim();
    return getXpath().evaluate(xpath.trim(), document, returnType);
  }

  /**
   * A helper that creates an xml &lt;doc&gt; containing all of the
   * fields and values specified
   *
   * @param fieldsAndValues 0 and Even numbered args are fields names odds are field values.
   */
  public static String makeSimpleDoc(String... fieldsAndValues) {

    try {
      StringWriter w = new StringWriter(32);
      w.append("<doc>");
      for (int i = 0; i < fieldsAndValues.length; i+=2) {
        XML.writeXML(w, "field", fieldsAndValues[i + 1], "name",
            fieldsAndValues[i]);
      }
      w.append("</doc>");
      return w.toString();
    } catch (IOException e) {
      throw new RuntimeException
          ("this should never happen with a StringWriter", e);
    }
  }

  /**
   * Generates a delete by query xml string
   * @param q Query that has not already been xml escaped
   * @param args The attributes of the delete tag
   */
  public static String deleteByQuery(String q, String... args) {
    try {
      StringWriter r = new StringWriter(64);
      XML.writeXML(r, "query", q);
      return delete(r.getBuffer().toString(), args);
    } catch(IOException e) {
      throw new RuntimeException
          ("this should never happen with a StringWriter", e);
    }
  }

  /**
   * Generates a delete by id xml string
   * @param id ID that has not already been xml escaped
   * @param args The attributes of the delete tag
   */
  public static String deleteById(String id, String... args) {
    try {
      StringWriter r = new StringWriter();
      XML.writeXML(r, "id", id);
      return delete(r.getBuffer().toString(), args);
    } catch(IOException e) {
      throw new RuntimeException
          ("this should never happen with a StringWriter", e);
    }
  }

  /**
   * Generates a delete xml string
   * @param val text that has not already been xml escaped
   * @param args 0 and Even numbered args are params, Odd numbered args are XML escaped values.
   */
  private static String delete(String val, String... args) {
    try {
      StringWriter r = new StringWriter();
      XML.writeUnescapedXML(r, "delete", val, (Object[]) args);
      return r.getBuffer().toString();
    } catch(IOException e) {
      throw new RuntimeException
          ("this should never happen with a StringWriter", e);
    }
  }

  /**
   * Helper that returns an &lt;optimize&gt; String with
   * optional key/val pairs.
   *
   * @param args 0 and Even numbered args are params, Odd numbered args are values.
   */
  public static String optimize(String... args) {
    return simpleTag("optimize", args);
  }

  public static String simpleTag(String tag, String... args) {
    try {
      StringWriter writer = new StringWriter();
      XML.writeXML(writer, tag, (String) null, (Object[])args);
      return writer.getBuffer().toString();
    } catch (IOException e) {
      throw new RuntimeException
          ("this should never happen with a StringWriter", e);
    }
  }

  /**
   * Helper that returns an &lt;commit&gt; String with
   * optional key/val pairs.
   *
   * @param args 0 and Even numbered args are params, Odd numbered args are values.
   */
  public static String commit(String... args) {
    return simpleTag("commit", args);
  }

  /** Reloads the core */
  abstract public void reload() throws Exception;

  /**
   * Processes an "update" (add, commit or optimize) and
   * returns the response as a String.
   * 
   * This method does NOT commit after the request.
   *
   * @param xml The XML of the update
   * @return The XML response to the update
   */
  abstract public String update(String xml);

  /**
   * Validates that an "update" (add, commit or optimize) results in success.
   *
   * :TODO: currently only deals with one add/doc at a time, this will need changed if/when SOLR-2 is resolved
   *
   * @param xml The XML of the update
   * @return null if successful, otherwise the XML response to the update
   */
  public String validateUpdate(String xml) throws SAXException {
    return checkUpdateStatus(xml, "0");
  }

  /**
   * Validates that an "update" (add, commit or optimize) results in success.
   *
   * :TODO: currently only deals with one add/doc at a time, this will need changed if/when SOLR-2 is resolved
   *
   * @param xml The XML of the update
   * @return null if successful, otherwise the XML response to the update
   */
  public String validateErrorUpdate(String xml) throws SAXException {
    try {
      return checkUpdateStatus(xml, "1");
    } catch (SolrException e) {
      // return ((SolrException)e).getMessage();
      return null;  // success
    }
  }

  /**
   * Validates that an "update" (add, commit or optimize) results in success.
   *
   * :TODO: currently only deals with one add/doc at a time, this will need changed if/when SOLR-2 is resolved
   *
   * @param xml The XML of the update
   * @return null if successful, otherwise the XML response to the update
   */
  public String checkUpdateStatus(String xml, String code) throws SAXException {
    try {
      String res = update(xml);
      String valid = validateXPath(res, "//int[@name='status']="+code );
      return (null == valid) ? null : res;
    } catch (XPathExpressionException e) {
      throw new RuntimeException
          ("?!? static xpath has bug?", e);
    }
  }
}
