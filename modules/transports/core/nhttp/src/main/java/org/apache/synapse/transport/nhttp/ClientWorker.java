/*
 *  Licensed to the Apache Software Foundation (ASF) under one
 *  or more contributor license agreements.  See the NOTICE file
 *  distributed with this work for additional information
 *  regarding copyright ownership.  The ASF licenses this file
 *  to you under the Apache License, Version 2.0 (the
 *  "License"); you may not use this file except in compliance
 *  with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 *  KIND, either express or implied.  See the License for the
 *  specific language governing permissions and limitations
 *  under the License.
 */
package org.apache.synapse.transport.nhttp;

import org.apache.axiom.om.OMException;
import org.apache.axiom.soap.*;
import org.apache.axiom.soap.impl.llom.soap11.SOAP11Factory;
import org.apache.axis2.AxisFault;
import org.apache.axis2.Constants;
import org.apache.axis2.builder.BuilderUtil;
import org.apache.axis2.context.ConfigurationContext;
import org.apache.axis2.context.MessageContext;
import org.apache.axis2.description.WSDL2Constants;
import org.apache.axis2.description.Parameter;
import org.apache.axis2.engine.AxisEngine;
import org.apache.axis2.transport.TransportUtils;
import org.apache.axis2.transport.http.HTTPTransportUtils;
import org.apache.axis2.wsdl.WSDLConstants;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.http.Header;
import org.apache.http.HttpResponse;
import org.apache.http.protocol.HTTP;
import org.apache.synapse.transport.nhttp.debug.ClientConnectionDebug;

import javax.xml.stream.XMLStreamException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Comparator;
import java.util.Map;
import java.util.TreeMap;

/**
 * Performs processing of the HTTP response received for our outgoing request. An instance of this
 * class is created to process each unique response.
 */
public class ClientWorker implements Runnable {

    private static final Log log = LogFactory.getLog(ClientWorker.class);

    /** the Axis2 configuration context */
    private ConfigurationContext cfgCtx = null;
    /** the response message context that would be created */
    private MessageContext responseMsgCtx = null;
    /** the InputStream out of which the response body should be read */
    private InputStream in = null;
    /** the HttpResponse received */
    private HttpResponse response = null;
    /** the endpoint URL prefix */
    private String endpointURLPrefix = null;

    /**
     * Create the thread that would process the response message received for the outgoing message
     * context sent
     * @param cfgCtx the Axis2 configuration context
     * @param in the InputStream to read the body of the response message received
     * @param response HTTP response received from the server
     * @param outMsgCtx the original outgoing message context (i.e. corresponding request)
     * @param endpointURLPrefix The endpoint URL prefix
     */
    public ClientWorker(ConfigurationContext cfgCtx, InputStream in,
        HttpResponse response, MessageContext outMsgCtx, String endpointURLPrefix) {

        this.cfgCtx = cfgCtx;
        this.in = in;
        this.response = response;
        this.endpointURLPrefix = endpointURLPrefix;

        try {
            responseMsgCtx = outMsgCtx.getOperationContext().
                getMessageContext(WSDL2Constants.MESSAGE_LABEL_IN);
            // fix for RM to work because of a soapAction and wsaAction conflict
            if (responseMsgCtx != null) {
                responseMsgCtx.setSoapAction("");
            }
        } catch (AxisFault af) {
            log.error("Error getting IN message context from the operation context", af);
            return;
        }

        // this conditional block is to support Sandesha, as it uses an out-in mep, but without
        // creating the message context to write the response and adding it into the operation
        // context, as it may get a 202 accepted or 200. So if the operation is complete ignore
        // this message, else, create a new message context and handle this
        if (responseMsgCtx == null && outMsgCtx.getOperationContext().isComplete()) {

            if (log.isDebugEnabled()) {
                log.debug("Error getting IN message context from the operation context. " +
                        "Possibly an RM terminate sequence message");
            }

        } else {
            if (responseMsgCtx == null) {
                responseMsgCtx = new MessageContext();
                responseMsgCtx.setOperationContext(outMsgCtx.getOperationContext());
            }

            responseMsgCtx.setServerSide(true);
            responseMsgCtx.setDoingREST(outMsgCtx.isDoingREST());
            responseMsgCtx.setProperty(MessageContext.TRANSPORT_IN, outMsgCtx
                .getProperty(MessageContext.TRANSPORT_IN));
            responseMsgCtx.setTransportIn(outMsgCtx.getTransportIn());
            responseMsgCtx.setTransportOut(outMsgCtx.getTransportOut());

            // set any transport headers received
            Header[] headers = response.getAllHeaders();
            if (headers != null && headers.length > 0) {

                Map<String, String> headerMap
                        = new TreeMap<String, String>(new Comparator<String>() {
                    public int compare(String o1, String o2) {
                        return o1.compareToIgnoreCase(o2);
                    }
                });
                
                for (int i=0; i<headers.length; i++) {
                    Header header = headers[i];
                    if ("Location".equals(header.getName())
                        && endpointURLPrefix != null
                        && outMsgCtx.getProperty(NhttpConstants.SERVICE_PREFIX) != null) {
                        
                        headerMap.put(header.getName(),
                            header.getValue().replaceAll(endpointURLPrefix,
                                (String) outMsgCtx.getProperty(NhttpConstants.SERVICE_PREFIX)));
                    } else {
                        headerMap.put(header.getName(), header.getValue());
                    }
                }
                responseMsgCtx.setProperty(MessageContext.TRANSPORT_HEADERS, headerMap);
            }

            responseMsgCtx.setAxisMessage(outMsgCtx.getOperationContext().getAxisOperation().
                getMessage(WSDLConstants.MESSAGE_LABEL_IN_VALUE));
            responseMsgCtx.setOperationContext(outMsgCtx.getOperationContext());
            responseMsgCtx.setConfigurationContext(outMsgCtx.getConfigurationContext());
            responseMsgCtx.setTo(null);

            // Ensure MessageContext has a ClientConnectionDebug attached before we start streaming
            ClientConnectionDebug cd = (ClientConnectionDebug)
                outMsgCtx.getProperty(ClientHandler.CLIENT_CONNECTION_DEBUG);
            if (cd != null) {
                responseMsgCtx.setProperty(ClientHandler.CLIENT_CONNECTION_DEBUG, cd);
            }
        }
    }

    /**
     * Process the received response through Axis2
     */
    public void run() {
        // to support Sandesha.. if there isn't a response message context, we cannot read any
        // response and populate it with the soap envelope
        if (responseMsgCtx == null) {
            return;
        }

        try {
            if (in != null) {
                Header cType = response.getFirstHeader(HTTP.CONTENT_TYPE);
                String contentType;
                if (cType != null) {
                    // This is the most common case - Most of the time servers send the Content-Type
                    contentType = cType.getValue();
                } else {
                    // Server hasn't sent the header - Try to infer the content type
                    contentType = inferContentType();
                }

                String charSetEnc = BuilderUtil.getCharSetEncoding(contentType);
                if (charSetEnc == null) {
                    charSetEnc = MessageContext.DEFAULT_CHAR_SET_ENCODING;
                }

                responseMsgCtx.setProperty(
                        Constants.Configuration.CHARACTER_SET_ENCODING, charSetEnc);

                // workaround for Axis2 TransportUtils.createSOAPMessage() issue, where a response
                // of content type "text/xml" is thought to be REST if !MC.isServerSide(). This
                // question is still under debate and due to the timelines, I am commiting this
                // workaround as Axis2 1.2 is about to be released and Synapse 1.0
                responseMsgCtx.setServerSide(false);
                SOAPEnvelope envelope;
                try {
                    envelope = TransportUtils.createSOAPMessage(
                            responseMsgCtx,
                            HTTPTransportUtils.handleGZip(responseMsgCtx, in),
                            contentType);

                } catch (OMException e) {
                    // handle non SOAP and POX/REST payloads (probably text/html)
                    String errorMessage = "Unexpected response received. HTTP response code : "
                        + this.response.getStatusLine().getStatusCode() + " HTTP status : "
                        + this.response.getStatusLine().getReasonPhrase() + " exception : "
                        + e.getMessage();

                    log.warn(errorMessage);
                    if (log.isDebugEnabled()) {
                        log.debug(errorMessage, e);
                        log.debug("Creating the SOAPFault to be injected...");
                    }
                    SOAPFactory factory = new SOAP11Factory();
                    envelope = factory.getDefaultFaultEnvelope();
                    SOAPFaultDetail detail = factory.createSOAPFaultDetail();
                    detail.setText(errorMessage);
                    envelope.getBody().getFault().setDetail(detail);
                    SOAPFaultReason reason = factory.createSOAPFaultReason();
                    reason.setText(errorMessage);
                    envelope.getBody().getFault().setReason(reason);
                    SOAPFaultCode code = factory.createSOAPFaultCode();
                    code.setText(Integer.toString(this.response.getStatusLine().getStatusCode()));
                    envelope.getBody().getFault().setCode(code);
                }
                responseMsgCtx.setServerSide(true);
                responseMsgCtx.setEnvelope(envelope);

            } else {
                // there is no response entity-body
                responseMsgCtx.setProperty(NhttpConstants.NO_ENTITY_BODY, Boolean.TRUE);
                responseMsgCtx.setEnvelope(new SOAP11Factory().getDefaultEnvelope());
            }

            // copy the HTTP status code as a message context property with the key HTTP_SC to be
            // used at the sender to set the propper status code when passing the message
            int statusCode = this.response.getStatusLine().getStatusCode();
            responseMsgCtx.setProperty(NhttpConstants.HTTP_SC, statusCode);
            if (statusCode >= 400) {
                responseMsgCtx.setProperty(NhttpConstants.FAULT_MESSAGE, NhttpConstants.TRUE);
            }
            responseMsgCtx.setProperty(NhttpConstants.NON_BLOCKING_TRANSPORT, true);
            if (endpointURLPrefix != null) {
                responseMsgCtx.setProperty(NhttpConstants.ENDPOINT_PREFIX, endpointURLPrefix);
            }

            // process response received
            try {
                AxisEngine.receive(responseMsgCtx);
            } catch (AxisFault af) {
                log.error("Fault processing response message through Axis2", af);
            }

        } catch (AxisFault af) {
            log.error("Fault creating response SOAP envelope", af);
            return;
        } catch (XMLStreamException e) {
            log.error("Error creating response SOAP envelope", e);
        } catch (IOException e) {
            log.error("Error closing input stream from which message was read", e);

        } finally {
            // this is the guaranteed location to close the RESPONSE_SOURCE_CHANNEL that was used
            // to read the response back from the server.
            try {
                if (in != null) {
                    in.close();
                }
            } catch (IOException ignore) {}
        }
    }

    private String inferContentType() {
        // Try to get the content type from the message context
        Object cTypeProperty = responseMsgCtx.getProperty(NhttpConstants.CONTENT_TYPE);
        if (cTypeProperty != null) {
            return cTypeProperty.toString();
        }

        // Try to get the content type from the axis configuration
        Parameter cTypeParam = cfgCtx.getAxisConfiguration().getParameter(
                NhttpConstants.CONTENT_TYPE);
        if (cTypeParam != null) {
            return cTypeParam.getValue().toString();
        }

        // Unable to determine the content type - Return default value
        return NhttpConstants.DEFAULT_CONTENT_TYPE;
    }
}
