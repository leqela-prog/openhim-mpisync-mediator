/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.openhim.mediator.denormalization;

import java.text.SimpleDateFormat;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import akka.actor.ActorRef;
import akka.actor.ActorSelection;
import akka.actor.UntypedActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.model.v25.message.ACK;
import ca.uhn.hl7v2.model.v25.message.ADT_A01;
import ca.uhn.hl7v2.model.v25.message.ADT_A39;
import ca.uhn.hl7v2.model.v25.message.QBP_Q21;
import ca.uhn.hl7v2.model.v25.message.RSP_K23;
import ca.uhn.hl7v2.model.v25.segment.MSH;
import ca.uhn.hl7v2.parser.GenericParser;
import ca.uhn.hl7v2.parser.Parser;
import ca.uhn.hl7v2.util.Terser;
import org.openhim.mediator.datatypes.AssigningAuthority;
import org.openhim.mediator.datatypes.Identifier;
import org.openhim.mediator.engine.MediatorConfig;
import org.openhim.mediator.engine.messages.ExceptError;
import org.openhim.mediator.engine.messages.MediatorRequestMessage;
import org.openhim.mediator.engine.messages.MediatorSocketRequest;
import org.openhim.mediator.engine.messages.MediatorSocketResponse;
import org.openhim.mediator.messages.ATNAAudit;
import org.openhim.mediator.messages.RegisterNewPatient;
import org.openhim.mediator.messages.RegisterNewPatientXds;
import org.openhim.mediator.messages.MergePatientXds;
import org.openhim.mediator.messages.RegisterNewPatientResponse;
import org.openhim.mediator.messages.ResolvePatientIdentifier;
import org.openhim.mediator.messages.ResolvePatientIdentifierResponse;

/**
 * Actor for processing PIX messages.
 * <br/><br/>
 * Supports identifier cross-referencing requests (QBP_Q21) and Patient Identity Feed (ADT_A04).
 * <br/><br/>
 * Messages supported:
 * <ul>
 * <li>ResolvePatientIdentifier - responds with ResolvePatientIdentifierResponse. The identifier returned will be null if the id could not be resolved.</li>
 * <li>RegisterNewPatient - responds with RegisterNewPatientResponse</li>
 * </ul>
 */
public class PIXRequestActor extends UntypedActor {
    LoggingAdapter log = Logging.getLogger(getContext().system(), this);

    private MediatorConfig config;

    private Map<String, MediatorRequestMessage> originalRequests = new HashMap<>();
    private ActorRef requestHandler;

    private static final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMddHHmmssZ");
    private static final SimpleDateFormat dateFormatDay = new SimpleDateFormat("yyyyMMdd");


    public PIXRequestActor(MediatorConfig config) {
        this.config = config;
    }


    private void constructBasicMSHSegment(String correlationId, Terser t) throws HL7Exception {
        MSH msh = (MSH) t.getSegment("MSH");
        t.set("MSH-1", "|");
        t.set("MSH-2", "^~\\&");
        t.set("MSH-3-1", config.getProperty("pix.sendingApplication"));
        t.set("MSH-4-1", config.getProperty("pix.sendingFacility"));
        t.set("MSH-5-1", config.getProperty("pix.receivingApplication"));
        t.set("MSH-6-1", config.getProperty("pix.receivingFacility"));
        msh.getDateTimeOfMessage().getTime().setValue(dateFormat.format(new Date()));
        t.set("MSH-10", correlationId);
        t.set("MSH-11-1", "P");
        t.set("MSH-12-1-1", "2.3.1");
    }

    public String constructADT_A40(String correlationId, MergePatientXds msg) throws HL7Exception {

        ADT_A39 adt_a39 = new ADT_A39();
        Terser t = new Terser(adt_a39);

        constructBasicMSHSegment(correlationId, t);

        t.set("MSH-9-1", "ADT");
        t.set("MSH-9-2", "A40");
        t.set("MSH-9-3", "ADT_A39");

        t.set("EVN-2", dateFormatDay.format(new Date()));

        for (Identifier id : msg.getPatientIdentifiers()) {
            t.set("/PATIENT(0)/PID-3-1", id.getIdentifier());
            t.set("/PATIENT(0)/PID-3-4", id.getAssigningAuthority().getAssigningAuthority());
            t.set("/PATIENT(0)/PID-3-4-2", id.getAssigningAuthority().getAssigningAuthorityId());
            t.set("/PATIENT(0)/PID-3-4-3", id.getAssigningAuthority().getAssigningAuthorityIdType());
        }

        for(Identifier id : msg.getPreUpdateIdentifiers()) {
            t.set("/PATIENT(0)/MRG-1-1", id.getIdentifier());
            t.set("/PATIENT(0)/MRG-1-4", id.getAssigningAuthority().getAssigningAuthority());
            t.set("/PATIENT(0)/MRG-1-4-2", id.getAssigningAuthority().getAssigningAuthorityId());
            t.set("/PATIENT(0)/MRG-1-4-3", id.getAssigningAuthority().getAssigningAuthorityIdType());
        }
        
        Parser p = new GenericParser();

        return p.encode(adt_a39);
    }


    public String constructADT_A04(String correlationId, RegisterNewPatientXds msg) throws HL7Exception {

        ADT_A01 adt_a04 = new ADT_A01();
        Terser t = new Terser(adt_a04);

        constructBasicMSHSegment(correlationId, t);

        t.set("MSH-9-1", "ADT");
        t.set("MSH-9-2", "A04");
        t.set("MSH-9-3", "ADT_A01");

        t.set("EVN-2", dateFormatDay.format(new Date()));

        for (Identifier id : msg.getPatientIdentifiers()) {
            t.set("PID-3-1", id.getIdentifier());
            t.set("PID-3-4", id.getAssigningAuthority().getAssigningAuthority());
            t.set("PID-3-4-2", id.getAssigningAuthority().getAssigningAuthorityId());
            t.set("PID-3-4-3", id.getAssigningAuthority().getAssigningAuthorityIdType());
        }

        t.set("PV1-2", "O");
        
        Parser p = new GenericParser();

        return p.encode(adt_a04);
    }

    private void syncXDSRegistryPixFeed(RegisterNewPatientXds msg) {
        try {
            String correlationId = UUID.randomUUID().toString();
            String pixRequest = constructADT_A04(correlationId, msg);
            originalRequests.put(correlationId, msg);

            int port = Integer.parseInt(config.getProperty("xds.registry.port"));

            ActorSelection connector = getContext().actorSelection(config.userPathFor("mllp-connector"));
            MediatorSocketRequest request = new MediatorSocketRequest(
                    msg.getRequestHandler(), getSelf(), "Sync XDS Registry PixFeed", correlationId,
                    config.getProperty("xds.registry.host"), port, pixRequest);
            connector.tell(request, getSelf());

        } catch (HL7Exception ex) {
            msg.getRequestHandler().tell(new ExceptError(ex), getSelf());
        }
    }

    private void syncXDSRegistryMergeFeed(MergePatientXds msg) {
        try {
            String correlationId = UUID.randomUUID().toString();
            String pixRequest = constructADT_A40(correlationId, msg);

            originalRequests.put(correlationId, msg);

            int port = Integer.parseInt(config.getProperty("xds.registry.port"));

            ActorSelection connector = getContext().actorSelection(config.userPathFor("mllp-connector"));
            MediatorSocketRequest request = new MediatorSocketRequest(
                    msg.getRequestHandler(), getSelf(), "Sync XDS Registry PixFeed", correlationId,
                    config.getProperty("xds.registry.host"), port, pixRequest);
            connector.tell(request, getSelf());

        } catch (HL7Exception ex) {
            msg.getRequestHandler().tell(new ExceptError(ex), getSelf());
        }
    }

    private void processSyncXdsADT_A04Response(MediatorSocketResponse msg, RegisterNewPatientXds originalRequest) {
        String err = null;
        log.info(msg.getBody());
        try {
            err = parseACKError(msg.getBody());
            originalRequest.getRespondTo().tell(new RegisterNewPatientResponse(originalRequest, err == null, err), getSelf());
        } catch (HL7Exception ex) {
            msg.getOriginalRequest().getRequestHandler().tell(new ExceptError(ex), getSelf());
        } finally {
            Identifier pid = originalRequest.getPatientIdentifiers().get(0);
        }
    }

    private void processSyncXdsADT_A40Response(MediatorSocketResponse msg, MergePatientXds originalRequest) {
        String err = null;
        log.info(msg.getBody());
        try {
            err = parseACKError(msg.getBody());
            originalRequest.getRespondTo().tell(new RegisterNewPatientResponse(originalRequest, err == null, err), getSelf());
        } catch (HL7Exception ex) {
            msg.getOriginalRequest().getRequestHandler().tell(new ExceptError(ex), getSelf());
        } finally {
            Identifier pid = originalRequest.getPatientIdentifiers().get(0);
        }
    }

    private String parseACKError(String response) throws HL7Exception {
        Parser parser = new GenericParser();
        Object parsedMsg = parser.parse(response);

        //Terser terser = new Terser(parsedMsg);
        Message modelmsg = (Message)parsedMsg;
        Terser terser = new Terser(modelmsg);

        /*if (!(parsedMsg instanceof ACK)) {
            return "Message response received in unsupported format: " + parsedMsg.getClass();
        }*/
        if (!("ACK".equalsIgnoreCase(terser.get("/.MSH-9-1")))){
            return "Message response received in unsupported format: " + modelmsg.getClass();
        }

        /*ACK msg = (ACK)parsedMsg;
        if (msg.getMSA()!=null && msg.getMSA().getAcknowledgmentCode()!=null &&
                "AA".equalsIgnoreCase(msg.getMSA().getAcknowledgmentCode().getValue())) {
            return null;
        }*/

        if (terser.get("/.MSA-1") != null && "AA".equalsIgnoreCase(terser.get("/.MSA-1"))){
            return null;
        }

        String err = "Failed to register new patient:\n";

        /*if (msg.getERR()!=null && msg.getERR().getErr3_HL7ErrorCode()!=null) {
            if (msg.getERR().getErr3_HL7ErrorCode().getCwe1_Identifier()!=null) {
                err += msg.getERR().getErr3_HL7ErrorCode().getCwe1_Identifier().getValue() + "\n";
            }
            if (msg.getERR().getErr3_HL7ErrorCode().getCwe2_Text()!=null) {
                err += msg.getERR().getErr3_HL7ErrorCode().getCwe2_Text().getValue() + "\n";
            }
        }*/

        return err;
    }

    private void processResponse(MediatorSocketResponse msg) {
        MediatorRequestMessage originalRequest = originalRequests.remove(msg.getOriginalRequest().getCorrelationId());

        if (originalRequest instanceof RegisterNewPatientXds) {
            log.info("Sync XDS PIX Feed Success.");
            log.info(msg.getBody());
            processSyncXdsADT_A04Response(msg, (RegisterNewPatientXds) originalRequest);
        } else if(originalRequest instanceof MergePatientXds) {
            log.info("Sync XDS PIX Merge Feed Success.");
            log.info(msg.getBody());
            processSyncXdsADT_A40Response(msg, (MergePatientXds) originalRequest);
        } else {

        }
    }

    @Override
    public void onReceive(Object msg) throws Exception {
        if (msg instanceof MediatorSocketResponse) {
            processResponse((MediatorSocketResponse) msg);
        } else if (msg instanceof RegisterNewPatientXds) {
            log.info("Received Sync Pix Feed to XDS Registry to register new patient");
            syncXDSRegistryPixFeed((RegisterNewPatientXds) msg);
        } else if (msg instanceof MergePatientXds) {
            log.info("Received Sync Pix Feed to XDS Registry to merge existing patients");
            syncXDSRegistryMergeFeed((MergePatientXds) msg);
        } else {
            unhandled(msg);
        }
    }
}
