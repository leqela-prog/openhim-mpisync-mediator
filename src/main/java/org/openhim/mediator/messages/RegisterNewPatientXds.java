/*
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package org.openhim.mediator.messages;

import java.util.List;

import akka.actor.ActorRef;
import org.openhim.mediator.datatypes.Identifier;
import org.openhim.mediator.engine.messages.MediatorRequestMessage;

/**
 * Create a new patient demographic record.
 */
public class RegisterNewPatientXds extends MediatorRequestMessage {
    private final List<Identifier> patientIdentifiers;

    public RegisterNewPatientXds(ActorRef requestHandler, ActorRef respondTo, List<Identifier> patientIdentifiers) {
        super(requestHandler, respondTo);
        this.patientIdentifiers = patientIdentifiers;
    }

    public List<Identifier> getPatientIdentifiers() {
        return patientIdentifiers;
    }
}