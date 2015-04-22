/*
 * JBoss, Home of Professional Open Source
 * Copyright XXXX, Red Hat Middleware LLC, and individual contributors as indicated
 * by the @authors tag. All rights reserved.
 * See the copyright.txt in the distribution for a full listing
 * of individual contributors.
 * This copyrighted material is made available to anyone wishing to use,
 * modify, copy, or redistribute it subject to the terms and conditions
 * of the GNU General Public License, v. 2.0.
 * This program is distributed in the hope that it will be useful, but WITHOUT A
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 * You should have received a copy of the GNU General Public License,
 * v. 2.0 along with this distribution; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston,
 * MA 02110-1301, USA.
 */
package org.ocs.client;

import java.io.IOException;
import java.io.InputStream;
import java.util.Set;

import org.apache.log4j.Logger;
import org.jdiameter.api.Answer;
import org.jdiameter.api.ApplicationId;
import org.jdiameter.api.Avp;
import org.jdiameter.api.AvpDataException;
import org.jdiameter.api.AvpSet;
import org.jdiameter.api.Configuration;
import org.jdiameter.api.EventListener;
import org.jdiameter.api.IllegalDiameterStateException;
import org.jdiameter.api.InternalException;
import org.jdiameter.api.MetaData;
import org.jdiameter.api.Network;
import org.jdiameter.api.NetworkReqListener;
import org.jdiameter.api.OverloadException;
import org.jdiameter.api.Request;
import org.jdiameter.api.RouteException;
import org.jdiameter.api.Session;
import org.jdiameter.api.SessionFactory;
import org.jdiameter.api.Stack;
import org.jdiameter.api.StackType;
import org.jdiameter.client.impl.DictionarySingleton;
import org.jdiameter.common.impl.validation.DictionaryImpl;
import org.jdiameter.server.impl.StackImpl;
import org.jdiameter.server.impl.helpers.XMLConfiguration;
import org.mobicents.diameter.dictionary.AvpDictionary;
import org.ocs.util.MessageLogger;

public class QuotaClient implements EventListener<Request, Answer> {

	private static final Logger log = Logger.getLogger(QuotaClient.class);

	// configuration files
	private static final String configFile = "ocs/client-jdiameter-config.xml";
	private static final String dictionaryFile = "ocs/dictionary.xml";
	// our destination
	private static final String serverHost = "127.0.0.1";
	private static final String serverPort = "3868";
	private static final String serverURI = "aaa://" + serverHost + ":" + serverPort;
	// our realm
	private static final String realmName = "mymac";

	private static final int commandCode = 16777214;
	private static final long vendorID = 999999;
	private static final long applicationID = 16777215;
	private ApplicationId authAppId = ApplicationId.createByAuthAppId(applicationID);

	private static final int opTypeCode = 400000; // INTEGER32
	private static final int UserIdCode = 400001; // OCTETSTRING
	private static final int TenantIdCode = 400005; // OCTETSTRING
	private static final int requestQuotaCode = 400002; // UNSIGNED64
	private static final int usedQuotaCode = 400003; // UNSIGNED64
	private static final int grantedQuotaCode = 400004; // UNSIGNED64

	// enum values for OpType AVP
	private static final int OP_TYPE_INITIAL = 1;
	private static final int OP_TYPE_UPDATE = 2;
	private static final int OP_TYPE_TERMINATING = 3;

	// Dictionary, for informational purposes.
	private AvpDictionary dictionary = AvpDictionary.INSTANCE;
	// stack and session factory
	private Stack stack;
	private SessionFactory factory;

	// ////////////////////////////////////////
	// Objects which will be used in action //
	// ////////////////////////////////////////
	private Session session; // session used as handle for communication
	private boolean finished = false; // boolean telling if we finished our
										// interaction

	private void initStack() {
		if (log.isInfoEnabled()) {
			log.info("Initializing Stack...");
		}
		InputStream is = null;
		try {
			// Parse dictionary, it is used for user friendly info.
			
			//What the fuck!
			DictionarySingleton.getDictionary().configure(this.getClass().getClassLoader().getResourceAsStream(dictionaryFile));
			dictionary.parseDictionary((InputStream) null);
			log.info("AVP Dictionary successfully parsed.");
			
			log.info(dictionary.getAvp(opTypeCode, vendorID));

			this.stack = new StackImpl();
			// Parse stack configuration
			is = this.getClass().getClassLoader().getResourceAsStream(configFile);
			Configuration config = new XMLConfiguration(is);
			factory = stack.init(config);
			if (log.isInfoEnabled()) {
				log.info("Stack Configuration successfully loaded.");
			}
			// Print info about applicatio
			Set<org.jdiameter.api.ApplicationId> appIds = stack.getMetaData().getLocalPeer().getCommonApplications();

			log.info("Diameter Stack  :: Supporting " + appIds.size() + " applications.");
			for (org.jdiameter.api.ApplicationId x : appIds) {
				log.info("Diameter Stack  :: Common :: " + x);
			}
			is.close();
			// Register network req listener, even though we wont receive
			// requests
			// this has to be done to inform stack that we support application
			Network network = stack.unwrap(Network.class);
			network.addNetworkReqListener(new NetworkReqListener() {

				@Override
				public Answer processRequest(Request request) {
					// this wontbe called.
					return null;
				}
			}, this.authAppId); // passing our example app id.

		} catch (Exception e) {
			e.printStackTrace();
			if (this.stack != null) {
				this.stack.destroy();
			}

			if (is != null) {
				try {
					is.close();
				} catch (IOException e1) {

					e1.printStackTrace();
				}
			}
			return;
		}

		MetaData metaData = stack.getMetaData();
		// ignore for now.
		if (metaData.getStackType() != StackType.TYPE_SERVER || metaData.getMinorVersion() <= 0) {
			stack.destroy();
			if (log.isEnabledFor(org.apache.log4j.Level.ERROR)) {
				log.error("Incorrect driver");
			}
			return;
		}

		try {
			if (log.isInfoEnabled()) {
				log.info("Starting stack");
			}
			stack.start();
			if (log.isInfoEnabled()) {
				log.info("Stack is running.");
			}
		} catch (Exception e) {
			e.printStackTrace();
			stack.destroy();
			return;
		}
		if (log.isInfoEnabled()) {
			log.info("Stack initialization successfully completed.");
		}
	}

	/**
	 * @return
	 */
	private boolean finished() {
		return this.finished;
	}

	/**
	 * 
	 */
	private void start() {
		try {
			// wait for connection to peer
			try {
				Thread.currentThread().sleep(5000);
			} catch (InterruptedException e) {

				e.printStackTrace();
			}
			// do send
			this.session = this.factory
					.getNewSession("BadCustomSessionId;YesWeCanPassId;" + System.currentTimeMillis());
			sendNextRequest(OP_TYPE_INITIAL);
		} catch (InternalException e) {
			e.printStackTrace();
		} catch (IllegalDiameterStateException e) {
			e.printStackTrace();
		} catch (RouteException e) {
			e.printStackTrace();
		} catch (OverloadException e) {
			e.printStackTrace();
		}

	}

	private void sendNextRequest(int enumType) throws InternalException, IllegalDiameterStateException, RouteException,
			OverloadException {
		Request r = this.session.createRequest(commandCode, this.authAppId, realmName, serverHost// serverURI
				);
		// here we have all except our custom avps

		AvpSet requestAvps = r.getAvps();
		// code , value , vendor, mandatory,protected
		Avp opType = requestAvps.addAvp(opTypeCode, (long) enumType, vendorID, true, false, true);

		if (enumType == OP_TYPE_INITIAL) {
			requestAvps.addAvp(UserIdCode, "abc", vendorID, true, false, true);
			requestAvps.addAvp(TenantIdCode, "3", vendorID, true, false, true);
			requestAvps.addAvp(requestQuotaCode, 1000l, vendorID, true, false, false);
		} else if (enumType == OP_TYPE_UPDATE) {
			requestAvps.addAvp(requestQuotaCode, 1000l, vendorID, true, false, false);
			requestAvps.addAvp(usedQuotaCode, 1000l, vendorID, true, false, false);
		} else if (enumType == OP_TYPE_TERMINATING) {
			requestAvps.addAvp(usedQuotaCode, 1000l, vendorID, true, false, false);
		}

		// send
		this.session.send(r, this);
		MessageLogger.dumpMessage(r, true); // dump info on console
	}

	@Override
	public void receivedSuccessMessage(Request request, Answer answer) {
		MessageLogger.dumpMessage(answer, false);
		if (answer.getCommandCode() != commandCode) {
			log.error("Received bad answer: " + answer.getCommandCode());
			return;
		}
		AvpSet answerAvpSet = answer.getAvps();

		Avp opTypeAvp = answerAvpSet.getAvp(opTypeCode, vendorID);
		Avp resultAvp = answer.getResultCode();

		try {
			// for bad formatted request.
			if (resultAvp.getUnsigned32() == 5005 || resultAvp.getUnsigned32() == 5004) {
				// missing || bad value of avp
				this.session.release();
				this.session = null;
				log.error("Something wrong happened at server side!");
				finished = true;
			}
			switch ((int) opTypeAvp.getInteger32()) {
			case OP_TYPE_INITIAL:
				sendNextRequest(OP_TYPE_UPDATE);
				break;
			case OP_TYPE_UPDATE:
				sendNextRequest(OP_TYPE_TERMINATING);
				break;
			case OP_TYPE_TERMINATING:
				// good, we reached end of FSM.
				finished = true;
				// release session and its resources.
				this.session.release();
				this.session = null;
				break;
			default:
				log.error("Bad value of Op-Type avp: " + opTypeAvp.getUnsigned32());
				break;
			}
		} catch (AvpDataException e) {
			// thrown when interpretation of byte[] fails
			e.printStackTrace();
		} catch (InternalException e) {
			e.printStackTrace();
		} catch (IllegalDiameterStateException e) {
			e.printStackTrace();
		} catch (RouteException e) {
			e.printStackTrace();
		} catch (OverloadException e) {
			e.printStackTrace();
		}

	}

	@Override
	public void timeoutExpired(Request request) {

	}

	public static void main(String[] args) {
		QuotaClient ec = new QuotaClient();
		ec.initStack();
		ec.start();

		while (!ec.finished()) {
			try {
				Thread.currentThread().sleep(5000);
			} catch (InterruptedException e) {

				e.printStackTrace();
			}
		}
	}

}