package org.ocs.util;

import java.io.InputStream;
import java.util.Properties;

import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;
import org.jdiameter.api.Avp;
import org.jdiameter.api.AvpDataException;
import org.jdiameter.api.AvpSet;
import org.jdiameter.api.Message;
import org.mobicents.diameter.dictionary.AvpDictionary;
import org.mobicents.diameter.dictionary.AvpRepresentation;

public class MessageLogger {

	private static final Logger log = Logger.getLogger(MessageLogger.class);
	static {
		configLog4j();
	}

	private static void configLog4j() {
		InputStream inStreamLog4j = MessageLogger.class.getClassLoader()
				.getResourceAsStream("log4j.properties");
		Properties propertiesLog4j = new Properties();
		try {
			propertiesLog4j.load(inStreamLog4j);
			PropertyConfigurator.configure(propertiesLog4j);
		} catch (Exception e) {
			e.printStackTrace();
		}

		log.debug("log4j configured");

	}

	public static void dumpMessage(Message message, boolean sending) {
		if (log.isInfoEnabled()) {
			log.info((sending ? "Sending " : "Received ")
					+ (message.isRequest() ? "Request: " : "Answer: ")
					+ message.getCommandCode() + "\nE2E:"
					+ message.getEndToEndIdentifier() + "\nHBH:"
					+ message.getHopByHopIdentifier() + "\nAppID:"
					+ message.getApplicationId());
			log.info("AVPS[" + message.getAvps().size() + "]: \n");
			try {
				printAvps(message.getAvps());
			} catch (AvpDataException e) {
				e.printStackTrace();
			}
		}
	}

	public static void printAvps(AvpSet avpSet) throws AvpDataException {
		printAvpsAux(avpSet, 0);
	}

	/**
	 * Prints the AVPs present in an AvpSet with a specified 'tab' level
	 * 
	 * @param avpSet
	 *            the AvpSet containing the AVPs to be printed
	 * @param level
	 *            an int representing the number of 'tabs' to make a pretty
	 *            print
	 * @throws AvpDataException
	 */
	public static void printAvpsAux(AvpSet avpSet, int level) throws AvpDataException {
		String prefix = "                      ".substring(0, level * 2);

		for (Avp avp : avpSet) {
			AvpRepresentation avpRep = AvpDictionary.INSTANCE.getAvp(
					avp.getCode(), avp.getVendorId());

			if (avpRep != null && avpRep.getType().equals("Grouped")) {
				log.info(prefix + "<avp name=\"" + avpRep.getName()
						+ "\" code=\"" + avp.getCode() + "\" vendor=\""
						+ avp.getVendorId() + "\">");
				printAvpsAux(avp.getGrouped(), level + 1);
				log.info(prefix + "</avp>");
			} else if (avpRep != null) {
				String value = "";

				if (avpRep.getType().equals("Integer32"))
					value = String.valueOf(avp.getInteger32());
				else if (avpRep.getType().equals("Integer64")
						|| avpRep.getType().equals("Unsigned64"))
					value = String.valueOf(avp.getInteger64());
				else if (avpRep.getType().equals("Unsigned32"))
					value = String.valueOf(avp.getUnsigned32());
				else if (avpRep.getType().equals("Float32"))
					value = String.valueOf(avp.getFloat32());
				else
					value = new String(avp.getOctetString());

				log.info(prefix + "<avp name=\"" + avpRep.getName()
						+ "\" code=\"" + avp.getCode() + "\" vendor=\""
						+ avp.getVendorId() + "\" value=\"" + value + "\" />");
			}
		}
	}
}
