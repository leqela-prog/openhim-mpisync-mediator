package org.openhim.mediator;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.UUID;
import java.util.List;

import akka.actor.ActorRef;
import akka.actor.ActorSelection;
import akka.actor.Props;
import akka.actor.UntypedActor;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import ca.uhn.hl7v2.model.Segment;
import ca.uhn.hl7v2.model.Type;
import ca.uhn.hl7v2.parser.EncodingCharacters;
import org.apache.http.HttpStatus;
import org.openhim.mediator.denormalization.PIXRequestActor;
import org.openhim.mediator.engine.MediatorConfig;
import org.openhim.mediator.engine.messages.MediatorHTTPRequest;
import org.openhim.mediator.engine.messages.MediatorHTTPResponse;
import org.openhim.mediator.engine.messages.MediatorSocketRequest;
import org.openhim.mediator.engine.messages.MediatorSocketResponse;

import ca.uhn.hl7v2.model.Message;
import ca.uhn.hl7v2.model.Structure;
import ca.uhn.hl7v2.HL7Exception;
import ca.uhn.hl7v2.model.v25.message.ACK;
import ca.uhn.hl7v2.model.v25.message.ADT_A01;
import ca.uhn.hl7v2.model.v25.message.QBP_Q21;
import ca.uhn.hl7v2.model.v25.message.RSP_K23;
import ca.uhn.hl7v2.model.v25.segment.MSH;
import ca.uhn.hl7v2.parser.GenericParser;
import ca.uhn.hl7v2.parser.Parser;
import ca.uhn.hl7v2.util.Terser;

import org.openhim.mediator.datatypes.AssigningAuthority;
import org.openhim.mediator.datatypes.Identifier;
import org.openhim.mediator.messages.RegisterNewPatient;
import org.openhim.mediator.messages.RegisterNewPatientXds;
import org.openhim.mediator.messages.MergePatientXds;
import org.openhim.mediator.messages.RegisterNewPatientResponse;
import org.openhim.mediator.messages.ResolvePatientIdentifier;
import org.openhim.mediator.messages.ResolvePatientIdentifierResponse;
import scala.util.parsing.combinator.testing.Tester;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;

public class DefaultOrchestrator extends UntypedActor {
    LoggingAdapter log = Logging.getLogger(getContext().system(), this);

    private ActorRef requestHandler;

    private final MediatorConfig config;

    private ActorRef requesthandler;

    private MediatorHTTPRequest originalRequest;

    protected ActorRef resolvePatientIDActor;

    private Identifier patientId;
    private Identifier resolvedPatientId;

    private String messageBuffer;
    private String finalMediatorResponseBody;

    private static final SimpleDateFormat dateFormat = new SimpleDateFormat("yyyyMMddHHmmssZ");
    private static final SimpleDateFormat dateFormatDay = new SimpleDateFormat("yyyyMMdd");

    public DefaultOrchestrator(MediatorConfig config) {
        this.config = config;
        resolvePatientIDActor = getContext().actorOf(Props.create(PIXRequestActor.class, config), "pix-denormalization");
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

    private Identifier parseADT01(String request) throws HL7Exception {

        log.info("The original ID request string is : " + request);
        //log.info("The ID from the ADT_A01 message is : " + id);

        Parser parser = new GenericParser();
        Object parsedMsg = parser.parse(request);

        //Terser terser = new Terser(parsedMsg);
        Message modelmsg = (Message)parsedMsg;

        Terser terser = new Terser(modelmsg);

        String identifier = terser.get("/.PID-3-1");

        String assigningAuthority = null;
        //if (msg.getPID().getPatientIdentifierList(0).getAssigningAuthority().getNamespaceID()!=null) {
        if(terser.get("/.PID-3-4") != null){
            assigningAuthority = terser.get("/.PID-3-4");       //msg.getPID().getPatientIdentifierList(0).getAssigningAuthority().getNamespaceID().getValue();
        }

        String assigningAuthorityId = null;
        //if (msg.getPID().getPatientIdentifierList(0).getAssigningAuthority().getUniversalID()!=null) {
        if(terser.get("/.PID-3-4-2") != null) {
            assigningAuthorityId = terser.get("/.PID-3-4-2");   //msg.getPID().getPatientIdentifierList(0).getAssigningAuthority().getUniversalID().getValue();
        }

        String assigningAuthorityIdType = null;
        //if (msg.getPID().getPatientIdentifierList(0).getAssigningAuthority().getUniversalIDType()!=null) {
        if(terser.get("/.PID-3-4-3") != null) {
            assigningAuthorityIdType = terser.get("/.PID-3-4-3");      //msg.getPID().getPatientIdentifierList(0).getAssigningAuthority().getUniversalIDType().getValue();
        }
        return new Identifier(identifier, new AssigningAuthority(assigningAuthority, assigningAuthorityId, assigningAuthorityIdType));
    }

    private void convertJSONMessageSendtoXDSRegistry(MediatorHTTPRequest request) {
            originalRequest = request;
            requestHandler = request.getRequestHandler();

            // Get the request body
            messageBuffer = request.getBody().trim();

            JsonObject jsonObject = new JsonParser().parse(messageBuffer).getAsJsonObject();
            String operation = jsonObject.get("source").toString();
            String transition = jsonObject.get("transition").toString();

            JsonArray preUpdateArr = jsonObject.getAsJsonArray("preUpdateIdentifiers");
            JsonArray arr = jsonObject.getAsJsonArray("postUpdateIdentifiers");

            String identifier = "", assigningAuthority = "", assigningAuthorityId = "", assigningAuthorityIdType = "", 
            preUpdateIdentifier = "";

            for (int i = 0; i < arr.size(); i++) {
                JsonElement identifierDomain = arr.get(i).getAsJsonObject().get("identifierDomain");
                String identifierDomainName = identifierDomain.getAsJsonObject().get("identifierDomainName").getAsString();

                if(identifierDomainName.contains("OpenEMPI")) {
                    identifier = arr.get(i).getAsJsonObject().get("identifier").getAsString();
                    assigningAuthority = identifierDomain.getAsJsonObject().get("namespaceIdentifier").getAsString();
                    assigningAuthorityId = identifierDomain.getAsJsonObject().get("universalIdentifier").getAsString();
                    assigningAuthorityIdType = identifierDomain.getAsJsonObject().get("universalIdentifierTypeCode").getAsString();
                }
            }

            for (int i = 0; i < preUpdateArr.size(); i++) {
                JsonElement identifierDomain = preUpdateArr.get(i).getAsJsonObject().get("identifierDomain");
                String identifierDomainName = identifierDomain.getAsJsonObject().get("identifierDomainName").getAsString();

                if(identifierDomainName.contains("OpenEMPI")) {
                    preUpdateIdentifier = preUpdateArr.get(i).getAsJsonObject().get("identifier").getAsString();
                    assigningAuthority = identifierDomain.getAsJsonObject().get("namespaceIdentifier").getAsString();
                    assigningAuthorityId = identifierDomain.getAsJsonObject().get("universalIdentifier").getAsString();
                    assigningAuthorityIdType = identifierDomain.getAsJsonObject().get("universalIdentifierTypeCode").getAsString();
                }
            }

            // Declare Post Update and Pre-Update identifier lists
            List<Identifier> identifierList = new LinkedList<>();
            List<Identifier> preUpdateIdentifierList = new LinkedList<>();

            if (operation.contains("ADD") && transition.contains("JOIN")) {
                Identifier patientIdentifier = new Identifier(identifier, new AssigningAuthority(
                                            assigningAuthority, assigningAuthorityId, assigningAuthorityIdType));

                identifierList.add(patientIdentifier);
                RegisterNewPatientXds requestXds = new RegisterNewPatientXds(requestHandler, getSelf(), identifierList);
                resolvePatientIDActor.tell(requestXds, getSelf());

            } else if (operation.contains("UPDATE") && transition.contains("JOIN")) {
                Identifier patientIdentifier = new Identifier(identifier, new AssigningAuthority(
                                            assigningAuthority, assigningAuthorityId, assigningAuthorityIdType));
                Identifier preUpdatePatientId = new Identifier(preUpdateIdentifier, new AssigningAuthority(
                                            assigningAuthority, assigningAuthorityId, assigningAuthorityIdType));

                identifierList.add(patientIdentifier);
                preUpdateIdentifierList.add(preUpdatePatientId);
                MergePatientXds mergeRequestXds = new MergePatientXds(requestHandler, getSelf(), identifierList, preUpdateIdentifierList);
                resolvePatientIDActor.tell(mergeRequestXds, getSelf());
            }
    }

    private void processRegisterNewPatientResponse(RegisterNewPatientResponse response) {
        if (response.isSuccessful()) {
            log.info("Patient successfully registered in XDS Registry.");

            Map<String, String> headers = new HashMap<>();
            headers.put("content-type", "application/hl7-v2; charset=ISO-8859-1");

            MediatorHTTPResponse httpresponse = new MediatorHTTPResponse(originalRequest, finalMediatorResponseBody,
                    HttpStatus.SC_OK, headers);
            originalRequest.getRespondTo().tell(httpresponse.toFinishRequest(), getSelf());
        } else {
            // do nothing for now
        }
    }

    @Override
    public void onReceive(Object msg) throws Exception {
        if (msg instanceof MediatorHTTPRequest) {
            convertJSONMessageSendtoXDSRegistry((MediatorHTTPRequest) msg);
        } else if (msg instanceof RegisterNewPatientResponse) {
            processRegisterNewPatientResponse((RegisterNewPatientResponse) msg);
        } else {
            unhandled(msg);
        }
    }
}
